package jp.unknowntech.melonterminal.pos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The LAN link between a POS register and this terminal — see `docs/POS-Protocol.md`
 * for the full specification. Every packet, over UDP (discovery) or TCP (commands),
 * is one JSON [Envelope] terminated by a newline on TCP.
 *
 * The envelope reserves `alg`/`sig` so a future version can add an HMAC or an
 * AES-GCM payload without changing the frame; today only `alg = "none"` is accepted.
 * The command/response object rides in [Envelope.msg]; a request carries `command`,
 * a response carries `type`.
 */
object PosProtocol {
    /** Protocol version and magic — a packet whose `melon_pos` differs is rejected. */
    const val VERSION = 1

    /** UDP port the terminal listens on for broadcast discovery. */
    const val DISCOVERY_PORT = 65024

    /** TCP port the terminal serves JSON commands on. */
    const val COMMAND_PORT = 65025

    /** The only authentication algorithm implemented today (cleartext). */
    const val ALG_NONE = "none"

    /** Shared JSON codec: tolerant on read, compact on write (nulls dropped). */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

// ----- envelope -----

@Serializable
data class Envelope(
    val melon_pos: Int = PosProtocol.VERSION,
    val alg: String = PosProtocol.ALG_NONE,
    val sig: String? = null,
    val msg: JsonObject,
)

// ----- response messages (terminal -> POS), distinguished by `type` -----

/** Reply to a UDP `discover` broadcast (sent unicast to the sender). */
@Serializable
data class AnnounceMsg(
    val type: String = "announce",
    val terminal_id: String,
    val name: String,
    val ip: String? = null,
    val tcp_port: Int = PosProtocol.COMMAND_PORT,
    val app_version: String,
    val state: String,
)

/** Reply to an `info` command — static terminal capabilities. */
@Serializable
data class InfoMsg(
    val type: String = "info",
    val protocol: Int = PosProtocol.VERSION,
    val terminal_id: String,
    val name: String,
    val app_version: String,
    val jobs: List<String> = listOf("payment", "topup", "balance", "refund_query", "refund_execute"),
)

/**
 * The transaction snapshot — the reply to `payment` / `balance` / `refund_query` /
 * `refund_execute` / `status` / `cancel`. POS polls by re-sending `status` until
 * [state] is terminal (`success` / `failed` / `cancelled`).
 */
@Serializable
data class StatusMsg(
    val type: String = "status",
    val transaction_id: String? = null,
    val request_id: String? = null,
    /** `payment` | `topup` | `balance` | `refund_query` | `refund` | null when idle. */
    val job: String? = null,
    /** `idle` | `pending` | `waiting_card` | `processing` | `success` | `failed` |
     *  `cancelled`. */
    val state: String,
    val status_text: String,
    val amount: Long? = null,
    /** The refundable transactions returned by a completed `refund_query`. */
    val refundable: List<RefundableMsg>? = null,
    val updated_at: Long? = null,
    /** The final outcome; null until the transaction reaches a terminal state. */
    val result: ResultMsg? = null,
)

@Serializable
data class RefundableMsg(
    val payment_id: String,
    val amount: Long,
    val fee: Long = 0,
    val refunded: Long = 0,
    val refundable: Long,
    val occurred_at: String,
)

/**
 * The union of every completed-operation payload. Fields not relevant to a given
 * job are omitted (`explicitNulls = false`). On failure only the error trio is set.
 */
@Serializable
data class ResultMsg(
    val ok: Boolean,
    // failure
    val code: String? = null,
    val title: String? = null,
    val detail: String? = null,
    // shared
    val account_id: String? = null,
    val amount: Long? = null,
    // payment
    val fee: Long? = null,
    val balance: Long? = null,
    // balance
    val total: Long? = null,
    val buckets: List<BucketMsg>? = null,
    val expires_at: String? = null,
)

@Serializable
data class BucketMsg(
    val bucket_id: String,
    val remaining: Long,
    val expires_at: String,
)

/** A refused or malformed command. */
@Serializable
data class ErrorMsg(
    val type: String = "error",
    val code: String,
    val message: String,
)

// ----- reply union -----

/** What a [PosCommandHandler] hands back to the server for a single request. */
sealed interface PosReply {
    data class Status(val msg: StatusMsg) : PosReply
    data class Info(val msg: InfoMsg) : PosReply
    data class Announce(val msg: AnnounceMsg) : PosReply
    data class Error(val msg: ErrorMsg) : PosReply
}

/** Stable error codes returned in [ErrorMsg.code]. */
object PosError {
    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val UNSUPPORTED_ALG = "UNSUPPORTED_ALG"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val BUSY = "BUSY"
}

/**
 * The terminal-side command sink. Every method is called from a [PosServer] network
 * thread and must be thread-safe; each returns the reply to serialize back. Starting
 * a card operation only *arms* the terminal — the actual card tap completes later and
 * is observed by POS through subsequent `status` polls.
 */
interface PosCommandHandler {
    fun handlePayment(requestId: String?, amount: Long, note: String?): PosReply
    fun handleTopup(requestId: String?, amount: Long): PosReply
    fun handleBalance(requestId: String?): PosReply
    fun handleRefundQuery(requestId: String?): PosReply
    fun handleRefundExecute(requestId: String?, paymentId: String, amount: Long?): PosReply
    fun handleStatus(): PosReply
    fun handleCancel(requestId: String?): PosReply
    fun handleInfo(): PosReply
    /** Build the discovery announcement; [localIp] is the address the reply goes out on. */
    fun announce(localIp: String?): AnnounceMsg
}
