package com.fundamentalanalysis.app.ui.stocks

import com.fundamentalanalysis.app.ui.components.LocalUiLayout
import com.fundamentalanalysis.app.domain.model.UiComponent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.fundamentalanalysis.app.domain.analysis.CalendarTables
import com.fundamentalanalysis.app.domain.analysis.STOCK_PERIODS
import com.fundamentalanalysis.app.domain.analysis.rebaseTogether
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.FinancialPeriod
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.StockFactor
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase
import com.fundamentalanalysis.app.ui.components.CalendarTableView
import com.fundamentalanalysis.app.ui.components.CellShading
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.ChartSeries
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.KeyValueRow
import com.fundamentalanalysis.app.ui.components.LineChart
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.HistoryPending
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.changeColor
import com.fundamentalanalysis.app.ui.components.shareCsv
import com.fundamentalanalysis.app.ui.navigation.StockDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

sealed interface StockDetailUiState {
    data object Loading : StockDetailUiState
    data object NotFound : StockDetailUiState
    data class Ready(val stock: AnalyzedStock, val benchmark: MarketIndex?, val historyReady: Boolean = true) : StockDetailUiState
}

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeStocks: ObserveAnalyzedStocksUseCase,
    market: MarketRepository,
) : ViewModel() {
    val symbol: String = savedStateHandle.toRoute<StockDetailRoute>().symbol

    val state: StateFlow<StockDetailUiState> =
        combine(observeStocks.one(symbol), market.observeIndices(), market.observeAvailability()) { stock, indices, availability ->
            if (stock == null) StockDetailUiState.NotFound
            else StockDetailUiState.Ready(stock, indices.firstOrNull { it.symbol == stock.stock.indexSymbol }, availability.historyReady)
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StockDetailUiState.Loading)
}

@Composable
fun StockDetailScreen(onBack: () -> Unit, viewModel: StockDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = viewModel.symbol,
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (val s = state) {
                StockDetailUiState.Loading -> LoadingState()
                StockDetailUiState.NotFound -> EmptyState("This stock is not in the current dataset.")
                is StockDetailUiState.Ready -> StockContent(s.stock, s.benchmark, s.historyReady)
            }
        }
    }
}

@Composable
private fun StockContent(st: AnalyzedStock, benchmark: MarketIndex?, historyReady: Boolean) {
    val m = st.metrics
    val benchName = benchmark?.name ?: st.stock.indexSymbol
    val ui = LocalUiLayout.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column {
                Text(st.stock.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${st.stock.industry} · ${benchName} constituent · market cap ${Fmt.crore(st.stock.marketCapCr)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(Fmt.rupees(m.lastClose), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChangeText(Fmt.signedPercent(m.change1d), m.change1d, style = MaterialTheme.typography.titleMedium)
                    Text("  ·  " + Fmt.day(m.lastDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ui.shows(UiComponent.STOCK_VS_INDEX)) item { VersusIndexCard(st, benchName) }
        if (benchmark != null && ui.shows(UiComponent.STOCK_CHART)) item { RelativeChartCard(st, benchmark, historyReady) }
        if (ui.shows(UiComponent.STOCK_FACTORS)) item { FactorsCard(st, simple = ui.simple(UiComponent.STOCK_FACTORS)) }
        if (!historyReady) {
            item { SectionCard("Year-by-month returns and financials") { HistoryPending("this stock") } }
        }
        if (st.stock.monthly.size > 12 && ui.shows(UiComponent.STOCK_YEARS)) item { YearTableCard(st) }
        if (st.stock.annual.isNotEmpty() && ui.shows(UiComponent.STOCK_ANNUAL)) {
            item {
                SectionCard("Annual financials", subtitle = "Fiscal years · ₹ crore (EPS in ₹)") {
                    FinancialsTable(st.stock.annual, showBalanceSheet = !ui.simple(UiComponent.STOCK_ANNUAL))
                }
            }
        }
        if (st.stock.quarterly.isNotEmpty() && ui.shows(UiComponent.STOCK_QUARTERLY)) {
            item {
                SectionCard("Quarterly results", subtitle = "Latest quarters available · ₹ crore (EPS in ₹)") { FinancialsTable(st.stock.quarterly, showBalanceSheet = false) }
            }
        }
        item {
            Text(
                "Prices: Yahoo Finance (NSE) · fundamentals: Yahoo Finance, as reported · classification: NSE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VersusIndexCard(st: AnalyzedStock, benchName: String) {
    val m = st.metrics
    SectionCard("Performance vs $benchName", subtitle = "3Y is annualised · pp = percentage points") {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("Period", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            listOf(st.symbol, benchName, "Difference").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(84.dp))
            }
        }
        HorizontalDivider()
        STOCK_PERIODS.forEach { p ->
            val s = m.returns[p]
            val b = m.benchmarkReturns[p]
            val e = m.excess(p)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                ChangeText(Fmt.signedPercent(s), s, Modifier.width(84.dp), MaterialTheme.typography.bodyMedium, TextAlign.End)
                ChangeText(Fmt.signedPercent(b), b, Modifier.width(84.dp), MaterialTheme.typography.bodyMedium, TextAlign.End)
                ChangeText(
                    e?.let { String.format(Locale.US, "%+.1f pp", it * 100) + if (it > 0) " ✓" else "" } ?: "—",
                    e, Modifier.width(84.dp), MaterialTheme.typography.bodyMedium, TextAlign.End,
                )
            }
        }
    }
}

private val YEAR_SPANS = listOf(1, 3, 5, 10)
private val monthLabel = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)

@Composable
private fun RelativeChartCard(st: AnalyzedStock, benchmark: MarketIndex, historyReady: Boolean) {
    var years by rememberSaveable { mutableIntStateOf(5) }
    val (dates, stock, index) = remember(st.stock, benchmark, years) {
        val months = years * 12 + 1
        rebaseTogether(st.stock.monthly.takeLast(months), benchmark.monthly)
    }
    SectionCard("Price vs ${benchmark.name}", subtitle = "Month-end closes rebased to 100") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            YEAR_SPANS.forEachIndexed { i, y ->
                SegmentedButton(years == y, onClick = { years = y }, shape = SegmentedButtonDefaults.itemShape(i, YEAR_SPANS.size)) { Text("${y}Y") }
            }
        }
        if (years > 1 && !historyReady) {
            HistoryPending("${years}Y month-end closes")
        } else if (dates.size < 2) {
            Text("Not enough shared history.", style = MaterialTheme.typography.bodySmall)
        } else {
            LineChart(
                xLabels = dates.map { it.format(monthLabel) },
                series = listOf(ChartSeries(st.symbol, stock), ChartSeries(benchmark.name, index)),
                valueFormatter = { String.format(Locale.US, "%.0f", it) },
                includeZero = false,
                reference = 100.0,
                height = 200.dp,
            )
            if (dates.size < years * 12) {
                Text(
                    "Listed history starts ${Fmt.month(dates.first())}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FactorsCard(st: AnalyzedStock, simple: Boolean = false) {
    SectionCard("Factor profile", subtitle = "The measures used in the Rankings tab") {
        StockFactor.entries.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (!simple) Text(f.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            (if (simple) listOf(f.primary) else (listOf(f.primary) + f.supporting).distinct()).forEach { metric ->
                val v = st.valueOf(metric)
                val signed = metric.format == com.fundamentalanalysis.app.domain.model.StockFormat.SIGNED_PERCENT ||
                    metric.format == com.fundamentalanalysis.app.domain.model.StockFormat.POINTS
                KeyValueRow(metric.label, Fmt.stock(metric, v), valueColor = if (signed) changeColor(v) else androidx.compose.ui.graphics.Color.Unspecified)
            }
        }
    }
}

@Composable
private fun YearTableCard(st: AnalyzedStock) {
    val context = LocalContext.current
    val table = remember(st.stock) { CalendarTables.monthlyReturns(st.stock.monthly, st.stock.daily.lastOrNull()?.date) }
    SectionCard(
        "Performance by year",
        subtitle = "Month-on-month % change; last column is the calendar-year return · * = to date",
    ) {
        CalendarTableView(
            table = table,
            yearHeader = "Year %",
            cellText = { String.format(Locale.US, "%+.1f", it * 100) },
            yearText = { String.format(Locale.US, "%+.1f", it * 100) },
            shading = CellShading.DIVERGING,
        )
        Text(
            "Export CSV",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp).clickable {
                shareCsv(context, "${st.symbol} monthly returns by year (%)",
                    CalendarTables.csv(table, "Year return", CalendarTables::percentCell, CalendarTables::percentCell))
            },
        )
    }
}

private val periodLabel = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)

@Composable
private fun FinancialsTable(periods: List<FinancialPeriod>, showBalanceSheet: Boolean) {
    val cols = periods.reversed() // newest first
    val rows = buildList<Pair<String, (FinancialPeriod) -> String>> {
        add("Revenue" to { p -> Fmt.crore(p.revenue) })
        add("Net profit" to { p -> Fmt.crore(p.netIncome) })
        add("Net margin" to { p -> if (p.revenue != null && p.netIncome != null && p.revenue > 0) Fmt.percent(p.netIncome / p.revenue) else "—" })
        add("EPS (₹)" to { p -> p.eps?.let { String.format(Locale.US, "%.2f", it) } ?: "—" })
        if (showBalanceSheet) {
            add("Operating income" to { p -> Fmt.crore(p.operatingIncome) })
            add("Equity" to { p -> Fmt.crore(p.equity) })
            add("Debt" to { p -> Fmt.crore(p.debt) })
            add("ROE" to { p -> if (p.equity != null && p.netIncome != null && p.equity > 0) Fmt.percent(p.netIncome / p.equity) else "—" })
        }
    }
    Row {
        Column {
            Text("", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp))
            rows.forEach { (label, _) -> Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp).width(112.dp)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            cols.forEach { p ->
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(p.endDate.format(periodLabel), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
                    rows.forEach { (_, value) -> Text(value(p), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }
}
