package com.fundamentalanalysis.app.ui.stocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fundamentalanalysis.app.domain.analysis.DistributionStats
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.DistributionChart
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.KeyValueRow
import com.fundamentalanalysis.app.ui.components.SectionCard
import com.fundamentalanalysis.app.ui.theme.LocalChartColors
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun DistributionView(state: StocksUiState, onPeriod: (Period) -> Unit, onOpenStock: (String) -> Unit) {
    val period = state.selection.period
    val stats = state.distribution
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(SECTOR_PERIODS) { p -> FilterChip(period == p, onClick = { onPeriod(p) }, label = { Text(p.label) }) }
            }
        }
        if (stats == null) {
            item { EmptyState("Not enough stocks report a ${period.label} return to draw a distribution.") }
            return@LazyColumn
        }
        item {
            SectionCard(
                "${state.indexName} stocks: ${period.label} return distribution",
                subtitle = "${stats.n} of ${state.universeSize} stocks · bars = stocks per range · curve = normal distribution with the same mean and SD",
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                DistributionChart(stats, benchmarkLabel = "Index")
            }
        }
        item { ShapeCard(stats, state.indexName, period, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
        item { BandsCard(stats, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
        item { StockListCard(state.distributionRows, stats, period, onOpenStock, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
    }
}

@Composable
private fun ShapeCard(stats: DistributionStats, indexName: String, period: Period, modifier: Modifier) {
    SectionCard("Shape", subtitle = "How the ${period.label} returns are spread", modifier = modifier) {
        KeyValueRow("Mean / median", "${Fmt.signedPercent(stats.mean)} / ${Fmt.signedPercent(stats.median)}")
        KeyValueRow("Standard deviation", Fmt.percent(stats.sd))
        KeyValueRow("Range", "${stats.min.first} ${Fmt.signedPercent(stats.min.second)} … ${stats.max.first} ${Fmt.signedPercent(stats.max.second)}")
        stats.benchmark?.let { b ->
            KeyValueRow("$indexName return", Fmt.signedPercent(b))
            KeyValueRow("Stocks that beat it", "${stats.aboveBenchmark} of ${stats.n} (${(100.0 * stats.aboveBenchmark / stats.n).toInt()}%)")
            KeyValueRow("Index's percentile among stocks", stats.benchmarkPercentile?.let { String.format(Locale.US, "%.0fth", it) } ?: "—")
        }
        KeyValueRow("Skewness", String.format(Locale.US, "%+.2f", stats.skewness))
        KeyValueRow("Excess kurtosis", String.format(Locale.US, "%+.2f", stats.excessKurtosis))
        Text(
            interpretation(stats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Plain-language reading of the distribution's shape. */
private fun interpretation(s: DistributionStats): String {
    val skew = when {
        s.skewness > 0.5 -> "Skewed right: a few big winners pull the mean above the median, so the typical stock did worse than the average suggests."
        s.skewness < -0.5 -> "Skewed left: a few big losers drag the mean below the median."
        else -> "Roughly symmetric around the mean."
    }
    val tails = when {
        s.excessKurtosis > 1 -> "Fat tails: more extreme moves than a normal curve predicts."
        s.excessKurtosis < -1 -> "Thin tails: returns are bunched more evenly than a normal curve."
        else -> "Tails are close to normal."
    }
    val beat = s.benchmark?.let {
        if (s.aboveBenchmark * 2 < s.n) " Fewer than half the stocks beat the index — its return was driven by a minority (often the largest weights)."
        else " Most stocks beat the index."
    } ?: ""
    return "$skew $tails$beat"
}

@Composable
private fun BandsCard(stats: DistributionStats, modifier: Modifier) {
    val colors = LocalChartColors.current
    val maxCount = stats.bands.maxOf { maxOf(it.actual.toDouble(), it.expected) }.coerceAtLeast(1.0)
    SectionCard("Actual vs normal", subtitle = "Stocks in each standard-deviation band, against what a normal distribution expects", modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("Band", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
            Text("Actual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Normal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
        }
        stats.bands.forEach { band ->
            val lo = stats.mean + band.fromSigma * stats.sd
            val hi = stats.mean + band.toSigma * stats.sd
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(104.dp)) {
                    Text(band.label, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            band.fromSigma.isInfinite() -> "< ${Fmt.signedPercent(hi)}"
                            band.toSigma.isInfinite() -> "> ${Fmt.signedPercent(lo)}"
                            else -> "${Fmt.signedPercent(lo)} … ${Fmt.signedPercent(hi)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width((140 * band.actual / maxCount).dp.coerceAtLeast(2.dp))
                            .height(14.dp)
                            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                            .background(colors.series[0]),
                    )
                    Text("  ${band.actual}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(String.format(Locale.US, "%.1f", band.expected), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
        if (stats.outliers.isNotEmpty()) {
            Text(
                "Beyond 2 SD: " + stats.outliers.joinToString(", ") { "${it.symbol} ${Fmt.signedPercent(it.value)} (${String.format(Locale.US, "%+.1fσ", it.zScore)})" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StockListCard(
    rows: List<Pair<AnalyzedStock, Double>>,
    stats: DistributionStats,
    period: Period,
    onOpenStock: (String) -> Unit,
    modifier: Modifier,
) {
    SectionCard("All stocks by ${period.label} return", subtitle = "z = standard deviations from the mean · tap to open", modifier = modifier) {
        rows.forEachIndexed { i, (st, r) ->
            if (i > 0) HorizontalDivider()
            val z = if (stats.sd > 0) (r - stats.mean) / stats.sd else 0.0
            Row(
                Modifier.fillMaxWidth().clickable { onOpenStock(st.symbol) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(st.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(st.stock.industry, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                ChangeText(Fmt.signedPercent(r), r, Modifier.width(84.dp), MaterialTheme.typography.bodyMedium, TextAlign.End)
                Text(
                    String.format(Locale.US, "%+.1fσ", z),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (abs(z) > 2) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
    }
}

/** CSV of every stock's return, z-score and band for the selected period. */
internal fun distributionCsv(rows: List<Pair<AnalyzedStock, Double>>, stats: DistributionStats?, benchmark: Double?): String = buildString {
    appendLine("symbol,name,sector,return_pct,z_score,beat_index")
    rows.forEach { (s, r) ->
        val z = stats?.takeIf { it.sd > 0 }?.let { (r - it.mean) / it.sd }
        appendLine(
            listOf(
                s.symbol, s.stock.name, s.stock.industry, String.format(Locale.US, "%.2f", r * 100),
                z?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(), benchmark?.let { (r > it).toString() }.orEmpty(),
            ).joinToString(",") { v -> if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v },
        )
    }
}
