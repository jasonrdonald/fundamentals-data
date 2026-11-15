package com.fundamentalanalysis.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.fundamentalanalysis.app.domain.analysis.CalendarTables
import com.fundamentalanalysis.app.domain.analysis.CalendarTable
import com.fundamentalanalysis.app.domain.model.CalendarKind
import com.fundamentalanalysis.app.domain.analysis.IndexAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.ValuationMeasure
import com.fundamentalanalysis.app.ui.components.CalendarTableView
import com.fundamentalanalysis.app.ui.components.CellShading
import com.fundamentalanalysis.app.ui.components.shareCsv
import com.fundamentalanalysis.app.ui.dashboard.HEADLINE_INDEX
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.MonthlyMove
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.ChartSeries
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.GroupedBarChart
import com.fundamentalanalysis.app.ui.components.HeadlineQuote
import com.fundamentalanalysis.app.ui.components.HorizontalBars
import com.fundamentalanalysis.app.ui.components.LineChart
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.MomentumPanel
import com.fundamentalanalysis.app.ui.components.ReturnsGrid
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.SessionGrid
import com.fundamentalanalysis.app.ui.components.StarButton
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDay = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
fun IndexDetailScreen(
    onBack: () -> Unit,
    viewModel: IndexDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? IndexDetailUiState.Ready

    ScreenScaffold(
        title = ready?.index?.index?.name ?: viewModel.symbol,
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            if (ready != null) StarButton(ready.index.isWatchlisted, ready.index.index.name, viewModel::toggleWatchlist)
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (val s = state) {
                IndexDetailUiState.Loading -> LoadingState()
                IndexDetailUiState.NotFound -> EmptyState("This index is no longer in the shared dataset.")
                is IndexDetailUiState.Ready -> DetailContent(s, viewModel::onRangeChange)
            }
        }
    }
}

@Composable
private fun DetailContent(state: IndexDetailUiState.Ready, onRange: (ChartRange) -> Unit) {
    val idx = state.index
    val a = idx.analytics
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column {
                Text(
                    "${idx.symbol} · ${idx.index.category.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HeadlineQuote(idx)
            }
        }
        a.latest?.let { session ->
            item { SectionCard("Previous session", subtitle = Fmt.day(session.date)) { SessionGrid(session) } }
        }
        item { SectionCard("Performance") { ReturnsGrid(idx) } }
        item { PriceChartCard(idx, state.range, onRange) }
        item { SectionCard("Momentum, volume & risk") { MomentumPanel(idx) } }
        if (a.monthly.size > 1) {
            item { MonthlyChartCard(a.monthly) }
            if (idx.symbol == HEADLINE_INDEX) item { ReturnsByYearCard(idx, state.calendars[CalendarKind.RETURNS]) }
            val valuation = ValuationMeasure.entries.mapNotNull { m -> state.calendars[m.kind]?.let { m to it } }.toMap()
            if (valuation.isNotEmpty() || idx.index.valuation.isNotEmpty()) item { ValuationByYearCard(idx, valuation) }
            item {
                SectionCard(
                    "Month-on-month",
                    subtitle = "Newest first · MTD = month to date" + if (!state.historyReady) " · older months are still loading" else "",
                ) { MonthlyTable(a.monthly) }
            }
        }
        if (idx.index.daily.any { (it.volume ?: 0) > 0 }) {
            item { VolumeCard(idx) }
        }
        state.composition?.let { comp -> item { CompositionCard(comp) } }
        idx.index.source?.let { src ->
            item {
                Text(
                    "Source: $src · prices in index points",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PriceChartCard(idx: AnalyzedIndex, range: ChartRange, onRange: (ChartRange) -> Unit) {
    val daily = idx.index.daily
    val closes = daily.map { it.close }
    val dma50 = IndexAnalyticsCalculator.rollingSma(closes, 50)
    val dma200 = IndexAnalyticsCalculator.rollingSma(closes, 200)
    val from = (daily.size - range.sessions).coerceAtLeast(0)
    SectionCard("Price", subtitle = "Daily close with 50- and 200-day moving averages") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            ChartRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = range == r,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, ChartRange.entries.size),
                ) { Text(r.label) }
            }
        }
        LineChart(
            xLabels = daily.drop(from).map { it.date.format(shortDay) },
            series = listOf(
                ChartSeries("Close", closes.drop(from)),
                ChartSeries("50-DMA", dma50.drop(from)),
                ChartSeries("200-DMA", dma200.drop(from)),
            ),
            valueFormatter = Fmt::points,
            includeZero = false,
            height = 220.dp,
        )
    }
}

@Composable
private fun MonthlyChartCard(monthly: List<MonthlyMove>) {
    val recent = monthly.drop(1).takeLast(24)
    SectionCard("Monthly returns", subtitle = "Close vs previous month's close, last ${recent.size} months") {
        GroupedBarChart(
            xLabels = recent.map { Fmt.month(it.month) + if (it.isMonthToDate) "*" else "" },
            series = listOf(ChartSeries("Month-on-month", recent.map { it.changePct })),
            valueFormatter = Fmt::signedPercent,
            colorBySign = true,
            height = 180.dp,
        )
    }
}

@Composable
private fun ReturnsByYearCard(idx: AnalyzedIndex, precomputed: CalendarTable?) {
    val context = LocalContext.current
    // Precomputed by the pipeline from the full history, so it is complete before history loads.
    val table = precomputed ?: remember(idx.index) { CalendarTables.monthlyReturns(idx.index.monthly, idx.index.daily.lastOrNull()?.date) }
    val first = table.rows.lastOrNull()?.year
    SectionCard(
        "Performance by year",
        subtitle = "Month-on-month % change of month-end close; last column is the calendar-year return · * = to date" +
            (first?.let { " · since $it" } ?: ""),
    ) {
        CalendarTableView(
            table = table,
            yearHeader = "Year %",
            cellText = { String.format(Locale.US, "%+.1f", it * 100) },
            yearText = { String.format(Locale.US, "%+.1f", it * 100) },
            shading = CellShading.DIVERGING,
        )
        ExportLink {
            shareCsv(
                context,
                "${idx.index.name} monthly returns by year (%)",
                CalendarTables.csv(table, "Year return", CalendarTables::percentCell, CalendarTables::percentCell),
            )
        }
    }
}

@Composable
private fun ValuationByYearCard(idx: AnalyzedIndex, precomputed: Map<ValuationMeasure, CalendarTable>) {
    val context = LocalContext.current
    var measure by rememberSaveable { mutableStateOf(ValuationMeasure.PE) }
    val latest = idx.index.daily.lastOrNull()?.date
    val table = precomputed[measure] ?: remember(idx.index, measure) { CalendarTables.valuation(idx.index.valuation, measure, latest) }
    val format: (Double) -> String = if (measure == ValuationMeasure.DIVIDEND_YIELD) {
        { String.format(Locale.US, "%.2f", it) }
    } else {
        { String.format(Locale.US, "%.1f", it) }
    }
    SectionCard(
        "${measure.short} history",
        subtitle = "Month-end ${measure.label.lowercase()}${if (measure == ValuationMeasure.DIVIDEND_YIELD) " (%)" else ""}; " +
            "last column is the change over the year · * = to date · source: NSE",
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            ValuationMeasure.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = measure == m,
                    onClick = { measure = m },
                    shape = SegmentedButtonDefaults.itemShape(i, ValuationMeasure.entries.size),
                ) { Text(m.short, maxLines = 1) }
            }
        }
        // Summary from the table's month-end cells (newest row first, so the first value found is the latest).
        val latestCell = table.rows.firstNotNullOfOrNull { r ->
            r.months.indexOfLast { it != null }.takeIf { it >= 0 }?.let { m -> Triple(r.year, m, r.months[m]!!) }
        }
        val values = table.rows.flatMap { r -> r.months.filterNotNull() }
        if (latestCell != null) {
            val (year, month, value) = latestCell
            Text(
                "Latest ${format(value)} (${Fmt.month(java.time.LocalDate.of(year, month + 1, 1))}) · range ${values.minOrNull()?.let(format)}–${values.maxOrNull()?.let(format)}" +
                    " · median ${values.sorted().let { s -> if (s.isEmpty()) "—" else format(s[s.size / 2]) }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        CalendarTableView(
            table = table,
            yearHeader = "Chg %",
            cellText = format,
            yearText = { String.format(Locale.US, "%+.1f", it * 100) },
            shading = CellShading.SEQUENTIAL,
        )
        ExportLink {
            shareCsv(
                context,
                "${idx.index.name} month-end ${measure.short} by year",
                CalendarTables.csv(table, "Change over year (%)", CalendarTables::numberCell, CalendarTables::percentCell),
            )
        }
    }
}

@Composable
private fun ExportLink(onClick: () -> Unit) {
    Text(
        "Export CSV",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp).clickable(onClick = onClick),
    )
}

@Composable
private fun MonthlyTable(monthly: List<MonthlyMove>) {
    var showAll by remember { mutableStateOf(false) }
    val rows = monthly.reversed().let { if (showAll) it else it.take(12) }
    val hasVolume = monthly.any { (it.volume ?: 0) > 0 }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            TableRow(listOf("Month", "Open", "High", "Low", "Close", "MoM", "O→C") + if (hasVolume) listOf("Volume") else emptyList(), header = true)
            HorizontalDivider()
            rows.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Cell(Fmt.month(m.month) + if (m.isMonthToDate) " MTD" else "", 80.dp, TextAlign.Start)
                    Cell(Fmt.points(m.open))
                    Cell(Fmt.points(m.high))
                    Cell(Fmt.points(m.low))
                    Cell(Fmt.points(m.close), bold = true)
                    ChangeText(Fmt.signedPercent(m.changePct), m.changePct, Modifier.width(76.dp), MaterialTheme.typography.bodySmall, TextAlign.End)
                    ChangeText(Fmt.signedPercent(m.openToClosePct), m.openToClosePct, Modifier.width(76.dp), MaterialTheme.typography.bodySmall, TextAlign.End)
                    if (hasVolume) Cell(Fmt.volume(m.volume?.toDouble()))
                }
            }
        }
    }
    if (monthly.size > 12) {
        Text(
            if (showAll) "Show last 12 months" else "Show all ${monthly.size} months",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp).clickable { showAll = !showAll },
        )
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, c ->
            Cell(c, if (i == 0) 80.dp else 76.dp, if (i == 0) TextAlign.Start else TextAlign.End, bold = header)
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp = 76.dp, align: TextAlign = TextAlign.End, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun VolumeCard(idx: AnalyzedIndex) {
    val recent = idx.index.daily.takeLast(60)
    SectionCard("Daily volume", subtitle = "Last ${recent.size} sessions") {
        GroupedBarChart(
            xLabels = recent.map { it.date.format(shortDay) },
            series = listOf(ChartSeries("Volume", recent.map { it.volume?.toDouble()?.takeIf { v -> v > 0 } })),
            valueFormatter = Fmt::volume,
            height = 140.dp,
        )
    }
}

@Composable
private fun CompositionCard(comp: Composition) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard("Sector & industry classification", subtitle = "${comp.constituents.size} constituents · NSE industries · as of ${comp.asOf ?: "—"}") {
        HorizontalBars(
            items = comp.byIndustry.map { (industry, members) -> industry to members.size.toDouble() },
            valueFormatter = { "${it.toInt()}" },
            labelWidth = 150.dp,
        )
        Text(
            if (expanded) "Hide constituents" else "Show constituents",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp).clickable { expanded = !expanded },
        )
        if (expanded) {
            comp.byIndustry.forEach { (industry, members) ->
                Text(industry, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                members.forEach { c ->
                    Text("${c.symbol} · ${c.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
