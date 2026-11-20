package com.fundamentalanalysis.app.ui.stocks

import com.fundamentalanalysis.app.ui.components.LocalUiLayout
import com.fundamentalanalysis.app.domain.model.UiComponent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.domain.analysis.HoldingFilter
import com.fundamentalanalysis.app.domain.analysis.SectorPerformance
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.analysis.StockReports
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.StockFactor
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.shareCsv

@Composable
fun StocksScreen(
    onOpenStock: (String) -> Unit,
    onOpenHeatmap: () -> Unit = {},
    viewModel: StocksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ui = LocalUiLayout.current
    // Tabs hidden on the Customize page are skipped; if all are hidden, all are shown.
    val tabs = StocksTab.entries.filter { ui.shows(it.component) }.ifEmpty { StocksTab.entries }
    val sel = state.selection.let { s -> if (s.tab in tabs) s else s.copy(tab = tabs.first()) }
    val index = state.indexName.ifBlank { "Index" }

    ScreenScaffold(
        title = "Stocks",
        actions = {
            IconButton(onClick = onOpenHeatmap) { Icon(Icons.Filled.GridView, contentDescription = "Heatmap") }
            if (sel.tab != StocksTab.ALL) {
                IconButton(onClick = {
                    if (sel.tab == StocksTab.SECTORS) {
                        shareCsv(context, "$index stocks vs index by sector (${sel.period.label})", StockReports.sectorCsv(state.sectors, sel.period))
                    } else if (sel.tab == StocksTab.DISTRIBUTION) {
                        shareCsv(context, "$index stocks ${sel.period.label} return distribution", distributionCsv(state.distributionRows, state.distribution, state.distribution?.benchmark))
                    } else {
                        val side = if (sel.top) "Top" else "Bottom"
                        shareCsv(context, "$side ${sel.count} $index stocks by ${sel.factor.label}", StockReports.rankingCsv(state.ranking, sel.factor))
                    }
                }, enabled = !state.loading) { Icon(Icons.Filled.Share, contentDescription = "Export as CSV") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Which index's constituents to analyse; each is benchmarked against its own index.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                items(state.universes, key = { it.symbol }) { u ->
                    FilterChip(
                        selected = sel.universe == u.symbol,
                        onClick = { viewModel.onUniverse(u.symbol) },
                        label = { Text("${u.name} (${u.size})") },
                    )
                }
                if (state.portfolioSize > 0 && ui.shows(UiComponent.STOCKS_PORTFOLIO)) item(key = PORTFOLIO_UNIVERSE) {
                    FilterChip(
                        selected = state.isPortfolio,
                        onClick = { viewModel.onUniverse(PORTFOLIO_UNIVERSE) },
                        label = { Text("Portfolio (${state.portfolioSize})") },
                    )
                }
            }
            if (state.isPortfolio) {
                // Which holdings: all stocks, one index's, or those outside the tracked indices.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    items(HoldingFilter.entries) { f ->
                        FilterChip(
                            selected = sel.holdings == f,
                            onClick = { viewModel.onHoldings(f) },
                            label = { Text("${f.label} (${state.holdingCounts[f] ?: 0})") },
                        )
                    }
                }
            }
            // Scrollable tabs: four views do not fit as segmented buttons on a phone.
            androidx.compose.material3.SecondaryScrollableTabRow(
                selectedTabIndex = tabs.indexOf(sel.tab),
                edgePadding = 16.dp,
            ) {
                tabs.forEach { tab ->
                    androidx.compose.material3.Tab(
                        selected = sel.tab == tab,
                        onClick = { viewModel.onTab(tab) },
                        text = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
            when {
                state.loading -> LoadingState()
                state.isPortfolio && sel.tab == StocksTab.ALL -> HoldingsView(state, viewModel, onOpenStock)
                state.isPortfolio && state.all.isEmpty() -> EmptyState(
                    if ((state.holdingCounts[sel.holdings] ?: 0) == 0) "No ${sel.holdings.label} stocks in your portfolio."
                    else "No market data for these holdings yet. Stocks outside the dataset are fetched on this " +
                        "phone from Yahoo Finance when it's online. The All stocks tab lists them with their value and P&L.",
                )
                state.all.isEmpty() && sel.query.isEmpty() -> EmptyState("No constituent data in the current dataset.")
                sel.tab == StocksTab.SECTORS -> SectorsView(state, viewModel, onOpenStock)
                sel.tab == StocksTab.RANKINGS -> RankingsView(state, viewModel, onOpenStock)
                sel.tab == StocksTab.DISTRIBUTION -> DistributionView(state, viewModel::onPeriod, onOpenStock)
                else -> AllStocksView(state, viewModel, onOpenStock)
            }
        }
    }
}

@Composable
private fun PeriodChips(selected: Period, onSelect: (Period) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(SECTOR_PERIODS) { p -> FilterChip(selected == p, onClick = { onSelect(p) }, label = { Text(p.label) }) }
    }
}

// ---------------------------------------------------------------- Sectors: who beat the Nifty

@Composable
private fun SectorsView(state: StocksUiState, viewModel: StocksViewModel, onOpenStock: (String) -> Unit) {
    val period = state.selection.period
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        item { PeriodChips(period) { viewModel.onPeriod(it) } }
        if (state.unanalysed > 0) item { LeftOutNote(state.unanalysed) }
        item {
            SectionCard(
                "Beat ${state.beatLabel}: ${state.totalOutperformers} of ${state.totalMeasured} stocks",
                subtitle = "${period.label} return · " +
                    (state.benchmarkReturn?.let { "index ${Fmt.signedPercent(it)} · " }
                        ?: "each stock vs the index it belongs to (Nifty 500 for stocks outside them) · ") +
                    "sectors ordered by share of stocks that beat it",
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                state.sectors.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.sector, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${s.outperformers}/${s.measured} beat", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
                        ChangeText(Fmt.signedPercent(s.averageReturn), s.averageReturn, Modifier.width(76.dp), MaterialTheme.typography.labelMedium, TextAlign.End)
                    }
                }
                Text(
                    "Right column: equal-weighted average return of the sector's stocks.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        items(state.sectors, key = { it.sector }) { s -> SectorCard(s, period, onOpenStock) }
    }
}

@Composable
private fun SectorCard(s: SectorPerformance, period: Period, onOpenStock: (String) -> Unit) {
    SectionCard(
        s.sector,
        subtitle = "${s.outperformers} of ${s.measured} beat " +
            (s.benchmarkReturn?.let { "the index (${Fmt.signedPercent(it)})" } ?: "their own index") +
            " · sector avg ${Fmt.signedPercent(s.averageReturn)}",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("Stock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(period.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
            Text("vs index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
        }
        s.stocks.forEachIndexed { i, st ->
            if (i > 0) HorizontalDivider()
            val r = st.metrics.returns[period]
            val e = st.metrics.excess(period)
            Row(
                Modifier.fillMaxWidth().clickable { onOpenStock(st.symbol) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        st.symbol + if (e != null && e > 0) "  ✓ beat" else "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(st.stock.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                ChangeText(Fmt.signedPercent(r), r, Modifier.width(72.dp), MaterialTheme.typography.bodyMedium, TextAlign.End)
                ChangeText(e?.let { String.format(java.util.Locale.US, "%+.1f pp", it * 100) } ?: "—", e, Modifier.width(80.dp), MaterialTheme.typography.bodyMedium, TextAlign.End)
            }
        }
    }
}

// ---------------------------------------------------------------- Rankings: top / bottom N by factor

@Composable
private fun RankingsView(state: StocksUiState, viewModel: StocksViewModel, onOpenStock: (String) -> Unit) {
    val sel = state.selection
    val factor = sel.factor
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(StockFactor.entries) { f -> FilterChip(factor == f, onClick = { viewModel.onFactor(f) }, label = { Text(f.label) }) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    listOf(true, false).forEachIndexed { i, top ->
                        SegmentedButton(sel.top == top, onClick = { viewModel.onTop(top) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(if (top) "Top" else "Bottom")
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    RANK_SIZES.forEachIndexed { i, n ->
                        SegmentedButton(sel.count == n, onClick = { viewModel.onCount(n) }, shape = SegmentedButtonDefaults.itemShape(i, RANK_SIZES.size)) {
                            Text("$n")
                        }
                    }
                }
            }
        }
        if (state.unanalysed > 0) item { LeftOutNote(state.unanalysed) }
        item {
            Text(
                "${factor.label}: ${factor.description}. " + if (sel.top) "Best first." else "Weakest first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.ranking.isEmpty()) {
            item { EmptyState("No stocks report ${factor.primary.label}.") }
        } else {
            item {
                SectionCard(
                    "${if (sel.top) "Top" else "Bottom"} ${state.ranking.size} by ${factor.primary.label}",
                    subtitle = "Also shown: " + factor.supporting.joinToString(" · ") { it.label },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    state.ranking.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenStock(row.stock.symbol) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#${row.rank}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.stock.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    factor.supporting.joinToString("  ·  ") { m -> "${m.label} ${Fmt.stock(m, row.stock.valueOf(m))}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Text(row.stock.stock.industry, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(
                                Fmt.stock(factor.primary, row.value),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(88.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Portfolio universe: holdings outside the tracked indices aren't in the analysis. */
@Composable
internal fun LeftOutNote(count: Int) {
    Text(
        "$count ${if (count == 1) "holding has" else "holdings have"} no market data yet and " +
            "${if (count == 1) "isn't" else "aren't"} included here. Stocks outside the dataset are fetched on this phone " +
            "from Yahoo Finance when it's online. See All stocks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- Portfolio holdings

@Composable
private fun HoldingsView(state: StocksUiState, viewModel: StocksViewModel, onOpenStock: (String) -> Unit) {
    val rows = state.holdings
    val value = rows.sumOf { it.value }
    val invested = rows.sumOf { it.invested }
    val costed = rows.sumOf { it.costedValue }
    // Day move of the holdings with one: gain over their value before it.
    val day = rows.mapNotNull { p -> p.dayChange?.let { p to it } }
    val dayGain = day.sumOf { it.second }
    val dayStart = day.sumOf { (p, g) -> p.value - g }
    Column {
        OutlinedTextField(
            value = state.selection.query,
            onValueChange = viewModel::onQuery,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search symbol, company or sector") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${rows.size} holdings · ${Fmt.inr(value)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (dayStart > 0) ChangeText("1D " + Fmt.signedPercent(dayGain / dayStart), dayGain, style = MaterialTheme.typography.labelMedium)
            if (invested > 0) {
                if (dayStart > 0) Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChangeText("P&L " + Fmt.signedPercent(costed / invested - 1), costed - invested, style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(rows, key = { it.symbol }) { p ->
                HoldingStockRow(p, onClick = { onOpenStock(p.symbol) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HoldingStockRow(p: Position, onClick: () -> Unit) {
    val tracked = p.tracked
    Row(
        Modifier.fillMaxWidth().then(if (tracked != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.symbol, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(tracked?.stock?.name, p.sector.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            tracked?.metrics?.returns?.get(Period.Y1)?.let { r ->
                ChangeText("1Y " + Fmt.signedPercent(r), r, style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.inr(p.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            tracked?.metrics?.change1d?.let { ChangeText("1D " + Fmt.signedPercent(it), it, style = MaterialTheme.typography.labelMedium) }
            p.pnlPct?.let { ChangeText("P&L " + Fmt.signedPercent(it), it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

// ---------------------------------------------------------------- All constituents

@Composable
private fun AllStocksView(state: StocksUiState, viewModel: StocksViewModel, onOpenStock: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = state.selection.query,
            onValueChange = viewModel::onQuery,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search symbol, company or sector") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Text(
            "${state.all.size} stocks · largest market cap first",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(state.all, key = { it.symbol }) { st ->
                StockRow(st, onClick = { onOpenStock(st.symbol) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StockRow(st: AnalyzedStock, onClick: () -> Unit) {
    val m = st.metrics
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(st.symbol, style = MaterialTheme.typography.titleSmall)
            Text(st.stock.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("${st.stock.industry} · ${Fmt.crore(st.stock.marketCapCr)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.rupees(m.lastClose), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            ChangeText(Fmt.signedPercent(m.change1d), m.change1d, style = MaterialTheme.typography.labelMedium)
            ChangeText("1Y " + Fmt.signedPercent(m.returns[Period.Y1]), m.returns[Period.Y1], style = MaterialTheme.typography.labelSmall)
        }
    }
}
