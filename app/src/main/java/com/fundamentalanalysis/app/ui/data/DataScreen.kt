package com.fundamentalanalysis.app.ui.data

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.DataOrigin
import com.fundamentalanalysis.app.domain.model.DataPart
import com.fundamentalanalysis.app.domain.model.DataStatus
import com.fundamentalanalysis.app.domain.model.PipelineRun
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.SyncEvent
import com.fundamentalanalysis.app.domain.model.SyncEventType
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class DataUiState(val status: DataStatus? = null, val busy: Boolean = false)

@HiltViewModel
class DataViewModel @Inject constructor(
    private val repository: MarketRepository,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    val state: StateFlow<DataUiState> = combine(repository.observeDataStatus(), busy) { status, b -> DataUiState(status, b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataUiState())

    fun checkForUpdates() = run { describe(repository.refresh()) }
    fun reloadAll() = run { describe(repository.reloadAll()) }
    fun resetToBundled() = run { repository.resetToBundled(); "Reloaded the data bundled with the app" }

    private fun run(action: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val message = try { action() } finally { busy.value = false }
            messages.send(message)
        }
    }

    private fun describe(result: RefreshResult) = when (result) {
        is RefreshResult.Updated -> "Updated to v${result.version}: ${result.parts.joinToString { it.label.lowercase() }}"
        RefreshResult.UpToDate -> "Already up to date"
        RefreshResult.NotConfigured -> "No shared data source configured in this build"
        is RefreshResult.Failed -> "Update failed: ${result.message}"
    }
}

private enum class TimelineTab(val label: String) { PIPELINE("Pipeline runs"), DEVICE("This device") }

@Composable
fun DataScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }
    var confirmReset by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Data",
        snackbarHostState = snackbar,
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    ) { padding ->
        val status = state.status
        if (status == null) {
            LoadingState()
            return@ScreenScaffold
        }
        val working = state.busy || status.progress.active
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item { DatasetCard(status, working, viewModel::checkForUpdates, viewModel::reloadAll, onReset = { confirmReset = true }) }
            item { PartsCard(status) }
            item { PrecomputedCard(status) }
            item { SourcesCard(status.pipelineUrl) }
            item { TimelineCard(status) }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset to bundled data?") },
            text = { Text("Downloaded data is removed and the copy shipped with the app is loaded again. Your watchlist and settings stay. You can download the latest data again afterwards.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetToBundled() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DatasetCard(status: DataStatus, working: Boolean, onCheck: () -> Unit, onReloadAll: () -> Unit, onReset: () -> Unit) {
    val core = status.stored[DataPart.CORE]
    val updateAvailable = status.published.any { (part, p) -> (status.stored[part]?.version ?: 0) < p.version }
    SectionCard("Dataset", subtitle = "Shared by every install · no sign-in") {
        Text(
            "Data through ${status.asOf ?: "—"}",
            style = MaterialTheme.typography.headlineSmall,
        )
        val stateLine = when {
            status.progress.active -> status.progress.step ?: "Updating…"
            updateAvailable -> "A newer version (v${status.publishedVersion}) is available"
            !status.historyReady -> "Recent data loaded · history still loading"
            else -> "All parts loaded"
        }
        Text(stateLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Line("On this device", core?.let { "v${it.version} · ${if (it.origin == DataOrigin.REMOTE) "downloaded" else "bundled with the app"}" } ?: "nothing yet")
            Line("Published", status.publishedVersion?.let { "v$it · data through ${status.publishedAsOf ?: "—"}" } ?: "not checked yet")
            Line("Last checked", status.lastCheckedAtMillis?.let { Fmt.dateTime(it) } ?: if (status.remoteConfigured) "not yet" else "no remote source in this build")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = onCheck, enabled = !working && status.remoteConfigured) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Text("Check for updates", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onReloadAll, enabled = !working && status.remoteConfigured) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, Modifier.size(18.dp))
                Text("Download all again", Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onReset, enabled = !working) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, Modifier.size(18.dp))
                Text("Reset to bundled", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PartsCard(status: DataStatus) {
    SectionCard("Load order", subtitle = "1 and 2 load before the first screen; 3 loads in the background") {
        DataPart.entries.forEachIndexed { i, part ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
            val stored = status.stored[part]
            val published = status.published[part]
            val loading = status.progress.active && (status.progress.part == part || (part == DataPart.ANALYTICS && status.progress.part == DataPart.CORE))
            Row(verticalAlignment = Alignment.Top) {
                val (icon, tint) = when {
                    loading -> Icons.Filled.CloudDownload to MaterialTheme.colorScheme.primary
                    stored != null -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
                    else -> Icons.Filled.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("${i + 1}. ${part.label}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        published?.description ?: PART_DESCRIPTIONS.getValue(part),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val detail = when {
                        loading -> "Loading…"
                        stored == null -> "Waiting"
                        else -> "v${stored.version} · ${Fmt.bytes(stored.bytes)} · ${"%,d".format(Locale.US, stored.records)} records · " +
                            "${if (stored.origin == DataOrigin.REMOTE) "downloaded" else "bundled"} · loaded in ${stored.loadMillis} ms"
                    }
                    Text(detail, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    if (published != null && (stored?.version ?: 0) < published.version) {
                        Text(
                            "v${published.version} available (${Fmt.bytes(published.bytes)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (part == DataPart.HISTORY) {
                        Text(
                            if (stored != null) "Unlocks: 3Y and 5Y comparisons, 3Y–10Y stock charts, year-by-month tables, financials"
                            else "Until it loads: comparisons stop at 1Y; long-range charts and financials show a placeholder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private val PART_DESCRIPTIONS = mapOf(
    DataPart.ANALYTICS to "Precomputed metrics, sector aggregates and calendar tables",
    DataPart.CORE to "Latest sessions, recent months, constituents and stock list",
    DataPart.HISTORY to "Full monthly history, valuation history, stock daily closes and financials",
)

@Composable
private fun PrecomputedCard(status: DataStatus) {
    SectionCard("Precomputed analytics", subtitle = "Calculated once by the data pipeline on each update, not on every phone at every launch") {
        if (status.precomputed.isEmpty()) {
            Text("Not loaded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.precomputed.forEach { (table, rows) ->
            Line(table, "%,d rows".format(Locale.US, rows))
        }
        Text(
            "Index metrics: returns 1D–5Y, RSI, moving averages, 52-week range, volume, volatility. " +
                "Stock metrics: returns vs index, momentum, beta/alpha, valuation, growth, quality. " +
                "Sector aggregates: beat counts and averages by universe × period × sector. " +
                "Calendar tables: Nifty 50 returns and P/E, P/B, dividend yield by year and month.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SourcesCard(pipelineUrl: String?) {
    val context = LocalContext.current
    SectionCard("Updating from public sources", subtitle = "Runs on GitHub, not on this phone") {
        Text(
            "A scheduled pipeline fetches prices from Yahoo Finance and constituents, industries and valuation from NSE's public archives " +
                "every weekday at 17:00 IST, precomputes the analytics and publishes a new version. Phones then download only the parts that changed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (pipelineUrl != null) {
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, pipelineUrl.toUri())) },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                Text("Open pipeline on GitHub", Modifier.padding(start = 8.dp))
            }
            Text(
                "Maintainers signed in to GitHub can run it now with \"Run workflow\", then tap Check for updates here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TimelineCard(status: DataStatus) {
    var tab by rememberSaveable { mutableStateOf(TimelineTab.PIPELINE) }
    SectionCard("Update timeline") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            TimelineTab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(i, TimelineTab.entries.size),
                ) { Text(t.label) }
            }
        }
        when (tab) {
            TimelineTab.PIPELINE -> if (status.pipelineRuns.isEmpty()) Empty("No published runs seen yet")
            else status.pipelineRuns.take(60).forEachIndexed { i, run ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PipelineRunRow(run, current = status.stored[DataPart.CORE]?.version)
            }
            TimelineTab.DEVICE -> if (status.events.isEmpty()) Empty("Nothing yet")
            else status.events.take(60).forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                EventRow(e)
            }
        }
    }
}

@Composable
private fun PipelineRunRow(run: PipelineRun, current: Int?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("v${run.version}", style = MaterialTheme.typography.titleSmall)
            Text(
                " · data through ${run.asOf ?: "—"}" + (run.datasetVersion?.let { " · dataset v$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (current == run.version) Text("on device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Published ${isoToLocal(run.generatedAt)} · ${run.indices} indices · ${run.stocks} stocks",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            run.changed.forEach { AssistChip(onClick = {}, label = { Text(it.label, style = MaterialTheme.typography.labelSmall) }) }
        }
        if (run.failures.isNotEmpty()) {
            Text("Kept previous data for: ${run.failures.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EventRow(e: SyncEvent) {
    val icon: ImageVector = when {
        !e.success -> Icons.Filled.ErrorOutline
        e.type == SyncEventType.CHECK -> Icons.Filled.Search
        e.type == SyncEventType.DOWNLOAD -> Icons.Filled.CloudDownload
        e.type == SyncEventType.RESET -> Icons.Filled.RestartAlt
        else -> Icons.Filled.Storage
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon, contentDescription = e.type.name.lowercase(),
            tint = if (e.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(e.message, style = MaterialTheme.typography.bodySmall, color = if (e.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            Text(
                listOfNotNull(Fmt.dateTime(e.atMillis), e.bytes?.let(Fmt::bytes), e.durationMillis?.let { "$it ms" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(156.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

private val LOCAL_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

private fun isoToLocal(iso: String?): String =
    iso?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(LOCAL_TIME) }.getOrNull() } ?: "—"
