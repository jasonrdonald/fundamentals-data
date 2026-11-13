package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.IndexAnalytics
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.MonthlyMove
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.SessionSummary
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure, deterministic analytics over an index's daily and monthly bars. */
object IndexAnalyticsCalculator {

    private const val TRADING_DAYS = 252

    fun calculate(index: MarketIndex): IndexAnalytics {
        val daily = index.daily
        val closes = daily.map { it.close }
        val last = closes.lastOrNull()
        val window52 = daily.takeLast(TRADING_DAYS)
        val high52 = window52.maxOfOrNull { it.high }
        val low52 = window52.minOfOrNull { it.low }
        val dma50 = sma(closes, 50)
        val dma200 = sma(closes, 200)
        val volumes = daily.mapNotNull { it.volume?.toDouble()?.takeIf { v -> v > 0 } }
        val lastWithVolume = daily.lastOrNull { (it.volume ?: 0) > 0 }

        return IndexAnalytics(
            latest = latestSession(daily),
            returns = Period.entries.associateWith { periodReturn(index, it) },
            rsi14 = rsi(closes, 14),
            dma50 = dma50,
            dma200 = dma200,
            vsDma50 = relative(last, dma50),
            vsDma200 = relative(last, dma200),
            high52w = high52,
            low52w = low52,
            fromHigh52w = relative(last, high52),
            fromLow52w = relative(last, low52),
            latestVolume = lastWithVolume?.volume,
            latestVolumeDate = lastWithVolume?.date,
            avgVolume20 = volumes.takeLast(20).averageOrNull(),
            relativeVolume = lastWithVolume?.volume?.let { v ->
                volumes.takeLast(20).averageOrNull()?.let { avg -> v / avg }
            },
            volumeTrend = ratio(volumes.takeLast(63).averageOrNull(), volumes.takeLast(TRADING_DAYS).averageOrNull()),
            volatility1m = annualisedVolatility(closes.takeLast(22)),
            volatility1y = annualisedVolatility(closes.takeLast(TRADING_DAYS + 1)),
            monthly = monthlyMoves(index.monthly, daily.lastOrNull()?.date),
        )
    }

    fun latestSession(daily: List<Bar>): SessionSummary? {
        val bar = daily.lastOrNull() ?: return null
        return SessionSummary(
            date = bar.date, open = bar.open, high = bar.high, low = bar.low, close = bar.close,
            previousClose = daily.getOrNull(daily.size - 2)?.close, volume = bar.volume,
        )
    }

    fun periodReturn(index: MarketIndex, period: Period): Double? {
        val closes = index.daily.map { it.close }
        val last = closes.lastOrNull() ?: return null
        return when (period) {
            Period.YTD -> {
                val year = index.daily.last().date.year
                val base = index.daily.lastOrNull { it.date.year < year }?.close
                relative(last, base)
            }
            Period.Y3 -> cagr(index.monthly, last, years = 3)
            Period.Y5 -> cagr(index.monthly, last, years = 5)
            else -> {
                val n = period.sessions ?: return null
                relative(last, closes.getOrNull(closes.size - 1 - n))
            }
        }
    }

    /** CAGR from the month-end close `years` years before the latest month. */
    internal fun cagr(monthly: List<Bar>, last: Double, years: Int): Double? {
        val base = monthly.getOrNull(monthly.size - 1 - years * 12)?.close ?: return null
        if (base <= 0 || last <= 0) return null
        return (last / base).pow(1.0 / years) - 1
    }

    fun sma(values: List<Double>, n: Int): Double? =
        if (values.size < n) null else values.takeLast(n).average()

    /** Moving average aligned to [values]; null until [n] values are available. */
    fun rollingSma(values: List<Double>, n: Int): List<Double?> {
        var sum = 0.0
        return values.mapIndexed { i, v ->
            sum += v
            if (i >= n) sum -= values[i - n]
            if (i >= n - 1) sum / n else null
        }
    }

    /** Wilder's RSI. Needs at least [n] + 1 closes. */
    fun rsi(closes: List<Double>, n: Int = 14): Double? {
        if (closes.size <= n) return null
        val changes = closes.zipWithNext { a, b -> b - a }
        var gain = changes.take(n).sumOf { maxOf(it, 0.0) } / n
        var loss = changes.take(n).sumOf { maxOf(-it, 0.0) } / n
        for (change in changes.drop(n)) {
            gain = (gain * (n - 1) + maxOf(change, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-change, 0.0)) / n
        }
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
        return 100 - 100 / (1 + gain / loss)
    }

    fun annualisedVolatility(closes: List<Double>): Double? {
        if (closes.size < 3) return null
        val logReturns = closes.zipWithNext { a, b -> if (a > 0 && b > 0) ln(b / a) else 0.0 }
        val mean = logReturns.average()
        val variance = logReturns.sumOf { (it - mean) * (it - mean) } / (logReturns.size - 1)
        return sqrt(variance) * sqrt(TRADING_DAYS.toDouble())
    }

    fun monthlyMoves(monthly: List<Bar>, latestSession: LocalDate?): List<MonthlyMove> =
        monthly.mapIndexed { i, bar ->
            val previous = monthly.getOrNull(i - 1)
            MonthlyMove(
                month = bar.date,
                open = bar.open,
                high = bar.high,
                low = bar.low,
                close = bar.close,
                volume = bar.volume,
                changePct = relative(bar.close, previous?.close),
                openToClosePct = relative(bar.close, bar.open),
                isMonthToDate = i == monthly.lastIndex && latestSession != null &&
                    latestSession.withDayOfMonth(1) == bar.date && latestSession < lastWeekdayOf(bar.date),
            )
        }

    private fun lastWeekdayOf(monthStart: LocalDate): LocalDate {
        var d = monthStart.withDayOfMonth(monthStart.lengthOfMonth())
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    private fun relative(value: Double?, base: Double?): Double? =
        if (value == null || base == null || base == 0.0) null else value / base - 1

    private fun ratio(a: Double?, b: Double?): Double? =
        if (a == null || b == null || b == 0.0) null else a / b

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
