package jp.unknowntech.melonterminal.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.unknowntech.melonterminal.net.MerchantView

@Composable
fun MerchantScreen(vm: TerminalViewModel, onClose: () -> Unit) {
    var load by remember { mutableStateOf<MerchantLoad>(MerchantLoad.Loading) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadTick) {
        load = MerchantLoad.Loading
        load = vm.fetchMerchant()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("加盟店情報", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextButton(onClick = onClose) { Text("閉じる") }
        }

        when (val st = load) {
            MerchantLoad.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is MerchantLoad.Failed -> FailedBody(st, onRetry = { reloadTick++ })
            is MerchantLoad.Loaded -> LoadedBody(st.merchant, onRefresh = { reloadTick++ })
        }
    }
}

@Composable
private fun FailedBody(st: MerchantLoad.Failed, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠", fontSize = 40.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            st.error.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        st.error.detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("再読み込み") }
    }
}

@Composable
private fun LoadedBody(m: MerchantView, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Settlement balance — the headline figure, red when the merchant owes the issuer.
        Stat("精算残高(発行者からの受取額)", yen(m.collected), negative = m.collected < 0)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("決済手数料率", pct(m.fee_bps), Modifier.weight(1f))
            Stat("与信限度", yen(m.credit_limit), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Stat(
            "チャージ可能額(余力)",
            yen(m.collected + m.credit_limit),
            negative = m.collected + m.credit_limit < 0,
        )

        Spacer(Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("加盟店", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                InfoRow("コード", m.code)
                InfoRow("名称", m.name)
                InfoRow("状態", statusLabel(m.status))
                InfoRow("加盟店 ID", m.id)
                InfoRow("登録日時", isoDate(m.created_at))
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "精算残高 = 受領した支払 − チャージ取扱 − 返金・取消 + 調整。" +
                    "マイナスは発行者への支払い(集金した金額)を表します。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        ) { Text("再読み込み") }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    negative: Boolean = false
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    HorizontalDivider()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, textAlign = TextAlign.End)
    }
}

private fun statusLabel(status: String): String = when (status) {
    "active" -> "有効"
    "suspended" -> "停止中"
    "closed" -> "閉鎖"
    else -> status
}
