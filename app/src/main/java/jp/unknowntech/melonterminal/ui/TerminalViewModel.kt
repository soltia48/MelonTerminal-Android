package jp.unknowntech.melonterminal.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.unknowntech.melonterminal.core.AuthOutcome
import jp.unknowntech.melonterminal.core.CardFlow
import jp.unknowntech.melonterminal.core.Op
import jp.unknowntech.melonterminal.core.Settings
import jp.unknowntech.melonterminal.core.UiError
import jp.unknowntech.melonterminal.core.classify
import jp.unknowntech.melonterminal.net.ApiException
import jp.unknowntech.melonterminal.net.BalanceResp
import jp.unknowntech.melonterminal.net.MelonClient
import jp.unknowntech.melonterminal.net.MerchantView
import jp.unknowntech.melonterminal.net.PayResp
import jp.unknowntech.melonterminal.net.RefundableView
import jp.unknowntech.melonterminal.net.TopupResp
import jp.unknowntech.melonterminal.nfc.CardSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class NfcState { ENABLED, DISABLED, UNSUPPORTED }

/** The server result to render on the "done" sheet. */
sealed interface OpResult {
    data class Balance(val r: BalanceResp) : OpResult
    data class Pay(val r: PayResp) : OpResult
    data class Topup(val r: TopupResp) : OpResult
    data class Refund(val amount: Long, val balance: Long?) : OpResult
}

/** Modal shown over the main screen. */
sealed interface Sheet {
    data object None : Sheet
    data class Processing(val message: String) : Sheet
    data class Done(val op: Op, val result: OpResult, val accountId: String?) : Sheet
    data class Failed(val error: UiError) : Sheet
    data class RefundSelect(val accountId: String, val items: List<RefundableView>) : Sheet
    data class RefundAmount(val item: RefundableView, val select: RefundSelect) : Sheet
}

/** Load state of the merchant-info screen. */
sealed interface MerchantLoad {
    data object Loading : MerchantLoad
    data class Loaded(val merchant: MerchantView) : MerchantLoad
    data class Failed(val error: UiError) : MerchantLoad
}

data class UiState(
    val op: Op = Op.PAY,
    val amount: String = "",
    val configured: Boolean = false,
    val nfc: NfcState = NfcState.ENABLED,
    val serverUrl: String = Settings.DEFAULT_SERVER,
    val sheet: Sheet = Sheet.None,
    val busy: Boolean = false,
    /** For pay/top-up only: the operator pressed 支払う/チャージする, so a tap is now
     *  accepted. Guards against an accidental tap charging a card. */
    val armed: Boolean = false,
    /** Optional free-text note the merchant attaches to a payment. */
    val memo: String = "",
) {
    val amountValue: Long get() = amount.toLongOrNull() ?: 0L
}

private const val MAX_AMOUNT_DIGITS = 7 // ¥9,999,999
private const val MAX_MEMO_LEN = 200

class TerminalViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    private val _state = MutableStateFlow(
        UiState(configured = settings.isConfigured, serverUrl = settings.serverUrl)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile
    private var systemCodesCache: List<Int>? = null

    /** True while a tap is being processed — blocks the reader re-dispatching the same
     *  card (which it does on every connect/close cycle) as a fresh operation. */
    private val handling = AtomicBoolean(false)

    private fun client(): MelonClient? =
        settings.apiKey?.let { MelonClient(settings.serverUrl, it) }

    // ----- UI events -----

    fun setOp(op: Op) = _state.update { it.copy(op = op, amount = "", armed = false, memo = "") }

    fun setMemo(memo: String) = _state.update { it.copy(memo = memo.take(MAX_MEMO_LEN)) }

    fun pressKey(key: String) = _state.update { st ->
        if (!st.op.needsAmount) return@update st
        val next = when (key) {
            "C" -> ""
            "<" -> st.amount.dropLast(1)
            else -> (st.amount + key).trimStart('0').take(MAX_AMOUNT_DIGITS)
        }
        st.copy(amount = next)
    }

    /** Arm a pay/top-up: after this, one card tap is accepted. No-op unless an amount
     *  is entered and nothing else is in progress. */
    fun arm() = _state.update { st ->
        if (st.op.needsAmount && st.amountValue > 0 && st.sheet == Sheet.None) st.copy(armed = true) else st
    }

    fun disarm() = _state.update { it.copy(armed = false) }

    fun dismissSheet() =
        _state.update { it.copy(sheet = Sheet.None, amount = "", armed = false, memo = "") }

    fun setNfcState(nfc: NfcState) = _state.update { it.copy(nfc = nfc) }

    fun refreshConfigured() =
        _state.update {
            it.copy(
                configured = settings.isConfigured,
                serverUrl = settings.serverUrl
            )
        }

    // ----- settings -----

    /** Validate the key against `/v1/system-codes`, and save it on success. Returns a
     *  Japanese error message, or null on success. */
    suspend fun saveConfig(serverUrl: String, apiKey: String): String? {
        val url = serverUrl.trim()
        val key = apiKey.trim()
        if (key.isEmpty()) return "API キーを入力してください"
        if (!MelonClient.isValidBaseUrl(url)) return "サーバ URL が正しくありません"
        return try {
            MelonClient(url, key).systemCodes() // 401/403 if the key is wrong
            settings.serverUrl = url
            settings.apiKey = key
            systemCodesCache = null
            refreshConfigured()
            null
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) "API キーが正しくありません"
            else "サーバエラー: ${e.message}"
        } catch (_: Exception) {
            "サーバに接続できません"
        }
    }

    // ----- merchant info -----

    /** Fetch this terminal's merchant profile (`GET /v1/me`). */
    suspend fun fetchMerchant(): MerchantLoad {
        val client = client()
            ?: return MerchantLoad.Failed(
                UiError(
                    "端末が未設定です",
                    "「設定」から API キーを入力してください"
                )
            )
        return runCatching { MerchantLoad.Loaded(client.me()) }
            .getOrElse { MerchantLoad.Failed(classify(it)) }
    }

    // ----- tap flow -----

    /**
     * Handle a tapped card. Called on the NFC reader-mode callback thread — the tag is
     * connected **here, synchronously**, before any coroutine hop, so it can't go stale
     * ("Tag is out of date"). The already-connected card is then handed to a background
     * coroutine that drives the online relay + operation, and is closed at the end.
     */
    fun onTag(tag: Tag) {
        val s = _state.value
        // Accept a tap only when idle — no modal is showing. This is what stops a card
        // left on the reader from being charged again and again: after an operation the
        // result sheet stays up until dismissed, so every re-dispatch is ignored.
        if (s.sheet != Sheet.None) return
        // Pay/top-up must be explicitly armed (支払う/チャージする pressed) before a tap
        // counts — this is what prevents an accidental tap from charging a card. Balance
        // and refund carry no amount, so they accept a tap directly.
        if (s.op.needsAmount && (!s.armed || s.amountValue <= 0)) return
        // Reader Mode fires on a binder thread and re-dispatches the same card; take the
        // gate atomically so only one tap is ever in flight.
        if (!handling.compareAndSet(false, true)) return
        val client = client()
        if (client == null) {
            handling.set(false)
            _state.update {
                it.copy(
                    sheet = Sheet.Failed(
                        UiError(
                            "端末が未設定です",
                            "「設定」から API キーを入力してください"
                        )
                    )
                )
            }
            return
        }
        // Connect right now, on this (reader-mode callback) thread, to avoid the deferral
        // window that makes the tag go out of date.
        val nfcF = try {
            CardSession.connect(tag)
        } catch (e: Exception) {
            handling.set(false)
            _state.update { it.copy(sheet = Sheet.Failed(classify(e))) }
            return
        }
        _state.update { it.copy(busy = true, armed = false, sheet = Sheet.Processing("処理中…")) }
        val op = s.op
        val amount = s.amountValue
        val note = s.memo.trim().ifBlank { null }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sheet = runCatching {
                    val codes = systemCodes(client)
                    val auth = CardFlow.authenticate(nfcF, client, codes)
                    runOperation(op, amount, note, client, auth)
                }.getOrElse { Sheet.Failed(classify(it)) }
                _state.update { it.copy(busy = false, sheet = sheet) }
            } finally {
                runCatching { nfcF.close() }
                handling.set(false)
            }
        }
    }

    private suspend fun systemCodes(client: MelonClient): List<Int> =
        systemCodesCache ?: client.systemCodes().also { systemCodesCache = it }

    private suspend fun runOperation(
        op: Op,
        amount: Long,
        note: String?,
        client: MelonClient,
        auth: AuthOutcome,
    ): Sheet = when (op) {
        Op.BALANCE -> Sheet.Done(
            op,
            OpResult.Balance(client.balance(auth.sessionId)),
            auth.accountId
        )

        Op.PAY -> Sheet.Done(
            op,
            OpResult.Pay(client.pay(auth.sessionId, amount, note)),
            auth.accountId
        )

        Op.TOPUP -> Sheet.Done(
            op,
            OpResult.Topup(client.topup(auth.sessionId, amount)),
            auth.accountId
        )

        Op.REFUND -> Sheet.RefundSelect(auth.accountId, client.refundable(auth.accountId))
    }

    // ----- refund phase 2 (no card needed) -----

    fun pickRefund(item: RefundableView) = _state.update {
        val cur = it.sheet
        if (cur is Sheet.RefundSelect) it.copy(sheet = Sheet.RefundAmount(item, cur)) else it
    }

    fun backToRefundSelect() = _state.update {
        val cur = it.sheet
        if (cur is Sheet.RefundAmount) it.copy(sheet = cur.select) else it
    }

    fun executeRefund(item: RefundableView, amount: Long?) {
        val client = client() ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, sheet = Sheet.Processing("返金処理中…")) }
            val sheet = runCatching {
                val r = client.refund(item.id, amount)
                Sheet.Done(
                    Op.REFUND,
                    OpResult.Refund(r.amount, r.balance),
                    item.account_id
                ) as Sheet
            }.getOrElse { Sheet.Failed(classify(it)) }
            _state.update { it.copy(busy = false, sheet = sheet) }
        }
    }
}
