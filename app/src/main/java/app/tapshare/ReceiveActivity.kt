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
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lato ricevente:
 *  1. crea un gruppo Wi-Fi Direct con nome e password casuali;
 *  2. pubblica i dati di connessione tramite il servizio NFC (TapHostApduService);
 *  3. aspetta il file su un socket TCP e lo salva in Download/TapShare.
 */
@SuppressLint("MissingPermission")
class ReceiveActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var server: ServerSocket? = null

    @Volatile
    private var closing = false
    private val token = Protocol.randomToken()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (missingPermissions().isEmpty()) startReceiving()
            else setStatus(getString(R.string.perm_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        nfcProblem(needHce = true)?.let {
            setStatus(it)
            return
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) startReceiving() else permLauncher.launch(missing)
    }

    private fun setStatus(text: String) = runOnUiThread { status.text = text }

    private fun startReceiving() {
        if (!wifiOn()) {
            setStatus(getString(R.string.wifi_off))
            return
        }
        setStatus(getString(R.string.preparing))

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
                    setStatus(getString(R.string.ready_receive))
                    startServer()
                }

                override fun onFailure(reason: Int) {
                    setStatus(getString(R.string.failed, "Wi-Fi Direct ($reason)"))
                }
            })
        }

        // libera un eventuale gruppo rimasto da prima, poi crea il nostro
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
                    if (handle(socket)) break // un trasferimento riuscito chiude la sessione
                }
            } catch (e: Exception) {
                if (!closing) setStatus(getString(R.string.failed, e.message ?: e.javaClass.simpleName))
            } finally {
                ReceiveSession.offer = null
                runCatching { server?.close() }
                if (!closing) manager?.removeGroup(channel, null)
            }
        }.start()
    }

    /** Gestisce una connessione. Restituisce true solo se il file è stato ricevuto per intero. */
    private fun handle(socket: Socket): Boolean {
        var uri: Uri? = null
        try {
            socket.use { s ->
                s.soTimeout = 20_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                val h = Protocol.readHeader(input)
                if (h.token != token) return false // chi non conosce il token viene scartato
                if (h.size < 0) throw IOException("Dimensione non valida")

                val name = h.name.substringAfterLast('/')
                    .replace(Regex("[\\\\:*?\"<>|]"), "_")
                    .ifBlank { "file" }
                setStatus(getString(R.string.receiving, name))
                runOnUiThread { progress.setFraction(0, h.size) }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, h.mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TapShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val target = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Impossibile creare il file")
                uri = target

                contentResolver.openOutputStream(target)!!.use { out ->
                    Protocol.copyExact(input, out, h.size) { done ->
                        runOnUiThread { progress.setFraction(done, h.size) }
                    }
                }
                contentResolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )

                s.getOutputStream().apply {
                    write(1) // conferma di ricezione
                    flush()
                }
                runOnUiThread { progress.setFraction(1, 1) }
                setStatus(getString(R.string.received_ok, name, Formatter.formatShortFileSize(this, h.size)))
            }
            return true
        } catch (e: Exception) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            if (!closing) {
                setStatus(getString(R.string.failed, e.message ?: e.javaClass.simpleName) +
                    "\n" + getString(R.string.retry_hint))
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
