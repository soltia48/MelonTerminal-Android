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
import jp.unknowntech.melonterminal.pos.AnnounceMsg
import jp.unknowntech.melonterminal.pos.BucketMsg
import jp.unknowntech.melonterminal.pos.ErrorMsg
import jp.unknowntech.melonterminal.pos.InfoMsg
import jp.unknowntech.melonterminal.pos.PosCommandHandler
import jp.unknowntech.melonterminal.pos.PosError
import jp.unknowntech.melonterminal.pos.PosReply
import jp.unknowntech.melonterminal.pos.PosServer
import jp.unknowntech.melonterminal.pos.RefundableMsg
import jp.unknowntech.melonterminal.pos.ResultMsg
import jp.unknowntech.melonterminal.pos.StatusMsg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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
    /** Standalone keypad, or the POS-linked bridge. */
    val mode: AppMode = AppMode.STANDALONE,
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

class TerminalViewModel(app: Application) : AndroidViewModel(app), PosCommandHandler {
    private val settings = Settings(app)

    private val _state = MutableStateFlow(
        UiState(configured = settings.isConfigured, serverUrl = settings.serverUrl)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _pos = MutableStateFlow(PosUiState())
    val pos: StateFlow<PosUiState> = _pos.asStateFlow()

    private val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    @Volatile
    private var posServer: PosServer? = null

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
        if (_state.value.mode == AppMode.POS) onTagPos(tag) else onTagStandalone(tag)
    }

    private fun onTagStandalone(tag: Tag) {
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

    // ===================== POS-linked mode =====================
    //
    // The terminal answers commands from a POS register over the LAN (see PosServer and
    // docs/POS-Protocol.md). A single transaction is tracked in [_pos]; the network
    // handler methods below run on PosServer threads and mutate it through the
    // thread-safe MutableStateFlow. A card-driven job (payment/balance/refund-query) is
    // only *armed* here — the actual tap is delivered later to [onTagPos].

    /** Enter POS mode and bring the LAN server up. */
    fun enterPosMode() {
        if (_state.value.mode == AppMode.POS) return
        _state.update { it.copy(mode = AppMode.POS) }
        startPosServer()
    }

    /** Leave POS mode: cancel any live transaction and shut the server down. */
    fun exitPosMode() {
        if (_state.value.mode == AppMode.STANDALONE) return
        handleCancel(null)
        stopPosServer()
        _pos.value = PosUiState()
        _state.update { it.copy(mode = AppMode.STANDALONE) }
    }

    /** Terminal-side cancel button — same path as a POS `cancel`. */
    fun posCancel() {
        handleCancel(null)
    }

    private fun startPosServer() {
        if (posServer != null) return
        val server = PosServer(getApplication(), this)
        server.start()
        posServer = server
        _pos.update { it.copy(serverRunning = true, address = server.address) }
    }

    private fun stopPosServer() {
        posServer?.stop()
        posServer = null
        _pos.update { it.copy(serverRunning = false, address = null) }
    }

    // ----- PosCommandHandler (called on PosServer network threads) -----

    override fun handlePayment(requestId: String?, amount: Long, note: String?): PosReply {
        if (client() == null) return notConfigured()
        if (amount <= 0) return badRequest("amount must be positive")
        activeConflict(requestId, PosJob.PAYMENT)?.let { return it }
        val txn = PosTxn(
            id = newTxnId(),
            requestId = requestId,
            job = PosJob.PAYMENT,
            amount = amount,
            state = PosTxnState.PENDING,
            statusText = PENDING_TEXT,
            note = note?.trim()?.ifBlank { null },
        )
        setTxn(txn)
        prepareForCard(txn.id)
        return statusReply(txn)
    }

    override fun handleTopup(requestId: String?, amount: Long): PosReply {
        if (client() == null) return notConfigured()
        if (amount <= 0) return badRequest("amount must be positive")
        activeConflict(requestId, PosJob.TOPUP)?.let { return it }
        val txn = PosTxn(
            id = newTxnId(),
            requestId = requestId,
            job = PosJob.TOPUP,
            amount = amount,
            state = PosTxnState.PENDING,
            statusText = PENDING_TEXT,
        )
        setTxn(txn)
        prepareForCard(txn.id)
        return statusReply(txn)
    }

    override fun handleBalance(requestId: String?): PosReply {
        if (client() == null) return notConfigured()
        activeConflict(requestId, PosJob.BALANCE)?.let { return it }
        val txn = PosTxn(
            id = newTxnId(),
            requestId = requestId,
            job = PosJob.BALANCE,
            amount = 0,
            state = PosTxnState.PENDING,
            statusText = PENDING_TEXT,
        )
        setTxn(txn)
        prepareForCard(txn.id)
        return statusReply(txn)
    }

    override fun handleRefundQuery(requestId: String?): PosReply {
        if (client() == null) return notConfigured()
        activeConflict(requestId, PosJob.REFUND_QUERY)?.let { return it }
        val txn = PosTxn(
            id = newTxnId(),
            requestId = requestId,
            job = PosJob.REFUND_QUERY,
            amount = 0,
            state = PosTxnState.PENDING,
            statusText = PENDING_TEXT,
        )
        setTxn(txn)
        prepareForCard(txn.id)
        return statusReply(txn)
    }

    /**
     * Execute a refund. This is fully independent of [handleRefundQuery]: POS may pass a
     * `payment_id` it already knows (e.g. recorded at payment time) without any prior
     * query. No card is involved — the terminal only talks to the server. The server
     * validates the payment_id and amount and reports any error (REFUND_EXCEEDS_PAYMENT,
     * NOT_FOUND, …).
     */
    override fun handleRefundExecute(requestId: String?, paymentId: String, amount: Long?): PosReply {
        if (client() == null) return notConfigured()
        if (paymentId.isBlank()) return badRequest("refund_execute requires payment_id")
        if (amount != null && amount < 1) return badRequest("amount must be positive")
        activeConflict(requestId, PosJob.REFUND)?.let { return it }
        val txn = PosTxn(
            id = newTxnId(),
            requestId = requestId,
            job = PosJob.REFUND,
            amount = amount ?: 0,
            state = PosTxnState.PROCESSING,
            statusText = PROCESSING_TEXT,
        )
        setTxn(txn)
        executeRefundPos(txn.id, paymentId, amount)
        return statusReply(txn)
    }

    override fun handleStatus(): PosReply = statusReply(_pos.value.txn)

    override fun handleCancel(requestId: String?): PosReply {
        var cancelledId: String? = null
        _pos.update { st ->
            val cur = st.txn
            // Ignore a cancel once processing has begun — refusing it keeps the terminal
            // in sync with the server (see PosTxnState.isCancellable). The current status
            // is returned unchanged so POS sees the transaction still in progress.
            if (cur != null && cur.state.isCancellable) {
                cancelledId = cur.id
                st.copy(
                    txn = cur.copy(
                        state = PosTxnState.CANCELLED,
                        statusText = CANCELLED_TEXT,
                        result = ResultMsg(ok = false, code = "CANCELLED", title = CANCELLED_TEXT),
                        updatedAt = now(),
                    )
                )
            } else st
        }
        cancelledId?.let { scheduleClear(it) }
        return statusReply(_pos.value.txn)
    }

    override fun handleInfo(): PosReply = PosReply.Info(
        InfoMsg(
            terminal_id = settings.terminalId,
            name = settings.terminalName,
            app_version = appVersion,
        )
    )

    override fun announce(localIp: String?): AnnounceMsg = AnnounceMsg(
        terminal_id = settings.terminalId,
        name = settings.terminalName,
        ip = localIp,
        app_version = appVersion,
        state = _pos.value.let { if (it.idle) "idle" else it.txn!!.state.wire },
    )

    // ----- POS tap flow -----

    private fun onTagPos(tag: Tag) {
        val txn = _pos.value.txn ?: return
        if (txn.state != PosTxnState.WAITING_CARD) return
        if (!handling.compareAndSet(false, true)) return
        val client = client()
        if (client == null) {
            handling.set(false)
            completeFail(txn.id, ResultMsg(ok = false, code = PosError.NOT_CONFIGURED, title = "端末が未設定です"))
            return
        }
        val nfcF = try {
            CardSession.connect(tag)
        } catch (e: Exception) {
            handling.set(false)
            completeFail(txn.id, errorResult(e))
            return
        }
        updateTxn(txn.id) { it.copy(state = PosTxnState.PROCESSING, statusText = PROCESSING_TEXT, updatedAt = now()) }
        val id = txn.id
        val job = txn.job
        val amount = txn.amount
        val note = txn.note
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCardPhase(id, job, amount, note, client, nfcF)
            } finally {
                runCatching { nfcF.close() }
                handling.set(false)
            }
        }
    }

    private suspend fun runCardPhase(
        id: String,
        job: PosJob,
        amount: Long,
        note: String?,
        client: MelonClient,
        nfcF: android.nfc.tech.NfcF,
    ) {
        val auth = runCatching {
            val codes = systemCodes(client)
            CardFlow.authenticate(nfcF, client, codes)
        }.getOrElse { completeFail(id, errorResult(it)); return }

        updateTxn(id) { it.copy(accountId = auth.accountId) }

        when (job) {
            PosJob.PAYMENT -> {
                val r = runCatching { client.pay(auth.sessionId, amount, note) }
                    .getOrElse { completeFail(id, errorResult(it)); return }
                completeSuccess(
                    id, "支払完了",
                    ResultMsg(
                        ok = true,
                        account_id = auth.accountId,
                        amount = r.amount,
                        fee = r.fee,
                        balance = r.balance,
                    )
                )
            }

            PosJob.TOPUP -> {
                val r = runCatching { client.topup(auth.sessionId, amount) }
                    .getOrElse { completeFail(id, errorResult(it)); return }
                completeSuccess(
                    id, "チャージ完了",
                    ResultMsg(
                        ok = true,
                        account_id = auth.accountId,
                        amount = r.amount,
                        balance = r.balance,
                        expires_at = r.expires_at,
                    )
                )
            }

            PosJob.BALANCE -> {
                val r = runCatching { client.balance(auth.sessionId) }
                    .getOrElse { completeFail(id, errorResult(it)); return }
                completeSuccess(
                    id, BALANCE_DONE_TEXT,
                    ResultMsg(
                        ok = true,
                        account_id = r.account_id,
                        total = r.total,
                        buckets = r.buckets.map { BucketMsg(it.bucket_id, it.remaining, it.expires_at) },
                    )
                )
            }

            PosJob.REFUND_QUERY -> {
                val list = runCatching { client.refundable(auth.accountId) }
                    .getOrElse { completeFail(id, errorResult(it)); return }
                val refundable = list.map {
                    RefundableMsg(it.id, it.amount, it.fee, it.refunded, it.refundable, it.occurred_at)
                }
                // A lookup is complete once the list is fetched — it is its own terminal
                // transaction (like a balance inquiry). The choices go to POS in the
                // status; POS later runs an independent refund_execute.
                var done = false
                _pos.update { st ->
                    val cur = st.txn
                    if (cur != null && cur.id == id && cur.state == PosTxnState.PROCESSING) {
                        done = true
                        st.copy(
                            txn = cur.copy(
                                state = PosTxnState.SUCCESS,
                                statusText = REFUND_QUERY_DONE_TEXT,
                                accountId = auth.accountId,
                                refundable = refundable,
                                result = ResultMsg(ok = true, account_id = auth.accountId),
                                updatedAt = now(),
                            )
                        )
                    } else st
                }
                if (done) scheduleClear(id)
            }

            // Refund execution is card-free (handled in executeRefundPos) and never
            // reaches this card phase; present only for exhaustiveness.
            PosJob.REFUND -> Unit
        }
    }

    private fun executeRefundPos(id: String, paymentId: String, amount: Long?) {
        val client = client()
        if (client == null) {
            completeFail(id, ResultMsg(ok = false, code = PosError.NOT_CONFIGURED, title = "端末が未設定です"))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val r = client.refund(paymentId, amount)
                ResultMsg(ok = true, account_id = _pos.value.txn?.accountId, amount = r.amount, balance = r.balance)
            }.getOrElse { errorResult(it) }
            if (result.ok) completeSuccess(id, REFUND_DONE_TEXT, result) else completeFail(id, result)
        }
    }

    // ----- POS card preparation & completion -----

    /** Prime the system-code cache (the "connecting to Melon" phase), then wait for a tap. */
    private fun prepareForCard(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = client()
            if (client == null) {
                completeFail(id, ResultMsg(ok = false, code = PosError.NOT_CONFIGURED, title = "端末が未設定です"))
                return@launch
            }
            val err = runCatching { systemCodes(client) }.exceptionOrNull()
            if (err != null) {
                completeFail(id, errorResult(err))
                return@launch
            }
            updateTxn(id) { t ->
                if (t.state == PosTxnState.PENDING) {
                    t.copy(state = PosTxnState.WAITING_CARD, statusText = WAITING_CARD_TEXT, updatedAt = now())
                } else t
            }
        }
    }

    private fun completeSuccess(id: String, statusText: String, result: ResultMsg) {
        var done = false
        _pos.update { st ->
            val cur = st.txn
            if (cur != null && cur.id == id && cur.state.isActive) {
                done = true
                st.copy(txn = cur.copy(state = PosTxnState.SUCCESS, statusText = statusText, result = result, updatedAt = now()))
            } else st
        }
        if (done) scheduleClear(id)
    }

    private fun completeFail(id: String, result: ResultMsg) {
        var done = false
        _pos.update { st ->
            val cur = st.txn
            if (cur != null && cur.id == id && cur.state.isActive) {
                done = true
                st.copy(txn = cur.copy(state = PosTxnState.FAILED, statusText = result.title ?: "エラー", result = result, updatedAt = now()))
            } else st
        }
        if (done) scheduleClear(id)
    }

    /** After the 5 s result display, return the screen to 待受中 (the record stays
     *  queryable by polling until a new command replaces it). */
    private fun scheduleClear(id: String) {
        viewModelScope.launch {
            delay(RESULT_DISPLAY_MS)
            _pos.update { st ->
                if (st.txn?.id == id && st.txn.state.isTerminal) st.copy(displayCleared = true) else st
            }
        }
    }

    // ----- POS helpers -----

    private fun setTxn(txn: PosTxn) = _pos.update { it.copy(txn = txn, displayCleared = false) }

    private fun updateTxn(id: String, transform: (PosTxn) -> PosTxn) = _pos.update { st ->
        val cur = st.txn
        if (cur != null && cur.id == id) st.copy(txn = transform(cur)) else st
    }

    /**
     * BUSY when a transaction is already live. A retry is treated as idempotent (and the
     * live status returned) only when BOTH the request_id AND the job match — otherwise a
     * reused request_id on a different command must not silently ride the wrong
     * transaction (e.g. a `topup` returning a still-running `balance`).
     */
    private fun activeConflict(requestId: String?, job: PosJob): PosReply? {
        val active = _pos.value.txn?.takeIf { it.state.isActive } ?: return null
        return if (requestId != null && active.requestId == requestId && active.job == job) statusReply(active)
        else PosReply.Error(ErrorMsg(code = PosError.BUSY, message = "a transaction is already in progress"))
    }

    private fun statusReply(txn: PosTxn?): PosReply.Status = PosReply.Status(txn?.toStatusMsg() ?: idleStatus())

    private fun PosTxn.toStatusMsg(): StatusMsg = StatusMsg(
        transaction_id = id,
        request_id = requestId,
        job = job.wire,
        state = state.wire,
        status_text = statusText,
        amount = amount.takeIf { it > 0 },
        refundable = refundable.takeIf { it.isNotEmpty() },
        updated_at = updatedAt,
        result = result,
    )

    private fun idleStatus(): StatusMsg =
        StatusMsg(state = "idle", status_text = IDLE_TEXT, updated_at = now())

    private fun errorResult(e: Throwable): ResultMsg {
        val code = (e as? ApiException)?.code
        val ui = classify(e)
        return ResultMsg(ok = false, code = code, title = ui.title, detail = ui.detail)
    }

    private fun notConfigured(): PosReply =
        PosReply.Error(ErrorMsg(code = PosError.NOT_CONFIGURED, message = "terminal has no API key configured"))

    private fun badRequest(message: String): PosReply =
        PosReply.Error(ErrorMsg(code = PosError.BAD_REQUEST, message = message))

    private fun newTxnId(): String = UUID.randomUUID().toString()

    private fun now(): Long = System.currentTimeMillis()

    override fun onCleared() {
        super.onCleared()
        stopPosServer()
    }

    private companion object {
        const val RESULT_DISPLAY_MS = 5_000L
        const val IDLE_TEXT = "待受中"
        const val PENDING_TEXT = "お待ちください"
        const val WAITING_CARD_TEXT = "カードをかざしてください"
        const val PROCESSING_TEXT = "処理中…"
        const val BALANCE_DONE_TEXT = "残高照会完了"
        const val REFUND_QUERY_DONE_TEXT = "照会完了しました"
        const val REFUND_DONE_TEXT = "返金完了"
        const val CANCELLED_TEXT = "キャンセルしました"
    }
}
