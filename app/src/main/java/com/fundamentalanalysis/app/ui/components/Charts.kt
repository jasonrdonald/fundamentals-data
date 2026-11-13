package com.fundamentalanalysis.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fundamentalanalysis.app.ui.theme.LocalChartColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class ChartSeries(val name: String, val values: List<Double?>)

private const val MAX_X_LABELS = 6

/**
 * Grouped vertical bars with a zero baseline (negatives hang below it). With [colorBySign] a
 * single series is coloured gain/loss. Tap a group to read exact values.
 */
@Composable
fun GroupedBarChart(
    xLabels: List<String>,
    series: List<ChartSeries>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    colorBySign: Boolean = false,
) {
    val colors = LocalChartColors.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = textColor)
    val measurer = rememberTextMeasurer()
    var selected by remember(xLabels, series) { mutableStateOf<Int?>(null) }
    val all = series.flatMap { it.values.filterNotNull() }
    val maxV = max(all.maxOrNull() ?: 0.0, 0.0)
    val minV = min(all.minOrNull() ?: 0.0, 0.0)
    val range = (maxV - minV).takeIf { it > 0 } ?: 1.0

    Column(modifier) {
        ChartReadout(xLabels, series, selected, valueFormatter)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = describe(xLabels, series, valueFormatter) }
                .pointerInput(xLabels.size) {
                    detectTapGestures { pos ->
                        val idx = ((pos.x / size.width) * xLabels.size).toInt().coerceIn(0, xLabels.lastIndex)
                        selected = idx.takeIf { it != selected }
                    }
                },
        ) {
            val axisLabelHeight = 18.dp.toPx()
            val plotH = size.height - axisLabelHeight
            val zeroY = (maxV / range * plotH).toFloat()
            val groupW = size.width / xLabels.size.coerceAtLeast(1)
            val gap = 2.dp.toPx()
            val barW = max(((groupW * 0.75f) / series.size.coerceAtLeast(1)) - gap, 1f)
            val every = labelStride(xLabels.size)

            drawGrid(plotH, colors.grid)
            var labelEdge = 0f
            xLabels.forEachIndexed { i, label ->
                val groupLeft = i * groupW + groupW * 0.125f
                if (selected == i) drawRect(colors.grid, Offset(i * groupW, 0f), Size(groupW, plotH))
                series.forEachIndexed { s, ser ->
                    val v = ser.values.getOrNull(i) ?: return@forEachIndexed
                    val h = (abs(v) / range * plotH).toFloat()
                    val color = if (colorBySign) (if (v >= 0) colors.gain else colors.loss) else colors.series[s % colors.series.size]
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(groupLeft + s * (barW + gap), if (v >= 0) zeroY - h else zeroY),
                        size = Size(barW, h.coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(min(4.dp.toPx(), barW / 2)),
                    )
                }
                if (i % every == 0) {
                    labelEdge = drawCenteredLabel(measurer, label, labelStyle, i * groupW + groupW / 2, plotH + 2.dp.toPx(), size.width, labelEdge)
                }
            }
            drawLine(textColor.copy(alpha = 0.6f), Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        }
        if (series.size > 1) Legend(series.map { it.name })
    }
}

/**
 * Line chart with 2px lines. Markers are drawn only for short series. Use [includeZero] = false
 * for price levels so the scale fits the data. Tap to inspect a point.
 */
@Composable
fun LineChart(
    xLabels: List<String>,
    series: List<ChartSeries>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    includeZero: Boolean = true,
    reference: Double? = null,
) {
    val colors = LocalChartColors.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = textColor)
    val measurer = rememberTextMeasurer()
    var selected by remember(xLabels, series) { mutableStateOf<Int?>(null) }
    val all = series.flatMap { it.values.filterNotNull() } + listOfNotNull(reference)
    val dataMax = all.maxOrNull() ?: 0.0
    val dataMin = all.minOrNull() ?: 0.0
    val maxV = if (includeZero) max(dataMax, 0.0) else dataMax + (dataMax - dataMin) * 0.05
    val minV = if (includeZero) min(dataMin, 0.0) else dataMin - (dataMax - dataMin) * 0.05
    val range = (maxV - minV).takeIf { it > 0 } ?: 1.0

    Column(modifier) {
        ChartReadout(xLabels, series, selected, valueFormatter)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = describe(xLabels, series, valueFormatter) }
                .pointerInput(xLabels.size) {
                    detectTapGestures { pos ->
                        val step = size.width / xLabels.size.coerceAtLeast(1)
                        val idx = (pos.x / step).toInt().coerceIn(0, xLabels.lastIndex)
                        selected = idx.takeIf { it != selected }
                    }
                },
        ) {
            val axisLabelHeight = 18.dp.toPx()
            val pad = 6.dp.toPx()
            val plotH = size.height - axisLabelHeight - pad
            val step = size.width / xLabels.size.coerceAtLeast(1)
            fun x(i: Int) = i * step + step / 2
            fun y(v: Double) = (pad + (maxV - v) / range * plotH).toFloat()
            val showMarkers = xLabels.size <= 24
            val every = labelStride(xLabels.size)

            drawGrid(plotH + pad, colors.grid)
            if (includeZero && minV < 0) {
                drawLine(textColor.copy(alpha = 0.6f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            }
            reference?.let { drawLine(textColor.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx()) }
            selected?.let { drawLine(textColor.copy(alpha = 0.5f), Offset(x(it), 0f), Offset(x(it), plotH + pad), 1.dp.toPx()) }
            series.forEachIndexed { s, ser ->
                val color = colors.series[s % colors.series.size]
                val path = Path()
                var started = false
                ser.values.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    if (!started) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                    started = true
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                ser.values.forEachIndexed { i, v ->
                    if (v != null && (showMarkers || i == selected)) {
                        drawCircle(surface, radius = 5.dp.toPx(), center = Offset(x(i), y(v)))
                        drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(i), y(v)))
                    }
                }
            }
            var labelEdge = 0f
            xLabels.forEachIndexed { i, label ->
                if (i % every == 0) {
                    labelEdge = drawCenteredLabel(measurer, label, labelStyle, x(i), plotH + pad + 2.dp.toPx(), size.width, labelEdge)
                }
            }
        }
        if (series.size > 1) Legend(series.map { it.name })
    }
}

/**
 * Horizontal bars built from layout so labels stay crisp and accessible. With [signed] the
 * bars diverge from a centre line and are coloured gain/loss; values keep their sign in text.
 */
@Composable
fun HorizontalBars(
    items: List<Pair<String, Double>>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    labelWidth: Dp = 96.dp,
) {
    val colors = LocalChartColors.current
    val maxAbs = items.maxOfOrNull { abs(it.second) }?.takeIf { it > 0 } ?: 1.0
    val hasNegative = signed && items.any { it.second < 0 }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(labelWidth), maxLines = 1)
                val fraction = (abs(value) / maxAbs).toFloat().coerceIn(0.02f, 1f)
                val color = if (signed) (if (value >= 0) colors.gain else colors.loss) else colors.series[0]
                if (hasNegative) {
                    // Two half-width tracks around a centre line.
                    Row(Modifier.weight(1f).height(16.dp)) {
                        Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.CenterEnd) {
                            if (value < 0) Bar(fraction, color, leftRounded = true)
                        }
                        Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outline))
                        Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.CenterStart) {
                            if (value >= 0) Bar(fraction, color, leftRounded = false)
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).height(16.dp)) { Bar(fraction, color, leftRounded = false) }
                }
                Text(
                    valueFormatter(value),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(76.dp).padding(start = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, leftRounded: Boolean) {
    val shape = if (leftRounded) RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
    else RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    Box(Modifier.fillMaxWidth(fraction).height(16.dp).clip(shape).background(color))
}

@Composable
private fun ChartReadout(
    xLabels: List<String>,
    series: List<ChartSeries>,
    selected: Int?,
    valueFormatter: (Double) -> String,
) {
    val text = if (selected == null) "Tap the chart for values"
    else xLabels[selected] + ":  " + series.joinToString("   ") { s ->
        val v = s.values.getOrNull(selected)
        (if (series.size > 1) s.name + " " else "") + (v?.let(valueFormatter) ?: "—")
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun Legend(names: List<String>) {
    val colors = LocalChartColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        names.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(colors.series[i % colors.series.size]))
                Spacer(Modifier.width(6.dp))
                Text(name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun labelStride(count: Int): Int = max(1, ceil(count / MAX_X_LABELS.toDouble()).toInt())

private fun DrawScope.drawGrid(plotH: Float, color: Color) {
    repeat(4) { i ->
        val y = plotH * i / 4f
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
    }
}

/**
 * Draws an axis label centred on [cx], clamped inside the chart. Skips it (returning the
 * unchanged [minLeft]) when it would overlap the previous label; otherwise returns its right edge.
 */
private fun DrawScope.drawCenteredLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    cx: Float,
    top: Float,
    maxWidth: Float,
    minLeft: Float,
): Float {
    val layout = measurer.measure(text, style)
    val left = (cx - layout.size.width / 2f).coerceIn(0f, max(0f, maxWidth - layout.size.width))
    if (left < minLeft) return minLeft
    drawText(layout, topLeft = Offset(left, top))
    return left + layout.size.width + 8.dp.toPx()
}

private fun describe(xLabels: List<String>, series: List<ChartSeries>, f: (Double) -> String): String =
    series.joinToString(". ") { s ->
        val points = xLabels.indices.mapNotNull { i -> s.values.getOrNull(i)?.let { xLabels[i] + " " + f(it) } }
        // Long series: summarise first and last points rather than reading hundreds of values.
        s.name + ": " + if (points.size > 24) points.first() + " … " + points.last() else points.joinToString(", ")
    }
