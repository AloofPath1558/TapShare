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
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
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
 * Lato mittente:
 *  1. scegli il file (o condividilo da un'altra app);
 *  2. avvicina il telefono al ricevitore: l'NFC (modalità lettore) fornisce i dati di connessione;
 *  3. ci colleghiamo alla rete Wi-Fi Direct del ricevitore e inviamo il file su un socket TCP.
 */
@SuppressLint("MissingPermission")
class SendActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var fileLabel: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

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
        fileLabel = findViewById(R.id.fileLabel)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        findViewById<Button>(R.id.btnPick).setOnClickListener { picker.launch(arrayOf("*/*")) }

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
        if (problem != null) setStatus(problem) else armReader()
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    private fun setStatus(text: String) = runOnUiThread { status.text = text }

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
            setStatus(getString(R.string.size_unknown))
            return
        }
        fileUri = uri
        fileName = name
        fileSize = size
        fileMime = contentResolver.getType(uri) ?: "application/octet-stream"
        fileLabel.text = getString(R.string.file_info, name, Formatter.formatShortFileSize(this, size))
        progress.setFraction(0, 1)
    }

    private fun armReader() {
        val adapter = nfc ?: return
        if (fileUri == null) {
            setStatus(getString(R.string.pick_first))
            return
        }
        if (busy.get()) return
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        setStatus(getString(R.string.tap_now))
    }

    // --- NFC: legge l'offerta dal ricevitore ---

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

    // --- Wi-Fi diretto + invio ---

    private fun connectAndSend(offer: Offer) {
        if (!wifiOn()) {
            fail(getString(R.string.wifi_off))
            return
        }
        setStatus(getString(R.string.connecting))
        progress.setFraction(0, 1)

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
                Thread.sleep(700) // il ricevitore può impiegare un attimo a essere pronto
            }
        }
        throw last ?: IOException(getString(R.string.connect_failed))
    }

    private fun transfer(network: Network, offer: Offer) {
        val uri = fileUri
        if (uri == null) {
            fail(getString(R.string.pick_first))
            return
        }
        try {
            connect(network, offer).use { s ->
                s.soTimeout = 20_000
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                Protocol.writeHeader(out, FileHeader(offer.token, fileName, fileMime, fileSize))
                setStatus(getString(R.string.sending, fileName))
                contentResolver.openInputStream(uri)!!.use { input ->
                    Protocol.copyExact(input, out, fileSize) { done ->
                        runOnUiThread { progress.setFraction(done, fileSize) }
                    }
                }
                out.flush()
                if (s.getInputStream().read() != 1) throw IOException(getString(R.string.no_ack))
            }
            runOnUiThread { progress.setFraction(1, 1) }
            setStatus(getString(R.string.sent_ok, fileName))
            release()
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lascia la rete Wi-Fi Direct e rimette l'app pronta a un nuovo invio. */
    private fun release() {
        netCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        netCallback = null
        busy.set(false)
    }

    private fun fail(message: String) {
        setStatus(getString(R.string.failed, message) + "\n" + getString(R.string.retry_hint))
        release()
        runOnUiThread {
            val adapter = nfc
            if (adapter != null && fileUri != null) {
                adapter.enableReaderMode(
                    this,
                    this,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
    }
}
