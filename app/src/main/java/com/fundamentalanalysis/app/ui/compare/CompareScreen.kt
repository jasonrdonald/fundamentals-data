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
            else CompareContent(state, viewModel::add, viewModel::remove, viewModel::onRangeChange, onOpenIndex)
        }
    }
}

@Composable
private fun CompareContent(
    state: CompareUiState,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRange: (CompareRange) -> Unit,
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
            item { SectionCard("Side by side", subtitle = "Best value per row in bold") { ComparisonTable(state.selected) } }
        }
    }
}

@Composable
private fun RelativePerformanceCard(state: CompareUiState, onRange: (CompareRange) -> Unit) {
    // Rebase every index to 100 at the start of the window so different levels compare fairly.
    val (dates, series) = when (state.range) {
        CompareRange.Y1 -> {
            val dates = state.selected.flatMap { it.index.daily.takeLast(253).map { b -> b.date } }.distinct().sorted()
            dates to state.selected.map { idx -> rebased(idx.index.name, dates, idx.index.daily.associate { it.date to it.close }) }
        }
        CompareRange.Y5 -> {
            val dates = state.selected.flatMap { it.index.monthly.takeLast(61).map { b -> b.date } }.distinct().sorted()
            dates to state.selected.map { idx -> rebased(idx.index.name, dates, idx.index.monthly.associate { it.date to it.close }) }
        }
    }
    SectionCard("Relative performance", subtitle = "Rebased to 100 at the start of the window") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            CompareRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = state.range == r,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, CompareRange.entries.size),
                ) { Text(r.label) }
            }
        }
        LineChart(
            xLabels = dates.map { if (state.range == CompareRange.Y1) it.format(shortDay) else Fmt.month(it) },
            series = series,
            valueFormatter = { String.format(Locale.US, "%.1f", it) },
            includeZero = false,
            reference = 100.0,
            height = 220.dp,
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
