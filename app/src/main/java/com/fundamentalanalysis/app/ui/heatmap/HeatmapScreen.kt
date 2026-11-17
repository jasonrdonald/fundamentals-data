package com.fundamentalanalysis.app.ui.heatmap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.domain.analysis.HEAT_SCALE
import com.fundamentalanalysis.app.domain.analysis.HeatGrouping
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.ui.components.ChangeText
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.Fmt
import com.fundamentalanalysis.app.ui.components.HeatLegend
import com.fundamentalanalysis.app.ui.components.Heatmap
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import com.fundamentalanalysis.app.ui.components.SectionCard
import kotlin.math.sqrt

/** Map height for [tiles] tiles at [width], so tiles stay tappable. */
fun heatmapHeight(width: Dp, tiles: Int, grouped: Boolean): Dp {
    val factor = (0.75 + sqrt(tiles.toDouble()) / 18).coerceIn(0.9, 1.8) + if (grouped) 0.15 else 0.0
    return width * factor.toFloat()
}

@Composable
fun HeatmapScreen(onBack: () -> Unit, onOpenStock: (String) -> Unit, viewModel: HeatmapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = "${state.indexName} heatmap",
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    ) { padding ->
        when {
            state.loading -> LoadingState()
            state.stocks.isEmpty() -> EmptyState("No Nifty 50 stock data yet.")
            else -> HeatmapContent(state, viewModel, onOpenStock, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HeatmapContent(state: HeatmapUiState, vm: HeatmapViewModel, onOpenStock: (String) -> Unit, modifier: Modifier) {
    val sel = state.selection
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val fmt: (Double?) -> String = Fmt::signedPercent
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HeatGrouping.entries) { g -> FilterChip(sel.grouping == g, onClick = { vm.onGrouping(g) }, label = { Text(g.label) }) }
            }
        }
        state.summary?.let { s ->
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.up} up · ${s.down} down" + (if (s.flat > 0) " · ${s.flat} flat" else ""), style = MaterialTheme.typography.bodyMedium)
                    state.indexChange?.let {
                        Text(" · ${state.indexName} ", style = MaterialTheme.typography.bodyMedium)
                        ChangeText(fmt(it), it)
                    }
                }
                Text(
                    "Latest session · ${Fmt.day(state.asOf)} · tile size = size of the move, colour = up or down · tap a tile for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                val tiles = state.groups.sumOf { it.tiles.size }
                Heatmap(
                    groups = state.groups,
                    scale = HEAT_SCALE,
                    formatValue = fmt,
                    selectedKey = selected,
                    onSelect = { selected = if (selected == it.key) null else it.key },
                    modifier = Modifier.fillMaxWidth().height(heatmapHeight(maxWidth, tiles, state.groups.size > 1)),
                )
            }
        }
        item {
            HeatLegend(HEAT_SCALE, fmt, showNoData = (state.summary?.noData ?: 0) > 0, modifier = Modifier.padding(horizontal = 16.dp))
        }
        state.stocks[selected]?.let { s ->
            item { SelectedStockCard(s, onOpenStock) }
        }
        if (state.sectorRows.isNotEmpty()) {
            item {
                SectionCard(
                    "Sectors", Modifier.padding(horizontal = 16.dp),
                    subtitle = "Latest session; a sector's change is weighted by market cap",
                ) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        val h = MaterialTheme.typography.labelSmall
                        val c = MaterialTheme.colorScheme.onSurfaceVariant
                        Text("Sector", style = h, color = c, modifier = Modifier.weight(1f))
                        Text("Up / down", style = h, color = c, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                        Text("Change", style = h, color = c, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                    }
                    HorizontalDivider()
                    state.sectorRows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${r.stocks} stocks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${r.up} / ${r.down}", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                            ChangeText(fmt(r.weighted), r.weighted, Modifier.width(72.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
        if (state.gainers.isNotEmpty() || state.losers.isNotEmpty()) {
            item {
                SectionCard("Biggest moves", Modifier.padding(horizontal = 16.dp), subtitle = "Latest session") {
                    MoveList("Top gainers", state.gainers, onOpenStock)
                    MoveList("Top losers", state.losers, onOpenStock, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun MoveList(title: String, rows: List<Pair<AnalyzedStock, Double>>, onOpenStock: (String) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        rows.forEach { (s, r) ->
            Row(Modifier.fillMaxWidth().clickable { onOpenStock(s.symbol) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.symbol, style = MaterialTheme.typography.bodyMedium)
                    Text(s.stock.industry, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChangeText(Fmt.signedPercent(r), r, Modifier.width(80.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun SelectedStockCard(s: AnalyzedStock, onOpenStock: (String) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.symbol, style = MaterialTheme.typography.titleMedium)
                    Text(s.stock.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.stock.industry, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val v = s.metrics.change1d
                ChangeText(Fmt.signedPercent(v), v, style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Close ${Fmt.rupees(s.metrics.lastClose)} · Mcap ${Fmt.crore(s.stock.marketCapCr)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpenStock(s.symbol) }) { Text("Open") }
            }
        }
    }
}
