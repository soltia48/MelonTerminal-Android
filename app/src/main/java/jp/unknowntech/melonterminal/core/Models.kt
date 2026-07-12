package jp.unknowntech.melonterminal.core

import android.nfc.TagLostException
import jp.unknowntech.melonterminal.net.ApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The four terminal operations, in tab order. */
enum class Op(val label: String, val actionLabel: String, val needsAmount: Boolean) {
    PAY("支払い", "支払う", true),
    TOPUP("チャージ", "チャージする", true),
    BALANCE("残高", "残高を確認", false),
    REFUND("返金", "返金する支払いを表示", false),
}

/** Result of a completed mutual authentication for one tapped card. */
data class AuthOutcome(
    val systemCode: Int,
    val sessionId: String,
    /** This merchant's pseudonym for the card — never the raw IDi. */
    val accountId: String,
)

/** The card exposes no system code that this server can authenticate. */
class NoSupportedSystemException :
    IOException("この端末が対応するシステムをカードが持っていません")

/** A user-facing error split into a title and an optional detail line. */
data class UiError(val title: String, val detail: String?)

/** Map a server error `code` (or a fallback message) to friendly Japanese text. */
fun localizeError(code: String?, fallback: String?): UiError = when (code) {
    "INSUFFICIENT_FUNDS" -> UiError("残高が不足しています", "チャージしてからお試しください")
    "CREDIT_LIMIT_EXCEEDED" -> UiError("チャージできません", "加盟店の与信限度を超えています")
    "REFUND_EXCEEDS_PAYMENT" -> UiError("返金できません", "返金可能額を超えています")
    "UNAUTHORIZED" -> UiError("端末の設定エラー", "API キーを確認してください")
    "FORBIDDEN" -> UiError("この加盟店は利用できません", null)
    "BAD_REQUEST" -> UiError("入力を確認してください", null)
    "IDEMPOTENCY_CONFLICT" -> UiError("すでに処理済みです", null)
    "NOT_FOUND" -> UiError("対象が見つかりません", null)
    "INTERNAL" -> UiError("サーバでエラーが発生しました", "しばらくして再度お試しください")
    else -> UiError("エラーが発生しました", fallback)
}

/** Classify any failure from the tap flow into a user-facing error. */
fun classify(e: Throwable): UiError = when (e) {
    is ApiException -> localizeError(e.code, e.message)
    is TagLostException -> UiError("カードが離れました", "もう一度かざしてください")
    is NoSupportedSystemException -> UiError(
        "対応していないカードです",
        "このカードは利用できません"
    )

    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        UiError("接続できません", "通信環境を確認してください")
    // "Permission Denial: Tag … is out of date" — the platform invalidated the tag
    // handle. Ask the user to tap again.
    is SecurityException -> UiError("カードを認識できませんでした", "もう一度かざしてください")
    is IOException -> UiError("カードを認識できませんでした", "もう一度かざしてください")
    else -> UiError("エラーが発生しました", e.message)
}
