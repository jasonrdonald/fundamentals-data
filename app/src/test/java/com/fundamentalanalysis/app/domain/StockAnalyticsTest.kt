package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.analysis.StockAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.StockReports
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.FinancialPeriod
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class StockAnalyticsTest {

    private val end = LocalDate.of(2026, 11, 13)

    private fun daily(closes: List<Double>): List<Bar> {
        val dates = generateSequence(end) { it.minusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .take(closes.size).toList().reversed()
        return dates.zip(closes) { d, c -> Bar(d, c, c, c, c, 1000) }
    }

    private fun monthly(closes: List<Double>): List<Bar> = closes.indices.map { i ->
        val m = LocalDate.of(2026, 11, 1).minusMonths((closes.size - 1 - i).toLong())
        Bar(m, closes[i], closes[i], closes[i], closes[i], null)
    }

    private fun index(daily: List<Double>, monthly: List<Double>) =
        MarketIndex("NIFTY 50", "Nifty 50", IndexCategory.BROAD, null, daily(daily), monthly(monthly))

    private fun stock(
        symbol: String,
        industry: String,
        daily: List<Double>,
        monthly: List<Double> = emptyList(),
        pe: Double? = 20.0,
        annual: List<FinancialPeriod> = emptyList(),
    ) = Stock(symbol, symbol, industry, "NIFTY 50", 1000.0, pe, 3.0, monthly(monthly), daily(daily), annual, emptyList())

    @Test fun `beta and alpha recover a known linear relationship`() {
        // Stock monthly return = 1% + 1.5 x index return, so beta 1.5 and alpha 12% a year.
        val idxReturns = (0 until 36).map { if (it % 2 == 0) 0.03 else -0.02 }
        val idx = idxReturns.runningFold(100.0) { p, r -> p * (1 + r) }
        val stk = idxReturns.map { 0.01 + 1.5 * it }.runningFold(100.0) { p, r -> p * (1 + r) }
        val (beta, alpha) = StockAnalyticsCalculator.betaAlpha(monthly(stk), monthly(idx))
        assertEquals(1.5, beta!!, 1e-9)
        assertEquals(0.12, alpha!!, 1e-9)
    }

    @Test fun `beta needs at least a year of overlapping months`() {
        val (beta, alpha) = StockAnalyticsCalculator.betaAlpha(monthly(List(6) { 100.0 + it }), monthly(List(6) { 50.0 + it }))
        assertNull(beta)
        assertNull(alpha)
    }

    @Test fun `excess return compares the same period with the benchmark`() {
        val bench = index(List(300) { 100.0 + it * 0.1 }, List(40) { 100.0 })
        val s = StockAnalyticsCalculator.calculate(stock("UP", "IT", List(300) { 100.0 + it }), bench)
        val expected = s.returns[Period.Y1]!! - s.benchmarkReturns[Period.Y1]!!
        assertEquals(expected, s.excess(Period.Y1)!!, 1e-12)
    }

    @Test fun `growth and quality ratios come from the latest two fiscal years`() {
        val annual = listOf(
            FinancialPeriod(LocalDate.of(2025, 3, 31), revenue = 1000.0, netIncome = 100.0, operatingIncome = null, equity = 500.0, debt = 250.0, eps = 10.0),
            FinancialPeriod(LocalDate.of(2026, 3, 31), revenue = 1200.0, netIncome = 150.0, operatingIncome = null, equity = 600.0, debt = 300.0, eps = 15.0),
        )
        val m = StockAnalyticsCalculator.calculate(stock("G", "IT", List(30) { 100.0 }, annual = annual), null)
        assertEquals(0.2, m.revenueGrowth!!, 1e-12)
        assertEquals(0.5, m.profitGrowth!!, 1e-12)
        assertEquals(0.5, m.epsGrowth!!, 1e-12)
        assertEquals(0.25, m.roe!!, 1e-12)
        assertEquals(0.125, m.netMargin!!, 1e-12)
        assertEquals(0.5, m.debtToEquity!!, 1e-12)
    }

    @Test fun `profit growth is undefined from a loss`() {
        val annual = listOf(
            FinancialPeriod(LocalDate.of(2025, 3, 31), 1000.0, -50.0, null, 500.0, 0.0, -1.0),
            FinancialPeriod(LocalDate.of(2026, 3, 31), 1100.0, 80.0, null, 520.0, 0.0, 2.0),
        )
        val m = StockAnalyticsCalculator.calculate(stock("L", "IT", List(30) { 100.0 }, annual = annual), null)
        assertNull(m.profitGrowth)
        assertNull(m.epsGrowth)
    }

    @Test fun `valuation top is cheapest and bottom is most expensive, loss makers excluded`() {
        val stocks = listOf(
            analyzed(stock("CHEAP", "A", List(30) { 1.0 }, pe = 8.0)),
            analyzed(stock("MID", "A", List(30) { 1.0 }, pe = 20.0)),
            analyzed(stock("RICH", "A", List(30) { 1.0 }, pe = 60.0)),
            analyzed(stock("LOSS", "A", List(30) { 1.0 }, pe = -5.0)),
        )
        assertEquals(listOf("CHEAP", "MID"), StockReports.rank(stocks, StockFactor.VALUATION, 2, top = true).map { it.stock.symbol })
        assertEquals(listOf("RICH", "MID"), StockReports.rank(stocks, StockFactor.VALUATION, 2, top = false).map { it.stock.symbol })
    }

    @Test fun `performance ranks by the selected period's return`() {
        // UP rose steadily over the year but fell in the last month; DIP fell all year, then rallied.
        val up = analyzed(stock("UP", "A", List(279) { 100.0 + it } + List(21) { 378.0 - it * 3 }))
        val dip = analyzed(stock("DIP", "A", List(279) { 300.0 - it * 0.5 } + List(21) { 160.5 + it * 5 }))
        assertEquals("UP", StockReports.rank(listOf(up, dip), StockFactor.PERFORMANCE, 1, top = true, period = Period.Y1).single().stock.symbol)
        assertEquals("DIP", StockReports.rank(listOf(up, dip), StockFactor.PERFORMANCE, 1, top = true, period = Period.M1).single().stock.symbol)
        assertEquals("1M return", StockFactor.PERFORMANCE.primaryLabel(Period.M1))
    }

    @Test fun `sector performance counts stocks that beat the benchmark`() {
        val bench = index(List(300) { 100.0 }, List(40) { 100.0 }) // flat index
        val stocks = listOf(
            stock("WIN1", "Banks", List(300) { 100.0 + it }),
            stock("LOSE1", "Banks", List(300) { 200.0 - it * 0.5 }),
            stock("WIN2", "IT", List(300) { 100.0 + it * 0.2 }),
        ).map { AnalyzedStock(it, StockAnalyticsCalculator.calculate(it, bench)) }
        val sectors = StockReports.sectorPerformance(stocks, Period.Y1)
        val banks = sectors.first { it.sector == "Banks" }
        assertEquals(1, banks.outperformers)
        assertEquals(2, banks.measured)
        assertEquals("IT sector has the higher share of winners", "IT", sectors.first().sector)
        assertEquals(listOf("WIN1", "LOSE1"), banks.stocks.map { it.symbol })
    }

    private fun analyzed(s: Stock) = AnalyzedStock(s, StockAnalyticsCalculator.calculate(s, null))
}
