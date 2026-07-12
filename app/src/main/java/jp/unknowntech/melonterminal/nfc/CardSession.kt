package jp.unknowntech.melonterminal.nfc

import android.nfc.Tag
import android.nfc.tech.NfcF
import java.io.IOException

/** Owns the connection to one tapped NFC-F card. */
object CardSession {

    /** Per-transceive timeout (ms). Auth relay frames are quick; be generous so a
     *  slightly slow card/crypto step doesn't abort the tap. */
    private const val TRANSCEIVE_TIMEOUT_MS = 2000

    /**
     * Connect [NfcF] for [tag] **on the current thread**. This must be called straight
     * from the NFC reader-mode callback thread: deferring the `connect()` (e.g. onto a
     * coroutine dispatched through the main looper) leaves a window in which the platform
     * re-issues the tag handle and invalidates this one, which then surfaces as
     * "Permission Denial: Tag … is out of date" on the first connect/transceive.
     *
     * The caller owns the returned connection and MUST [NfcF.close] it when finished.
     * Throws [IOException] if the tag is not NFC-F, or [android.nfc.TagLostException] if
     * it has already left the field.
     */
    fun connect(tag: Tag): NfcF {
        val nfcF = NfcF.get(tag) ?: throw IOException("FeliCa (NFC-F) ではないカードです")
        nfcF.connect()
        runCatching { nfcF.setTimeout(TRANSCEIVE_TIMEOUT_MS) }
        return nfcF
    }
}
