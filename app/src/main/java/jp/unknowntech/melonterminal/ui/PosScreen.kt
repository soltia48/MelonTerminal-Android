package jp.unknowntech.melonterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// A fixed white kiosk face, independent of the app theme.
private val BG = Color.White
private val FG = Color.Black
private val OFF = Color.White
private val CYAN = Color(0xFF0074FF)
private val RED = Color(0xFFFF0000)

// Status rectangle, 1:1.5 (height:width).
private val RECT_H = 30.dp
private val RECT_W = 45.dp

/** Blink half-period: 0.5s on, 0.5s off. */
private const val BLINK_HALF_MS = 500L

@Composable
fun PosScreen(vm: TerminalViewModel) {
    val pos by vm.pos.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        val txn = pos.txn
        if (pos.idle || txn == null) {
            IdleFace()
        } else {
            PaymentFace(txn = txn, onCancel = vm::posCancel)
        }

        // Minimal exit affordance — returns to the standalone keypad.
        TextButton(
            onClick = vm::exitPosMode,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding(),
        ) {
            Text("← スタンドアロン", color = Color(0xFF9E9E9E), fontSize = 13.sp)
        }
    }
}

/** 待受中 splash: centered black text on white. */
@Composable
private fun IdleFace() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("待受中", color = FG, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

/** Transaction face: status rectangles, business type, amount, status line. */
@Composable
private fun PaymentFace(txn: PosTxn, onCancel: () -> Unit) {
    val blinking = txn.state == PosTxnState.WAITING_CARD || txn.state == PosTxnState.PROCESSING
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(blinking) {
        if (!blinking) {
            blinkOn = true
        } else {
            while (true) {
                blinkOn = true
                delay(BLINK_HALF_MS)
                blinkOn = false
                delay(BLINK_HALF_MS)
            }
        }
    }
    val rectColor = when (txn.state) {
        PosTxnState.WAITING_CARD, PosTxnState.PROCESSING -> if (blinkOn) CYAN else OFF
        PosTxnState.SUCCESS -> CYAN
        PosTxnState.FAILED -> RED
        else -> OFF // pending / cancelled
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val quarter = maxHeight * 0.25f

        // Card-free jobs (refund execution) show no rectangles.
        if (txn.job.showRects) {
            StatusRect(rectColor, Modifier.align(Alignment.TopStart))
            StatusRect(rectColor, Modifier.align(Alignment.TopEnd))
            StatusRect(rectColor, Modifier.align(Alignment.TopStart).offset(y = quarter - RECT_H / 2))
            StatusRect(rectColor, Modifier.align(Alignment.TopEnd).offset(y = quarter - RECT_H / 2))
        }

        // Center-ish column: title slightly above the middle.
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(RECT_H))
            Text(
                "Melon ${txn.job.label}",
                color = FG,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            // Prominent value per job; a refund lookup returns only a list.
            val bigAmount: Long? = when (txn.job) {
                PosJob.PAYMENT, PosJob.TOPUP -> txn.amount
                PosJob.BALANCE -> txn.result?.total
                PosJob.REFUND -> txn.result?.amount
                PosJob.REFUND_QUERY -> null
            }
            if (bigAmount != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    yen(bigAmount),
                    color = FG,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Balance success shows the total alone — no status line.
            val showStatus = !(txn.job == PosJob.BALANCE && txn.state == PosTxnState.SUCCESS)
            if (showStatus) {
                Spacer(Modifier.height(20.dp))
                Text(
                    txn.statusText,
                    color = FG,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            if (txn.job == PosJob.PAYMENT && txn.state == PosTxnState.SUCCESS) {
                txn.result?.balance?.let { balance ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "支払後残高 ${yen(balance)}",
                        color = FG,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Cancel only before the card tap / server op (see PosTxnState.isCancellable).
        if (txn.state.isCancellable) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFECECEC), contentColor = FG),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 28.dp)
                    .height(52.dp),
            ) {
                Text("キャンセル", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusRect(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = RECT_W, height = RECT_H)
            .background(color)
    )
}
