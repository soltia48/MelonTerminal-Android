package jp.unknowntech.melonterminal.core

import android.nfc.tech.NfcF
import jp.unknowntech.melonterminal.felica.FelicaProtocolException
import jp.unknowntech.melonterminal.felica.WILDCARD_SYSTEM_CODE
import jp.unknowntech.melonterminal.felica.hexToBytes
import jp.unknowntech.melonterminal.felica.polling
import jp.unknowntech.melonterminal.felica.relayFrame
import jp.unknowntech.melonterminal.felica.requestSystemCode
import jp.unknowntech.melonterminal.felica.toHex
import jp.unknowntech.melonterminal.net.MelonClient
import jp.unknowntech.melonterminal.net.MutualAuthContinue
import jp.unknowntech.melonterminal.net.MutualAuthStart

/**
 * The card-present half of one operation: resolve which system to authenticate, then
 * relay the server-driven mutual authentication. The server holds the keys and emits
 * each command frame; this app only sends it to the card and returns the response.
 *
 * Every FeliCa call may throw [android.nfc.TagLostException] if the card leaves the
 * field mid-flow; the caller surfaces that as "カードが離れました".
 */
object CardFlow {

    // Melon fixes the authenticated area/service at 0x0000.
    private val AREAS = listOf(0x0000)
    private val SERVICES = listOf(0x0000)

    /** Fixed cap on relay round-trips — mutual auth is a small, bounded exchange. */
    private const val MAX_RELAY_STEPS = 16

    /**
     * Authenticate a tapped card. [serverSystemCodes] are the systems the server can
     * authenticate, in priority order (from `GET /v1/system-codes`).
     */
    suspend fun authenticate(
        nfcF: NfcF,
        client: MelonClient,
        serverSystemCodes: List<Int>,
    ): AuthOutcome {
        // 1. Wildcard poll: the first system the card answers on (its IDm only).
        val first = polling(nfcF, WILDCARD_SYSTEM_CODE)

        // 2. Ask the card which systems it exposes (card order).
        val cardCodes = requestSystemCode(nfcF, first.idm)

        // 3. Choose the first server-supported system — server order wins.
        val chosen = serverSystemCodes.firstOrNull { it in cardCodes }
            ?: throw NoSupportedSystemException()

        // 4. Re-poll the chosen system: each system has its OWN IDm.
        val sys = polling(nfcF, chosen)

        // 5. Relay the mutual authentication the server drives.
        var resp = client.mutualAuthStart(
            MutualAuthStart(
                idm = sys.idm.toHex(),
                pmm = sys.pmm.toHex(),
                system_code = chosen,
                areas = AREAS,
                services = SERVICES,
            )
        )
        val sessionId = resp.session_id
            ?: throw FelicaProtocolException("server did not return a session_id")

        var steps = 0
        while (resp.step != "complete") {
            if (steps++ >= MAX_RELAY_STEPS) {
                throw FelicaProtocolException("mutual authentication did not complete")
            }
            val cmd = resp.command
                ?: throw FelicaProtocolException("server did not return a command frame")
            val cardResponse = relayFrame(nfcF, hexToBytes(cmd.frame))
            resp = client.mutualAuthContinue(
                MutualAuthContinue(session_id = sessionId, card_response = cardResponse.toHex())
            )
        }

        val accountId = resp.result?.account_id
            ?: throw FelicaProtocolException("server did not return an account_id")
        return AuthOutcome(systemCode = chosen, sessionId = sessionId, accountId = accountId)
    }
}
