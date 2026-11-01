package app.tapshare

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.widget.ProgressBar
import androidx.core.content.ContextCompat

/** Returns a message explaining why NFC can't be used, or null when it can. */
fun Context.nfcProblem(needHce: Boolean): String? {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return getString(R.string.nfc_missing)
    if (needHce && !packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
        return getString(R.string.hce_missing)
    }
    if (!adapter.isEnabled) return getString(R.string.nfc_off)
    return null
}

fun Context.missingPermissions(): Array<String> =
    Protocol.wifiPermissions()
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

fun Context.wifiOn(): Boolean =
    (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

fun ProgressBar.setFraction(done: Long, total: Long) {
    progress = if (total <= 0) max else (done * max / total).toInt()
}
