package com.fundamentalanalysis.app.data.portfolio

import com.fundamentalanalysis.app.domain.model.CostSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CostResult(val avgPrice: Double, val costedQty: Double, val source: CostSource)

/**
 * A holding's cost when the broker's snapshot doesn't give one (some brokers export an average
 * price of 0). The account's own trades are replayed in date order: buys and priced allotments
 * open lots, sells and transfers out close the oldest lots first (FIFO), and splits / bonuses move
 * every open lot's quantity without changing its cost. Shares that can't be traced to a priced
 * buy have no cost: they're reported as such, never counted as free.
 */
object CostBasis {

    /** [cost] is the lot's total cost (null = unknown); a split changes [qty], never [cost]. */
    private class Lot(var qty: Double, var cost: Double?)

    fun of(
        row: BackupHoldingRowDto,
        accountId: String,
        trades: List<BackupTradeDto>,
        actions: List<BackupActionDto>,
        splits: List<BackupSplitDto>,
    ): CostResult {
        if (row.avg > 0) return CostResult(row.avg, row.qty, CostSource.BROKER)
        if (row.qty <= 0) return CostResult(0.0, 0.0, CostSource.UNKNOWN)

        fun sameSecurity(isin: String, symbol: String) =
            if (row.isin.isNotBlank() && isin.isNotBlank()) isin == row.isin else symbol == row.symbol

        // (date, order within the day, apply). Splits take effect at the start of their ex-date.
        val events = mutableListOf<Triple<String, Int, (MutableList<Lot>) -> Unit>>()
        trades.filter { it.accountId == accountId && sameSecurity(it.isin, it.symbol) && it.qty > 0 }.forEach { t ->
            when (t.side.lowercase()) {
                "buy" -> events += Triple(t.date, 1) { lots -> lots += Lot(t.qty, t.price.takeIf { it > 0 }?.let { it * t.qty }) }
                "sell", "out" -> events += Triple(t.date, 1) { lots -> close(lots, t.qty) }
            }
        }
        actions.filter { it.accountId == accountId && it.symbol == row.symbol && it.qty > 0 }.forEach { a ->
            if (a.direction.equals("in", true)) events += Triple(a.date, 1) { lots -> lots += Lot(a.qty, a.price?.takeIf { it > 0 }?.let { it * a.qty }) }
            else if (a.direction.equals("out", true)) events += Triple(a.date, 1) { lots -> close(lots, a.qty) }
        }
        splits.filter { it.num > 0 && it.den > 0 }.forEach { s ->
            val ratio = s.num.toDouble() / s.den
            events += Triple(s.date, 0) { lots -> lots.forEach { it.qty *= ratio } }
        }

        val lots = mutableListOf<Lot>()
        events.sortedWith(compareBy({ it.first }, { it.second })).forEach { it.third(lots) }

        val tolerance = max(0.01, row.qty * 1e-4)
        // More shares than the broker holds: sells are missing from the history, so close the oldest.
        val traced = lots.sumOf { it.qty }
        if (traced > row.qty + tolerance) close(lots, traced - row.qty)

        val priced = lots.filter { it.cost != null }
        val pricedQty = priced.sumOf { it.qty }
        if (pricedQty <= tolerance) return CostResult(0.0, 0.0, CostSource.UNKNOWN)
        val avg = priced.sumOf { it.cost!! } / pricedQty
        return if (abs(pricedQty - row.qty) <= tolerance) CostResult(avg, row.qty, CostSource.TRADES)
        else CostResult(avg, min(pricedQty, row.qty), CostSource.PARTIAL)
    }

    private fun close(lots: MutableList<Lot>, qty: Double) {
        var left = qty
        while (left > 1e-9 && lots.isNotEmpty()) {
            val first = lots.first()
            val take = min(left, first.qty)
            first.cost = first.cost?.let { it * (1 - take / first.qty) }
            first.qty -= take
            left -= take
            if (first.qty <= 1e-9) lots.removeAt(0)
        }
    }
}
