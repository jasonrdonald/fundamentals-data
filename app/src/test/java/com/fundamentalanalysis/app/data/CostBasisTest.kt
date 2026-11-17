package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.portfolio.BackupActionDto
import com.fundamentalanalysis.app.data.portfolio.BackupHoldingRowDto
import com.fundamentalanalysis.app.data.portfolio.BackupSplitDto
import com.fundamentalanalysis.app.data.portfolio.BackupTradeDto
import com.fundamentalanalysis.app.data.portfolio.CostBasis
import com.fundamentalanalysis.app.domain.analysis.PortfolioAnalytics
import com.fundamentalanalysis.app.domain.model.BrokerHolding
import com.fundamentalanalysis.app.domain.model.CostSource
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.PortfolioAccount
import com.fundamentalanalysis.app.domain.model.PortfolioData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Rebuilding a holding's cost from trades when the broker exports none. Synthetic data only. */
class CostBasisTest {
    private val acct = "a1"
    private fun row(qty: Double, avg: Double = 0.0, symbol: String = "ACME", isin: String = "INE000X01011") =
        BackupHoldingRowDto(symbol = symbol, isin = isin, qty = qty, avg = avg, close = 100.0)
    private fun buy(date: String, qty: Double, price: Double, account: String = acct) =
        BackupTradeDto(id = "$date-b-$qty-$price", accountId = account, symbol = "ACME", isin = "INE000X01011", date = date, side = "buy", qty = qty, price = price)
    private fun sell(date: String, qty: Double, price: Double) =
        BackupTradeDto(id = "$date-s-$qty", accountId = acct, symbol = "ACME", isin = "INE000X01011", date = date, side = "sell", qty = qty, price = price)

    @Test fun `broker's own average is kept`() {
        val r = CostBasis.of(row(10.0, avg = 250.0), acct, listOf(buy("2020-01-01", 10.0, 999.0)), emptyList(), emptyList())
        assertEquals(CostSource.BROKER, r.source)
        assertEquals(250.0, r.avgPrice, 1e-9)
        assertEquals(10.0, r.costedQty, 1e-9)
    }

    @Test fun `zero broker cost is rebuilt from trades across a bonus`() {
        // 10 @ 1,000 + 10 @ 1,200, then a 1:1 bonus (2/1): 40 shares costing 22,000.
        val trades = listOf(buy("2020-01-01", 10.0, 1000.0), buy("2021-01-01", 10.0, 1200.0))
        val r = CostBasis.of(row(40.0), acct, trades, emptyList(), listOf(BackupSplitDto("2022-06-01", 2, 1)))
        assertEquals(CostSource.TRADES, r.source)
        assertEquals(40.0, r.costedQty, 1e-9)
        assertEquals(22000.0 / 40, r.avgPrice, 1e-9)
    }

    @Test fun `sells close the oldest lots first and a buy on the ex-date is post-split`() {
        // 10 @ 100, 10 @ 200, sell 10 (closes the 100s); 2:1 split; buy 5 @ 90 on the ex-date.
        val trades = listOf(buy("2020-01-01", 10.0, 100.0), buy("2020-02-01", 10.0, 200.0), sell("2020-03-01", 10.0, 300.0), buy("2021-01-01", 5.0, 90.0))
        val r = CostBasis.of(row(25.0), acct, trades, emptyList(), listOf(BackupSplitDto("2021-01-01", 2, 1)))
        assertEquals(CostSource.TRADES, r.source)
        assertEquals((10 * 200.0 + 5 * 90.0) / 25, r.avgPrice, 1e-9)
    }

    @Test fun `shares that can't be traced are left without cost, not counted as free`() {
        // 30 bought, 2:1 bonus -> 60, but 64 held (e.g. rights shares with no record).
        val r = CostBasis.of(row(64.0), acct, listOf(buy("2020-01-01", 30.0, 1200.0)), emptyList(), listOf(BackupSplitDto("2023-03-15", 2, 1)))
        assertEquals(CostSource.PARTIAL, r.source)
        assertEquals(60.0, r.costedQty, 1e-9)
        assertEquals(600.0, r.avgPrice, 1e-9)
    }

    @Test fun `no trades means unknown cost`() {
        val r = CostBasis.of(row(42.0), acct, listOf(buy("2020-01-01", 5.0, 10.0, account = "other")), emptyList(), emptyList())
        assertEquals(CostSource.UNKNOWN, r.source)
        assertEquals(0.0, r.costedQty, 1e-9)
    }

    @Test fun `priced allotments count, unpriced ones don't`() {
        val ipo = BackupActionDto(id = "x", accountId = acct, type = "allot", symbol = "ACME", date = "2021-05-03", qty = 40.0, price = 50.0, issue = "ipo", direction = "in")
        val gift = ipo.copy(id = "y", qty = 10.0, price = null, issue = "transfer")
        assertEquals(CostSource.TRADES, CostBasis.of(row(40.0), acct, emptyList(), listOf(ipo), emptyList()).source)
        val r = CostBasis.of(row(50.0), acct, emptyList(), listOf(ipo, gift), emptyList())
        assertEquals(CostSource.PARTIAL, r.source)
        assertEquals(40.0, r.costedQty, 1e-9)
        assertEquals(50.0, r.avgPrice, 1e-9)
    }

    @Test fun `fund units within rounding count as fully costed`() {
        val trades = listOf(buy("2023-01-01", 37.607, 21.0), buy("2023-06-01", 37.64, 23.0))
        val r = CostBasis.of(row(75.25), acct, trades, emptyList(), emptyList())
        assertEquals(CostSource.TRADES, r.source)
        assertEquals(75.25, r.costedQty, 1e-9)
    }

    @Test fun `extra traced shares are trimmed oldest first`() {
        // History shows 20 but the broker holds 10: the missing sell closes the older lot.
        val r = CostBasis.of(row(10.0), acct, listOf(buy("2020-01-01", 10.0, 100.0), buy("2021-01-01", 10.0, 300.0)), emptyList(), emptyList())
        assertEquals(CostSource.TRADES, r.source)
        assertEquals(300.0, r.avgPrice, 1e-9)
    }

    @Test fun `P&L covers only shares with a known cost`() {
        val day = LocalDate.of(2026, 11, 1)
        val data = PortfolioData(
            accounts = listOf(PortfolioAccount("a", "Broker A", "••123", day, 0L), PortfolioAccount("b", "Broker B", "••456", day, 0L)),
            holdings = listOf(
                // 90 of 100 shares costed at 600; 5 more at a second broker at 1,200; price 1,300.
                BrokerHolding("a", "ACME", "INE000X01011", HoldingKind.STOCK, "", 100.0, 600.0, 1300.0, costedQty = 90.0, costSource = CostSource.PARTIAL),
                BrokerHolding("b", "ACME", "INE000X01011", HoldingKind.STOCK, "", 5.0, 1200.0, 1300.0),
                // No cost at all: value counts, P&L doesn't.
                BrokerHolding("a", "GIFT", "INE000Y01011", HoldingKind.STOCK, "", 10.0, 0.0, 50.0, costedQty = 0.0, costSource = CostSource.UNKNOWN),
            ),
        )
        val s = PortfolioAnalytics.summarize(data, emptyMap())
        val acme = s.positions.single { it.symbol == "ACME" }
        assertEquals(105.0, acme.qty, 1e-9)
        assertEquals(90 * 600.0 + 5 * 1200.0, acme.invested, 1e-9)
        assertEquals(95 * 1300.0, acme.costedValue, 1e-9)
        assertEquals(10.0, acme.uncostedQty, 1e-9)
        assertEquals(95 * 1300.0 / (90 * 600.0 + 5 * 1200.0) - 1, acme.pnlPct!!, 1e-12)
        val gift = s.positions.single { it.symbol == "GIFT" }
        assertNull(gift.pnlPct)
        assertEquals(105 * 1300.0 + 500.0, s.value, 1e-9)
        assertEquals(10 * 1300.0 + 500.0, s.uncostedValue, 1e-9)
        assertEquals(2, s.uncostedHoldings)
        assertEquals(95 * 1300.0 - acme.invested, s.pnl, 1e-9)
    }
}
