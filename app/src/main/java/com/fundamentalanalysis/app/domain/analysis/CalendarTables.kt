package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.CalendarKind
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import java.time.LocalDate
import java.util.Locale

/** One calendar year: 12 monthly cells (Jan..Dec, null where no data) plus a whole-year value. */
data class YearRow(
    val year: Int,
    val months: List<Double?>,
    val yearValue: Double?,
    /** The year is still in progress (its value is year-to-date). */
    val yearIsPartial: Boolean,
)

/** Year x month grid, newest year first, with a per-month average footer. */
data class CalendarTable(
    val rows: List<YearRow>,
    val monthAverages: List<Double?>,
    val yearAverage: Double?,
    /** Year and month (1-12) of a month-to-date cell, if the latest month is incomplete. */
    val monthToDate: Pair<Int, Int>?,
)

enum class ValuationMeasure(val label: String, val short: String, val kind: CalendarKind) {
    PE("Price / earnings", "P/E", CalendarKind.PE),
    PB("Price / book", "P/B", CalendarKind.PB),
    DIVIDEND_YIELD("Dividend yield", "Div. yield", CalendarKind.DIVIDEND_YIELD),
}

object CalendarTables {

    /**
     * Month-on-month returns (close vs previous month's close) by year. The year column is the
     * calendar-year return: December close vs the previous December close (year to date for the
     * current year). Years without a previous December close have no year value.
     */
    fun monthlyReturns(monthly: List<Bar>, latestSession: LocalDate?): CalendarTable {
        val moves = IndexAnalyticsCalculator.monthlyMoves(monthly, latestSession)
        val byYear = moves.groupBy { it.month.year }
        val decClose = monthly.filter { it.date.monthValue == 12 }.associate { it.date.year to it.close }
        val rows = byYear.keys.sortedDescending().map { year ->
            val cells = MutableList<Double?>(12) { null }
            byYear.getValue(year).forEach { cells[it.month.monthValue - 1] = it.changePct }
            val lastOfYear = byYear.getValue(year).maxBy { it.month }
            val base = decClose[year - 1]
            YearRow(
                year = year,
                months = cells,
                yearValue = base?.takeIf { it > 0 }?.let { lastOfYear.close / it - 1 },
                yearIsPartial = lastOfYear.month.monthValue < 12 || lastOfYear.isMonthToDate,
            )
        }
        val mtd = moves.lastOrNull()?.takeIf { it.isMonthToDate }?.let { it.month.year to it.month.monthValue }
        // Seasonality: average each calendar month over completed months only.
        val completed = rows.map { r ->
            r.months.mapIndexed { i, v -> if (mtd != null && r.year == mtd.first && i + 1 == mtd.second) null else v }
        }
        return CalendarTable(
            rows = rows,
            monthAverages = (0 until 12).map { m -> completed.mapNotNull { it[m] }.averageOrNull() },
            yearAverage = rows.filter { !it.yearIsPartial }.mapNotNull { it.yearValue }.averageOrNull(),
            monthToDate = mtd,
        )
    }

    /**
     * Month-end valuation by year. The year column is the re-rating over the year: the measure at
     * the year's last month-end vs the previous year's last month-end (year to date if in progress).
     */
    fun valuation(points: List<ValuationPoint>, measure: ValuationMeasure, latestSession: LocalDate?): CalendarTable {
        fun ValuationPoint.value(): Double? = when (measure) {
            ValuationMeasure.PE -> pe
            ValuationMeasure.PB -> pb
            ValuationMeasure.DIVIDEND_YIELD -> dividendYield
        }
        val withValues = points.filter { it.value() != null }
        val byYear = withValues.groupBy { it.date.year }
        val yearEnd = byYear.mapValues { (_, list) -> list.maxBy { it.date } }
        val rows = byYear.keys.sortedDescending().map { year ->
            val cells = MutableList<Double?>(12) { null }
            byYear.getValue(year).forEach { cells[it.date.monthValue - 1] = it.value() }
            val end = yearEnd.getValue(year)
            val base = yearEnd[year - 1]?.value()
            YearRow(
                year = year,
                months = cells,
                yearValue = base?.takeIf { it > 0 }?.let { end.value()!! / it - 1 },
                yearIsPartial = end.date.monthValue < 12 || (latestSession != null && end.date == latestSession && !isMonthEnd(end.date)),
            )
        }
        val last = withValues.lastOrNull()
        val mtd = last?.takeIf { latestSession != null && it.date == latestSession && !isMonthEnd(it.date) }
            ?.let { it.date.year to it.date.monthValue }
        return CalendarTable(
            rows = rows,
            monthAverages = (0 until 12).map { m -> rows.mapNotNull { it.months[m] }.averageOrNull() },
            yearAverage = rows.filter { !it.yearIsPartial }.mapNotNull { it.yearValue }.averageOrNull(),
            monthToDate = mtd,
        )
    }

    /** True when no weekday remains in the month after [date] (holidays aside). */
    private fun isMonthEnd(date: LocalDate): Boolean {
        var d = date.plusDays(1)
        while (d.month == date.month) {
            if (d.dayOfWeek.value <= 5) return false
            d = d.plusDays(1)
        }
        return true
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** [cell] formats month cells; [yearCell] formats the year column. */
    fun csv(table: CalendarTable, yearHeader: String, cell: (Double) -> String, yearCell: (Double) -> String): String = buildString {
        appendLine((listOf("Year") + MONTHS + yearHeader).joinToString(","))
        table.rows.forEach { r ->
            appendLine(
                (listOf(r.year.toString() + if (r.yearIsPartial) " (YTD)" else "") +
                    r.months.map { it?.let(cell).orEmpty() } + (r.yearValue?.let(yearCell).orEmpty())).joinToString(","),
            )
        }
        appendLine((listOf("Average") + table.monthAverages.map { it?.let(cell).orEmpty() } + (table.yearAverage?.let(yearCell).orEmpty())).joinToString(","))
    }

    fun percentCell(v: Double): String = String.format(Locale.US, "%.2f", v * 100)
    fun numberCell(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
