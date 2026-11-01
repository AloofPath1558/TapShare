package app.tapshare

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/** The receiver's current offer (set by ReceiveActivity, read by the NFC service). */
object ReceiveSession {
    @Volatile
    var offer: ByteArray? = null
}

/**
 * The receiving phone acts as an NFC card: when the sender sends SELECT for our AID,
 * it answers with the details needed to join the Wi-Fi Direct network.
 */
class TapHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (!Protocol.isSelect(commandApdu)) return Protocol.SW_INS_NOT_SUPPORTED
        val offer = ReceiveSession.offer ?: return Protocol.SW_NOT_READY
        return offer + Protocol.SW_OK
    }

    override fun onDeactivated(reason: Int) {}
}
