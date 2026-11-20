package com.fundamentalanalysis.app.ui.portfolio

import com.fundamentalanalysis.app.ui.components.LocalUiLayout
import com.fundamentalanalysis.app.domain.model.UiComponent
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import com.fundamentalanalysis.app.domain.model.periodGain
import com.fundamentalanalysis.app.domain.model.periodReturn
import com.fundamentalanalysis.app.domain.model.ReturnPeriod
import com.fundamentalanalysis.app.data.portfolio.HoldingsReconciler
import com.fundamentalanalysis.app.domain.model.CostSource
import com.fundamentalanalysis.app.domain.model.IndexGroup
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.IndexComparison
import com.fundamentalanalysis.app.domain.model.GroupSummary
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ElevatedCard
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.layout.ColumnScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.data.portfolio.PortfolioBackupParser
import com.fundamentalanalysis.app.domain.model.ImportPreview
import com.fundamentalanalysis.app.domain.model.PortfolioSummary
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.model.PriceSource
import com.fundamentalanalysis.app.domain.model.TradeSide
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.HorizontalBars
import com.fundamentalanalysis.app.ui.components.KeyValueRow
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.changeColor
import java.util.Locale

@Composable
fun PortfolioScreen(
    onBack: () -> Unit,
    onOpenStock: (String) -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val import by viewModel.import.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }
    var confirmDelete by remember { mutableStateOf(false) }
    // Storage Access Framework: the user picks the file; no storage permission is needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onFilePicked(it.toString()) }
    }
    val pick = { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", XLSX_MIME)) }

    ScreenScaffold(
        title = "Portfolio",
        snackbarHostState = snackbar,
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = {
            if (state.summary != null) {
                IconButton(onClick = viewModel::onToggleDetails) {
                    Icon(
                        if (state.showDetails) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (state.showDetails) "Hide quantities and costs" else "Show quantities and costs",
                    )
                }
            }
            IconButton(onClick = pick) { Icon(Icons.Filled.FileOpen, contentDescription = "Import portfolio") }
            if (state.summary != null) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete imported portfolio") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val summary = state.summary
            when {
                state.loading -> LoadingState()
                summary == null && state.broker == null -> EmptyPortfolio(onImport = pick)
                else -> PortfolioContent(state, viewModel, onOpenStock)
            }
        }
    }

    ImportDialog(import, onConfirm = viewModel::confirmImport, onDismiss = viewModel::dismissImport, onPickAgain = pick)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete imported portfolio?") },
            text = { Text("Holdings, trades, actions, notes and charges imported for every broker are removed from this phone. Your backup file isn't touched, so you can import it again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAll() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyPortfolio(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Import your portfolio", subtitle = "From a broker backup file (${PortfolioBackupParser.TEMPLATE_APP} JSON)") {
            Text("Supported Indian equity brokers:", style = MaterialTheme.typography.bodyMedium)
            PortfolioBackupParser.SUPPORTED_BROKERS.forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
            Text(
                "Holdings, trades, corporate actions, notes and charges are imported for these brokers. Other accounts in the file " +
                    "(US brokers, mutual fund houses, crypto wallets and exchanges) are listed and skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onImport, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Filled.FileOpen, contentDescription = null, Modifier.size(18.dp))
                Text("Choose backup file", Modifier.padding(start = 8.dp))
            }
        }
        PrivacyNote()
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Your portfolio stays on this phone: it is never uploaded, isn't included in cloud backups, and client IDs are stored masked.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PortfolioContent(state: PortfolioUiState, vm: PortfolioViewModel, onOpenStock: (String) -> Unit) {
    val summary = state.summary
    val ui = LocalUiLayout.current
    val tabs = PortfolioTab.entries.filter { t -> t.component?.let(ui::shows) ?: true }
    val tab = state.tab.takeIf { it in tabs } ?: PortfolioTab.HOLDINGS
    val showPeriods = ui.shows(UiComponent.PORTFOLIO_PERIODS)
    // Without the selector, returns are all time.
    androidx.compose.runtime.LaunchedEffect(showPeriods) { if (!showPeriods && state.period != ReturnPeriod.ALL) vm.onPeriod(ReturnPeriod.ALL) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        if (state.brokers.size > 1) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(state.broker == null, onClick = { vm.onBroker(null) }, label = { Text("All brokers") }) }
                    items(state.brokers) { b -> FilterChip(state.broker == b, onClick = { vm.onBroker(b) }, label = { Text(b) }) }
                }
            }
        }
        if (summary == null) {
            item { Text("Nothing imported for this broker.", Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        if (showPeriods) item {
            // Return window for the whole page: summary, index groups and holdings.
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReturnPeriod.entries) { rp -> FilterChip(state.period == rp, onClick = { vm.onPeriod(rp) }, label = { Text(rp.label) }) }
            }
        }
        if (ui.shows(UiComponent.PORTFOLIO_SUMMARY)) item {
            SummaryCard(summary, state.period, Modifier.padding(horizontal = 16.dp), simple = ui.simple(UiComponent.PORTFOLIO_SUMMARY))
        }
        item {
            SecondaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 16.dp) {
                tabs.forEach { t ->
                    Tab(selected = tab == t, onClick = { vm.onTab(t) }, text = { Text(t.label, maxLines = 1) })
                }
            }
        }
        when (tab) {
            PortfolioTab.HOLDINGS -> {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HoldingSort.entries) { s ->
                            FilterChip(state.sort == s, onClick = { vm.onSort(s) }, label = { Text(if (s == HoldingSort.RETURN) "${state.period.label} return" else s.label) })
                        }
                    }
                }
                item {
                    Text(
                        "${state.holdings.size} holdings by index · tap a section to fold it · tap a tracked stock for its analysis",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(state.groups, key = { it.group.name }) { g ->
                    GroupCard(g, Modifier.padding(horizontal = 16.dp), period = state.period) {
                        g.positions.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider()
                            HoldingRow(p, state.period, state.showDetails, onOpenStock, simple = ui.simple(UiComponent.PORTFOLIO_HOLDING_ROWS))
                        }
                    }
                }
            }
            PortfolioTab.HEATMAP -> item { PortfolioHeatmap(summary, onOpenStock) }
            PortfolioTab.ALLOCATION -> item { AllocationCards(summary) }
            PortfolioTab.VS_INDEX -> item { VsIndexCards(summary, onOpenStock, state, vm::onBenchmark) }
            PortfolioTab.ACTIVITY -> item { ActivityCards(summary) }
        }
        if (ui.shows(UiComponent.PORTFOLIO_PRIVACY)) item { Column(Modifier.padding(horizontal = 16.dp)) { PrivacyNote() } }
    }
}

@Composable
private fun SummaryCard(s: PortfolioSummary, period: ReturnPeriod, modifier: Modifier, simple: Boolean = false) {
    SectionCard("Current value", modifier, subtitle = s.accounts.joinToString(" · ") { "${it.broker} ${it.clientId}" }) {
        Text(Fmt.inr(s.value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        if (period != ReturnPeriod.ALL) {
            val r = s.positions.periodReturn(period)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangeText("${Fmt.inr(r.gain, signed = true)} (${Fmt.signedPercent(r.pct)})", r.gain, style = MaterialTheme.typography.titleMedium)
                Text("  ${period.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Price change of today's holdings over ${period.label}, for the ${r.measured} of ${r.total} holdings with market data " +
                    "(Nifty 50, Next 50 and Midcap 150 stocks)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (period == ReturnPeriod.ALL) Row(verticalAlignment = Alignment.CenterVertically) {
            ChangeText("${Fmt.inr(s.pnl, signed = true)} (${Fmt.signedPercent(s.pnlPct)})", s.pnl, style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.uncostedValue > 1) "  on holdings with a known cost" else "  overall",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (simple) return@SectionCard
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyValueRow("Invested (cost)", Fmt.inr(s.invested))
            if (s.uncostedValue > 1) {
                KeyValueRow("No recorded cost (not in P&L)", "${Fmt.inr(s.uncostedValue)} · ${s.uncostedHoldings} holdings")
            }
            s.dayChange?.let { KeyValueRow("Latest session (tracked stocks)", Fmt.inr(it, signed = true), valueColor = changeColor(it)) }
            KeyValueRow("Holdings", "${s.positions.size} · ${s.trackedCount} with market data")
        }
        val brokerDates = s.accounts.mapNotNull { a -> a.holdingsAsOf?.let { "${a.broker} ${Fmt.day(it)}" } }
        Text(
            "Prices: NSE close of ${Fmt.day(s.positions.firstOrNull { it.priceSource == PriceSource.DATASET }?.priceDate)} for tracked stocks; " +
                "broker snapshot for the rest (${brokerDates.joinToString()}). Quantities and costs are as of each broker's snapshot." +
                (s.lastImport?.let { " Imported ${Fmt.dateTime(it.atMillis)}${it.fileName?.let { f -> " from $f" } ?: ""}." } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HoldingRow(p: Position, period: ReturnPeriod, showDetails: Boolean, onOpenStock: (String) -> Unit, simple: Boolean = false) {
    val tracked = p.tracked
    Column(
        Modifier.fillMaxWidth()
            .then(if (tracked != null) Modifier.clickable { onOpenStock(tracked.symbol) } else Modifier)
            .padding(vertical = if (simple) 6.dp else 10.dp),
    ) {
        Row(verticalAlignment = if (simple) Alignment.CenterVertically else Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(p.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!simple) Text(
                    listOfNotNull(tracked?.stock?.name, p.sector).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (simple) {
                val r = p.periodReturn(period)
                val gain = p.periodGain(period)
                Text(Fmt.inr(p.value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                if (r != null && gain != null) ChangeText(Fmt.signedPercent(r), gain, style = MaterialTheme.typography.bodyMedium)
                else Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.inr(p.value), style = MaterialTheme.typography.titleSmall)
                val r = p.periodReturn(period)
                val gain = p.periodGain(period)
                if (r != null && gain != null) {
                    ChangeText("${Fmt.signedPercent(r)} · ${Fmt.inr(gain, signed = true)}", gain, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        if (period == ReturnPeriod.ALL) "P&L —" else "${period.label} —",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showDetails) Text(
            "${Fmt.qty(p.qty)} × ${Fmt.rupees(p.price)} · ${costText(p)}" +
                (tracked?.metrics?.change1d?.let { " · today ${Fmt.signedPercent(it)}" } ?: "") +
                " · " + p.lots.joinToString { if (p.lots.size > 1) "${it.broker} ${Fmt.qty(it.qty)}" else it.broker } +
                if (p.priceSource == PriceSource.BROKER) " · price ${Fmt.day(p.priceDate)}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        p.note?.let {
            Text("“$it”", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** "avg ₹637.53 (rebuilt from trades) · 10 without cost", or why there's no cost. */
private fun costText(p: Position): String {
    if (!p.costKnown) return "no recorded cost"
    val rebuilt = p.lots.filter { it.costSource == CostSource.TRADES || it.costSource == CostSource.PARTIAL }
    val fromFile = p.lots.filter { it.costSource == CostSource.FILE }
    val source = when {
        rebuilt.isNotEmpty() -> " (rebuilt from ${rebuilt.joinToString { it.broker }} trades)"
        fromFile.isNotEmpty() && fromFile.size == p.lots.size -> " (holdings file)"
        fromFile.isNotEmpty() -> " (${fromFile.joinToString { it.broker }} from holdings file)"
        else -> ""
    }
    val missing = if (p.uncostedQty > 1e-6) " · ${Fmt.qty(p.uncostedQty)} without cost" else ""
    return "avg ${Fmt.rupees(p.avgPrice)}$source$missing"
}

@Composable
private fun AllocationCards(s: PortfolioSummary) {
    val pct: (Double) -> String = { String.format(Locale.US, "%.1f%%", it * 100) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("By sector", subtitle = "Share of current value · NSE industry for tracked stocks") {
            HorizontalBars(s.bySector.map { "${it.label} (${it.count})" to it.share }, pct, labelWidth = 170.dp)
        }
        SectionCard("By broker") {
            HorizontalBars(s.byBroker.map { it.label to it.share }, pct, labelWidth = 130.dp)
            s.byBroker.forEach { KeyValueRow(it.label, "${Fmt.inr(it.value)} · ${it.count} holdings") }
        }
        SectionCard("By index", subtitle = "Which tracked index each stock belongs to") {
            HorizontalBars(s.byIndex.map { "${it.label} (${it.count})" to it.share }, pct, labelWidth = 170.dp)
        }
        SectionCard("Largest positions") {
            val top = s.positions.take(10)
            HorizontalBars(top.map { it.symbol to it.value / s.value.coerceAtLeast(1.0) }, pct, labelWidth = 120.dp)
            Text(
                "Top 10 = ${pct(top.sumOf { it.value } / s.value.coerceAtLeast(1.0))} of the portfolio",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun VsIndexCards(s: PortfolioSummary, onOpenStock: (String) -> Unit, state: PortfolioUiState, onBenchmark: (String?) -> Unit) {
    val tracked = s.groups.filter { it.group.tracked }
    val untracked = s.groups.filterNot { it.group.tracked }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val chosenName = state.benchmarks.firstOrNull { it.first == state.benchmark }?.second
        SectionCard(
            "All tracked holdings",
            subtitle = "${s.trackedCount} of ${s.positions.size} holdings have market data; " +
                if (chosenName == null) "each is compared with its own index (the Nifty 500 for stocks outside the tracked indices)"
                else "all are compared with the $chosenName",
        ) {
            Text("Compare with", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                item { FilterChip(state.benchmark == null, onClick = { onBenchmark(null) }, label = { Text("Own index") }) }
                items(state.benchmarks, key = { it.first }) { (symbol, name) ->
                    FilterChip(state.benchmark == symbol, onClick = { onBenchmark(symbol) }, label = { Text(name) })
                }
            }
            val chosen = state.vsBenchmark
            if (chosen == null) ComparisonTable(s.vsIndex, showIndex = false) else ComparisonTable(chosen, showIndex = true)
            Text(
                "Weighted by today's value; price returns over the period, ignoring when you bought.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        tracked.forEach { g ->
            GroupCard(g) {
                ComparisonTable(g.vsIndex, showIndex = true)
                Text(
                    "1Y vs ${g.group.label} · pp = percentage points above (+) or below (−) the index",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                g.positions.sortedByDescending { it.tracked?.metrics?.excess(Period.Y1) ?: Double.NEGATIVE_INFINITY }
                    .forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider()
                        val m = p.tracked!!.metrics
                        val r = m.returns[Period.Y1]
                        val excess = m.excess(Period.Y1)
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenStock(p.symbol) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.symbol, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${p.tracked.stock.industry} · ${Fmt.inr(p.value)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            ChangeText(Fmt.signedPercent(r), r, Modifier.width(80.dp), textAlign = TextAlign.End)
                            ChangeText(excess?.let { String.format(Locale.US, "%+.1f pp", it * 100) } ?: "—", excess, Modifier.width(84.dp), textAlign = TextAlign.End)
                        }
                    }
            }
        }
        untracked.firstOrNull { it.group == IndexGroup.OTHER }?.let { g -> OtherStocksCard(g) }
        untracked.filter { it.group == IndexGroup.FUNDS }.forEach { g ->
            SectionCard("Not compared") {
                KeyValueRow(g.group.label, "${g.positions.size} holdings · ${Fmt.inr(g.value)}")
                Text(
                    "Funds and ETFs aren't compared with an index here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val monthYear = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/**
 * Stocks outside the tracked indices, compared with the Nifty 50 since they were bought: the app
 * has no price history for them, so 1M–1Y returns aren't available.
 */
@Composable
private fun OtherStocksCard(g: GroupSummary) {
    val sp = g.sincePurchase
    GroupCard(g) {
        if (sp == null) {
            Text("Nifty 50 history isn't loaded yet.", style = MaterialTheme.typography.bodyMedium)
            return@GroupCard
        }
        Text("Since purchase vs ${sp.benchmark}", style = MaterialTheme.typography.titleSmall)
        Text(
            "Return on cost compared with the ${sp.benchmark}'s return from each holding's average purchase date. " +
                "These stocks aren't in a tracked index, so 1M–1Y returns aren't available.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (sp.rows.isNotEmpty()) {
            val ownReturn = sp.value / sp.invested - 1
            val niftyReturn = sp.benchmarkValue / sp.invested - 1
            KeyValueRow("Beat the ${sp.benchmark}", "${sp.beat} of ${sp.rows.size}")
            KeyValueRow("Invested", Fmt.inr(sp.invested))
            KeyValueRow("Worth now", "${Fmt.inr(sp.value)} (${Fmt.signedPercent(ownReturn)})", valueColor = changeColor(ownReturn))
            KeyValueRow(
                "Same money in ${sp.benchmark}", "${Fmt.inr(sp.benchmarkValue)} (${Fmt.signedPercent(niftyReturn)})",
                valueColor = changeColor(niftyReturn),
            )
            val diff = sp.value - sp.benchmarkValue
            ChangeText(
                if (diff >= 0) "Ahead of the ${sp.benchmark} by ${Fmt.inr(diff)}" else "Behind the ${sp.benchmark} by ${Fmt.inr(-diff)}",
                diff,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                val h = MaterialTheme.typography.labelSmall
                Text("Holding", style = h, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Yours", style = h, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                Text("Nifty 50", style = h, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                Text("vs", style = h, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
            }
            HorizontalDivider()
            sp.rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.position.symbol, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "since ~${r.avgBuyDate.format(monthYear)} · ${Fmt.inr(r.position.value)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ChangeText(Fmt.signedPercent(r.holdingReturn), r.holdingReturn, Modifier.width(72.dp), textAlign = TextAlign.End)
                    ChangeText(Fmt.signedPercent(r.benchmarkReturn), r.benchmarkReturn, Modifier.width(72.dp), textAlign = TextAlign.End)
                    ChangeText(String.format(Locale.US, "%+.0f pp", r.excess * 100), r.excess, Modifier.width(76.dp), textAlign = TextAlign.End)
                }
            }
        }
        if (sp.withoutHistory.isNotEmpty()) {
            Text(
                "Not compared (no dated purchase in the imported trades, e.g. transferred in or bought earlier): " +
                    sp.withoutHistory.joinToString { it.symbol },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Period | beat count | holdings' weighted return | (the index's return). */
@Composable
private fun ComparisonTable(rows: List<IndexComparison>, showIndex: Boolean) {
    val header = MaterialTheme.typography.labelSmall
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("Period", style = header, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Text("Beat index", style = header, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Holdings", style = header, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        if (showIndex) Text("Index", style = header, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
    HorizontalDivider()
    rows.forEach { c ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.period.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
            Text("${c.beat} of ${c.measured}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ChangeText(Fmt.signedPercent(c.weightedReturn), c.weightedReturn, Modifier.width(80.dp), textAlign = TextAlign.End)
            if (showIndex) ChangeText(Fmt.signedPercent(c.indexReturn), c.indexReturn, Modifier.width(72.dp), textAlign = TextAlign.End)
        }
    }
}

/** A card for one index's holdings: summary header (tap to fold) and [content]. */
@Composable
private fun GroupCard(g: GroupSummary, modifier: Modifier = Modifier, period: ReturnPeriod = ReturnPeriod.ALL, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(g.group) { mutableStateOf(true) }
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${g.group.label} · ${g.positions.size}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Fmt.inr(g.value)} · ${String.format(Locale.US, "%.1f%%", g.share * 100)} of portfolio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (period != ReturnPeriod.ALL) {
                        val r = g.positions.periodReturn(period)
                        if (r.pct == null) {
                            Text("${period.label} —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ChangeText(
                                "${period.label} ${Fmt.signedPercent(r.pct)} (${Fmt.inr(r.gain, signed = true)})" +
                                    if (r.measured < r.total) " · ${r.measured} of ${r.total}" else "",
                                r.gain, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (period == ReturnPeriod.ALL) Row {
                        ChangeText("P&L ${Fmt.signedPercent(g.pnlPct)} (${Fmt.inr(g.pnl, signed = true)})", g.pnl, style = MaterialTheme.typography.bodySmall)
                        g.dayChange?.let {
                            Text(" · ", style = MaterialTheme.typography.bodySmall)
                            ChangeText("latest ${Fmt.inr(it, signed = true)}", it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Fold ${g.group.label}" else "Show ${g.group.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                Column(Modifier.padding(top = 8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun ActivityCards(s: PortfolioSummary) {
    var showAll by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("By year", subtitle = "Trade value bought and sold, and charges paid (brokerage, STT, exchange, stamp, GST)") {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                listOf("Year" to 52, "Bought" to 0, "Sold" to 0, "Charges" to 0).forEach { (h, w) ->
                    Text(
                        h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (w > 0) TextAlign.Start else TextAlign.End,
                        modifier = if (w > 0) Modifier.width(w.dp) else Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider()
            s.activity.forEach { y ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${y.year}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
                    listOf(Fmt.inr(y.bought), Fmt.inr(y.sold), if (y.charges > 0) Fmt.inr(y.charges) else "—").forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
            Text(
                "${s.trades.size} trades · charges ${Fmt.inr(s.activity.sumOf { it.charges })} in total" +
                    " (only brokers whose backup lists charges)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (s.actions.isNotEmpty()) {
            SectionCard("Corporate actions & transfers") {
                s.actions.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.symbol, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${Fmt.day(a.date)} · ${s.broker(a.accountId)} · " +
                                    a.issue.ifBlank { a.type }.let { if (it.equals("ipo", true)) "IPO" else it.replaceFirstChar { c -> c.uppercase() } } +
                                    if (a.incoming) " in" else " out",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${if (a.incoming) "+" else "−"}${Fmt.qty(a.qty)} shares",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (a.incoming) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        SectionCard("Trades", subtitle = "Newest first") {
            val list = if (showAll) s.trades else s.trades.take(50)
            list.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.symbol}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Fmt.day(t.date)} · ${s.broker(t.accountId)}${if (t.exchange.isNotBlank()) " · ${t.exchange}" else ""}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val buy = t.side == TradeSide.BUY
                        Text(
                            "${if (buy) "Buy" else "Sell"} ${Fmt.qty(t.qty)} @ ${Fmt.rupees(t.price)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (buy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
                        )
                        Text(Fmt.inr(t.value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.trades.size > 50) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show latest 50" else "Show all ${s.trades.size} trades") }
            }
        }
    }
}

@Composable
private fun ImportDialog(state: ImportState, onConfirm: () -> Unit, onDismiss: () -> Unit, onPickAgain: () -> Unit) {
    when (state) {
        ImportState.Idle -> Unit
        ImportState.Reading, ImportState.Saving -> AlertDialog(
            onDismissRequest = {},
            title = { Text(if (state == ImportState.Reading) "Reading file…" else "Importing…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text("  Please wait") } },
            confirmButton = {},
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Couldn't import") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { onDismiss(); onPickAgain() }) { Text("Choose another file") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        is ImportState.Review -> ReviewDialog(state.preview, onConfirm, onDismiss)
    }
}

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
private fun ReviewDialog(p: ImportPreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (p.isHoldingsFile) return HoldingsReviewDialog(p, onConfirm, onDismiss)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.canImport) "Import portfolio?" else "Nothing to import") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(p.fileName, p.exportedAt?.let { "exported ${it.take(10)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.included.isNotEmpty()) {
                    Text("Will import", style = MaterialTheme.typography.titleSmall)
                    p.included.forEach { a ->
                        Column {
                            Text("${a.broker} ${a.clientId}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "${a.holdings} holdings${a.holdingsAsOf?.let { " (as of $it)" } ?: ""} · ${a.trades} trades" +
                                    (if (a.actions > 0) " · ${a.actions} actions" else "") +
                                    (if (a.notes > 0) " · ${a.notes} notes" else "") +
                                    (if (a.fees > 0) " · ${a.fees} charge lines" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (p.skipped.isNotEmpty()) {
                    Text("Skipped", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    p.skipped.forEach { s ->
                        Text("${s.broker}: ${s.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    if (p.canImport) "Data previously imported for these brokers is replaced. Everything stays on this phone."
                    else "This file has no ${PortfolioBackupParser.SUPPORTED_BROKERS.joinToString()} equity accounts.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { if (p.canImport) TextButton(onClick = onConfirm) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (p.canImport) "Cancel" else "Close") } },
    )
}

/** Preview of a holdings workbook: per broker, what changes against the stored holdings. */
@Composable
private fun HoldingsReviewDialog(p: ImportPreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update holdings?") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(p.fileName, p.exportedAt?.let { "prices as of $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                p.holdingsUpdates.forEach { u ->
                    Column {
                        Text(u.broker + if (u.newAccount) " (new)" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        val lines = buildList {
                            add("${u.holdings} holdings" + (u.asOf?.let { " as of $it" } ?: ""))
                            add(if (u.costChanged.isEmpty()) "Average costs unchanged" else "${u.costChanged.size} average costs updated: ${HoldingsReconciler.examples(u.costChanged)}")
                            if (u.qtyChanged.isNotEmpty()) add("${u.qtyChanged.size} quantities changed: ${HoldingsReconciler.examples(u.qtyChanged)}")
                            if (u.added.isNotEmpty()) add("${u.added.size} new: ${HoldingsReconciler.examples(u.added)}")
                            if (u.removed.isNotEmpty()) add("${u.removed.size} no longer held: ${HoldingsReconciler.examples(u.removed)}")
                            if (u.withoutCost > 0) add("${u.withoutCost} without an average cost")
                        }
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (p.skipped.isNotEmpty()) {
                    Text("Skipped", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    Text(p.skipped.joinToString { it.broker }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.skipped.first().reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Holdings, quantities, average costs and prices for these brokers are replaced by the file's. " +
                        "Trades, corporate actions, notes and charges from the backup are kept. Everything stays on this phone.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
