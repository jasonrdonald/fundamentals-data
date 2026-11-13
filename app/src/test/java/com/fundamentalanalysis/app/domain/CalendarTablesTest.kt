package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.analysis.CalendarTables
import com.fundamentalanalysis.app.domain.analysis.ValuationMeasure
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarTablesTest {

    /** Month bars from [start] with the given closes. */
    private fun months(start: LocalDate, closes: List<Double>) = closes.mapIndexed { i, c ->
        val m = start.plusMonths(i.toLong())
        Bar(m, c, c, c, c, null, m.withDayOfMonth(m.lengthOfMonth()))
    }

    @Test fun `returns table puts month-on-month changes under the right year and month`() {
        // Dec 2024 = 100, then Jan..Dec 2025 rising 1% a month compounded, then Jan 2026 = flat.
        val closes = listOf(100.0) + (1..12).map { 100.0 * Math.pow(1.01, it.toDouble()) } + listOf(100.0 * Math.pow(1.01, 12.0))
        val table = CalendarTables.monthlyReturns(months(LocalDate.of(2024, 12, 1), closes), latestSession = LocalDate.of(2026, 1, 30))

        assertEquals(listOf(2026, 2025, 2024), table.rows.map { it.year })
        val y2025 = table.rows.first { it.year == 2025 }
        y2025.months.forEach { assertEquals(0.01, it!!, 1e-12) }
        assertEquals(Math.pow(1.01, 12.0) - 1, y2025.yearValue!!, 1e-12)
        assertFalse(y2025.yearIsPartial)

        val y2026 = table.rows.first { it.year == 2026 }
        assertEquals(0.0, y2026.months[0]!!, 1e-12)
        assertNull(y2026.months[1])
        assertTrue("current year is year-to-date", y2026.yearIsPartial)

        // 2024 has only December and no prior December close.
        assertNull(table.rows.first { it.year == 2024 }.yearValue)
        assertEquals(0.01, table.monthAverages[5]!!, 1e-12)
    }

    @Test fun `latest incomplete month is flagged month to date and kept out of averages`() {
        val closes = listOf(100.0, 110.0, 99.0)
        val table = CalendarTables.monthlyReturns(months(LocalDate.of(2026, 9, 1), closes), latestSession = LocalDate.of(2026, 11, 13))
        assertEquals(2026 to 11, table.monthToDate)
        assertNull("MTD month excluded from November average", table.monthAverages[10])
        assertEquals(0.10, table.monthAverages[9]!!, 1e-12)
    }

    @Test fun `valuation table shows month-end values and year re-rating`() {
        val points = listOf(
            ValuationPoint(LocalDate.of(2024, 12, 31), 20.0, 3.0, 1.2),
            ValuationPoint(LocalDate.of(2025, 1, 31), 21.0, 3.1, 1.1),
            ValuationPoint(LocalDate.of(2025, 12, 31), 22.0, 3.3, 1.0),
            ValuationPoint(LocalDate.of(2026, 1, 15), 23.1, null, 1.0),
        )
        val table = CalendarTables.valuation(points, ValuationMeasure.PE, latestSession = LocalDate.of(2026, 1, 15))
        val y2025 = table.rows.first { it.year == 2025 }
        assertEquals(21.0, y2025.months[0]!!, 1e-12)
        assertEquals(22.0, y2025.months[11]!!, 1e-12)
        assertEquals(22.0 / 20.0 - 1, y2025.yearValue!!, 1e-12)
        assertFalse(y2025.yearIsPartial)
        assertTrue(table.rows.first { it.year == 2026 }.yearIsPartial)
        assertEquals(2026 to 1, table.monthToDate)

        val pb = CalendarTables.valuation(points, ValuationMeasure.PB, latestSession = LocalDate.of(2026, 1, 15))
        assertEquals("points without P/B are skipped", listOf(2025, 2024), pb.rows.map { it.year })
    }

    @Test fun `csv has header, one line per year and an average line`() {
        val table = CalendarTables.monthlyReturns(months(LocalDate.of(2024, 12, 1), List(13) { 100.0 + it }), null)
        val lines = CalendarTables.csv(table, "Year return", CalendarTables::percentCell, CalendarTables::percentCell).trim().lines()
        assertEquals("Year,Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec,Year return", lines.first())
        assertEquals(1 + table.rows.size + 1, lines.size)
        assertTrue(lines.last().startsWith("Average,"))
    }
}
