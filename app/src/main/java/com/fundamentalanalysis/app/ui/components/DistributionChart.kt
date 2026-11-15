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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fundamentalanalysis.app.domain.analysis.DistributionStats
import com.fundamentalanalysis.app.ui.theme.LocalChartColors
import java.util.Locale
import kotlin.math.max

/**
 * Histogram of returns (bars = number of stocks per range) with the fitted normal curve on top,
 * plus vertical markers for the index's own return and the stocks' mean. Tap a bar to list its stocks.
 */
@Composable
fun DistributionChart(
    stats: DistributionStats,
    benchmarkLabel: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    val colors = LocalChartColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val markerStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
    // ElevatedCard's container colour, so label plates blend into the card.
    val labelPlate = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    var selected by remember(stats) { mutableStateOf<Int?>(null) }
    val bins = stats.bins
    val lo = bins.first().from
    val hi = bins.last().to
    val peakCurve = stats.normalCount(stats.mean)
    val yMax = max(bins.maxOf { it.count }.toDouble(), peakCurve) * 1.12

    Column(modifier) {
        val readout = selected?.let { i ->
            val b = bins[i]
            "${pct(b.from)} to ${pct(b.to)}: ${b.count} stock${if (b.count == 1) "" else "s"}" +
                if (b.count > 0) " — " + b.members.take(8).joinToString(", ") + if (b.count > 8) ", …" else "" else ""
        } ?: "Tap a bar to see its stocks"
        Text(
            readout,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected == null) muted else ink,
            modifier = Modifier.padding(bottom = 6.dp),
            minLines = 2,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    contentDescription = "Histogram of ${stats.n} stock returns from ${pct(stats.min.second)} to ${pct(stats.max.second)}, " +
                        "mean ${pct(stats.mean)}, ${benchmarkLabel} ${stats.benchmark?.let(::pct) ?: "unknown"}"
                }
                .pointerInput(stats) {
                    detectTapGestures { pos ->
                        val i = ((pos.x / size.width) * bins.size).toInt().coerceIn(0, bins.lastIndex)
                        selected = i.takeIf { it != selected }
                    }
                },
        ) {
            val axisH = 18.dp.toPx()
            val plotH = size.height - axisH
            fun x(v: Double) = ((v - lo) / (hi - lo) * size.width).toFloat()
            fun y(c: Double) = (plotH - c / yMax * plotH).toFloat()
            val gap = 2.dp.toPx()
            val slot = size.width / bins.size

            // Recessive grid
            repeat(4) { i -> drawLine(colors.grid, Offset(0f, plotH * i / 4f), Offset(size.width, plotH * i / 4f), 1f) }

            // Bars
            bins.forEachIndexed { i, b ->
                if (selected == i) drawRect(colors.grid, Offset(i * slot, 0f), Size(slot, plotH))
                if (b.count > 0) {
                    val top = y(b.count.toDouble())
                    drawRoundRect(
                        color = colors.series[0],
                        topLeft = Offset(i * slot + gap / 2, top),
                        size = Size(slot - gap, plotH - top),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }

            // Fitted normal curve
            val path = Path()
            val steps = 120
            for (s in 0..steps) {
                val v = lo + (hi - lo) * s / steps
                val px = x(v)
                val py = y(stats.normalCount(v))
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, colors.series[1], style = Stroke(width = 2.dp.toPx()))

            // Baseline
            drawLine(muted.copy(alpha = 0.6f), Offset(0f, plotH), Offset(size.width, plotH), 1.dp.toPx())

            // Markers: mean (dashed) and the index's own return (solid), labelled at the top.
            @Suppress("UNUSED_PARAMETER")
            fun marker(v: Double, label: String, dashed: Boolean, labelRow: Int) {
                if (v < lo || v > hi) return
                val px = x(v)
                val layout = measurer.measure(label, markerStyle)
                val left = (px + 4.dp.toPx()).let { if (it + layout.size.width > size.width) px - 4.dp.toPx() - layout.size.width else it }
                val top = labelRow * (layout.size.height + 4.dp.toPx())
                // Card-coloured plate so other marker lines never run through the text.
                val pad = 3.dp.toPx()
                drawRoundRect(
                    labelPlate,
                    topLeft = Offset(left - pad, top - 1f),
                    size = Size(layout.size.width + 2 * pad, layout.size.height + 2f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                drawText(layout, topLeft = Offset(left, top))
            }
            // Draw both lines first, then both labels, so no line crosses a label.
            val markers = listOfNotNull(
                stats.benchmark?.let { Triple(it, "$benchmarkLabel ${pct(it)}", false) },
                Triple(stats.mean, "Mean ${pct(stats.mean)}", true),
            )
            markers.forEach { (v, _, dashed) ->
                if (v in lo..hi) drawLine(
                    ink.copy(alpha = 0.85f), Offset(x(v), 0f), Offset(x(v), plotH), 1.5.dp.toPx(),
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
                )
            }
            markers.forEachIndexed { row, (v, label, dashed) -> marker(v, label, dashed, row) }

            // X-axis edge labels, thinned so they never collide.
            var edge = 0f
            val stride = max(1, (bins.size + 1) / 6)
            for (i in 0..bins.size step stride) {
                val v = lo + i * (hi - lo) / bins.size
                val layout = measurer.measure(pct(v, 0), labelStyle)
                val left = (x(v) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                if (left >= edge) {
                    drawText(layout, topLeft = Offset(left, plotH + 2.dp.toPx()))
                    edge = left + layout.size.width + 6.dp.toPx()
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            LegendSwatch(colors.series[0], "Stocks per range")
            LegendLine(colors.series[1], "Normal curve (same mean & SD)")
            Text("│ $benchmarkLabel   ┆ mean", style = MaterialTheme.typography.labelMedium, color = muted)
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LegendLine(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(2.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun pct(v: Double, decimals: Int = 1): String =
    String.format(Locale.US, "%+.${decimals}f%%", v * 100)
