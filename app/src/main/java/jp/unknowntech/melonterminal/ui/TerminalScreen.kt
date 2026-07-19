@file:OptIn(ExperimentalMaterial3Api::class)

package jp.unknowntech.melonterminal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import jp.unknowntech.melonterminal.R
import jp.unknowntech.melonterminal.core.Op
import jp.unknowntech.melonterminal.net.RefundableView

@Composable
fun TerminalScreen(vm: TerminalViewModel) {
    val state by vm.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showMerchant by remember { mutableStateOf(false) }

    // Force the settings screen until a valid API key is configured.
    LaunchedEffect(state.configured) { if (!state.configured) showSettings = true }

    if (showSettings) {
        SettingsScreen(vm, canClose = state.configured, onClose = { showSettings = false })
        return
    }
    if (showMerchant) {
        MerchantScreen(vm, onClose = { showMerchant = false })
        return
    }
    // POS-linked mode takes over the whole screen with its own kiosk face.
    if (state.mode == AppMode.POS) {
        PosScreen(vm)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Header(
            onPos = vm::enterPosMode,
            onMerchant = { showMerchant = true },
            onSettings = { showSettings = true },
        )
        if (state.nfc != NfcState.ENABLED) NfcBanner(state.nfc)
        OpTabs(state.op) { vm.setOp(it) }
        AmountArea(state, Modifier.weight(1f))
        // A note is only meaningful on a payment. Keep it ABOVE the keypad: the soft
        // keyboard rises over the keypad, so a field below it would be hidden — placing
        // it here lets the weighted amount area collapse and float the field into view.
        if (state.op == Op.PAY) {
            OutlinedTextField(
                value = state.memo,
                onValueChange = vm::setMemo,
                label = { Text("メモ(任意)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (state.op.needsAmount) {
            Keypad(onKey = vm::pressKey)
            // Pay/top-up require this explicit confirm before a card tap is accepted.
            Button(
                onClick = vm::arm,
                enabled = state.amountValue > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .height(56.dp),
            ) {
                Text(state.op.actionLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    when (val sheet = state.sheet) {
        Sheet.None -> {}
        is Sheet.Processing -> ProcessingSheet(sheet.message)
        is Sheet.Done -> DoneSheet(sheet, onClose = vm::dismissSheet)
        is Sheet.Failed -> FailedSheet(sheet, onClose = vm::dismissSheet)
        is Sheet.RefundSelect -> RefundSelectSheet(
            sheet,
            onPick = vm::pickRefund,
            onClose = vm::dismissSheet
        )

        is Sheet.RefundAmount -> RefundAmountSheet(
            sheet,
            onBack = vm::backToRefundSelect,
            onConfirm = { amt -> vm.executeRefund(sheet.item, amt) },
        )
    }

    // Armed pay/top-up: waiting for the card. Shown while nothing else is in progress.
    if (state.armed && state.sheet == Sheet.None) {
        ArmedSheet(op = state.op, amount = state.amountValue, onCancel = vm::disarm)
    }
}

@Composable
private fun ArmedSheet(op: Op, amount: Long, onCancel: () -> Unit) {
    SheetScaffold(dismissible = false, onDismiss = onCancel) {
        Text("📶", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text("カードをかざしてください", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            yen(amount),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (op == Op.TOPUP) "を チャージします" else "を お支払いいただきます",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) { Text("キャンセル") }
    }
}

@Composable
private fun Header(onPos: () -> Unit, onMerchant: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.melon_logo),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Melon 端末", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPos) { Text("🖥 POS連動") }
            TextButton(onClick = onMerchant) { Text("🏬 加盟店") }
            TextButton(onClick = onSettings) { Text("⚙ 設定") }
        }
    }
}

@Composable
private fun NfcBanner(nfc: NfcState) {
    val msg = when (nfc) {
        NfcState.DISABLED -> "NFC が無効です。設定から NFC をオンにしてください。"
        NfcState.UNSUPPORTED -> "この端末は NFC に対応していません。"
        NfcState.ENABLED -> return
    }
    Text(
        msg,
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun OpTabs(current: Op, onSelect: (Op) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Op.entries.forEach { op ->
            val selected = op == current
            Surface(
                onClick = { onSelect(op) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    op.label,
                    Modifier.padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AmountArea(state: UiState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        if (state.op.needsAmount) {
            Text(
                yen(state.amountValue),
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.amountValue == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.amountValue > 0) "金額を確認し「${state.op.actionLabel}」を押してください" else "金額を入力してください",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("カードをかざしてください", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.op == Op.BALANCE) "残高を表示します" else "返金できる支払いを表示します",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(onKey: (String) -> Unit) {
    // Calculator/numpad layout (7-8-9 on top), matching the desktop kiosk.
    // Bottom row: 0, 00, backspace (long-press backspace clears the whole amount).
    val keys = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "0", "00", "<")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .combinedClickable(
                                onClick = { onKey(key) },
                                onLongClick = { if (key == "<") onKey("C") },
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (key == "<") "⌫" else key,
                                fontSize = if (key == "<") 22.sp else 26.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----- sheets -----

@Composable
private fun SheetScaffold(
    dismissible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = false
        ),
    ) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

@Composable
private fun ProcessingSheet(message: String) {
    SheetScaffold(dismissible = false, onDismiss = {}) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text(message, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("カードを離さないでください", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailedSheet(sheet: Sheet.Failed, onClose: () -> Unit) {
    SheetScaffold(dismissible = true, onDismiss = onClose) {
        Text("⚠", fontSize = 40.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            sheet.error.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        sheet.error.detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
    }
}

@Composable
private fun DoneSheet(sheet: Sheet.Done, onClose: () -> Unit) {
    SheetScaffold(dismissible = true, onDismiss = onClose) {
        Text("✓", fontSize = 40.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        val title = when (sheet.op) {
            Op.PAY -> "支払い完了"
            Op.TOPUP -> "チャージ完了"
            Op.BALANCE -> "残高"
            Op.REFUND -> "返金完了"
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        when (val r = sheet.result) {
            is OpResult.Balance -> {
                Big(yen(r.r.total)); Caption("利用可能残高")
                Rows(buildList {
                    r.r.buckets.forEach { add("${isoDate(it.expires_at)} まで" to yen(it.remaining)) }
                    if (r.r.buckets.isEmpty()) add("有効な残高" to "なし")
                    sheet.accountId?.let { add("利用者 ID" to shortId(it)) }
                })
            }

            is OpResult.Pay -> {
                Big(yen(r.r.amount)); Caption("お支払いありがとうございます")
                Rows(rowsWith(sheet.accountId, "お支払い後残高" to yen(r.r.balance)))
            }

            is OpResult.Topup -> {
                Big(yen(r.r.amount)); Caption("チャージしました")
                Rows(
                    rowsWith(
                        sheet.accountId,
                        "チャージ後残高" to yen(r.r.balance),
                        "有効期限" to isoDate(r.r.expires_at)
                    )
                )
            }

            is OpResult.Refund -> {
                Big(yen(r.amount)); Caption("返金しました")
                Rows(buildList {
                    r.balance?.let { add("返金後残高" to yen(it)) }
                    sheet.accountId?.let { add("利用者 ID" to shortId(it)) }
                })
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("完了") }
    }
}

@Composable
private fun RefundSelectSheet(
    sheet: Sheet.RefundSelect,
    onPick: (RefundableView) -> Unit,
    onClose: () -> Unit
) {
    SheetScaffold(dismissible = true, onDismiss = onClose) {
        Text("返金する支払いを選択", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "利用者 ID ${shortId(sheet.accountId)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        if (sheet.items.isEmpty()) {
            Text(
                "返金できる支払いはありません",
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            sheet.items.forEach { p ->
                Surface(
                    onClick = { onPick(p) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(isoDate(p.occurred_at))
                            Text(
                                "支払 ${yen(p.amount)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(yen(p.refundable), fontWeight = FontWeight.Bold)
                            Text(
                                "返金可",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("戻る") }
    }
}

@Composable
private fun RefundAmountSheet(
    sheet: Sheet.RefundAmount,
    onBack: () -> Unit,
    onConfirm: (Long?) -> Unit
) {
    val max = sheet.item.refundable
    var amount by remember { mutableStateOf(max) }
    SheetScaffold(dismissible = false, onDismiss = onBack) {
        Text("返金", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "${isoDate(sheet.item.occurred_at)} の支払い ${yen(sheet.item.amount)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Big(yen(amount)); Caption("返金額(返金可能額 ${yen(max)})")
        Spacer(Modifier.height(12.dp))
        Keypad { key ->
            amount = when (key) {
                "C" -> 0L
                "<" -> amount / 10
                else -> (amount.toString().trimStart('0') + key).toLongOrNull()?.coerceAtMost(max)
                    ?: amount
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("戻る") }
            Button(
                onClick = { if (amount in 1..max) onConfirm(amount) },
                enabled = amount in 1..max,
                modifier = Modifier.weight(1f),
            ) { Text("返金する") }
        }
    }
}

// ----- small sheet building blocks -----

@Composable
private fun Big(text: String) =
    Text(text, fontSize = 40.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Caption(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (k, v) ->
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v)
            }
        }
        if (rows.isNotEmpty()) HorizontalDivider()
    }
}

private fun rowsWith(
    accountId: String?,
    vararg rows: Pair<String, String>
): List<Pair<String, String>> =
    buildList {
        addAll(rows)
        accountId?.let { add("利用者 ID" to shortId(it)) }
    }
