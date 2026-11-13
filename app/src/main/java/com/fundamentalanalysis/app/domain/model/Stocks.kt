package com.fundamentalanalysis.app.domain.model

import java.time.LocalDate

/** A constituent stock of a Nifty index. Amounts are INR crore; prices and EPS are INR. */
data class Stock(
    val symbol: String,
    val name: String,
    /** NSE industry classification (the "sector" used throughout the app). */
    val industry: String,
    val indexSymbol: String,
    val marketCapCr: Double?,
    val pe: Double?,
    val pb: Double?,
    /** Calendar months, oldest first; the last may be month-to-date. */
    val monthly: List<Bar>,
    /** About a year of daily closes (open = high = low = close). */
    val daily: List<Bar>,
    /** Fiscal years, oldest first. */
    val annual: List<FinancialPeriod>,
    /** Quarters, oldest first. */
    val quarterly: List<FinancialPeriod>,
)

data class FinancialPeriod(
    val endDate: LocalDate,
    val revenue: Double?,
    val netIncome: Double?,
    val operatingIncome: Double?,
    val equity: Double?,
    val debt: Double?,
    val eps: Double?,
)

/** Everything the app derives for one stock, relative to its benchmark index. */
data class StockMetrics(
    val lastClose: Double?,
    val lastDate: LocalDate?,
    val change1d: Double?,
    val returns: Map<Period, Double?>,
    /** Same periods for the stock's own index, for outperformance. */
    val benchmarkReturns: Map<Period, Double?>,
    // Momentum
    val momentum12m1m: Double?,
    val rsi14: Double?,
    val vsDma200: Double?,
    val fromHigh52w: Double?,
    // Risk
    val volatility1y: Double?,
    val beta: Double?,
    /** Annualised Jensen's alpha vs the benchmark from up to 36 monthly returns. */
    val alpha: Double?,
    // Valuation
    val pe: Double?,
    val pb: Double?,
    val earningsYield: Double?,
    // Growth
    val revenueGrowth: Double?,
    val revenueCagr: Double?,
    val epsGrowth: Double?,
    val quarterlyRevenueYoY: Double?,
    // Quality & profit
    val roe: Double?,
    val netMargin: Double?,
    val debtToEquity: Double?,
    val netProfitCr: Double?,
    val profitGrowth: Double?,
) {
    fun excess(period: Period): Double? {
        val s = returns[period] ?: return null
        val b = benchmarkReturns[period] ?: return null
        return s - b
    }
}

data class AnalyzedStock(val stock: Stock, val metrics: StockMetrics) {
    val symbol: String get() = stock.symbol
    fun valueOf(metric: StockMetric): Double? = metric.value(metrics)
}

/** POINTS = a difference between two percentages, shown in percentage points (pp). */
enum class StockFormat { SIGNED_PERCENT, PERCENT, NUMBER, MULTIPLE, CRORE, POINTS }

/** Measures available for stock rankings and tables. */
enum class StockMetric(
    val label: String,
    val format: StockFormat,
    val higherIsBetter: Boolean,
    val value: (StockMetrics) -> Double?,
) {
    RETURN_1M("1M return", StockFormat.SIGNED_PERCENT, true, { it.returns[Period.M1] }),
    RETURN_3M("3M return", StockFormat.SIGNED_PERCENT, true, { it.returns[Period.M3] }),
    RETURN_6M("6M return", StockFormat.SIGNED_PERCENT, true, { it.returns[Period.M6] }),
    RETURN_1Y("1Y return", StockFormat.SIGNED_PERCENT, true, { it.returns[Period.Y1] }),
    CAGR_3Y("3Y CAGR", StockFormat.SIGNED_PERCENT, true, { it.returns[Period.Y3] }),
    EXCESS_1Y("1Y vs index", StockFormat.POINTS, true, { it.excess(Period.Y1) }),
    MOMENTUM_12_1("12-1M momentum", StockFormat.SIGNED_PERCENT, true, { it.momentum12m1m }),
    RSI_14("RSI (14)", StockFormat.NUMBER, true, { it.rsi14 }),
    VS_DMA_200("vs 200-DMA", StockFormat.SIGNED_PERCENT, true, { it.vsDma200 }),
    FROM_52W_HIGH("From 52W high", StockFormat.SIGNED_PERCENT, true, { it.fromHigh52w }),
    VOLATILITY_1Y("Volatility (1Y)", StockFormat.PERCENT, false, { it.volatility1y }),
    BETA("Beta", StockFormat.NUMBER, false, { it.beta }),
    ALPHA("Alpha (ann.)", StockFormat.SIGNED_PERCENT, true, { it.alpha }),
    PE("P/E", StockFormat.MULTIPLE, false, { m -> m.pe?.takeIf { it > 0 } }),
    PB("P/B", StockFormat.MULTIPLE, false, { m -> m.pb?.takeIf { it > 0 } }),
    EARNINGS_YIELD("Earnings yield", StockFormat.PERCENT, true, { it.earningsYield }),
    REVENUE_GROWTH("Revenue growth (FY)", StockFormat.SIGNED_PERCENT, true, { it.revenueGrowth }),
    REVENUE_CAGR("Revenue CAGR", StockFormat.SIGNED_PERCENT, true, { it.revenueCagr }),
    EPS_GROWTH("EPS growth (FY)", StockFormat.SIGNED_PERCENT, true, { it.epsGrowth }),
    QUARTERLY_REVENUE_YOY("Revenue YoY (qtr)", StockFormat.SIGNED_PERCENT, true, { it.quarterlyRevenueYoY }),
    ROE("ROE", StockFormat.PERCENT, true, { it.roe }),
    NET_MARGIN("Net margin", StockFormat.PERCENT, true, { it.netMargin }),
    DEBT_TO_EQUITY("Debt / equity", StockFormat.NUMBER, false, { it.debtToEquity }),
    NET_PROFIT("Net profit (FY)", StockFormat.CRORE, true, { it.netProfitCr }),
    PROFIT_GROWTH("Profit growth (FY)", StockFormat.SIGNED_PERCENT, true, { it.profitGrowth }),
}

/**
 * Ranking sections. Each ranks by its [primary] measure ("top" = best for that measure, e.g.
 * lowest P/E or lowest volatility) and shows [supporting] measures alongside.
 */
enum class StockFactor(
    val label: String,
    val primary: StockMetric,
    val supporting: List<StockMetric>,
    val description: String,
) {
    MOMENTUM("Momentum", StockMetric.MOMENTUM_12_1, listOf(StockMetric.RSI_14, StockMetric.VS_DMA_200),
        "12-month return excluding the latest month"),
    VALUATION("Valuation", StockMetric.PE, listOf(StockMetric.PB, StockMetric.EARNINGS_YIELD),
        "Trailing P/E; top = cheapest (loss-makers excluded)"),
    GROWTH("Growth", StockMetric.REVENUE_GROWTH, listOf(StockMetric.REVENUE_CAGR, StockMetric.EPS_GROWTH),
        "Latest fiscal-year revenue growth"),
    QUALITY("Quality", StockMetric.ROE, listOf(StockMetric.NET_MARGIN, StockMetric.DEBT_TO_EQUITY),
        "Return on equity, latest fiscal year"),
    PERFORMANCE("Performance", StockMetric.RETURN_1Y, listOf(StockMetric.RETURN_3M, StockMetric.EXCESS_1Y),
        "Price return over the last year"),
    PROFIT("Profit", StockMetric.PROFIT_GROWTH, listOf(StockMetric.NET_PROFIT, StockMetric.NET_MARGIN),
        "Net profit growth, latest fiscal year"),
    VOLATILITY("Volatility", StockMetric.VOLATILITY_1Y, listOf(StockMetric.BETA, StockMetric.FROM_52W_HIGH),
        "Annualised volatility; top = steadiest"),
    ALPHA("Alpha", StockMetric.ALPHA, listOf(StockMetric.BETA, StockMetric.EXCESS_1Y),
        "Return above what its beta to its index explains (3Y monthly)"),
}
