package jp.unknowntech.melonterminal.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A non-2xx response from the server, carrying the stable `code` the UI localizes. */
class ApiException(val status: Int, val code: String?, message: String) : IOException(message)

/**
 * Typed client for the melon-server JSON API. Authenticates with the merchant API
 * key as `Authorization: Bearer …` (never logged); money operations carry a fresh
 * `Idempotency-Key`. All calls run on [Dispatchers.IO]. Immutable — build a new one
 * when the server URL or key changes.
 */
class MelonClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultHttp,
) {
    private val base: String = baseUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ----- endpoints -----

    suspend fun systemCodes(): List<Int> =
        get("/v1/system-codes", SystemCodesResp.serializer()).system_codes

    suspend fun mutualAuthStart(req: MutualAuthStart): MutualAuthResp =
        post(
            "/v1/mutual-authentication",
            req,
            MutualAuthStart.serializer(),
            MutualAuthResp.serializer()
        )

    suspend fun mutualAuthContinue(req: MutualAuthContinue): MutualAuthResp =
        post(
            "/v1/mutual-authentication",
            req,
            MutualAuthContinue.serializer(),
            MutualAuthResp.serializer()
        )

    suspend fun me(): MerchantView = get("/v1/me", MerchantView.serializer())

    suspend fun balance(sessionId: String): BalanceResp =
        post(
            "/v1/balance",
            SessionReq(sessionId),
            SessionReq.serializer(),
            BalanceResp.serializer()
        )

    suspend fun topup(sessionId: String, amount: Long): TopupResp =
        post(
            "/v1/topups",
            AmountReq(sessionId, amount),
            AmountReq.serializer(),
            TopupResp.serializer(),
            idempotencyKey = newKey(),
        )

    suspend fun pay(sessionId: String, amount: Long): PayResp =
        post(
            "/v1/payments",
            AmountReq(sessionId, amount),
            AmountReq.serializer(),
            PayResp.serializer(),
            idempotencyKey = newKey(),
        )

    suspend fun refundable(accountId: String): List<RefundableView> =
        getList(
            "/v1/payments/refundable?account_id=${enc(accountId)}",
            RefundableView.serializer(),
        )

    suspend fun refund(paymentId: String, amount: Long?): RefundResp =
        post(
            "/v1/refunds",
            RefundReq(paymentId, amount),
            RefundReq.serializer(),
            RefundResp.serializer(),
            idempotencyKey = newKey(),
        )

    // ----- plumbing -----

    private suspend fun <T> get(path: String, resp: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val req =
                Request.Builder().url(base + path).header("Authorization", "Bearer $apiKey").get()
                    .build()
            execute(req, resp)
        }

    private suspend fun <T> getList(path: String, element: KSerializer<T>): List<T> =
        withContext(Dispatchers.IO) {
            val req =
                Request.Builder().url(base + path).header("Authorization", "Bearer $apiKey").get()
                    .build()
            executeText(req).let { json.decodeFromString(ListSerializer(element), it) }
        }

    private suspend fun <B, T> post(
        path: String,
        body: B,
        bodySer: SerializationStrategy<B>,
        resp: DeserializationStrategy<T>,
        idempotencyKey: String? = null,
    ): T = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(bodySer, body).toRequestBody(JSON_MEDIA)
        val builder = Request.Builder().url(base + path)
            .header("Authorization", "Bearer $apiKey")
            .post(payload)
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        execute(builder.build(), resp)
    }

    private fun <T> execute(req: Request, resp: DeserializationStrategy<T>): T =
        json.decodeFromString(resp, executeText(req))

    /** Run the request, returning the body text on 2xx or throwing [ApiException]. */
    private fun executeText(req: Request): String {
        http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return text
            val err = runCatching {
                json.decodeFromString(
                    ErrorResp.serializer(),
                    text
                ).error
            }.getOrNull()
            throw ApiException(
                status = response.code,
                code = err?.code,
                message = err?.message ?: "HTTP ${response.code}",
            )
        }
    }

    private fun newKey(): String = UUID.randomUUID().toString()

    private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Validate a base URL is a well-formed http(s) URL. */
        fun isValidBaseUrl(url: String): Boolean =
            runCatching { url.trim().toHttpUrl() }.isSuccess
    }
}
