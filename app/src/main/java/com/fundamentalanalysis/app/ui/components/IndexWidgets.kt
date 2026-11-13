package com.fundamentalanalysis.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.model.MetricFormat
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.SessionSummary

/** List row: name, last close, 1D change and an optional extra metric. */
@Composable
fun IndexRow(
    index: AnalyzedIndex,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraMetric: Metric? = null,
    onToggleWatch: (() -> Unit)? = null,
) {
    val session = index.analytics.latest
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(index.index.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                index.index.category.label + (extraMetric?.let { " · ${it.label} ${Fmt.metric(it, index.valueOf(it))}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.points(session?.close), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            ChangeText(Fmt.signedPercent(session?.changePct), session?.changePct, style = MaterialTheme.typography.labelMedium)
        }
        if (onToggleWatch != null) {
            IconButton(onClick = onToggleWatch) {
                Icon(
                    if (index.isWatchlisted) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (index.isWatchlisted) "Remove ${index.index.name} from watchlist" else "Add ${index.index.name} to watchlist",
                    tint = if (index.isWatchlisted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Open / high / low / close of the latest completed session, plus the previous close. */
@Composable
fun SessionGrid(session: SessionSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            LabeledValue("Open", Fmt.points(session.open), Modifier.weight(1f))
            LabeledValue("High", Fmt.points(session.high), Modifier.weight(1f))
        }
        Row {
            LabeledValue("Low", Fmt.points(session.low), Modifier.weight(1f))
            LabeledValue("Close", Fmt.points(session.close), Modifier.weight(1f))
        }
        Row {
            LabeledValue("Prev. close", Fmt.points(session.previousClose), Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                Text("Open → close", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChangeText(Fmt.signedPercent(session.intradayPct), session.intradayPct, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/** Trailing returns for each [Period], laid out as a wrapping grid. */
@Composable
fun ReturnsGrid(index: AnalyzedIndex, periods: List<Period> = Period.entries) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
        modifier = Modifier.fillMaxWidth(),
    ) {
        periods.forEach { p ->
            val v = index.analytics.returns[p]
            Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChangeText(Fmt.signedPercent(v), v, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Momentum, volume and risk readings with a short interpretation. */
@Composable
fun MomentumPanel(index: AnalyzedIndex) {
    val a = index.analytics
    val rsiNote = when {
        a.rsi14 == null -> ""
        a.rsi14 >= 70 -> " · overbought"
        a.rsi14 <= 30 -> " · oversold"
        else -> ""
    }
    Column {
        KeyValueRow("Trend (50/200-DMA)", a.trend.label)
        KeyValueRow("RSI (14)", Fmt.format(MetricFormat.NUMBER, a.rsi14) + rsiNote)
        KeyValueRow("vs 50-DMA (${Fmt.points(a.dma50)})", Fmt.signedPercent(a.vsDma50), valueColor = changeColor(a.vsDma50))
        KeyValueRow("vs 200-DMA (${Fmt.points(a.dma200)})", Fmt.signedPercent(a.vsDma200), valueColor = changeColor(a.vsDma200))
        KeyValueRow("52-week high (${Fmt.points(a.high52w)})", Fmt.signedPercent(a.fromHigh52w), valueColor = changeColor(a.fromHigh52w))
        KeyValueRow("52-week low (${Fmt.points(a.low52w)})", Fmt.signedPercent(a.fromLow52w), valueColor = changeColor(a.fromLow52w))
        if (a.avgVolume20 != null) {
            val asOf = a.latestVolumeDate?.takeIf { it != a.latest?.date }?.let { " (${Fmt.day(it)})" } ?: ""
            KeyValueRow("Volume, latest$asOf", Fmt.volume(a.latestVolume?.toDouble()))
            KeyValueRow("Volume, 20-session avg", Fmt.volume(a.avgVolume20))
            KeyValueRow("Relative volume", Fmt.format(MetricFormat.MULTIPLE, a.relativeVolume))
            KeyValueRow("Volume trend (3M vs 1Y)", Fmt.format(MetricFormat.MULTIPLE, a.volumeTrend))
        } else {
            KeyValueRow("Volume", "Not reported for this index")
        }
        KeyValueRow("Volatility 1M / 1Y", Fmt.percent(a.volatility1m) + " / " + Fmt.percent(a.volatility1y))
    }
}

@Composable
fun StarButton(watched: Boolean, name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (watched) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (watched) "Remove $name from watchlist" else "Add $name to watchlist",
        )
    }
}

@Composable
fun HeadlineQuote(index: AnalyzedIndex, modifier: Modifier = Modifier) {
    val s = index.analytics.latest
    Column(modifier) {
        Text(Fmt.points(s?.close), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChangeText(
                Fmt.signedPoints(s?.change) + "  (" + Fmt.signedPercent(s?.changePct) + ")",
                s?.changePct,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "  ·  " + Fmt.day(s?.date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
