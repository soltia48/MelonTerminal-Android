package jp.unknowntech.melonterminal.net

import kotlinx.serialization.Serializable

// Request/response shapes of the melon-server JSON API. Field names are snake_case
// to match the wire format exactly (the client uses ignoreUnknownKeys, so only the
// fields the app actually reads need to be declared).

@Serializable
data class SystemCodesResp(val system_codes: List<Int> = emptyList())

// ----- mutual authentication (relay) -----

@Serializable
data class MutualAuthStart(
    val idm: String,
    val pmm: String,
    val system_code: Int,
    val areas: List<Int>,
    val services: List<Int>,
)

@Serializable
data class MutualAuthContinue(
    val session_id: String,
    val card_response: String,
)

@Serializable
data class AuthCommand(
    val frame: String,
    val timeout: Double = 0.1,
)

@Serializable
data class AuthResult(
    val account_id: String? = null,
)

@Serializable
data class MutualAuthResp(
    val session_id: String? = null,
    val step: String,
    val command: AuthCommand? = null,
    val result: AuthResult? = null,
)

// ----- money operations -----

@Serializable
data class SessionReq(val session_id: String)

@Serializable
data class AmountReq(val session_id: String, val amount: Long)

@Serializable
data class RefundReq(val payment_id: String, val amount: Long? = null)

@Serializable
data class Bucket(
    val bucket_id: String,
    val remaining: Long,
    val expires_at: String,
)

@Serializable
data class BalanceResp(
    val account_id: String,
    val total: Long,
    val buckets: List<Bucket> = emptyList(),
)

@Serializable
data class TopupResp(
    val amount: Long,
    val balance: Long,
    val expires_at: String? = null,
)

@Serializable
data class PayResp(
    val amount: Long,
    val fee: Long = 0,
    val balance: Long,
)

@Serializable
data class RefundResp(
    val amount: Long,
    val balance: Long? = null,
)

@Serializable
data class RefundableView(
    val id: String,
    val account_id: String,
    val amount: Long,
    val fee: Long = 0,
    val refunded: Long = 0,
    val refundable: Long,
    val occurred_at: String,
)

@Serializable
data class MerchantView(
    val id: String,
    val code: String,
    val name: String,
    val status: String,
    val fee_bps: Int,
    val credit_limit: Long,
    val collected: Long,
    val created_at: String,
)

// ----- errors -----

@Serializable
data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class ErrorResp(
    val error: ErrorBody? = null,
)
