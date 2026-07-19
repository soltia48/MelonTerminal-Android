package jp.unknowntech.melonterminal

import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import jp.unknowntech.melonterminal.ui.AppMode
import jp.unknowntech.melonterminal.ui.NfcState
import jp.unknowntech.melonterminal.ui.TerminalScreen
import jp.unknowntech.melonterminal.ui.TerminalViewModel
import jp.unknowntech.melonterminal.ui.theme.MelonTerminalTheme

/**
 * The terminal is a single-activity app. The card is read with the **foreground
 * reader mode** (NFC-F only, NDEF checks skipped) — the reliable path for a POS-style
 * reader. Each detected tag is handed to [TerminalViewModel.onTag], which runs the
 * auth-relay + operation while the card stays in the field.
 */
private const val READER_PRESENCE_CHECK_DELAY_MS = 1000

class MainActivity : ComponentActivity() {

    private val viewModel: TerminalViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsState()
            // POS-linked mode runs as a full-screen kiosk: hide the system bars so the
            // status/navigation bars don't sit over the payment face. Swiping from an
            // edge reveals them transiently. Standalone mode keeps the bars visible.
            LaunchedEffect(state.mode) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (state.mode == AppMode.POS) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            MelonTerminalTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TerminalScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The system re-shows the bars when the app regains focus; re-hide them if the
        // terminal is still in POS kiosk mode.
        if (viewModel.state.value.mode == AppMode.POS) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        val adapter = nfcAdapter
        viewModel.setNfcState(
            when {
                adapter == null -> NfcState.UNSUPPORTED
                !adapter.isEnabled -> NfcState.DISABLED
                else -> NfcState.ENABLED
            }
        )
        // A longer presence-check delay stops the platform from constantly re-polling a
        // card that stays on the reader (which churns tag handles and logs "Tag is out of
        // date"). We connect the moment a tag is discovered, so slower presence checks
        // don't hurt responsiveness.
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, READER_PRESENCE_CHECK_DELAY_MS)
        }
        adapter?.enableReaderMode(
            this,
            { tag -> viewModel.onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            extras,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }
}
