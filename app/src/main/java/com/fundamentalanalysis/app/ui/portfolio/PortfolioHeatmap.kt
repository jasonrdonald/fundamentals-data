package com.fundamentalanalysis.app.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fundamentalanalysis.app.domain.analysis.HEAT_SCALE
import com.fundamentalanalysis.app.domain.analysis.HEATMAP_INDEX
import com.fundamentalanalysis.app.domain.analysis.HeatGrouping
import com.fundamentalanalysis.app.domain.analysis.HeatmapBuilder
import com.fundamentalanalysis.app.domain.model.PortfolioSummary
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.HeatLegend
import com.fundamentalanalysis.app.ui.components.Heatmap
import com.fundamentalanalysis.app.ui.heatmap.heatmapHeight

/**
 * The portfolio's Nifty 50 holdings as a treemap: area = size of the stock's move in the latest
 * session, colour = up or down. ETFs, funds and other stocks aren't shown.
 */
@Composable
fun PortfolioHeatmap(summary: PortfolioSummary, onOpenStock: (String) -> Unit) {
    var grouping by rememberSaveable { mutableStateOf(HeatGrouping.NONE) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(summary, grouping) { HeatmapBuilder.portfolio(summary.positions, grouping) }
    val stats = remember(groups) { HeatmapBuilder.summary(groups) }
    val nifty = remember(summary) { summary.positions.filter { it.value > 0 && it.tracked?.stock?.indexSymbol == HEATMAP_INDEX } }
    val fmt: (Double?) -> String = Fmt::signedPercent

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nifty.isEmpty()) {
            Text("No Nifty 50 stocks in this portfolio.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HeatGrouping.entries) { g ->
                FilterChip(grouping == g, onClick = { grouping = g }, label = { Text(if (g == HeatGrouping.NONE) "Holdings" else g.label) })
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            val dayChange = nifty.mapNotNull { it.dayChange }.takeIf { it.isNotEmpty() }?.sum()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${stats.up} up · ${stats.down} down · ", style = MaterialTheme.typography.bodyMedium)
                ChangeText(
                    "${fmt(stats.weighted)}" + (dayChange?.let { " (${Fmt.inr(it, signed = true)})" } ?: ""),
                    stats.weighted,
                )
            }
            Text(
                "${nifty.size} Nifty 50 holdings · ${Fmt.inr(nifty.sumOf { it.value })} · tile size = size of the day's move, " +
                    "colour = up or down in the latest session (${Fmt.day(nifty.firstNotNullOfOrNull { it.tracked?.metrics?.lastDate })}). " +
                    "ETFs, funds and other stocks aren't shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val tiles = groups.sumOf { it.tiles.size }
            Heatmap(
                groups = groups,
                scale = HEAT_SCALE,
                formatValue = fmt,
                selectedKey = selected,
                onSelect = { selected = if (selected == it.key) null else it.key },
                modifier = Modifier.fillMaxWidth().height(heatmapHeight(maxWidth, tiles, groups.size > 1)),
            )
        }
        HeatLegend(HEAT_SCALE, fmt, showNoData = stats.noData > 0, modifier = Modifier.padding(horizontal = 16.dp))
        nifty.firstOrNull { it.symbol == selected }?.let { SelectedHolding(it, onOpenStock) }
    }
}

@Composable
private fun SelectedHolding(p: Position, onOpenStock: (String) -> Unit) {
    val change = p.tracked?.metrics?.change1d
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.symbol, style = MaterialTheme.typography.titleMedium)
                    Text(
                        p.tracked?.stock?.industry ?: p.sector,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    ChangeText(Fmt.signedPercent(change), change, style = MaterialTheme.typography.titleMedium)
                    p.dayChange?.let { ChangeText(Fmt.inr(it, signed = true), it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Value ${Fmt.inr(p.value)} · P&L ${Fmt.signedPercent(p.pnlPct)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpenStock(p.symbol) }) { Text("Open") }
            }
        }
    }
}
