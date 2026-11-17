package com.fundamentalanalysis.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fundamentalanalysis.app.domain.analysis.HeatGroup
import com.fundamentalanalysis.app.domain.analysis.HeatTile
import com.fundamentalanalysis.app.domain.analysis.TreeRect
import com.fundamentalanalysis.app.domain.analysis.Treemap
import kotlin.math.abs
import kotlin.math.pow

/*
 * Tile colours are fixed (not themed) so white labels stay readable in light and dark mode. Gains
 * are blue and losses red, like the rest of the app, so the map survives red-green colour blindness.
 */
private val HeatGain = Color(0xFF1857A8)
private val HeatLoss = Color(0xFFC0312F)
private val HeatNeutral = Color(0xFF5E6470)
private val HeatNoData = Color(0xFFB4B8BF)
private val HeatHeader = Color(0xFF2A2E35)

/** Colour for [value] on a scale that saturates at ±[scale]. */
fun heatColor(value: Double?, scale: Double): Color {
    if (value == null) return HeatNoData
    val t = (abs(value) / scale).coerceIn(0.0, 1.0).pow(0.8).toFloat()
    return lerp(HeatNeutral, if (value >= 0) HeatGain else HeatLoss, t)
}

private class PlacedTile(val tile: HeatTile, val rect: TreeRect, val label: TextLayoutResult?, val value: TextLayoutResult?)
private class PlacedGroup(val group: HeatGroup, val rect: TreeRect, val header: TextLayoutResult?)
private class HeatLayout(val groups: List<PlacedGroup>, val tiles: List<PlacedTile>)

/**
 * A treemap heatmap: tile area from [HeatTile.weight], colour from [HeatTile.value]. With more
 * than one group each gets a labelled block. Tapping a tile calls [onSelect].
 */
@Composable
fun Heatmap(
    groups: List<HeatGroup>,
    scale: Double,
    formatValue: (Double?) -> String,
    selectedKey: String?,
    onSelect: (HeatTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val showHeaders = groups.size > 1
    val layout = remember(groups, size, formatValue) {
        if (size.width == 0 || size.height == 0) return@remember HeatLayout(emptyList(), emptyList())
        with(density) {
            val headerH = 18.dp.toPx().toDouble()
            val pad = 3.dp.toPx()
            val nonEmpty = groups.filter { g -> g.weight > 0 }
            val groupRects = Treemap.layout(nonEmpty.map { it.weight }, TreeRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble()))
            val placedGroups = mutableListOf<PlacedGroup>()
            val placedTiles = mutableListOf<PlacedTile>()
            val headerStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            nonEmpty.zip(groupRects).forEach { (g, r) ->
                val withHeader = showHeaders && r.h > headerH * 2.5 && r.w > 48.dp.toPx()
                val header = if (withHeader) {
                    measurer.measure(
                        "${g.label}  ${formatValue(g.value)}", headerStyle, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = (r.w - 2 * pad).toInt().coerceAtLeast(1)),
                    )
                } else null
                placedGroups += PlacedGroup(g, r, header)
                val inner = if (withHeader) TreeRect(r.x, r.y + headerH, r.w, r.h - headerH) else r
                Treemap.layout(g.tiles.map { it.weight }, inner).zip(g.tiles).forEach { (tr, t) ->
                    val w = tr.w.toFloat() - 2 * pad
                    val h = tr.h.toFloat() - 2 * pad
                    val color = if (t.value == null) Color(0xFF22252A) else Color.White
                    // Largest font (13sp down to 7sp) at which the symbol fits; the value is added when it fits too.
                    var label: TextLayoutResult? = null
                    var value: TextLayoutResult? = null
                    if (w > 12.dp.toPx() && h > 8.dp.toPx()) {
                        for (fs in listOf(13f, 12f, 11f, 10f, 9f, 8f, 7f)) {
                            val l = measurer.measure(t.label, TextStyle(color = color, fontSize = fs.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                            if (l.size.width > w || l.size.height > h) continue
                            label = l
                            value = measurer.measure(formatValue(t.value), TextStyle(color = color, fontSize = (fs * 0.9f).sp), maxLines = 1)
                                .takeIf { it.size.width <= w && it.size.height + l.size.height <= h }
                            break
                        }
                    }
                    placedTiles += PlacedTile(t, tr, label, value)
                }
            }
            HeatLayout(placedGroups, placedTiles)
        }
    }
    val gap = with(density) { 1.dp.toPx() }
    val description = remember(groups) {
        val tiles = groups.sumOf { it.tiles.size }
        "Heatmap of $tiles tiles" + if (showHeaders) " in ${groups.size} groups" else ""
    }
    Canvas(
        modifier
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .pointerInput(layout) {
                detectTapGestures { p ->
                    layout.tiles.firstOrNull { it.rect.contains(p.x.toDouble(), p.y.toDouble()) }?.let { onSelect(it.tile) }
                }
            },
    ) {
        layout.tiles.forEach { pt ->
            val r = pt.rect
            if (r.w <= gap || r.h <= gap) return@forEach
            drawRect(
                heatColor(pt.tile.value, scale),
                topLeft = Offset(r.x.toFloat() + gap / 2, r.y.toFloat() + gap / 2),
                size = Size(r.w.toFloat() - gap, r.h.toFloat() - gap),
            )
            val label = pt.label ?: return@forEach
            val textH = label.size.height + (pt.value?.size?.height ?: 0)
            val top = r.y.toFloat() + (r.h.toFloat() - textH) / 2
            drawText(label, topLeft = Offset(r.x.toFloat() + (r.w.toFloat() - label.size.width) / 2, top))
            pt.value?.let { drawText(it, topLeft = Offset(r.x.toFloat() + (r.w.toFloat() - it.size.width) / 2, top + label.size.height)) }
        }
        layout.groups.forEach { pg ->
            val r = pg.rect
            pg.header?.let { h ->
                val headerH = 18.dp.toPx()
                drawRect(HeatHeader, Offset(r.x.toFloat(), r.y.toFloat()), Size(r.w.toFloat(), headerH))
                drawText(h, topLeft = Offset(r.x.toFloat() + 3.dp.toPx(), r.y.toFloat() + (headerH - h.size.height) / 2))
            }
            if (showHeaders) {
                drawRect(
                    HeatHeader, Offset(r.x.toFloat(), r.y.toFloat()), Size(r.w.toFloat(), r.h.toFloat()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        layout.tiles.firstOrNull { it.tile.key == selectedKey }?.let { pt ->
            val r = pt.rect
            drawRect(
                Color.White, Offset(r.x.toFloat() + gap, r.y.toFloat() + gap), Size(r.w.toFloat() - 2 * gap, r.h.toFloat() - 2 * gap),
                style = Stroke(width = 3.dp.toPx()),
            )
            drawRect(
                Color.Black, Offset(r.x.toFloat() - gap, r.y.toFloat() - gap), Size(r.w.toFloat() + 2 * gap, r.h.toFloat() + 2 * gap),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/** Colour key: −scale … 0 … +scale, plus the no-data swatch when some tiles have none. */
@Composable
fun HeatLegend(scale: Double, formatValue: (Double?) -> String, showNoData: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp)).background(
                Brush.horizontalGradient((0..10).map { heatColor(scale * (it - 5) / 5.0, scale) }),
            ),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val st = MaterialTheme.typography.labelSmall
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("≤ ${formatValue(-scale)}", style = st, color = c)
            Text("0", style = st, color = c)
            Text("≥ ${formatValue(scale)}", style = st, color = c)
        }
        if (showNoData) {
            Row(Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(HeatNoData).padding(top = 2.dp))
                Text(
                    "No data", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
