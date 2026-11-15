package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.SectorStat
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockFactor
import com.fundamentalanalysis.app.domain.model.StockFormat
import com.fundamentalanalysis.app.domain.model.StockMetric
import com.fundamentalanalysis.app.domain.model.StockMetrics
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.pow

/** Periods used to compare stocks with the Nifty 50. */
val STOCK_PERIODS = listOf(Period.M1, Period.M3, Period.M6, Period.YTD, Period.Y1, Period.Y3)

object StockAnalyticsCalculator {

    fun calculate(stock: Stock, benchmark: MarketIndex?): StockMetrics {
        val closes = stock.daily.map { it.close }
        val last = closes.lastOrNull()
        val window52 = closes.takeLast(252)
        val (beta, alpha) = betaAlpha(stock.monthly, benchmark?.monthly.orEmpty())
        val latestA = stock.annual.lastOrNull()
        val prevA = stock.annual.getOrNull(stock.annual.size - 2)
        return StockMetrics(
            lastClose = last,
            lastDate = stock.daily.lastOrNull()?.date,
            change1d = relative(last, closes.getOrNull(closes.size - 2)),
            returns = STOCK_PERIODS.associateWith { periodReturn(stock.daily, stock.monthly, it) },
            benchmarkReturns = STOCK_PERIODS.associateWith { p ->
                benchmark?.let { periodReturn(it.daily, it.monthly, p) }
            },
            momentum12m1m = if (closes.size > 252) relative(closes[closes.size - 22], closes[closes.size - 253]) else null,
            rsi14 = IndexAnalyticsCalculator.rsi(closes, 14),
            vsDma200 = relative(last, IndexAnalyticsCalculator.sma(closes, 200)),
            fromHigh52w = relative(last, window52.maxOrNull()),
            volatility1y = IndexAnalyticsCalculator.annualisedVolatility(closes.takeLast(253)),
            beta = beta,
            alpha = alpha,
            pe = stock.pe,
            pb = stock.pb,
            earningsYield = stock.pe?.takeIf { it > 0 }?.let { 1 / it },
            revenueGrowth = growth(latestA?.revenue, prevA?.revenue),
            revenueCagr = financialCagr(stock),
            epsGrowth = growth(latestA?.eps, prevA?.eps),
            quarterlyRevenueYoY = quarterlyYoY(stock),
            roe = ratio(latestA?.netIncome, latestA?.equity?.takeIf { it > 0 }),
            netMargin = ratio(latestA?.netIncome, latestA?.revenue?.takeIf { it > 0 }),
            debtToEquity = ratio(latestA?.debt, latestA?.equity?.takeIf { it > 0 }),
            netProfitCr = latestA?.netIncome,
            profitGrowth = growth(latestA?.netIncome, prevA?.netIncome),
        )
    }

    /** Returns from daily closes for short windows and from month-end closes for multi-year CAGR. */
    fun periodReturn(daily: List<Bar>, monthly: List<Bar>, period: Period): Double? {
        val closes = daily.map { it.close }
        val last = closes.lastOrNull() ?: return null
        return when (period) {
            Period.YTD -> relative(last, daily.lastOrNull { it.date.year < daily.last().date.year }?.close)
            Period.Y3 -> IndexAnalyticsCalculator.cagr(monthly, last, 3)
            Period.Y5 -> IndexAnalyticsCalculator.cagr(monthly, last, 5)
            else -> period.sessions?.let { n -> relative(last, closes.getOrNull(closes.size - 1 - n)) }
        }
    }

    /**
     * Beta and annualised Jensen's alpha from up to 36 paired month-on-month returns
     * (stock vs benchmark, matched by month). Needs at least 12 pairs.
     */
    fun betaAlpha(stockMonthly: List<Bar>, benchMonthly: List<Bar>, months: Int = 36): Pair<Double?, Double?> {
        fun returns(bars: List<Bar>) = bars.zipWithNext { a, b -> b.date to (b.close / a.close - 1) }.toMap()
        val s = returns(stockMonthly)
        val b = returns(benchMonthly)
        val pairs = s.keys.intersect(b.keys).sorted().takeLast(months).map { s.getValue(it) to b.getValue(it) }
        if (pairs.size < 12) return null to null
        val ms = pairs.map { it.first }.average()
        val mb = pairs.map { it.second }.average()
        val cov = pairs.sumOf { (x, y) -> (x - ms) * (y - mb) }
        val varB = pairs.sumOf { (_, y) -> (y - mb) * (y - mb) }
        if (varB == 0.0) return null to null
        val beta = cov / varB
        return beta to (ms - beta * mb) * 12
    }

    private fun financialCagr(stock: Stock): Double? {
        val first = stock.annual.firstOrNull { (it.revenue ?: 0.0) > 0 } ?: return null
        val last = stock.annual.lastOrNull { (it.revenue ?: 0.0) > 0 } ?: return null
        val years = ChronoUnit.DAYS.between(first.endDate, last.endDate) / 365.25
        if (years < 1.5) return null
        return (last.revenue!! / first.revenue!!).pow(1 / years) - 1
    }

    private fun quarterlyYoY(stock: Stock): Double? {
        val q = stock.quarterly
        val latest = q.lastOrNull() ?: return null
        val yearAgo = q.firstOrNull { ChronoUnit.DAYS.between(it.endDate, latest.endDate) in 350..380 } ?: return null
        return growth(latest.revenue, yearAgo.revenue)
    }

    /** Growth needs a positive base, otherwise the percentage is meaningless. */
    private fun growth(now: Double?, before: Double?): Double? =
        if (now == null || before == null || before <= 0) null else now / before - 1

    private fun relative(value: Double?, base: Double?): Double? =
        if (value == null || base == null || base == 0.0) null else value / base - 1

    private fun ratio(a: Double?, b: Double?): Double? = if (a == null || b == null || b == 0.0) null else a / b
}

/** How the stocks in one NSE industry did against the benchmark over a period. */
data class SectorPerformance(
    val sector: String,
    /** Best first. */
    val stocks: List<AnalyzedStock>,
    val outperformers: Int,
    val measured: Int,
    /** Equal-weighted average return of the sector's stocks. */
    val averageReturn: Double?,
    val benchmarkReturn: Double?,
) {
    val outperformShare: Double get() = if (measured == 0) 0.0 else outperformers.toDouble() / measured
}

data class RankedStock(val rank: Int, val stock: AnalyzedStock, val value: Double)

object StockReports {

    /**
     * Sectors of [stocks] for [period]. When [precomputed] aggregates (from the dataset pipeline)
     * are given, their counts and averages are used; otherwise they are computed here.
     */
    fun sectorPerformance(
        stocks: List<AnalyzedStock>,
        period: Period,
        precomputed: List<SectorStat> = emptyList(),
    ): List<SectorPerformance> {
        val stats = precomputed.associateBy { it.sector }
        return stocks.groupBy { it.stock.industry }.map { (sector, members) ->
            val measured = members.filter { it.metrics.returns[period] != null }
            val bench = members.firstNotNullOfOrNull { it.metrics.benchmarkReturns[period] }
            val stat = stats[sector]
            SectorPerformance(
                sector = sector,
                stocks = members.sortedByDescending { it.metrics.returns[period] ?: Double.NEGATIVE_INFINITY },
                outperformers = stat?.outperformers ?: measured.count { (it.metrics.excess(period) ?: 0.0) > 0 },
                measured = stat?.measured ?: measured.size,
                averageReturn = if (stat != null) stat.averageReturn
                else measured.mapNotNull { it.metrics.returns[period] }.takeIf { it.isNotEmpty() }?.average(),
                benchmarkReturn = stat?.benchmarkReturn ?: bench,
            )
        }.sortedWith(compareByDescending<SectorPerformance> { it.outperformShare }.thenByDescending { it.averageReturn ?: Double.NEGATIVE_INFINITY })
    }

    /**
     * Top (best) or bottom (worst) [count] stocks for a factor, using the factor's primary measure
     * and its direction. Stocks without a value are left out.
     */
    fun rank(stocks: List<AnalyzedStock>, factor: StockFactor, count: Int, top: Boolean): List<RankedStock> {
        val metric = factor.primary
        val withValues = stocks.mapNotNull { s -> s.valueOf(metric)?.let { s to it } }
        val bestFirst = if (metric.higherIsBetter) withValues.sortedByDescending { it.second } else withValues.sortedBy { it.second }
        val ranked = bestFirst.mapIndexed { i, (s, v) -> RankedStock(i + 1, s, v) }
        return if (top) ranked.take(count) else ranked.takeLast(count).reversed()
    }

    fun rankingCsv(rows: List<RankedStock>, factor: StockFactor): String = buildString {
        val metrics = listOf(factor.primary) + factor.supporting
        appendLine((listOf("rank", "symbol", "name", "sector") + metrics.map { it.label }).joinToString(",") { csv(it) })
        rows.forEach { r ->
            appendLine(
                (listOf(r.rank.toString(), r.stock.symbol, r.stock.stock.name, r.stock.stock.industry) +
                    metrics.map { m -> r.stock.valueOf(m)?.let { raw(m, it) }.orEmpty() }).joinToString(",") { csv(it) },
            )
        }
    }

    fun sectorCsv(sectors: List<SectorPerformance>, period: Period): String = buildString {
        appendLine("sector,symbol,name,return_${period.label},nifty_${period.label},excess,beat_nifty")
        sectors.forEach { s ->
            s.stocks.forEach { st ->
                val r = st.metrics.returns[period]
                val e = st.metrics.excess(period)
                appendLine(
                    listOf(s.sector, st.symbol, st.stock.name, r.pct(), s.benchmarkReturn.pct(), e.pct(), if (e == null) "" else (e > 0).toString())
                        .joinToString(",") { csv(it) },
                )
            }
        }
    }

    private fun Double?.pct(): String = this?.let { String.format(Locale.US, "%.2f", it * 100) }.orEmpty()

    private fun raw(m: StockMetric, v: Double): String = when (m.format) {
        StockFormat.SIGNED_PERCENT, StockFormat.PERCENT, StockFormat.POINTS -> String.format(Locale.US, "%.2f", v * 100)
        else -> String.format(Locale.US, "%.2f", v)
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

/** Rebases two close series to 100 at their first shared date (for "stock vs Nifty" charts). */
fun rebaseTogether(a: List<Bar>, b: List<Bar>): Triple<List<LocalDate>, List<Double?>, List<Double?>> {
    val bByDate = b.associate { it.date to it.close }
    val shared = a.filter { it.date in bByDate }
    if (shared.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
    val baseA = shared.first().close
    val baseB = bByDate.getValue(shared.first().date)
    return Triple(
        shared.map { it.date },
        shared.map { it.close / baseA * 100 },
        shared.map { bByDate.getValue(it.date) / baseB * 100 },
    )
}
