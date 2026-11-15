package com.fundamentalanalysis.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import com.fundamentalanalysis.app.domain.model.DataPart
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.domain.model.DataOrigin
import com.fundamentalanalysis.app.domain.model.MetricFormat
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.DataStatus
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.HeadlineQuote
import com.fundamentalanalysis.app.ui.components.HorizontalBars
import com.fundamentalanalysis.app.ui.components.IndexRow
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.MomentumPanel
import com.fundamentalanalysis.app.ui.components.ReturnsGrid
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.SessionGrid
import com.fundamentalanalysis.app.ui.components.StatTile
import com.fundamentalanalysis.app.ui.components.changeColor
import com.fundamentalanalysis.app.ui.settings.ThemeAction

private val sectorPeriods = listOf(Period.D1, Period.W1, Period.M1, Period.M3, Period.Y1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenIndex: (String) -> Unit,
    onOpenData: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(
        title = "Market overview",
        snackbarHostState = snackbar,
        actions = {
            ThemeAction()
            IconButton(onClick = onOpenData) { Icon(Icons.Filled.Storage, contentDescription = "Data management") }
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh data") }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState()
            state.isEmpty -> EmptyState("No market data yet. Pull to refresh.")
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                DashboardContent(state, onOpenIndex, onOpenData, viewModel::onSectorPeriodChange)
            }
        }
    }
}

@Composable
private fun DashboardContent(state: DashboardUiState, onOpenIndex: (String) -> Unit, onOpenData: () -> Unit, onPeriod: (Period) -> Unit) {
    val headline = state.headline ?: return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { state.data?.let { DataBanner(it, onOpenData) } }
        item {
            SectionCard(headline.index.name, subtitle = "Latest completed session") {
                HeadlineQuote(headline)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                headline.analytics.latest?.let { SessionGrid(it) }
                TextButton(onClick = { onOpenIndex(headline.symbol) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Full analysis")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    "Breadth (1D)",
                    "${state.advancing} up / ${state.declining} down",
                    Modifier.weight(1f),
                    caption = "Across Nifty equity indices",
                )
                state.vix?.let { vix ->
                    StatTile(
                        "India VIX",
                        Fmt.format(MetricFormat.NUMBER, vix.analytics.latest?.close),
                        Modifier.weight(1f),
                        caption = Fmt.signedPercent(vix.analytics.latest?.changePct),
                        captionColor = changeColor(vix.analytics.latest?.changePct),
                    )
                }
            }
        }
        item { SectionCard("Performance", subtitle = headline.index.name) { ReturnsGrid(headline) } }
        item { SectionCard("Momentum & volume", subtitle = headline.index.name) { MomentumPanel(headline) } }
        item {
            SectionCard("Sector performance", subtitle = "Nifty sectoral indices, best first") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    sectorPeriods.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = state.sectorPeriod == p,
                            onClick = { onPeriod(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, sectorPeriods.size),
                        ) { Text(p.label) }
                    }
                }
                HorizontalBars(
                    items = state.sectors.mapNotNull { s ->
                        s.analytics.returns[state.sectorPeriod]?.let { Fmt.shortName(s.index.name) to it }
                    },
                    valueFormatter = Fmt::signedPercent,
                    signed = true,
                )
            }
        }
        state.composition?.let { comp ->
            item {
                SectionCard("${comp.indexSymbol} industry mix", subtitle = "Constituents per NSE industry · as of ${comp.asOf ?: "—"}") {
                    HorizontalBars(
                        items = comp.byIndustry.take(8).map { (industry, members) -> industry to members.size.toDouble() },
                        valueFormatter = { "${it.toInt()}" },
                        labelWidth = 140.dp,
                    )
                }
            }
        }
        if (state.watchlist.isNotEmpty()) {
            item {
                SectionCard("Your watchlist", subtitle = "Stored on this device only") {
                    Column {
                        state.watchlist.forEachIndexed { i, idx ->
                            if (i > 0) HorizontalDivider()
                            IndexRow(idx, onClick = { onOpenIndex(idx.symbol) })
                        }
                    }
                }
            }
        }
    }
}

/** One line on what data is loaded; tap for the Data page. */
@Composable
private fun DataBanner(data: DataStatus, onOpen: () -> Unit) {
    val core = data.stored[DataPart.CORE]
    val source = when (core?.origin) {
        DataOrigin.REMOTE -> "Shared dataset v${core.version}"
        DataOrigin.BUNDLED -> "Bundled dataset v${core.version}"
        null -> "No data yet"
    }
    val history = when {
        data.progress.active -> data.progress.step ?: "Updating…"
        !data.historyReady -> "history loading…"
        else -> "history loaded"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(source, style = MaterialTheme.typography.labelLarge)
            Text(
                "Data through ${data.asOf ?: "unknown"} · $history" +
                    (data.lastCheckedAtMillis?.let { " · checked ${Fmt.dateTime(it)}" } ?: "") +
                    if (!data.remoteConfigured) " · no remote source configured" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (data.progress.active) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open data management", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
