package com.fundamentalanalysis.app.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire format of the progressive dataset (schemaVersion 4), produced by tools/derive_parts.py
 * under shared-data/v4/ and bundled under assets/seed/. The app downloads the small manifest
 * first, then only the parts whose version changed: analytics + core first (enough for every
 * screen), history in the background.
 */

@Serializable
data class ManifestDto(
    val schemaVersion: Int,
    val version: Int,
    val datasetVersion: Int? = null,
    val asOf: String? = null,
    val generatedAt: String? = null,
    val loadOrder: List<String> = emptyList(),
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
)

@Serializable
data class PipelineRunDto(
    val version: Int,
    val datasetVersion: Int? = null,
    val generatedAt: String? = null,
    val asOf: String? = null,
    val changed: List<String> = emptyList(),
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

@Serializable
data class CorePartDto(
    val schemaVersion: Int,
    val asOf: String? = null,
    val indices: List<CoreIndexDto> = emptyList(),
    val composition: CompositionDto? = null,
    val universes: List<CompositionDto> = emptyList(),
    val stocks: CoreStocksDto = CoreStocksDto(),
)

@Serializable
data class CoreIndexDto(
    val symbol: String,
    val name: String,
    val category: String = "BROAD",
    val source: String? = null,
    val daily: BarColumnsDto = BarColumnsDto(),
    /** The most recent months only; history brings the rest. */
    val monthly: BarColumnsDto = BarColumnsDto(),
)

@Serializable
data class CoreStocksDto(
    val months: List<String> = emptyList(),
    val items: List<CoreStockDto> = emptyList(),
)

@Serializable
data class CoreStockDto(
    val symbol: String,
    val name: String,
    val industry: String = "",
    val index: String = "NIFTY 50",
    val source: String? = null,
    val marketCapCr: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    /** Month-end closes aligned to [CoreStocksDto.months]. */
    val mc: List<Double?> = emptyList(),
)

// ----------------------------------------------------------------------------------- history

@Serializable
data class HistoryPartDto(
    val schemaVersion: Int,
    val asOf: String? = null,
    val indices: List<HistoryIndexDto> = emptyList(),
    val stocks: HistoryStocksDto = HistoryStocksDto(),
)

@Serializable
data class HistoryIndexDto(
    val symbol: String,
    /** The full monthly series (replaces the recent months from core). */
    val monthly: BarColumnsDto = BarColumnsDto(),
    val valuation: ValuationColumnsDto = ValuationColumnsDto(),
)

@Serializable
data class HistoryStocksDto(
    val months: List<String> = emptyList(),
    val sessions: List<String> = emptyList(),
    val items: List<HistoryStockDto> = emptyList(),
)

@Serializable
data class HistoryStockDto(
    val symbol: String,
    val mc: List<Double?> = emptyList(),
    val dc: List<Double?> = emptyList(),
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
