package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.Allocation
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.GroupSummary
import com.fundamentalanalysis.app.domain.model.IndexComparison
import com.fundamentalanalysis.app.domain.model.IndexGroup
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.PurchaseComparison
import com.fundamentalanalysis.app.domain.model.SincePurchase
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
    val COMPARE_PERIODS = listOf(Period.M1, Period.M3, Period.M6, Period.Y1)

    /**
     * Combines the brokers' holdings snapshots into one position per security (by ISIN), valued at
     * the dataset's latest close for tracked stocks (fresher than the snapshot) and the broker's
     * close otherwise. Sector comes from NSE's classification when tracked, else the broker's.
     */
    fun summarize(data: PortfolioData, tracked: Map<String, AnalyzedStock>, benchmark: MarketIndex? = null): PortfolioSummary {
        val brokerOf = data.accounts.associate { it.id to it.broker }
        val asOf = data.accounts.associate { it.id to it.holdingsAsOf }
        val notes = data.notes.filter { it.comment.isNotBlank() }.groupBy { it.symbol }
        val positions = data.holdings.filter { it.qty > 0 }
            .groupBy { it.isin.ifBlank { it.symbol } }
            .map { (_, rows) ->
                val first = rows.first()
                val stock = tracked[first.symbol]?.takeIf { first.kind == HoldingKind.STOCK }
                val qty = rows.sumOf { it.qty }
                // Only shares with a known cost count towards cost and P&L (see BrokerHolding.costSource).
                val costedQty = rows.sumOf { it.costedQty }
                val invested = rows.sumOf { it.costedQty * it.avgPrice }
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
                    lots = rows.map { Lot(brokerOf[it.accountId] ?: "?", it.qty, it.avgPrice, it.costedQty, it.costSource) }.sortedByDescending { it.qty },
                    qty = qty,
                    invested = invested,
                    price = price,
                    priceDate = if (datasetPrice != null) stock.metrics.lastDate else rows.mapNotNull { asOf[it.accountId] }.maxOrNull(),
                    priceSource = if (datasetPrice != null) PriceSource.DATASET else PriceSource.BROKER,
                    value = qty * price,
                    dayChange = change?.let { c -> qty * price * c / (1 + c) },
                    tracked = stock,
                    note = notes[first.symbol]?.joinToString(" · ") { it.comment },
                    costedQty = costedQty,
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
        val vsIndex = compare(trackedPositions, withIndexReturn = false)
        val groups = positions.groupBy { IndexGroup.of(it) }.toSortedMap().map { (group, ps) ->
            val value = ps.sumOf { it.value }
            GroupSummary(
                group = group,
                positions = ps,
                value = value,
                invested = ps.sumOf { it.invested },
                share = if (total > 0) value / total else 0.0,
                dayChange = ps.mapNotNull { it.dayChange }.takeIf { it.isNotEmpty() }?.sum(),
                vsIndex = if (group.tracked) compare(ps, withIndexReturn = true) else emptyList(),
                sincePurchase = if (group == IndexGroup.OTHER && benchmark != null) sincePurchase(ps, data, benchmark) else null,
                costedValue = ps.sumOf { it.costedValue },
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
            byIndex = allocation(positions.groupBy { IndexGroup.of(it).label }),
            vsIndex = vsIndex,
            groups = groups,
            activity = activity,
            trades = data.trades,
            actions = data.actions,
            lastImport = data.imports.maxByOrNull { it.atMillis },
            pricedAsOf = positions.mapNotNull { it.priceDate }.maxOrNull(),
            costedValue = positions.sumOf { it.costedValue },
        )
    }

    /**
     * How many of [positions] (tracked ones only) beat their own index per period, and their
     * value-weighted return. [withIndexReturn] adds the index's own return (one-index groups).
     */
    private fun compare(positions: List<Position>, withIndexReturn: Boolean): List<IndexComparison> = COMPARE_PERIODS.map { period ->
        val measured = positions.mapNotNull { p -> p.tracked?.metrics?.returns?.get(period)?.let { p to it } }
        val weight = measured.sumOf { it.first.value }
        IndexComparison(
            period = period,
            measured = measured.size,
            beat = measured.count { (p, _) -> (p.tracked!!.metrics.excess(period) ?: 0.0) > 0 },
            weightedReturn = if (weight > 0) measured.sumOf { (p, r) -> p.value * r } / weight else null,
            indexReturn = if (withIndexReturn) positions.firstNotNullOfOrNull { it.tracked?.metrics?.benchmarkReturns?.get(period) } else null,
        )
    }

    /**
     * How many tracked [positions] beat one chosen index per period (instead of each stock's own
     * index), with their value-weighted return and the index's return. [indexReturns] are the
     * chosen index's returns, as shown on its index page.
     */
    fun compareWith(positions: List<Position>, indexReturns: Map<Period, Double?>): List<IndexComparison> = COMPARE_PERIODS.map { period ->
        val measured = positions.mapNotNull { p -> p.tracked?.metrics?.returns?.get(period)?.let { p to it } }
        val weight = measured.sumOf { it.first.value }
        val index = indexReturns[period]
        IndexComparison(
            period = period,
            measured = if (index == null) 0 else measured.size,
            beat = if (index == null) 0 else measured.count { (_, r) -> r > index },
            weightedReturn = if (weight > 0) measured.sumOf { (p, r) -> p.value * r } / weight else null,
            indexReturn = index,
        )
    }

    /**
     * Compares each holding with [benchmark] over the time since it was bought. The purchase date
     * is the value-weighted average of the holding's buy trades (and priced allotments, e.g.
     * IPOs) across the brokers that hold it; the benchmark's close on that date comes from daily
     * sessions when available, otherwise from month-end closes.
     */
    internal fun sincePurchase(positions: List<Position>, data: PortfolioData, benchmark: MarketIndex): SincePurchase {
        val latest = benchmark.daily.lastOrNull()?.close ?: benchmark.monthly.lastOrNull()?.close
        val buys = data.trades.filter { it.side == TradeSide.BUY }.map { Triple(it.isin.ifBlank { it.symbol }, it.date, it.value) } +
            data.actions.filter { it.incoming && (it.price ?: 0.0) > 0 }
                .map { a -> Triple(data.holdings.firstOrNull { it.symbol == a.symbol }?.isin?.ifBlank { null } ?: a.symbol, a.date, a.qty * a.price!!) }
        val byKey = buys.groupBy({ it.first }, { it.second to it.third })
        val rows = mutableListOf<PurchaseComparison>()
        val missing = mutableListOf<Position>()
        positions.forEach { p ->
            val lots = byKey[p.isin.ifBlank { p.symbol }].orEmpty().ifEmpty { byKey[p.symbol].orEmpty() }
            val amount = lots.sumOf { it.second }
            val date = if (amount > 0) java.time.LocalDate.ofEpochDay(Math.round(lots.sumOf { it.first.toEpochDay() * it.second } / amount)) else null
            val base = date?.let { closeOn(benchmark, it) }
            val ret = p.pnlPct
            if (date == null || base == null || latest == null || ret == null) missing += p
            else rows += PurchaseComparison(p, date, ret, latest / base - 1)
        }
        return SincePurchase(benchmark.name, rows.sortedByDescending { it.excess }, missing)
    }

    /** The benchmark's close on [date]: last daily session on or before it, else the previous month-end. */
    internal fun closeOn(index: MarketIndex, date: java.time.LocalDate): Double? {
        val daily = index.daily
        if (daily.isNotEmpty() && !date.isBefore(daily.first().date)) return daily.lastOrNull { !it.date.isAfter(date) }?.close
        // Monthly bars are dated the 1st; the last close before [date] is the previous month's.
        return index.monthly.lastOrNull { it.date.isBefore(date.withDayOfMonth(1)) }?.close
            ?: index.monthly.firstOrNull { !it.date.isAfter(date) }?.close
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
