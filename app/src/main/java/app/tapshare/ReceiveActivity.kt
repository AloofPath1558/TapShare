package app.tapshare

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Receiver side of the tap mode:
 *  1. creates a Wi-Fi Direct group with a random name and password;
 *  2. publishes the connection details through the NFC service (TapHostApduService);
 *  3. waits for the file on a TCP socket and saves it to Downloads/TapShare.
 */
@SuppressLint("MissingPermission")
class ReceiveActivity : AppCompatActivity() {

    private lateinit var ui: StateUi
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var server: ServerSocket? = null

    @Volatile
    private var closing = false
    private val token = Protocol.randomToken()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (missingPermissions().isEmpty()) {
                startReceiving()
            } else {
                ui.set(getString(R.string.cant_start), getString(R.string.perm_needed), error = true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = StateUi(findViewById(R.id.state))
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        nfcProblem(needHce = true)?.let {
            ui.set(getString(R.string.cant_start), it, error = true)
            return
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) startReceiving() else permLauncher.launch(missing)
    }

    private fun startReceiving() {
        if (!wifiOn()) {
            ui.set(getString(R.string.cant_start), getString(R.string.wifi_off), error = true)
            return
        }
        ui.set(getString(R.string.preparing_title), getString(R.string.preparing_detail), active = true)

        val mgr = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        val ch = mgr.initialize(this, mainLooper, null)
        manager = mgr
        channel = ch

        val ssid = Protocol.randomSsid()
        val pass = Protocol.randomPass()
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .enablePersistentMode(false)
            .build()

        val create = {
            mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    ReceiveSession.offer =
                        Offer(ssid, pass, Protocol.GROUP_OWNER_IP, Protocol.PORT, token).toBytes()
                    ui.set(getString(R.string.ready_title), getString(R.string.ready_detail), active = true)
                    startServer()
                }

                override fun onFailure(reason: Int) {
                    ui.set(
                        getString(R.string.failed_receive_title),
                        getString(R.string.wifi_direct_failed, reason),
                        error = true,
                    )
                    ui.action(getString(R.string.start_over)) { recreate() }
                }
            })
        }

        // free any group left over from before, then create ours
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                create()
            }

            override fun onFailure(reason: Int) {
                create()
            }
        })
    }

    private fun startServer() {
        Thread {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.PORT))
                }
                server = ss
                while (!closing) {
                    val socket = ss.accept()
                    if (handle(socket)) break // a completed transfer ends the session
                }
            } catch (e: Exception) {
                if (!closing) {
                    ui.set(
                        getString(R.string.failed_receive_title),
                        e.message ?: e.javaClass.simpleName,
                        error = true,
                    )
                    ui.hideProgress()
                    ui.action(getString(R.string.start_over)) { recreate() }
                }
            } finally {
                ReceiveSession.offer = null
                runCatching { server?.close() }
                if (!closing) manager?.removeGroup(channel, null)
            }
        }.start()
    }

    /** Handles one connection. Returns true only when the whole file was received. */
    private fun handle(socket: Socket): Boolean {
        var uri: Uri? = null
        try {
            socket.use { s ->
                s.soTimeout = 20_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                val h = Protocol.readHeader(input)
                if (h.token != token) return false // anyone who doesn't know the token is dropped
                if (h.size < 0) throw IOException("Invalid file size")

                val name = h.name.substringAfterLast('/')
                    .replace(Regex("[\\\\:*?\"<>|]"), "_")
                    .ifBlank { "file" }
                val sizeText = Formatter.formatShortFileSize(this, h.size)
                ui.set(getString(R.string.receiving_title), getString(R.string.receiving_detail, name, sizeText))
                ui.progress(0, h.size)

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, h.mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TapShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val target = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create the file")
                uri = target

                contentResolver.openOutputStream(target)!!.use { out ->
                    Protocol.copyExact(input, out, h.size) { done -> ui.progress(done, h.size) }
                }
                contentResolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )

                s.getOutputStream().apply {
                    write(1) // receipt confirmation
                    flush()
                }
                ui.progress(h.size, h.size)
                ui.set(getString(R.string.received_title), getString(R.string.received_detail, name, sizeText))
                ui.action(getString(R.string.receive_again)) { recreate() }
            }
            return true
        } catch (e: Exception) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            if (!closing) {
                ui.set(
                    getString(R.string.failed_receive_title),
                    (e.message ?: e.javaClass.simpleName) + "\n" + getString(R.string.retry_hint),
                    error = true,
                )
                ui.hideProgress()
            }
            return false
        }
    }

    override fun onDestroy() {
        closing = true
        ReceiveSession.offer = null
        runCatching { server?.close() }
        manager?.removeGroup(channel, null)
        super.onDestroy()
    }
}
