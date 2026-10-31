package app.tapshare

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/** Offerta corrente del ricevitore (impostata da ReceiveActivity, letta dal servizio NFC). */
object ReceiveSession {
    @Volatile
    var offer: ByteArray? = null
}

/**
 * Il telefono che riceve si comporta come una carta NFC: quando il mittente fa SELECT
 * sul nostro AID, risponde con i dati per collegarsi alla rete Wi-Fi Direct.
 */
class TapHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (!Protocol.isSelect(commandApdu)) return Protocol.SW_INS_NOT_SUPPORTED
        val offer = ReceiveSession.offer ?: return Protocol.SW_NOT_READY
        return offer + Protocol.SW_OK
    }

    override fun onDeactivated(reason: Int) {}
}
