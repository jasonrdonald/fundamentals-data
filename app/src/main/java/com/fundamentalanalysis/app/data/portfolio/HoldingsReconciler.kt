package com.fundamentalanalysis.app.data.portfolio

import com.fundamentalanalysis.app.data.local.PortfolioAccountEntity
import com.fundamentalanalysis.app.data.local.PortfolioHoldingEntity
import com.fundamentalanalysis.app.domain.model.CostSource
import com.fundamentalanalysis.app.domain.model.HoldingsUpdate
import kotlin.math.abs

/** What applying a holdings workbook writes, per broker account. */
data class HoldingsPlan(
    val accounts: List<PortfolioAccountEntity>,
    val holdings: List<PortfolioHoldingEntity>,
    val updates: List<HoldingsUpdate>,
)

/**
 * Reconciles a holdings workbook with what's stored. For each supported broker in the file, its
 * holdings are replaced by the file's: quantity, average cost (the file's cost already reflects
 * splits, bonuses, mergers and demergers) and price. Rows are matched by symbol ignoring NSE series
 * suffixes, keeping the stored ISIN, kind and sector. Trades, actions, notes and charges are left
 * alone. A broker with nothing stored yet gets a new account (no client id).
 */
object HoldingsReconciler {
    private const val EXAMPLES = 6

    fun plan(
        workbook: HoldingsWorkbook,
        accounts: List<PortfolioAccountEntity>,
        holdings: List<PortfolioHoldingEntity>,
        now: Long,
    ): HoldingsPlan {
        val outAccounts = mutableListOf<PortfolioAccountEntity>()
        val outHoldings = mutableListOf<PortfolioHoldingEntity>()
        val updates = mutableListOf<HoldingsUpdate>()
        workbook.brokers.forEach { wb ->
            val existing = accounts.firstOrNull { it.broker == wb.broker }
            val account = existing?.copy(holdingsAsOf = wb.asOf ?: existing.holdingsAsOf)
                ?: PortfolioAccountEntity("holdings-file:${wb.broker}", wb.broker, "", wb.asOf, now)
            outAccounts += account
            val stored = holdings.filter { it.accountId == account.id }.groupBy { HoldingsWorkbookParser.baseSymbol(it.symbol) }

            val qtyChanged = mutableListOf<String>()
            val costChanged = mutableListOf<String>()
            val added = mutableListOf<String>()
            // Same security listed twice in a sheet: merge, weighting cost by quantity.
            val rows = wb.rows.groupBy { HoldingsWorkbookParser.baseSymbol(it.symbol) }.map { (base, rs) ->
                val qty = rs.sumOf { it.qty }
                Triple(base, rs.first().copy(qty = qty, avgCost = if (qty > 0) rs.sumOf { it.qty * it.avgCost } / qty else 0.0), rs.size)
            }
            rows.forEach { (base, row, _) ->
                val old = stored[base]?.firstOrNull()
                val cost = row.avgCost > 0
                outHoldings += PortfolioHoldingEntity(
                    accountId = account.id,
                    symbol = base,
                    isin = old?.isin.orEmpty(),
                    kind = old?.kind ?: if (row.isFund) "mf" else "stock",
                    sector = old?.sector.orEmpty(),
                    qty = row.qty,
                    avgPrice = if (cost) row.avgCost else 0.0,
                    close = row.price,
                    costedQty = if (cost) null else 0.0,
                    costSource = if (cost) CostSource.FILE.name else CostSource.UNKNOWN.name,
                )
                if (old == null) {
                    added += base
                    return@forEach
                }
                // Fund units are rounded differently by different exports (801.87 vs 801.865).
                if (abs(old.qty - row.qty) > maxOf(0.01, row.qty * 1e-4)) qtyChanged += "$base ${fmt(old.qty)} → ${fmt(row.qty)}"
                val before = storedCost(old)
                if (cost && (before == null || abs(before / row.avgCost - 1) > 0.001)) {
                    costChanged += "$base ${before?.let { "₹" + money(it) } ?: "no cost"} → ₹${money(row.avgCost)}"
                }
            }
            val kept = rows.map { it.first }.toSet()
            val removed = stored.keys.filter { it !in kept }.sorted()
            updates += HoldingsUpdate(
                broker = wb.broker,
                asOf = wb.asOf,
                holdings = rows.size,
                newAccount = existing == null,
                withoutCost = rows.count { it.second.avgCost <= 0 },
                added = added.sorted(),
                removed = removed,
                qtyChanged = qtyChanged,
                costChanged = costChanged,
            )
        }
        return HoldingsPlan(outAccounts, outHoldings, updates)
    }

    /** The cost the app used for a stored row (null when it had none). */
    private fun storedCost(h: PortfolioHoldingEntity): Double? = when {
        h.avgPrice <= 0 -> null
        h.costSource == CostSource.UNKNOWN.name -> null
        else -> h.avgPrice
    }

    private fun fmt(q: Double) = if (q % 1.0 == 0.0) q.toLong().toString() else "%.3f".format(java.util.Locale.US, q).trimEnd('0')
    private fun money(x: Double) = "%,.2f".format(java.util.Locale("en", "IN"), x)

    /** Up to [EXAMPLES] items, then "and N more". */
    fun examples(items: List<String>): String =
        items.take(EXAMPLES).joinToString(", ") + if (items.size > EXAMPLES) " and ${items.size - EXAMPLES} more" else ""
}
