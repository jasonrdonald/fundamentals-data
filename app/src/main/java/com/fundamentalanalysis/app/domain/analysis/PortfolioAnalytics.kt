package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.Allocation
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.IndexComparison
import com.fundamentalanalysis.app.domain.model.Lot
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.PortfolioData
import com.fundamentalanalysis.app.domain.model.PortfolioSummary
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.model.PriceSource
import com.fundamentalanalysis.app.domain.model.TradeSide
import com.fundamentalanalysis.app.domain.model.YearActivity

object PortfolioAnalytics {

    const val FUNDS_SECTOR = "Funds & ETFs"
    private const val UNCLASSIFIED = "Unclassified"
    private val INDEX_NAMES = mapOf("NIFTY 50" to "Nifty 50", "NIFTY NEXT 50" to "Nifty Next 50", "NIFTY MIDCAP 150" to "Nifty Midcap 150")
    val COMPARE_PERIODS = listOf(Period.M1, Period.M3, Period.M6, Period.Y1)

    /**
     * Combines the brokers' holdings snapshots into one position per security (by ISIN), valued at
     * the dataset's latest close for tracked stocks (fresher than the snapshot) and the broker's
     * close otherwise. Sector comes from NSE's classification when tracked, else the broker's.
     */
    fun summarize(data: PortfolioData, tracked: Map<String, AnalyzedStock>): PortfolioSummary {
        val brokerOf = data.accounts.associate { it.id to it.broker }
        val asOf = data.accounts.associate { it.id to it.holdingsAsOf }
        val notes = data.notes.filter { it.comment.isNotBlank() }.groupBy { it.symbol }
        val positions = data.holdings.filter { it.qty > 0 }
            .groupBy { it.isin.ifBlank { it.symbol } }
            .map { (_, rows) ->
                val first = rows.first()
                val stock = tracked[first.symbol]?.takeIf { first.kind == HoldingKind.STOCK }
                val qty = rows.sumOf { it.qty }
                val invested = rows.sumOf { it.qty * it.avgPrice }
                val datasetPrice = stock?.metrics?.lastClose
                val price = datasetPrice ?: rows.maxBy { asOf[it.accountId] ?: java.time.LocalDate.MIN }.close
                val change = stock?.metrics?.change1d
                Position(
                    symbol = first.symbol,
                    isin = first.isin,
                    kind = first.kind,
                    sector = when {
                        stock != null -> stock.stock.industry
                        first.kind == HoldingKind.FUND -> FUNDS_SECTOR
                        else -> rows.firstNotNullOfOrNull { it.sector.ifBlank { null } }?.let(::normalizeSector) ?: UNCLASSIFIED
                    },
                    lots = rows.map { Lot(brokerOf[it.accountId] ?: "?", it.qty, it.avgPrice) }.sortedByDescending { it.qty },
                    qty = qty,
                    invested = invested,
                    price = price,
                    priceDate = if (datasetPrice != null) stock.metrics.lastDate else rows.mapNotNull { asOf[it.accountId] }.maxOrNull(),
                    priceSource = if (datasetPrice != null) PriceSource.DATASET else PriceSource.BROKER,
                    value = qty * price,
                    dayChange = change?.let { c -> qty * price * c / (1 + c) },
                    tracked = stock,
                    note = notes[first.symbol]?.joinToString(" · ") { it.comment },
                )
            }
            .sortedByDescending { it.value }
        val total = positions.sumOf { it.value }

        fun allocation(groups: Map<String, List<Position>>) = groups
            .map { (label, ps) -> Allocation(label, ps.sumOf { it.value }, if (total > 0) ps.sumOf { it.value } / total else 0.0, ps.size) }
            .sortedByDescending { it.value }

        val lotValue = positions.flatMap { p -> p.lots.map { it.broker to it.qty * p.price } }
        val byBroker = lotValue.groupBy({ it.first }, { it.second }).map { (b, values) ->
            Allocation(b, values.sum(), if (total > 0) values.sum() / total else 0.0, positions.count { p -> p.lots.any { it.broker == b } })
        }.sortedByDescending { it.value }

        val trackedPositions = positions.filter { it.tracked != null }
        val vsIndex = COMPARE_PERIODS.map { period ->
            val measured = trackedPositions.mapNotNull { p -> p.tracked!!.metrics.returns[period]?.let { p to it } }
            val weight = measured.sumOf { it.first.value }
            IndexComparison(
                period = period,
                measured = measured.size,
                beat = measured.count { (p, _) -> (p.tracked!!.metrics.excess(period) ?: 0.0) > 0 },
                weightedReturn = if (weight > 0) measured.sumOf { (p, r) -> p.value * r } / weight else null,
            )
        }

        val chargesByYear = data.fees.groupBy { it.date.year }.mapValues { (_, f) -> f.sumOf { it.amount } }
        val tradesByYear = data.trades.groupBy { it.date.year }
        val activity = (tradesByYear.keys + chargesByYear.keys).distinct().sortedDescending().map { y ->
            val ts = tradesByYear[y].orEmpty()
            YearActivity(
                year = y,
                bought = ts.filter { it.side == TradeSide.BUY }.sumOf { it.value },
                sold = ts.filter { it.side == TradeSide.SELL }.sumOf { it.value },
                trades = ts.size,
                charges = chargesByYear[y] ?: 0.0,
            )
        }

        return PortfolioSummary(
            accounts = data.accounts,
            positions = positions,
            invested = positions.sumOf { it.invested },
            value = total,
            dayChange = trackedPositions.mapNotNull { it.dayChange }.takeIf { it.isNotEmpty() }?.sum(),
            bySector = allocation(positions.groupBy { it.sector }),
            byBroker = byBroker,
            byIndex = allocation(positions.groupBy { p ->
                p.tracked?.stock?.indexSymbol?.let { INDEX_NAMES[it] ?: it } ?: if (p.kind == HoldingKind.FUND) FUNDS_SECTOR else "Not in a tracked index"
            }),
            vsIndex = vsIndex,
            activity = activity,
            trades = data.trades,
            actions = data.actions,
            lastImport = data.imports.maxByOrNull { it.atMillis },
            pricedAsOf = positions.mapNotNull { it.priceDate }.maxOrNull(),
        )
    }

    private val ACRONYMS = setOf("IT", "FMCG", "ETF", "ETFS", "PSU", "NBFC", "REIT", "INVIT", "AMC")

    /** Brokers sometimes report sectors in capitals ("FINANCIAL SERVICES"); match NSE's "Financial Services". */
    internal fun normalizeSector(raw: String): String {
        val s = raw.trim()
        if (s != s.uppercase() || s.none { it.isLetter() }) return s
        return s.split(" ").joinToString(" ") { w ->
            when {
                w.uppercase() in ACRONYMS -> w.uppercase()
                w == "&" || w.isEmpty() -> w
                w.lowercase() in setOf("and", "of") -> w.lowercase()
                else -> w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }
}
