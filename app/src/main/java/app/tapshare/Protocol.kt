package app.tapshare

import android.Manifest
import android.os.Build
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** Offerta che il ricevitore consegna via NFC al mittente. */
data class Offer(
    val ssid: String,
    val pass: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("s", ssid)
        .put("p", pass)
        .put("h", host)
        .put("o", port)
        .put("t", token)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): Offer? = try {
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            Offer(j.getString("s"), j.getString("p"), j.getString("h"), j.getInt("o"), j.getString("t"))
        } catch (e: Exception) {
            null
        }
    }
}

/** Intestazione che precede i byte del file sul socket. */
data class FileHeader(val token: String, val name: String, val mime: String, val size: Long)

object Protocol {
    /** AID proprietario (F0 + "TAPSHARE"): deve coincidere con res/xml/apduservice.xml */
    const val AID_HEX = "F05441505348415245"
    const val PORT = 8988

    /** Indirizzo predefinito del group owner in una rete Wi-Fi Direct Android. */
    const val GROUP_OWNER_IP = "192.168.49.1"

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NOT_READY = byteArrayOf(0x69, 0x85.toByte())
    val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)

    private val aid = hexToBytes(AID_HEX)
    private val selectHeader = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid
    val selectCommand: ByteArray = selectHeader + byteArrayOf(0x00)

    fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun isSelect(apdu: ByteArray): Boolean =
        apdu.size >= selectHeader.size &&
            apdu.copyOfRange(0, selectHeader.size).contentEquals(selectHeader)

    // --- credenziali casuali, nuove a ogni sessione ---

    private val rnd = SecureRandom()
    private const val ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun random(len: Int, alphabet: String): String =
        String(CharArray(len) { alphabet[rnd.nextInt(alphabet.length)] })

    /** Il nome di un gruppo Wi-Fi Direct deve iniziare con "DIRECT-xy". */
    fun randomSsid(): String = "DIRECT-TS-" + random(4, UPPER)
    fun randomPass(): String = random(12, ALNUM)
    fun randomToken(): String = random(16, ALNUM)

    fun wifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // --- intestazione e copia dei byte ---

    fun writeHeader(out: DataOutputStream, h: FileHeader) {
        out.writeUTF(h.token)
        out.writeUTF(h.name)
        out.writeUTF(h.mime)
        out.writeLong(h.size)
    }

    fun readHeader(input: DataInputStream): FileHeader =
        FileHeader(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong())

    /** Copia esattamente [size] byte; chiama [onProgress] al massimo ogni ~100 ms. */
    fun copyExact(input: InputStream, out: OutputStream, size: Long, onProgress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastTick = 0L
        while (done < size) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
            if (n < 0) throw EOFException("Connessione interrotta")
            out.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastTick > 100 || done == size) {
                lastTick = now
                onProgress(done)
            }
        }
    }
}
