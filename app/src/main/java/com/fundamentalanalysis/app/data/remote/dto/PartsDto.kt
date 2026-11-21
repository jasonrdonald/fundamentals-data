package com.fundamentalanalysis.app.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire format of the partitioned dataset (schemaVersion 5), produced by tools/derive_v5.py under
 * shared-data/v5/ and bundled under assets/seed/. The app downloads the small manifest first, then
 * only the parts whose version changed: the "primary" group (analytics, core, the daily months and
 * recent years; enough for every screen) first, the "history" group in the background.
 *
 *   analytics          precomputed metrics            core           index, constituent, stock lists
 *   daily-YYYY-MM      a month of daily prices        monthly-YYYY   a year of month-end data
 *   fundamentals       financials per stock
 */

@Serializable
data class ManifestDto(
    val schemaVersion: Int,
    val version: Int,
    val datasetVersion: Int? = null,
    val asOf: String? = null,
    val generatedAt: String? = null,
    /** First date the daily parts cover; older daily data on the device is dropped. */
    val dailyFrom: String? = null,
    val parts: List<PartMetaDto> = emptyList(),
    /** Pipeline runs that changed something, newest first. */
    val history: List<PipelineRunDto> = emptyList(),
)

@Serializable
data class PartMetaDto(
    val name: String,
    val file: String,
    val version: Int,
    val bytes: Long = 0,
    val sha256: String = "",
    val records: Int = 0,
    val description: String = "",
    /** "primary" (loaded before the first screen) or "history" (in the background). */
    val group: String = "",
)

@Serializable
data class PipelineRunDto(
    val version: Int,
    val datasetVersion: Int? = null,
    val generatedAt: String? = null,
    val asOf: String? = null,
    val changed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val indices: Int = 0,
    val stocks: Int = 0,
)

/** Bars as parallel arrays (one per field); `last` is set for monthly bars only. */
@Serializable
data class BarColumnsDto(
    val d: List<String> = emptyList(),
    val o: List<Double> = emptyList(),
    val h: List<Double> = emptyList(),
    val l: List<Double> = emptyList(),
    val c: List<Double> = emptyList(),
    val v: List<Long?> = emptyList(),
    val last: List<String?> = emptyList(),
)

@Serializable
data class ValuationColumnsDto(
    val d: List<String> = emptyList(),
    val pe: List<Double?> = emptyList(),
    val pb: List<Double?> = emptyList(),
    val dy: List<Double?> = emptyList(),
)

// ----------------------------------------------------------------------------------- core

/** Lists only (no prices): changes when constituents or fundamentals change (weekly). */
@Serializable
data class CorePartDto(
    val schemaVersion: Int,
    val indices: List<IndexInfoDto> = emptyList(),
    val composition: CompositionDto? = null,
    val universes: List<CompositionDto> = emptyList(),
    val stocks: List<StockInfoDto> = emptyList(),
)

@Serializable
data class IndexInfoDto(
    val symbol: String,
    val name: String,
    val category: String = "BROAD",
    val source: String? = null,
)

@Serializable
data class StockInfoDto(
    val symbol: String,
    val name: String,
    val industry: String = "",
    val index: String = "NIFTY 50",
    val source: String? = null,
    val marketCapCr: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
)

// ----------------------------------------------------------------------------------- prices

/** One calendar month of daily index bars and stock closes. */
@Serializable
data class DailyPartDto(
    val schemaVersion: Int,
    val month: String,
    val indices: List<IndexBarsDto> = emptyList(),
    val stocks: ClosesDto = ClosesDto(),
)

/** One year of month-end index bars (with `last`), valuation points and stock closes. */
@Serializable
data class MonthlyPartDto(
    val schemaVersion: Int,
    val year: Int,
    val indices: List<IndexBarsDto> = emptyList(),
    val valuation: List<IndexValuationDto> = emptyList(),
    val stocks: ClosesDto = ClosesDto(),
)

@Serializable
data class IndexBarsDto(val symbol: String, val bars: BarColumnsDto = BarColumnsDto())

@Serializable
data class IndexValuationDto(val symbol: String, val points: ValuationColumnsDto = ValuationColumnsDto())

/** Closes of each stock aligned to [dates] (null where it did not trade). */
@Serializable
data class ClosesDto(
    val dates: List<String> = emptyList(),
    val items: List<StockClosesDto> = emptyList(),
)

@Serializable
data class StockClosesDto(val symbol: String, val c: List<Double?> = emptyList())

// ----------------------------------------------------------------------------------- fundamentals

@Serializable
data class FundamentalsPartDto(
    val schemaVersion: Int,
    val items: List<StockFinancialsDto> = emptyList(),
)

@Serializable
data class StockFinancialsDto(
    val symbol: String,
    val annual: List<FinancialPeriodDto> = emptyList(),
    val quarterly: List<FinancialPeriodDto> = emptyList(),
)

// ----------------------------------------------------------------------------------- analytics

@Serializable
data class AnalyticsPartDto(
    val schemaVersion: Int,
    val asOf: String? = null,
    val indices: List<IndexMetricsDto> = emptyList(),
    /** Universe index symbol -> period name -> the index's return (stock benchmarks). */
    val benchmarks: Map<String, Map<String, Double?>> = emptyMap(),
    val stocks: List<StockMetricsDto> = emptyList(),
    val sectors: List<SectorStatDto> = emptyList(),
    val calendars: List<CalendarDto> = emptyList(),
)

/** Period keys are the app's Period enum names (D1, W1, ..., Y5). */
@Serializable
data class IndexMetricsDto(
    val symbol: String,
    val returns: Map<String, Double?> = emptyMap(),
    val rsi14: Double? = null,
    val dma50: Double? = null,
    val dma200: Double? = null,
    val vsDma50: Double? = null,
    val vsDma200: Double? = null,
    val high52w: Double? = null,
    val low52w: Double? = null,
    val fromHigh52w: Double? = null,
    val fromLow52w: Double? = null,
    val latestVolume: Long? = null,
    val latestVolumeDate: String? = null,
    val avgVolume20: Double? = null,
    val relativeVolume: Double? = null,
    val volumeTrend: Double? = null,
    val volatility1m: Double? = null,
    val volatility1y: Double? = null,
)

@Serializable
data class StockMetricsDto(
    val symbol: String,
    /** The universe index this stock is benchmarked against. */
    val index: String? = null,
    val lastClose: Double? = null,
    val lastDate: String? = null,
    val change1d: Double? = null,
    val returns: Map<String, Double?> = emptyMap(),
    val momentum12m1m: Double? = null,
    val rsi14: Double? = null,
    val vsDma200: Double? = null,
    val fromHigh52w: Double? = null,
    val volatility1y: Double? = null,
    val beta: Double? = null,
    val alpha: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    val earningsYield: Double? = null,
    val revenueGrowth: Double? = null,
    val revenueCagr: Double? = null,
    val epsGrowth: Double? = null,
    val quarterlyRevenueYoY: Double? = null,
    val roe: Double? = null,
    val netMargin: Double? = null,
    val debtToEquity: Double? = null,
    val netProfitCr: Double? = null,
    val profitGrowth: Double? = null,
)

/** Group-by universe x period x sector. */
@Serializable
data class SectorStatDto(
    val universe: String,
    val period: String,
    val sector: String,
    val stocks: Int,
    val measured: Int,
    val outperformers: Int,
    val averageReturn: Double? = null,
    val benchmarkReturn: Double? = null,
)

/** Year x month table. Each row is [year, [12 cells], yearValue, yearIsPartial]. */
@Serializable
data class CalendarDto(
    val symbol: String,
    /** RETURNS, PE, PB or DIVIDEND_YIELD. */
    val kind: String,
    val rows: List<kotlinx.serialization.json.JsonArray> = emptyList(),
    val monthAverages: List<Double?> = emptyList(),
    val yearAverage: Double? = null,
    val monthToDate: List<Int>? = null,
)
