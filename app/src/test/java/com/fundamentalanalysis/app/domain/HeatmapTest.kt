package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.analysis.HeatGrouping
import com.fundamentalanalysis.app.domain.analysis.HeatTile
import com.fundamentalanalysis.app.domain.analysis.combinedReturn
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.model.PriceSource
import com.fundamentalanalysis.app.domain.analysis.MIN_TILE_MOVE
import com.fundamentalanalysis.app.domain.analysis.HeatmapBuilder
import com.fundamentalanalysis.app.domain.analysis.TreeRect
import com.fundamentalanalysis.app.domain.analysis.Treemap
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class HeatmapTest {
    private val bounds = TreeRect(0.0, 0.0, 400.0, 300.0)

    @Test fun `treemap areas are proportional, inside the bounds and don't overlap`() {
        val weights = listOf(6.0, 6.0, 4.0, 3.0, 2.0, 2.0, 1.0, 0.5, 0.25)
        val rects = Treemap.layout(weights, bounds)
        assertEquals(weights.size, rects.size)
        val total = weights.sum()
        rects.forEachIndexed { i, r ->
            assertEquals(bounds.area * weights[i] / total, r.area, 1e-6)
            assertTrue(r.x >= -1e-9 && r.y >= -1e-9 && r.x + r.w <= 400 + 1e-6 && r.y + r.h <= 300 + 1e-6)
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            val ox = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
            val oy = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
            assertTrue("$i and $j overlap", ox <= 1e-6 || oy <= 1e-6)
        }
        assertEquals(bounds.area, rects.sumOf { it.area }, 1e-6)
    }

    @Test fun `treemap keeps tiles close to square`() {
        val rects = Treemap.layout(List(16) { 1.0 }, TreeRect(0.0, 0.0, 400.0, 400.0))
        rects.forEach { r -> assertTrue("aspect ${r.w / r.h}", max(r.w / r.h, r.h / r.w) < 2.0) }
    }

    @Test fun `treemap handles empty and zero weights`() {
        assertTrue(Treemap.layout(emptyList(), bounds).isEmpty())
        assertEquals(listOf(0.0, 0.0), Treemap.layout(listOf(0.0, 0.0), bounds).map { it.area })
    }


    private fun stock(symbol: String, index: String, sector: String, cap: Double, d1: Double?) = AnalyzedStock(
        Stock(symbol, "$symbol Ltd.", sector, index, cap, null, null, emptyList(), emptyList(), emptyList(), emptyList()),
        StockMetrics(
            lastClose = 100.0, lastDate = null, change1d = d1,
            returns = mapOf(Period.Y1 to 0.5), benchmarkReturns = emptyMap(),
            momentum12m1m = null, rsi14 = null, vsDma200 = null, fromHigh52w = null, volatility1y = null, beta = null, alpha = null,
            pe = null, pb = null, earningsYield = null, revenueGrowth = null, revenueCagr = null, epsGrowth = null,
            quarterlyRevenueYoY = null, roe = null, netMargin = null, debtToEquity = null, netProfitCr = null, profitGrowth = null,
        ),
    )

    private val stocks = listOf(
        stock("A", "NIFTY 50", "IT", 300.0, 0.02),
        stock("B", "NIFTY 50", "Banks", 100.0, -0.01),
        stock("D", "NIFTY 50", "IT", 100.0, null),
        stock("C", "NIFTY NEXT 50", "IT", 100.0, 0.05),
    )

    @Test fun `combined return is total now over total at the start`() {
        // 1,000 -> 1,020 (+2%) and 1,000 -> 990 (-1%): together 2,000 -> 2,010.
        val tiles = listOf(
            HeatTile("A", "A", "", 1020.0, 0.02),
            HeatTile("B", "B", "", 990.0, -0.01),
            HeatTile("C", "C", "", 500.0, null),
        )
        assertEquals(2010.0 / 2000 - 1, combinedReturn(tiles)!!, 1e-12)
    }

    @Test fun `only Nifty 50 stocks, sized and coloured by the latest day's change`() {
        val flat = HeatmapBuilder.stocks(stocks, HeatGrouping.NONE)
        assertEquals(listOf("A", "B", "D"), flat.single().tiles.map { it.key })
        assertEquals(listOf(0.02, -0.01, null), flat.single().tiles.map { it.value })

        val bySector = HeatmapBuilder.stocks(stocks, HeatGrouping.SECTOR)
        assertEquals(listOf("IT", "Banks"), bySector.map { it.label })
        assertEquals(listOf("A", "D"), bySector[0].tiles.map { it.key })
        // D has no change: left out of the sector's figure and counted as no data.
        assertEquals(0.02, bySector[0].value!!, 1e-12)
        val s = HeatmapBuilder.summary(bySector)
        assertEquals(1, s.up); assertEquals(1, s.down); assertEquals(1, s.noData)
        assertEquals(400 / (300 / 1.02 + 100 / 0.99) - 1, s.weighted!!, 1e-12)

        // Tile size is the size of the move, not market cap; flat or missing moves get the minimum.
        assertEquals(listOf(0.02, 0.01, MIN_TILE_MOVE), flat.single().tiles.map { it.weight })
    }

    private fun position(symbol: String, kind: HoldingKind, value: Double, tracked: AnalyzedStock?) = Position(
        symbol, "", kind, "Sector", emptyList(), 1.0, value, value, null, PriceSource.BROKER, value, null, tracked, null,
    )

    @Test fun `portfolio heatmap shows Nifty 50 holdings only`() {
        val positions = listOf(
            position("A", HoldingKind.STOCK, 5000.0, stocks[0]),
            position("C", HoldingKind.STOCK, 3000.0, stocks[3]),
            position("ETF", HoldingKind.FUND, 9000.0, null),
            position("OTHER", HoldingKind.STOCK, 2000.0, null),
            position("B", HoldingKind.STOCK, 1000.0, stocks[1]),
        )
        val g = HeatmapBuilder.portfolio(positions, HeatGrouping.NONE).single()
        assertEquals(listOf("A", "B"), g.tiles.map { it.key })
        assertEquals(listOf(0.02, 0.01), g.tiles.map { it.weight })
        assertEquals(listOf(5000.0, 1000.0), g.tiles.map { it.amount })
        // The day's change in rupees as a percentage: 6,000 now vs 5,000/1.02 + 1,000/0.99 before.
        assertEquals(6000 / (5000 / 1.02 + 1000 / 0.99) - 1, g.value!!, 1e-12)
        assertEquals(listOf("IT", "Banks"), HeatmapBuilder.portfolio(positions, HeatGrouping.SECTOR).map { it.label })
        assertTrue(HeatmapBuilder.portfolio(positions.drop(1).dropLast(1), HeatGrouping.NONE).isEmpty())
    }
}
