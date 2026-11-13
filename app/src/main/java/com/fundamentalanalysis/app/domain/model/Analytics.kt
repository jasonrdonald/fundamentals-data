package com.fundamentalanalysis.app.domain.model

import java.time.LocalDate

/** Trailing performance windows. Daily windows count trading sessions. */
enum class Period(val label: String, val sessions: Int?) {
    D1("1D", 1),
    W1("1W", 5),
    M1("1M", 21),
    M3("3M", 63),
    M6("6M", 126),
    YTD("YTD", null),
    Y1("1Y", 252),
    Y3("3Y CAGR", null),
    Y5("5Y CAGR", null),
}

/** The latest completed session ("previous day" once the next session opens). */
data class SessionSummary(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val previousClose: Double?,
    val volume: Long?,
) {
    val change: Double? get() = previousClose?.let { close - it }
    val changePct: Double? get() = previousClose?.takeIf { it != 0.0 }?.let { close / it - 1 }
    /** Close relative to the day's open: positive means buyers held control intraday. */
    val intradayPct: Double? get() = open.takeIf { it != 0.0 }?.let { close / it - 1 }
}

data class MonthlyMove(
    val month: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
    /** Close vs previous month's close (month-on-month). */
    val changePct: Double?,
    /** Close vs this month's open. */
    val openToClosePct: Double?,
    val isMonthToDate: Boolean,
)

data class IndexAnalytics(
    val latest: SessionSummary?,
    val returns: Map<Period, Double?>,
    // Momentum
    val rsi14: Double?,
    val dma50: Double?,
    val dma200: Double?,
    val vsDma50: Double?,
    val vsDma200: Double?,
    val high52w: Double?,
    val low52w: Double?,
    val fromHigh52w: Double?,
    val fromLow52w: Double?,
    // Volume. Some sources leave the newest session's volume blank, so "latest" is the most
    // recent session that reported one.
    val latestVolume: Long?,
    val latestVolumeDate: LocalDate?,
    val avgVolume20: Double?,
    val relativeVolume: Double?,
    val volumeTrend: Double?,
    // Risk
    val volatility1m: Double?,
    val volatility1y: Double?,
    val monthly: List<MonthlyMove>,
) {
    val trend: Trend
        get() = when {
            vsDma50 == null || vsDma200 == null -> Trend.UNKNOWN
            vsDma50 > 0 && vsDma200 > 0 -> Trend.UP
            vsDma50 < 0 && vsDma200 < 0 -> Trend.DOWN
            else -> Trend.MIXED
        }
}

enum class Trend(val label: String) {
    UP("Uptrend"), DOWN("Downtrend"), MIXED("Mixed"), UNKNOWN("—")
}

enum class MetricFormat { PERCENT, SIGNED_PERCENT, NUMBER, MULTIPLE }

/** Every measure the app can rank, compare or export. */
enum class Metric(
    val label: String,
    val format: MetricFormat,
    val higherIsBetter: Boolean,
    val description: String,
    val group: String,
) {
    RETURN_1D("1D return", MetricFormat.SIGNED_PERCENT, true, "Change in the latest session", "Performance"),
    RETURN_1W("1W return", MetricFormat.SIGNED_PERCENT, true, "Change over 5 sessions", "Performance"),
    RETURN_1M("1M return", MetricFormat.SIGNED_PERCENT, true, "Change over 21 sessions", "Performance"),
    RETURN_3M("3M return", MetricFormat.SIGNED_PERCENT, true, "Change over 63 sessions", "Performance"),
    RETURN_6M("6M return", MetricFormat.SIGNED_PERCENT, true, "Change over 126 sessions", "Performance"),
    RETURN_YTD("YTD return", MetricFormat.SIGNED_PERCENT, true, "Change since last year's final close", "Performance"),
    RETURN_1Y("1Y return", MetricFormat.SIGNED_PERCENT, true, "Change over 252 sessions", "Performance"),
    CAGR_3Y("3Y CAGR", MetricFormat.SIGNED_PERCENT, true, "Compound annual growth over 36 months", "Performance"),
    CAGR_5Y("5Y CAGR", MetricFormat.SIGNED_PERCENT, true, "Compound annual growth over 60 months", "Performance"),
    RSI_14("RSI (14)", MetricFormat.NUMBER, true, "Wilder RSI on daily closes; >70 overbought, <30 oversold", "Momentum"),
    VS_DMA_50("vs 50-DMA", MetricFormat.SIGNED_PERCENT, true, "Close relative to the 50-day moving average", "Momentum"),
    VS_DMA_200("vs 200-DMA", MetricFormat.SIGNED_PERCENT, true, "Close relative to the 200-day moving average", "Momentum"),
    FROM_52W_HIGH("From 52W high", MetricFormat.SIGNED_PERCENT, true, "Distance below the 52-week high", "Momentum"),
    REL_VOLUME("Relative volume", MetricFormat.MULTIPLE, true, "Latest session volume / 20-session average", "Volume"),
    VOLUME_TREND("Volume trend", MetricFormat.MULTIPLE, true, "Average volume, last 3 months / last 12 months", "Volume"),
    VOLATILITY_1Y("Volatility (1Y)", MetricFormat.PERCENT, false, "Annualised standard deviation of daily returns", "Risk"),
}

data class AnalyzedIndex(
    val index: MarketIndex,
    val analytics: IndexAnalytics,
    val isWatchlisted: Boolean,
) {
    val symbol: String get() = index.symbol

    fun valueOf(metric: Metric): Double? = with(analytics) {
        when (metric) {
            Metric.RETURN_1D -> returns[Period.D1]
            Metric.RETURN_1W -> returns[Period.W1]
            Metric.RETURN_1M -> returns[Period.M1]
            Metric.RETURN_3M -> returns[Period.M3]
            Metric.RETURN_6M -> returns[Period.M6]
            Metric.RETURN_YTD -> returns[Period.YTD]
            Metric.RETURN_1Y -> returns[Period.Y1]
            Metric.CAGR_3Y -> returns[Period.Y3]
            Metric.CAGR_5Y -> returns[Period.Y5]
            Metric.RSI_14 -> rsi14
            Metric.VS_DMA_50 -> vsDma50
            Metric.VS_DMA_200 -> vsDma200
            Metric.FROM_52W_HIGH -> fromHigh52w
            Metric.REL_VOLUME -> relativeVolume
            Metric.VOLUME_TREND -> volumeTrend
            Metric.VOLATILITY_1Y -> volatility1y
        }
    }
}
