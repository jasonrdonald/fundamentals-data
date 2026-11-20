package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.analysis.HoldingFilter
import com.fundamentalanalysis.app.domain.analysis.PortfolioStocks
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.model.PriceSource
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic holdings only. */
class PortfolioStocksTest {
    private fun stock(symbol: String, index: String, sector: String, y1: Double, bench: Double) = AnalyzedStock(
        Stock(symbol, "$symbol Ltd.", sector, index, 100.0, null, null, emptyList(), emptyList(), emptyList(), emptyList()),
        StockMetrics(
            lastClose = 100.0, lastDate = null, change1d = 0.01,
            returns = mapOf(Period.Y1 to y1), benchmarkReturns = mapOf(Period.Y1 to bench),
            momentum12m1m = null, rsi14 = null, vsDma200 = null, fromHigh52w = null, volatility1y = null, beta = null, alpha = null,
            pe = null, pb = null, earningsYield = null, revenueGrowth = null, revenueCagr = null, epsGrowth = null,
            quarterlyRevenueYoY = null, roe = null, netMargin = null, debtToEquity = null, netProfitCr = null, profitGrowth = null,
        ),
    )

    private fun position(symbol: String, kind: HoldingKind, value: Double, tracked: AnalyzedStock?) = Position(
        symbol, "", kind, "Sector", emptyList(), 1.0, value, value, null, PriceSource.BROKER, value, null, tracked, null,
    )

    private val a = stock("A", "NIFTY 50", "IT", 0.20, 0.10)
    private val b = stock("B", "NIFTY 50", "Banks", 0.05, 0.10)
    private val c = stock("C", "NIFTY NEXT 50", "IT", 0.30, 0.25)
    private val m = stock("M", "NIFTY MIDCAP 150", "Banks", -0.10, 0.15)
    private val positions = listOf(
        position("B", HoldingKind.STOCK, 1000.0, b),
        position("A", HoldingKind.STOCK, 5000.0, a),
        position("C", HoldingKind.STOCK, 3000.0, c),
        position("M", HoldingKind.STOCK, 500.0, m),
        position("ETF", HoldingKind.FUND, 9000.0, null),
        position("SMALL", HoldingKind.STOCK, 2000.0, null),
    )

    @Test fun `filters split stock holdings by index, never including funds`() {
        fun symbols(f: HoldingFilter) = PortfolioStocks.holdings(positions, f).map { it.symbol }
        assertEquals(listOf("A", "C", "SMALL", "B", "M"), symbols(HoldingFilter.ALL))
        assertEquals(listOf("A", "B"), symbols(HoldingFilter.NIFTY_50))
        assertEquals(listOf("C"), symbols(HoldingFilter.NEXT_50))
        assertEquals(listOf("M"), symbols(HoldingFilter.MIDCAP_150))
        assertEquals(listOf("SMALL"), symbols(HoldingFilter.UNCATEGORIZED))
        val counts = PortfolioStocks.counts(positions)
        assertEquals(5, counts[HoldingFilter.ALL])
        assertEquals(counts[HoldingFilter.ALL], HoldingFilter.entries.drop(1).sumOf { counts.getValue(it) })
    }

    @Test fun `only tracked holdings are analysed`() {
        val all = PortfolioStocks.holdings(positions, HoldingFilter.ALL)
        assertEquals(listOf("A", "C", "B", "M"), PortfolioStocks.analysed(all).map { it.symbol })
        assertEquals(emptyList<AnalyzedStock>(), PortfolioStocks.analysed(PortfolioStocks.holdings(positions, HoldingFilter.UNCATEGORIZED)))
    }

    @Test fun `each stock is judged against its own index`() {
        val stocks = PortfolioStocks.analysed(PortfolioStocks.holdings(positions, HoldingFilter.ALL))
        val sectors = PortfolioStocks.sectors(stocks, Period.Y1).associateBy { it.sector }
        // IT: A beat the Nifty 50 (20% vs 10%), C beat the Next 50 (30% vs 25%); two indices, no single benchmark.
        assertEquals(2, sectors.getValue("IT").outperformers)
        assertNull(sectors.getValue("IT").benchmarkReturn)
        // Banks: B lagged the Nifty 50, M lagged the Midcap 150.
        assertEquals(0, sectors.getValue("Banks").outperformers)
        assertEquals(2, sectors.getValue("Banks").measured)
        // One index only: its return is the sector's benchmark.
        val nifty = PortfolioStocks.sectors(PortfolioStocks.analysed(PortfolioStocks.holdings(positions, HoldingFilter.NIFTY_50)), Period.Y1)
        assertEquals(0.10, nifty.first().benchmarkReturn!!, 1e-12)
    }
}
