package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.portfolio.PortfolioBackupParser
import com.fundamentalanalysis.app.data.portfolio.UnsupportedBackupException
import com.fundamentalanalysis.app.data.portfolio.toRows
import com.fundamentalanalysis.app.domain.analysis.PortfolioAnalytics
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.BrokerHolding
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.PortfolioAccount
import com.fundamentalanalysis.app.domain.model.PortfolioData
import com.fundamentalanalysis.app.domain.model.PortfolioFee
import com.fundamentalanalysis.app.domain.model.PortfolioTrade
import com.fundamentalanalysis.app.domain.model.PriceSource
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockMetrics
import com.fundamentalanalysis.app.domain.model.TradeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Import template parsing and portfolio analytics, on a synthetic backup (no real data). */
class PortfolioImportTest {
    private val json = DatasetFiles.json
    private val sample = javaClass.classLoader!!.getResource("portfolio_backup_sample.json")!!.readText()

    @Test fun `only Indian equity brokers are imported`() {
        val parsed = PortfolioBackupParser.parse(json, sample, "backup.json")
        val p = parsed.preview
        assertEquals(listOf("Zerodha", "HDFC Securities", "Aionion Capital"), p.included.map { it.broker })
        assertEquals(listOf("Vested", "Nippon MF", "Zebpay"), p.skipped.map { it.broker })
        assertTrue(p.skipped.first { it.broker == "Vested" }.reason.contains("USD"))
        assertTrue(p.skipped.first { it.broker == "Nippon MF" }.reason.contains("Mutual fund"))
        assertTrue(p.skipped.first { it.broker == "Zebpay" }.reason.contains("Crypto"))
        // Nothing from skipped accounts leaks into what gets stored.
        val ids = setOf("acc-z", "acc-h", "acc-a")
        val b = parsed.backup
        assertTrue(b.trades.all { it.accountId in ids } && b.trades.size == 4)
        assertTrue(b.brokerHoldings.all { it.id in ids })
        assertTrue(b.portfolioNotes.all { it.accountId in ids })
        assertTrue(b.brokerFees.all { it.accountId in ids } && b.brokerFees.size == 3)
        val z = p.included.first { it.broker == "Zerodha" }
        assertEquals(2, z.holdings); assertEquals("2026-11-01", z.holdingsAsOf); assertEquals(2, z.trades); assertEquals(1, z.notes)
    }

    @Test fun `client ids are stored masked`() {
        val rows = PortfolioBackupParser.parse(json, sample, null).toRows(0)
        assertEquals("•••234", rows.accounts.first { it.broker == "Zerodha" }.clientId)
        assertEquals("•••••766", rows.accounts.first { it.broker == "HDFC Securities" }.clientId)
        assertEquals("XY9", rows.accounts.first { it.broker == "Aionion Capital" }.clientId)
        assertFalse(rows.accounts.any { it.clientId.contains("1234") })
    }

    @Test fun `duplicate rows in one snapshot are merged with a weighted average`() {
        val rows = PortfolioBackupParser.parse(json, sample, null).toRows(0)
        val beta = rows.holdings.single { it.symbol == "BETA" }
        assertEquals(4.0, beta.qty, 1e-9)
        assertEquals(1050.0, beta.avgPrice, 1e-9)
    }

    @Test fun `other files are rejected clearly`() {
        assertThrows(UnsupportedBackupException::class.java) { PortfolioBackupParser.parse(json, """{"app":"other","version":1}""", null) }
        assertThrows(UnsupportedBackupException::class.java) { PortfolioBackupParser.parse(json, "not json", null) }
        assertThrows(UnsupportedBackupException::class.java) {
            PortfolioBackupParser.parse(json, """{"app":"superapp-portfolio","version":2}""", null)
        }
    }

    // ------------------------------------------------------------------ analytics

    private val day = LocalDate.of(2026, 11, 13)
    private fun data() = PortfolioData(
        accounts = listOf(
            PortfolioAccount("z", "Zerodha", "•••234", LocalDate.of(2026, 11, 1), 0),
            PortfolioAccount("h", "HDFC Securities", "•••766", LocalDate.of(2026, 11, 2), 0),
        ),
        holdings = listOf(
            BrokerHolding("z", "ALPHA", "INE1", HoldingKind.STOCK, "Banks", 8.0, 100.0, 140.0),
            BrokerHolding("h", "ALPHA", "INE1", HoldingKind.STOCK, "", 5.0, 120.0, 141.0),
            BrokerHolding("h", "BETA", "INE2", HoldingKind.STOCK, "", 4.0, 1050.0, 900.0),
            BrokerHolding("z", "GOLDETF", "INF1", HoldingKind.FUND, "Others", 12.5, 50.0, 60.0),
        ),
        trades = listOf(
            PortfolioTrade("t1", "z", "ALPHA", "INE1", LocalDate.of(2024, 3, 4), TradeSide.BUY, 10.0, 100.0, "NSE", HoldingKind.STOCK),
            PortfolioTrade("t2", "z", "ALPHA", "INE1", LocalDate.of(2025, 6, 10), TradeSide.SELL, 2.0, 150.0, "NSE", HoldingKind.STOCK),
        ),
        fees = listOf(PortfolioFee("h", LocalDate.of(2025, 1, 15), "Brokerage", 20.0)),
    )

    /** ALPHA is a tracked Nifty 50 stock at 150 (+2% today), up 30% over 1Y vs the index's 10%. */
    private fun tracked() = mapOf(
        "ALPHA" to AnalyzedStock(
            Stock("ALPHA", "Alpha Bank Ltd.", "Financial Services", "NIFTY 50", null, null, null, emptyList(), emptyList(), emptyList(), emptyList()),
            StockMetrics(
                lastClose = 150.0, lastDate = day, change1d = 0.02,
                returns = mapOf(Period.Y1 to 0.30, Period.M1 to -0.05), benchmarkReturns = mapOf(Period.Y1 to 0.10, Period.M1 to 0.01),
                momentum12m1m = null, rsi14 = null, vsDma200 = null, fromHigh52w = null, volatility1y = null, beta = null, alpha = null,
                pe = null, pb = null, earningsYield = null, revenueGrowth = null, revenueCagr = null, epsGrowth = null,
                quarterlyRevenueYoY = null, roe = null, netMargin = null, debtToEquity = null, netProfitCr = null, profitGrowth = null,
            ),
        ),
    )

    @Test fun `positions merge brokers by ISIN and use the freshest price`() {
        val s = PortfolioAnalytics.summarize(data(), tracked())
        val alpha = s.positions.single { it.symbol == "ALPHA" }
        assertEquals(13.0, alpha.qty, 1e-9)
        assertEquals(8 * 100.0 + 5 * 120.0, alpha.invested, 1e-9)
        assertEquals(150.0, alpha.price, 1e-9)
        assertEquals(PriceSource.DATASET, alpha.priceSource)
        assertEquals("Financial Services", alpha.sector)
        assertEquals(listOf("Zerodha", "HDFC Securities"), alpha.lots.map { it.broker })
        assertEquals(13 * 150.0 * 0.02 / 1.02, alpha.dayChange!!, 1e-9)

        val beta = s.positions.single { it.symbol == "BETA" }
        assertEquals(PriceSource.BROKER, beta.priceSource)
        assertEquals(900.0, beta.price, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 2), beta.priceDate)
        assertNull(beta.dayChange)
        assertEquals("Unclassified", beta.sector)

        val gold = s.positions.single { it.symbol == "GOLDETF" }
        assertEquals(PortfolioAnalytics.FUNDS_SECTOR, gold.sector)

        assertEquals(13 * 150.0 + 4 * 900.0 + 12.5 * 60.0, s.value, 1e-9)
        assertEquals(1400.0 + 4200.0 + 625.0, s.invested, 1e-9)
        assertEquals(1.0, s.bySector.sumOf { it.share }, 1e-9)
        assertEquals(1.0, s.byBroker.sumOf { it.share }, 1e-9)
        assertEquals(s.dayChange!!, alpha.dayChange!!, 1e-9)
    }

    @Test fun `tracked holdings are compared with their index`() {
        val s = PortfolioAnalytics.summarize(data(), tracked())
        val y1 = s.vsIndex.single { it.period == Period.Y1 }
        assertEquals(1, y1.measured); assertEquals(1, y1.beat); assertEquals(0.30, y1.weightedReturn!!, 1e-12)
        assertEquals(0, s.vsIndex.single { it.period == Period.M1 }.beat)
        assertEquals(setOf("Nifty 50", "Other stocks", "Funds & ETFs"), s.byIndex.map { it.label }.toSet())
    }

    @Test fun `activity sums trades and charges by year`() {
        val s = PortfolioAnalytics.summarize(data(), tracked())
        assertEquals(listOf(2025, 2024), s.activity.map { it.year })
        val y2025 = s.activity.first()
        assertEquals(300.0, y2025.sold, 1e-9); assertEquals(0.0, y2025.bought, 1e-9); assertEquals(20.0, y2025.charges, 1e-9)
        assertEquals(1000.0, s.activity.last().bought, 1e-9)
    }

    @Test fun `filtering by broker keeps only its accounts`() {
        val hdfc = data().forBroker("HDFC Securities")
        val s = PortfolioAnalytics.summarize(hdfc, tracked())
        assertEquals(setOf("ALPHA", "BETA"), s.positions.map { it.symbol }.toSet())
        assertEquals(5.0, s.positions.single { it.symbol == "ALPHA" }.qty, 1e-9)
        assertTrue(s.trades.isEmpty())
    }

    @Test fun `capitalised broker sectors match NSE spelling`() {
        assertEquals("Financial Services", PortfolioAnalytics.normalizeSector("FINANCIAL SERVICES"))
        assertEquals("IT - Software", PortfolioAnalytics.normalizeSector("IT - SOFTWARE"))
        assertEquals("Oil and Gas", PortfolioAnalytics.normalizeSector("OIL AND GAS"))
        assertEquals("Metals & Mining", PortfolioAnalytics.normalizeSector("Metals & Mining"))
    }

    @Test fun `holdings are grouped by index with per-index comparison`() {
        val s = PortfolioAnalytics.summarize(data(), tracked())
        assertEquals(
            listOf(com.fundamentalanalysis.app.domain.model.IndexGroup.NIFTY_50, com.fundamentalanalysis.app.domain.model.IndexGroup.OTHER, com.fundamentalanalysis.app.domain.model.IndexGroup.FUNDS),
            s.groups.map { it.group },
        )
        assertEquals(s.positions.size, s.groups.sumOf { it.positions.size })
        assertEquals(1.0, s.groups.sumOf { it.share }, 1e-9)
        val nifty = s.groups.first()
        assertEquals(listOf("ALPHA"), nifty.positions.map { it.symbol })
        val y1 = nifty.vsIndex.single { it.period == Period.Y1 }
        assertEquals(1, y1.beat); assertEquals(0.30, y1.weightedReturn!!, 1e-12); assertEquals(0.10, y1.indexReturn!!, 1e-12)
        assertTrue(s.groups.filterNot { it.group.tracked }.all { it.vsIndex.isEmpty() })
        assertEquals(setOf("Nifty 50", "Other stocks", "Funds & ETFs"), s.byIndex.map { it.label }.toSet())
    }

    @Test fun `other stocks are compared with the Nifty 50 since purchase`() {
        fun bar(d: LocalDate, c: Double) = com.fundamentalanalysis.app.domain.model.Bar(d, c, c, c, c, null)
        val nifty = com.fundamentalanalysis.app.domain.model.MarketIndex(
            "NIFTY 50", "Nifty 50", com.fundamentalanalysis.app.domain.model.IndexCategory.BROAD, null,
            daily = listOf(bar(LocalDate.of(2025, 11, 3), 200.0), bar(LocalDate.of(2026, 1, 5), 210.0), bar(day, 220.0)),
            monthly = listOf(bar(LocalDate.of(2023, 1, 1), 100.0), bar(LocalDate.of(2023, 2, 1), 110.0), bar(LocalDate.of(2025, 11, 1), 200.0)),
        )
        // BETA (not in a tracked index): two buys, 1,000 on 5 Jan 2026 and 1,000 on 5 Jan 2026 -> one purchase date.
        val beta = { id: String, d: LocalDate -> PortfolioTrade(id, "h", "BETA", "INE2", d, TradeSide.BUY, 1.0, 1000.0, "NSE", HoldingKind.STOCK) }
        val withBuys = data().copy(trades = data().trades + beta("b1", LocalDate.of(2026, 1, 5)) + beta("b2", LocalDate.of(2026, 1, 5)))
        val s = PortfolioAnalytics.summarize(withBuys, tracked(), nifty)
        val other = s.groups.single { it.group == com.fundamentalanalysis.app.domain.model.IndexGroup.OTHER }
        val sp = other.sincePurchase!!
        val row = sp.rows.single()
        assertEquals("BETA", row.position.symbol)
        assertEquals(LocalDate.of(2026, 1, 5), row.avgBuyDate)
        assertEquals(220.0 / 210.0 - 1, row.benchmarkReturn, 1e-12)
        assertEquals(3600.0 / 4200.0 - 1, row.holdingReturn, 1e-12)
        assertEquals(0, sp.beat)
        assertEquals(4200.0 * 220.0 / 210.0, sp.benchmarkValue, 1e-9)
        // Without buy trades the holding is listed as not comparable.
        val none = PortfolioAnalytics.summarize(data(), tracked(), nifty).groups.single { it.group == com.fundamentalanalysis.app.domain.model.IndexGroup.OTHER }
        assertEquals(listOf("BETA"), none.sincePurchase!!.withoutHistory.map { it.symbol })
        // Purchases older than the daily window use the previous month-end close.
        assertEquals(100.0, PortfolioAnalytics.closeOn(nifty, LocalDate.of(2023, 2, 15))!!, 1e-12)
        assertEquals(210.0, PortfolioAnalytics.closeOn(nifty, LocalDate.of(2026, 1, 6))!!, 1e-12)
    }
}
