package com.fundamentalanalysis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fundamentalanalysis.app.domain.analysis.CalendarTable
import com.fundamentalanalysis.app.ui.theme.LocalChartColors
import kotlin.math.abs

private val MONTH_HEADERS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val YEAR_COL = 48.dp
private val MONTH_COL = 54.dp
private val TOTAL_COL = 62.dp

/** How month cells are shaded: by sign (returns) or by level within the table (valuation). */
enum class CellShading { DIVERGING, SEQUENTIAL }

/**
 * Year-by-month grid: years as rows (newest first), Jan..Dec as columns, the year value last,
 * and an "Avg" footer. Scrolls sideways on phones. Month-to-date cells are marked with "*".
 */
@Composable
fun CalendarTableView(
    table: CalendarTable,
    yearHeader: String,
    cellText: (Double) -> String,
    yearText: (Double) -> String,
    shading: CellShading,
) {
    val colors = LocalChartColors.current
    val monthValues = table.rows.flatMap { it.months.filterNotNull() }
    val maxAbs = monthValues.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    val min = monthValues.minOrNull() ?: 0.0
    val max = monthValues.maxOrNull() ?: 1.0
    val yearMaxAbs = table.rows.mapNotNull { it.yearValue }.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0

    fun monthTint(v: Double?): Color = when {
        v == null -> Color.Transparent
        shading == CellShading.DIVERGING ->
            (if (v >= 0) colors.gain else colors.loss).copy(alpha = colors.heatAlpha(abs(v) / maxAbs))
        else -> colors.series[0].copy(alpha = colors.heatAlpha((v - min) / (max - min).coerceAtLeast(1e-9)))
    }
    fun yearTint(v: Double?): Color = if (v == null) Color.Transparent
    else (if (v >= 0) colors.gain else colors.loss).copy(alpha = colors.heatAlpha(abs(v) / yearMaxAbs))

    // The Year column stays put; months and the year value scroll sideways beside it.
    Row {
        Column {
            Row(Modifier.padding(vertical = 6.dp)) { HeaderCell("Year", YEAR_COL, TextAlign.Start) }
            HorizontalDivider(Modifier.width(YEAR_COL))
            table.rows.forEach { row -> YearLabel(row.year.toString()) }
            HorizontalDivider(Modifier.width(YEAR_COL).padding(top = 2.dp))
            YearLabel("Avg")
        }
        ScrollingCells(table, yearHeader, cellText, yearText, ::monthTint, ::yearTint)
    }
}

@Composable
private fun YearLabel(text: String) {
    // Same height as a value cell: 1dp outer padding + 6dp vertical padding around bodySmall.
    ValueCell(text, YEAR_COL, Color.Transparent, bold = true, align = TextAlign.Start)
}

@Composable
private fun ScrollingCells(
    table: CalendarTable,
    yearHeader: String,
    cellText: (Double) -> String,
    yearText: (Double) -> String,
    monthTint: (Double?) -> Color,
    yearTint: (Double?) -> Color,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Row(Modifier.padding(vertical = 6.dp)) {
                MONTH_HEADERS.forEach { HeaderCell(it, MONTH_COL) }
                HeaderCell(yearHeader, TOTAL_COL)
            }
            HorizontalDivider()
            table.rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row.months.forEachIndexed { i, v ->
                        val mtd = table.monthToDate == row.year to i + 1
                        ValueCell(v?.let { cellText(it) + if (mtd) "*" else "" } ?: "", MONTH_COL, monthTint(v))
                    }
                    ValueCell(
                        row.yearValue?.let { yearText(it) + if (row.yearIsPartial) "*" else "" } ?: "—",
                        TOTAL_COL,
                        yearTint(row.yearValue),
                        bold = true,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                table.monthAverages.forEach { ValueCell(it?.let(cellText) ?: "", MONTH_COL, Color.Transparent, bold = true) }
                ValueCell(table.yearAverage?.let(yearText) ?: "—", TOTAL_COL, Color.Transparent, bold = true)
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, align: TextAlign = TextAlign.Center) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1, modifier = Modifier.width(width))
}

@Composable
private fun ValueCell(text: String, width: Dp, tint: Color, bold: Boolean = false, align: TextAlign = TextAlign.Center) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.width(width).padding(1.dp).background(tint).padding(vertical = 6.dp),
    )
}
