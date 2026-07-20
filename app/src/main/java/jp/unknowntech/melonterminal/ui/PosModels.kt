package jp.unknowntech.melonterminal.ui

import jp.unknowntech.melonterminal.pos.RefundableMsg
import jp.unknowntech.melonterminal.pos.ResultMsg

/** Which screen the terminal presents: the standalone keypad, or the POS bridge. */
enum class AppMode { STANDALONE, POS }

/**
 * The POS business types, with their on-screen label. [needsCard] jobs read a card (and
 * show the status rectangles); [REFUND] talks to the server only and shows no rectangles.
 * The refund lookup ([REFUND_QUERY]) and the refund itself ([REFUND]) are fully separate:
 * the refund can run standalone with a payment id supplied directly by POS.
 */
enum class PosJob(val wire: String, val label: String, val needsCard: Boolean) {
    PAYMENT("payment", "支払", true),
    TOPUP("topup", "チャージ", true),
    BALANCE("balance", "残高照会", true),
    REFUND_QUERY("refund_query", "返金照会", true),
    REFUND("refund", "返金", false);

    /** Only card-driven jobs light the touch rectangles. */
    val showRects: Boolean get() = needsCard
}

/**
 * Lifecycle of a single POS transaction. Only [SUCCESS], [FAILED] and [CANCELLED] are
 * terminal.
 */
enum class PosTxnState(val wire: String) {
    PENDING("pending"),
    WAITING_CARD("waiting_card"),
    PROCESSING("processing"),
    SUCCESS("success"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean get() = this == SUCCESS || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = !isTerminal

    /**
     * Cancellable only before the card tap / server operation. Once [PROCESSING], a
     * cancel is refused: the server may already have settled the transaction, so
     * marking it cancelled on the terminal alone would desync the two sides.
     */
    val isCancellable: Boolean get() = this == PENDING || this == WAITING_CARD
}

/**
 * One POS-driven transaction. [statusText] is the operator-facing status string (an
 * existing terminal string) and is also sent to POS. [result] is set once the
 * transaction completes; [refundable] holds the choices while awaiting a refund pick.
 */
data class PosTxn(
    val id: String,
    val requestId: String?,
    val job: PosJob,
    val amount: Long,
    val state: PosTxnState,
    val statusText: String,
    val note: String? = null,
    val accountId: String? = null,
    val refundable: List<RefundableMsg> = emptyList(),
    val result: ResultMsg? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * POS-mode UI state. [txn] is the current or most recently finished transaction (null =
 * idle, showing 待受中). [displayCleared] flips true 5 s after a transaction finishes so
 * the screen returns to 待受中 while the final [txn] stays queryable by polling.
 */
data class PosUiState(
    val serverRunning: Boolean = false,
    val address: String? = null,
    val txn: PosTxn? = null,
    val displayCleared: Boolean = false,
) {
    /** True when the screen should show the plain 待受中 splash. */
    val idle: Boolean get() = txn == null || displayCleared
}
