package com.fundamentalanalysis.app.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.domain.analysis.MonthlyMatrix
import com.fundamentalanalysis.app.domain.analysis.ReportBuilder
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.model.MetricFormat
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.HorizontalBars
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.components.shareCsv
import com.fundamentalanalysis.app.ui.theme.LocalChartColors
import kotlin.math.abs

@Composable
fun ReportsScreen(
    onOpenIndex: (String) -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val csv = state.toCsv()

    ScreenScaffold(
        title = "Reports",
        actions = {
            IconButton(onClick = { csv?.let { shareCsv(context, state.title, it) } }, enabled = !state.loading && csv != null) {
                Icon(Icons.Filled.Share, contentDescription = "Export report as CSV")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ReportType.entries.forEachIndexed { i, type ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { viewModel.onTypeChange(type) },
                        shape = SegmentedButtonDefaults.itemShape(i, ReportType.entries.size),
                    ) { Text(type.label, maxLines = 1) }
                }
            }
            when {
                state.loading -> LoadingState()
                state.type == ReportType.RANKING -> RankingReport(state, viewModel, onOpenIndex)
                state.type == ReportType.PERFORMANCE -> PerformanceReport(state.performance, onOpenIndex)
                state.type == ReportType.MONTHLY -> MonthlyReport(state.monthly, onOpenIndex)
                else -> ClassificationReport(state.composition)
            }
        }
    }
}

@Composable
private fun RankingReport(state: ReportsUiState, viewModel: ReportsViewModel, onOpenIndex: (String) -> Unit) {
    var metricMenu by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Box {
                OutlinedButton(onClick = { metricMenu = true }) {
                    Text(state.metric.label)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose metric")
                }
                DropdownMenu(expanded = metricMenu, onDismissRequest = { metricMenu = false }) {
                    Metric.entries.forEach { m ->
                        DropdownMenuItem(text = { Text("${m.group}: ${m.label}") }, onClick = { viewModel.onMetricChange(m); metricMenu = false })
                    }
                }
            }
            Text(
                state.metric.description + if (state.metric.higherIsBetter) " · ranked high to low" else " · ranked low to high",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp)) {
                item { FilterChip(state.category == null, onClick = { viewModel.onCategoryChange(null) }, label = { Text("All") }) }
                items(state.categories) { c ->
                    FilterChip(state.category == c, onClick = { viewModel.onCategoryChange(if (state.category == c) null else c) }, label = { Text(c.label) })
                }
            }
        }
        if (state.ranking.isEmpty()) {
            item { EmptyState("No index reports ${state.metric.label} in this selection.") }
        } else {
            item {
                SectionCard(state.title) {
                    HorizontalBars(
                        items = state.ranking.map { Fmt.shortName(it.index.index.name) to it.value },
                        valueFormatter = { Fmt.metric(state.metric, it) },
                        signed = state.metric.format == MetricFormat.SIGNED_PERCENT,
                        labelWidth = 110.dp,
                    )
                }
            }
            item {
                SectionCard("Full ranking", subtitle = "${state.ranking.size} indices · tap to open") {
                    state.ranking.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenIndex(row.index.symbol) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#${row.rank}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.index.index.name, style = MaterialTheme.typography.titleSmall)
                                Text(row.index.index.category.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Fmt.metric(state.metric, row.value), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceReport(indices: List<AnalyzedIndex>, onOpenIndex: (String) -> Unit) {
    val periods = ReportBuilder.performancePeriods
    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        item {
            SectionCard("Trailing returns", subtitle = "3Y and 5Y are annualised · scroll sideways") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column {
                        Row(Modifier.padding(vertical = 6.dp)) {
                            HeaderCell("Index", 120.dp, TextAlign.Start)
                            periods.forEach { HeaderCell(it.label.removeSuffix(" CAGR"), 64.dp) }
                        }
                        HorizontalDivider()
                        indices.forEach { idx ->
                            Row(Modifier.clickable { onOpenIndex(idx.symbol) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(Fmt.shortName(idx.index.name), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(120.dp))
                                periods.forEach { p ->
                                    val v = idx.analytics.returns[p]
                                    ChangeText(Fmt.signedPercent(v).replace("%", ""), v, Modifier.width(64.dp), MaterialTheme.typography.bodySmall, TextAlign.End)
                                }
                            }
                        }
                    }
                }
                Text("Values in %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MonthlyReport(matrix: MonthlyMatrix, onOpenIndex: (String) -> Unit) {
    if (matrix.months.isEmpty()) {
        EmptyState("No monthly data.")
        return
    }
    val colors = LocalChartColors.current
    val maxAbs = matrix.rows.flatMap { it.second.filterNotNull() }.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        item {
            SectionCard(
                "Month-on-month returns",
                subtitle = "% change of month-end close · shading scales with size" + (matrix.monthToDate?.let { " · ${Fmt.month(it)} is month to date" } ?: ""),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column {
                        Row(Modifier.padding(vertical = 6.dp)) {
                            HeaderCell("Index", 110.dp, TextAlign.Start)
                            matrix.months.forEach { HeaderCell(Fmt.month(it), 60.dp) }
                        }
                        HorizontalDivider()
                        matrix.rows.forEach { (idx, values) ->
                            Row(Modifier.clickable { onOpenIndex(idx.symbol) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(Fmt.shortName(idx.index.name), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(110.dp))
                                values.forEach { v ->
                                    val tint = when {
                                        v == null -> MaterialTheme.colorScheme.surface
                                        v >= 0 -> colors.gain.copy(alpha = colors.heatAlpha(abs(v) / maxAbs))
                                        else -> colors.loss.copy(alpha = colors.heatAlpha(abs(v) / maxAbs))
                                    }
                                    Text(
                                        v?.let { String.format(java.util.Locale.US, "%+.1f", it * 100) } ?: "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(60.dp).padding(1.dp).background(tint).padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassificationReport(composition: Composition?) {
    if (composition == null) {
        EmptyState("No classification data in the current dataset.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            SectionCard(
                "${composition.indexSymbol} by industry",
                subtitle = "${composition.constituents.size} constituents · NSE industry classification · as of ${composition.asOf ?: "—"}",
            ) {
                HorizontalBars(
                    items = composition.byIndustry.map { (industry, members) -> industry to members.size.toDouble() },
                    valueFormatter = { "${it.toInt()} (${String.format(java.util.Locale.US, "%.0f%%", it / composition.constituents.size * 100)})" },
                    labelWidth = 150.dp,
                )
            }
        }
        items(composition.byIndustry, key = { it.first }) { (industry, members) ->
            SectionCard(industry, subtitle = "${members.size} constituents") {
                members.forEach { c ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(c.symbol, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(100.dp))
                        Text(c.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, align: TextAlign = TextAlign.End) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1, modifier = Modifier.width(width))
}
