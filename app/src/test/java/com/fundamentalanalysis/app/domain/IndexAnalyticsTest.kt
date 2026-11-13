package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.analysis.IndexAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.ReportBuilder
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.pow

class IndexAnalyticsTest {

    /** Weekday sessions ending on [end], closes from [closes] (oldest first). */
    private fun dailyBars(closes: List<Double>, end: LocalDate = LocalDate.of(2026, 11, 13), volume: Long? = 1000): List<Bar> {
        val dates = generateSequence(end) { it.minusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .take(closes.size).toList().reversed()
        return dates.zip(closes) { d, c -> Bar(d, c * 0.99, c * 1.01, c * 0.98, c, volume) }
    }

    private fun monthlyBars(closes: List<Double>, lastMonth: LocalDate = LocalDate.of(2026, 11, 1)): List<Bar> =
        closes.indices.map { i ->
            val month = lastMonth.minusMonths((closes.size - 1 - i).toLong())
            Bar(month, closes[i], closes[i], closes[i], closes[i], null, month.plusDays(20))
        }

    private fun index(daily: List<Bar>, monthly: List<Bar> = emptyList(), symbol: String = "TEST") =
        MarketIndex(symbol, symbol, IndexCategory.SECTOR, null, daily, monthly)

    @Test fun `latest session reports change versus previous close`() {
        val s = IndexAnalyticsCalculator.latestSession(dailyBars(listOf(100.0, 102.0)))!!
        assertEquals(102.0, s.close, 1e-9)
        assertEquals(100.0, s.previousClose!!, 1e-9)
        assertEquals(2.0, s.change!!, 1e-9)
        assertEquals(0.02, s.changePct!!, 1e-9)
    }

    @Test fun `period returns count sessions back from the latest close`() {
        val closes = (0..300).map { 100.0 + it } // strictly rising
        val idx = index(dailyBars(closes))
        val last = closes.last()
        assertEquals(last / closes[closes.size - 2] - 1, IndexAnalyticsCalculator.periodReturn(idx, Period.D1)!!, 1e-12)
        assertEquals(last / closes[closes.size - 6] - 1, IndexAnalyticsCalculator.periodReturn(idx, Period.W1)!!, 1e-12)
        assertEquals(last / closes[closes.size - 253] - 1, IndexAnalyticsCalculator.periodReturn(idx, Period.Y1)!!, 1e-12)
    }

    @Test fun `ytd uses last close of the previous calendar year`() {
        val bars = dailyBars((1..40).map { it.toDouble() }, end = LocalDate.of(2026, 1, 20))
        val lastOf2025 = bars.last { it.date.year == 2025 }.close
        val ytd = IndexAnalyticsCalculator.periodReturn(index(bars), Period.YTD)!!
        assertEquals(bars.last().close / lastOf2025 - 1, ytd, 1e-12)
    }

    @Test fun `cagr uses month-end close n years back`() {
        val monthly = monthlyBars(List(61) { i -> 100.0 * 1.01.pow(i) })
        val last = monthly.last().close
        val cagr5 = IndexAnalyticsCalculator.cagr(monthly, last, 5)!!
        assertEquals(1.01.pow(12) - 1, cagr5, 1e-9)
        assertNull(IndexAnalyticsCalculator.cagr(monthly.takeLast(30), last, 3))
    }

    @Test fun `rsi is 100 for only gains, 0 for only losses, and needs enough data`() {
        assertEquals(100.0, IndexAnalyticsCalculator.rsi((1..30).map { it.toDouble() })!!, 1e-9)
        assertEquals(0.0, IndexAnalyticsCalculator.rsi((30 downTo 1).map { it.toDouble() })!!, 1e-9)
        assertNull(IndexAnalyticsCalculator.rsi(listOf(1.0, 2.0, 3.0)))
    }

    @Test fun `rolling sma aligns with input and matches simple average`() {
        val sma = IndexAnalyticsCalculator.rollingSma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), sma)
    }

    @Test fun `uptrend when price is above both moving averages`() {
        val a = IndexAnalyticsCalculator.calculate(index(dailyBars((1..260).map { 100.0 + it })))
        assertEquals(Trend.UP, a.trend)
        assertTrue(a.vsDma50!! > 0 && a.vsDma200!! > 0)
        assertEquals(0.0, a.fromHigh52w!!, 0.011) // closes at the high (bar high = close * 1.01)
    }

    @Test fun `volume metrics are null when the index reports no volume`() {
        val a = IndexAnalyticsCalculator.calculate(index(dailyBars(List(60) { 15.0 }, volume = null)))
        assertNull(a.avgVolume20)
        assertNull(a.relativeVolume)
        assertEquals(0.0, a.volatility1m!!, 1e-12)
    }

    @Test fun `monthly moves compute month-on-month and flag month to date`() {
        val monthly = monthlyBars(listOf(100.0, 110.0, 99.0))
        val moves = IndexAnalyticsCalculator.monthlyMoves(monthly, latestSession = LocalDate.of(2026, 11, 13))
        assertNull(moves[0].changePct)
        assertEquals(0.10, moves[1].changePct!!, 1e-12)
        assertEquals(-0.10, moves[2].changePct!!, 1e-12)
        assertTrue(moves[2].isMonthToDate)
        assertFalse(moves[1].isMonthToDate)
    }

    @Test fun `ranking respects metric direction and drops missing values`() {
        fun analyzed(symbol: String, closes: List<Double>, volume: Long?) =
            index(dailyBars(closes, volume = volume), symbol = symbol).let { AnalyzedIndex(it, IndexAnalyticsCalculator.calculate(it), false) }
        val calm = analyzed("CALM", List(300) { i -> 100.0 + (i % 2) * 0.1 }, 1000)
        val wild = analyzed("WILD", List(300) { i -> 100.0 + (i % 2) * 10 }, null)
        assertEquals(listOf("CALM", "WILD"), ReportBuilder.ranking(listOf(wild, calm), Metric.VOLATILITY_1Y).map { it.index.symbol })
        assertEquals(listOf("CALM"), ReportBuilder.ranking(listOf(wild, calm), Metric.REL_VOLUME).map { it.index.symbol })
    }
}
