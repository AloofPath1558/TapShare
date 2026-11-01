package app.tapshare

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sender side of the tap mode:
 *  1. pick a file (or share one from another app);
 *  2. hold the phone against the receiver: NFC (reader mode) provides the connection details;
 *  3. join the receiver's Wi-Fi Direct network and send the file over a TCP socket.
 */
@SuppressLint("MissingPermission")
class SendActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var drop: View
    private lateinit var dropTitle: TextView
    private lateinit var dropHint: TextView
    private lateinit var ui: StateUi

    private var nfc: NfcAdapter? = null
    private var fileUri: Uri? = null
    private var fileName = ""
    private var fileMime = "application/octet-stream"
    private var fileSize = -1L

    private val busy = AtomicBoolean(false)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) setFile(uri)
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        drop = findViewById(R.id.drop)
        dropTitle = findViewById(R.id.dropTitle)
        dropHint = findViewById(R.id.dropHint)
        ui = StateUi(findViewById(R.id.state))

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        drop.setOnClickListener { if (!busy.get()) picker.launch(arrayOf("*/*")) }

        val missing = missingPermissions()
        if (missing.isNotEmpty()) permLauncher.launch(missing)

        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { setFile(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        val problem = nfcProblem(needHce = false)
        nfc = if (problem == null) NfcAdapter.getDefaultAdapter(this) else null
        if (problem != null) {
            ui.set(getString(R.string.cant_start), problem, error = true)
        } else {
            armReader()
        }
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun setFile(uri: Uri) {
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        if (size < 0) {
            ui.set(getString(R.string.cant_start), getString(R.string.size_unknown), error = true)
            return
        }
        fileUri = uri
        fileName = name
        fileSize = size
        fileMime = contentResolver.getType(uri) ?: "application/octet-stream"

        dropTitle.text = name
        dropHint.text = Formatter.formatShortFileSize(this, size)
        drop.setBackgroundResource(R.drawable.bg_drop_filled)
        ui.hideProgress()
        ui.action(null)
    }

    private fun enableReader() {
        val adapter = nfc ?: return
        if (fileUri == null) return
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    private fun armReader() {
        if (nfc == null || busy.get()) return
        if (fileUri == null) {
            ui.set(getString(R.string.pick_title), getString(R.string.pick_detail))
            return
        }
        enableReader()
        ui.set(getString(R.string.tap_title), getString(R.string.tap_detail), active = true)
    }

    private fun resetForNew() {
        fileUri = null
        fileName = ""
        fileSize = -1L
        dropTitle.text = getString(R.string.drop_title)
        dropHint.text = getString(R.string.drop_hint)
        drop.setBackgroundResource(R.drawable.bg_drop)
        ui.hideProgress()
        ui.action(null)
        armReader()
    }

    // --- NFC: read the connection details from the receiver ---

    override fun onTagDiscovered(tag: Tag) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val iso = IsoDep.get(tag) ?: throw IOException(getString(R.string.not_tapshare))
            val resp = iso.use {
                it.connect()
                it.timeout = 4000
                it.transceive(Protocol.selectCommand)
            }
            if (resp.size < 2 ||
                resp[resp.size - 2] != 0x90.toByte() ||
                resp[resp.size - 1] != 0x00.toByte()
            ) {
                throw IOException(getString(R.string.not_tapshare))
            }
            val offer = Offer.parse(resp.copyOf(resp.size - 2))
                ?: throw IOException(getString(R.string.not_tapshare))
            runOnUiThread {
                nfc?.disableReaderMode(this)
                connectAndSend(offer)
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    // --- direct Wi-Fi + transfer ---

    private fun connectAndSend(offer: Offer) {
        if (!wifiOn()) {
            fail(getString(R.string.wifi_off))
            return
        }
        ui.set(getString(R.string.connecting_title), getString(R.string.connecting_detail), active = true)
        ui.hideProgress()

        val cm = getSystemService(ConnectivityManager::class.java)
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(offer.ssid)
            .setWpa2Passphrase(offer.pass)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val started = AtomicBoolean(false)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (started.compareAndSet(false, true)) {
                    Thread { transfer(network, offer) }.start()
                }
            }

            override fun onUnavailable() {
                fail(getString(R.string.wifi_unavailable))
            }
        }
        netCallback = cb
        cm.requestNetwork(request, cb, 40_000)
    }

    private fun connect(network: Network, offer: Offer): Socket {
        var last: Exception? = null
        for (attempt in 1..8) {
            try {
                val s = network.socketFactory.createSocket()
                try {
                    s.connect(InetSocketAddress(offer.host, offer.port), 4000)
                    return s
                } catch (e: Exception) {
                    s.close()
                    throw e
                }
            } catch (e: Exception) {
                last = e
                Thread.sleep(700) // the receiver may need a moment to be ready
            }
        }
        throw last ?: IOException(getString(R.string.connect_failed))
    }

    private fun transfer(network: Network, offer: Offer) {
        val uri = fileUri
        if (uri == null) {
            fail(getString(R.string.pick_title))
            return
        }
        val size = fileSize
        val sizeText = Formatter.formatShortFileSize(this, size)
        try {
            connect(network, offer).use { s ->
                s.soTimeout = 20_000
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                Protocol.writeHeader(out, FileHeader(offer.token, fileName, fileMime, size))
                ui.set(
                    getString(R.string.sending_title),
                    getString(R.string.sending_detail, fileName, sizeText),
                )
                ui.progress(0, size)
                contentResolver.openInputStream(uri)!!.use { input ->
                    Protocol.copyExact(input, out, size) { done -> ui.progress(done, size) }
                }
                out.flush()
                if (s.getInputStream().read() != 1) throw IOException(getString(R.string.no_ack))
            }
            ui.progress(size, size)
            ui.set(getString(R.string.sent_title), getString(R.string.sent_detail, fileName, sizeText))
            ui.action(getString(R.string.send_again)) { resetForNew() }
            release()
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Leaves the Wi-Fi Direct network and makes the screen ready for another transfer. */
    private fun release() {
        netCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        netCallback = null
        busy.set(false)
    }

    private fun fail(message: String) {
        ui.set(
            getString(R.string.failed_send_title),
            message + "\n" + getString(R.string.retry_hint),
            error = true,
        )
        ui.hideProgress()
        ui.action(null)
        release()
        runOnUiThread { enableReader() }
    }
}
