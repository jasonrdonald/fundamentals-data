package com.fundamentalanalysis.app.ui.stocks

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.BuildConfig
import com.fundamentalanalysis.app.domain.model.nseBaseSymbol
import com.fundamentalanalysis.app.domain.usecase.RequestStocksUseCase
import com.fundamentalanalysis.app.domain.usecase.TickerResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RequestStocksState(
    val input: String = "",
    val checking: Boolean = false,
    /** Results of the last check, for the input as it was then. */
    val results: List<TickerResult> = emptyList(),
    val checkedInput: String? = null,
) {
    val listed: List<TickerResult.Listed> get() = results.filterIsInstance<TickerResult.Listed>()
    val canSend: Boolean get() = !checking && checkedInput == input && listed.isNotEmpty()
}

@HiltViewModel
class RequestStocksViewModel @Inject constructor(private val requests: RequestStocksUseCase) : ViewModel() {
    private val _state = MutableStateFlow(RequestStocksState())
    val state: StateFlow<RequestStocksState> = _state.asStateFlow()

    /** Tickers requested from this device that the dataset doesn't have yet. */
    val pending: StateFlow<List<String>> = requests.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start(prefill: List<String>) { _state.value = RequestStocksState(input = prefill.joinToString(", ")) }
    fun onInput(text: String) { _state.update { it.copy(input = text) } }

    fun check() {
        val input = _state.value.input
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val results = requests.check(input)
            _state.update { it.copy(checking = false, results = results, checkedInput = input) }
        }
    }

    /** Opens the request email and remembers the tickers as requested. */
    fun send(context: Context) {
        val tickers = _state.value.listed.map { it.ticker }
        if (tickers.isEmpty()) return
        val (subject, body) = RequestStocksUseCase.email(tickers, BuildConfig.WISHLIST_WORKFLOW_URL)
        val admin = BuildConfig.WISHLIST_ADMIN_EMAIL.trim()
        val intent = if (admin.isNotEmpty()) {
            Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply { putExtra(Intent.EXTRA_EMAIL, arrayOf(admin)) }
        } else {
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        }.apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Send request"))
        viewModelScope.launch { requests.markRequested(tickers) }
    }
}

/**
 * Request stocks for the Wishlist: enter tickers, check them (one lookup each on Yahoo Finance),
 * then email the listed ones to the developer, who approves them. They appear in the Wishlist once
 * the dataset includes them.
 */
@Composable
fun RequestStocksDialog(prefill: List<String>, onDismiss: () -> Unit, viewModel: RequestStocksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(prefill) { viewModel.start(prefill) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request stocks for the Wishlist") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Enter NSE or BSE tickers. Each is checked on Yahoo Finance, then the listed ones are emailed to the " +
                        "dataset admin for approval. They appear in the Wishlist once added to the dataset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInput,
                    label = { Text("Tickers") },
                    placeholder = { Text("e.g. IRCON, CDSL") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (state.checkedInput == state.input) {
                    state.results.forEach { ResultRow(it) }
                }
            }
        },
        confirmButton = {
            if (state.canSend) {
                Button(onClick = { viewModel.send(context); onDismiss() }) {
                    Text("Send request (${state.listed.size})")
                }
            } else {
                Button(onClick = viewModel::check, enabled = !state.checking && state.input.isNotBlank()) { Text("Check") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResultRow(r: TickerResult) {
    val (icon, text) = when (r) {
        is TickerResult.Listed -> Icons.Filled.CheckCircle to "${r.symbol}: ${r.ticker.name} (${r.ticker.exchange})"
        is TickerResult.InDataset -> Icons.Filled.Info to "${r.symbol}: already in the dataset"
        is TickerResult.AlreadyRequested -> Icons.Filled.Info to "${r.symbol}: already requested, awaiting approval"
        is TickerResult.NotFound -> Icons.Filled.ErrorOutline to "${r.symbol}: not found as a listed Indian stock"
        is TickerResult.Invalid -> Icons.Filled.ErrorOutline to "${r.symbol}: not a valid ticker"
        is TickerResult.Failed -> Icons.Filled.ErrorOutline to "${r.symbol}: couldn't check (${r.message}); try again online"
    }
    val tint = when (r) {
        is TickerResult.Listed -> MaterialTheme.colorScheme.primary
        is TickerResult.InDataset, is TickerResult.AlreadyRequested -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A text button that opens [RequestStocksDialog], optionally pre-filled. */
@Composable
fun RequestStocksButton(label: String, prefill: List<String> = emptyList()) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(label) }
    if (open) RequestStocksDialog(prefill.map(::nseBaseSymbol).distinct(), onDismiss = { open = false })
}

/** Under the Wishlist chip: request more stocks, and what this device has requested so far. */
@Composable
fun WishlistRequestRow(viewModel: RequestStocksViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (pending.isEmpty()) "Missing a stock? Request it for the Wishlist."
            else "Requested, awaiting approval: ${pending.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        RequestStocksButton("Request stocks")
    }
}

