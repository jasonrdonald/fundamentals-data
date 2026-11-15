package com.fundamentalanalysis.app.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.ui.components.ChartSeries
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.LineChart
import com.fundamentalanalysis.app.ui.components.HorizontalBars
import com.fundamentalanalysis.app.ui.components.GroupedBarChart
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.changeColor
import com.fundamentalanalysis.app.ui.theme.LocalChartColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDay = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)

@Composable
fun CompareScreen(
    onOpenIndex: (String) -> Unit,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Compare") { padding ->
        Column(Modifier.padding(padding)) {
            if (state.loading) LoadingState()
            else CompareContent(state, viewModel::add, viewModel::remove, viewModel::onRangeChange, viewModel::onModeChange, onOpenIndex)
        }
    }
}

@Composable
private fun CompareContent(
    state: CompareUiState,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRange: (CompareRange) -> Unit,
    onMode: (ReturnMode) -> Unit,
    onOpenIndex: (String) -> Unit,
) {
    val colors = LocalChartColors.current
    var picker by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.selected.forEachIndexed { i, idx ->
                    InputChip(
                        selected = true,
                        onClick = { onOpenIndex(idx.symbol) },
                        label = { Text(idx.index.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(colors.series[i])) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${idx.index.name}",
                                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).clickable { onRemove(idx.symbol) },
                            )
                        },
                    )
                }
                if (state.canAddMore) {
                    Box {
                        AssistChip(
                            onClick = { picker = true },
                            label = { Text("Add index") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        )
                        DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                            state.all.filter { it !in state.selected }.forEach { idx ->
                                DropdownMenuItem(
                                    text = { Text("${idx.index.name} · ${idx.index.category.label}") },
                                    onClick = { onAdd(idx.symbol); picker = false },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.selected.isEmpty()) {
            item { EmptyState("Add up to ${CompareViewModel.MAX_SELECTED} indices to compare them side by side.") }
        } else {
            item { RelativePerformanceCard(state, onRange) }
            item { ReturnsByPeriodCard(state, onMode) }
            item { AllIndicesCard(state, onRange) }
            item { SectionCard("Side by side", subtitle = "Best value per row in bold") { ComparisonTable(state.selected) } }
        }
    }
}

@Composable
private fun RelativePerformanceCard(state: CompareUiState, onRange: (CompareRange) -> Unit) {
    // Rebase every index to 100 at the start of the window so different levels compare fairly.
    val range = state.range
    val sessions = range.sessions
    val daily = sessions != null
    // Same window for every index: the last N sessions (or months) plus the base point.
    val dates = state.selected.flatMap { idx ->
        if (sessions != null) idx.index.daily.takeLast(sessions + 1).map { it.date }
        else idx.index.monthly.takeLast((range.months ?: 0) + 1).map { it.date }
    }.distinct().sorted()
    val series = state.selected.map { idx ->
        val closes = (if (daily) idx.index.daily else idx.index.monthly).associate { it.date to it.close }
        rebased(idx.index.name, dates, closes)
    }
    SectionCard(
        "Relative performance",
        subtitle = "Rebased to 100 at the start of the window · " + if (daily) "daily closes" else "month-end closes",
    ) {
        RangeSelector(range, onRange)
        LineChart(
            xLabels = dates.map { if (daily) it.format(shortDay) else Fmt.month(it) },
            series = series,
            valueFormatter = { String.format(Locale.US, "%.1f", it) },
            includeZero = false,
            reference = 100.0,
            height = 220.dp,
        )
    }
}

@Composable
private fun RangeSelector(range: CompareRange, onRange: (CompareRange) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        CompareRange.entries.forEachIndexed { i, r ->
            SegmentedButton(
                selected = range == r,
                onClick = { onRange(r) },
                shape = SegmentedButtonDefaults.itemShape(i, CompareRange.entries.size),
                icon = {},
            ) { Text(r.label, maxLines = 1) }
        }
    }
}

/** Grouped bars: one group per period (3M..5Y), one bar per selected index. */
@Composable
private fun ReturnsByPeriodCard(state: CompareUiState, onMode: (ReturnMode) -> Unit) {
    val periods = CompareRange.entries
    SectionCard(
        "Returns by period",
        subtitle = if (state.mode == ReturnMode.TOTAL) "Cumulative return over each window"
        else "3Y and 5Y shown per year (CAGR); shorter windows cumulative",
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            ReturnMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = state.mode == m,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, ReturnMode.entries.size),
                ) { Text(m.label) }
            }
        }
        GroupedBarChart(
            xLabels = periods.map { it.label },
            series = state.selected.map { idx ->
                ChartSeries(Fmt.shortName(idx.index.name), periods.map { idx.returnFor(it, state.mode) })
            },
            valueFormatter = Fmt::signedPercent,
            height = 220.dp,
        )
        // Exact values, so nothing depends on reading bar heights or colour.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("", modifier = Modifier.weight(1.4f))
            periods.forEach {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.selected.forEach { idx ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(Fmt.shortName(idx.index.name), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1.4f))
                periods.forEach { p ->
                    val v = idx.returnFor(p, state.mode)
                    Text(
                        v?.let { String.format(Locale.US, "%+.1f", it * 100) } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = changeColor(v),
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            "Values in %",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Every equity index ranked for the chosen window, with the compared ones called out. */
@Composable
private fun AllIndicesCard(state: CompareUiState, onRange: (CompareRange) -> Unit) {
    val range = state.range
    val chosen = state.selected.map { it.symbol }.toSet()
    val rows = state.equityIndices
        .mapNotNull { idx -> idx.returnFor(range, state.mode)?.let { idx to it } }
        .sortedByDescending { it.second }
    SectionCard(
        "All indices, ${range.label}",
        subtitle = (if (range.years != null && state.mode == ReturnMode.ANNUALISED) "Per year (CAGR)" else "Cumulative return") +
            " · ● = in your comparison",
    ) {
        RangeSelector(range, onRange)
        HorizontalBars(
            items = rows.map { (idx, v) -> (if (idx.symbol in chosen) "● " else "") + Fmt.shortName(idx.index.name) to v },
            valueFormatter = Fmt::signedPercent,
            signed = true,
            labelWidth = 128.dp,
        )
    }
}

private fun rebased(name: String, dates: List<LocalDate>, closes: Map<LocalDate, Double>): ChartSeries {
    val base = dates.firstNotNullOfOrNull { closes[it] }
    return ChartSeries(name, dates.map { d -> closes[d]?.let { c -> base?.let { c / it * 100 } } })
}

private val comparedMetrics = listOf(
    Metric.RETURN_1D, Metric.RETURN_1W, Metric.RETURN_1M, Metric.RETURN_3M, Metric.RETURN_6M, Metric.RETURN_YTD,
    Metric.RETURN_1Y, Metric.CAGR_3Y, Metric.CAGR_5Y, Metric.RSI_14, Metric.VS_DMA_50, Metric.VS_DMA_200,
    Metric.FROM_52W_HIGH, Metric.REL_VOLUME, Metric.VOLATILITY_1Y,
)

@Composable
private fun ComparisonTable(indices: List<AnalyzedIndex>) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("Metric", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1.3f))
            indices.forEach {
                Text(
                    Fmt.shortName(it.index.name),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider()
        comparedMetrics.forEach { metric ->
            val values = indices.map { it.valueOf(metric) }
            val present = values.filterNotNull()
            // RSI has no "better" direction worth highlighting.
            val best = if (present.size < 2 || metric == Metric.RSI_14) null
            else if (metric.higherIsBetter) present.max() else present.min()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(metric.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f))
                values.forEach { v ->
                    val signed = metric.format == com.fundamentalanalysis.app.domain.model.MetricFormat.SIGNED_PERCENT
                    Text(
                        Fmt.metric(metric, v),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (v != null && v == best) FontWeight.Bold else FontWeight.Normal,
                        color = if (signed) changeColor(v) else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
