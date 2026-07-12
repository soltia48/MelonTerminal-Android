package jp.unknowntech.melonterminal.ui

import java.util.Locale

/** Yen with thousands separators, e.g. `¥1,234`. */
fun yen(n: Long): String = "¥" + String.format(Locale.JAPAN, "%,d", n)

/** Basis points as a percentage, e.g. 250 → `2.50%`. */
fun pct(bps: Int): String = String.format(Locale.JAPAN, "%.2f%%", bps / 100.0)

/** Shorten an opaque id for compact display (head … tail). */
fun shortId(id: String): String =
    if (id.length > 13) id.take(8) + "…" + id.takeLast(4) else id

/** First 10 chars of an ISO timestamp (the date), best-effort. */
fun isoDate(ts: String?): String = ts?.take(10) ?: ""
