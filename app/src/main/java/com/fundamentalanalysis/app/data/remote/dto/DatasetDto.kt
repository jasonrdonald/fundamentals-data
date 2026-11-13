package com.fundamentalanalysis.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire format of the shared dataset (schemaVersion 2: index-level market data), produced by
 * tools/build_dataset.py. The same shape is bundled as assets/seed_dataset.json.
 * [version] only ever increases; apps replace their copy when it does.
 */
@Serializable
data class DatasetDto(
    val schemaVersion: Int = 1,
    val version: Int,
    val updatedAt: String? = null,
    val generatedAt: String? = null,
    val note: String? = null,
    val indices: List<IndexDto> = emptyList(),
    val composition: CompositionDto? = null,
    /** Constituent lists of every index that has a stock universe. */
    val universes: List<CompositionDto> = emptyList(),
    val stocks: StocksDto? = null,
)

/**
 * Constituent stocks of one or more indices. Compact layout: [months] and [sessions] are shared
 * calendars, and each stock stores closes aligned to them (null where it did not trade yet).
 * Fundamentals are in INR crore.
 */
@Serializable
data class StocksDto(
    val currency: String = "INR",
    val months: List<String> = emptyList(),
    val sessions: List<String> = emptyList(),
    val items: List<StockDto> = emptyList(),
)

@Serializable
data class StockDto(
    val symbol: String,
    val name: String,
    val industry: String = "",
    /** The index this stock belongs to and is benchmarked against. */
    val index: String = "NIFTY 50",
    val source: String? = null,
    /** Month-end closes aligned to [StocksDto.months]. */
    val mc: List<Double?> = emptyList(),
    /** Daily closes aligned to [StocksDto.sessions]. */
    val dc: List<Double?> = emptyList(),
    val fundamentals: FundamentalsDto = FundamentalsDto(),
)

@Serializable
data class FundamentalsDto(
    val marketCapCr: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    val annual: List<FinancialPeriodDto> = emptyList(),
    val quarterly: List<FinancialPeriodDto> = emptyList(),
)

@Serializable
data class FinancialPeriodDto(
    val d: String,
    val revenue: Double? = null,
    val netIncome: Double? = null,
    val operatingIncome: Double? = null,
    val equity: Double? = null,
    val debt: Double? = null,
    val eps: Double? = null,
)

@Serializable
data class IndexDto(
    val symbol: String,
    val name: String,
    val category: String = "BROAD",
    val source: String? = null,
    val daily: List<BarDto> = emptyList(),
    val monthly: List<BarDto> = emptyList(),
    /** Month-end valuation (NSE): only for indices configured with it. */
    val valuation: List<ValuationDto> = emptyList(),
)

@Serializable
data class ValuationDto(
    val d: String,
    val pe: Double? = null,
    val pb: Double? = null,
    val dy: Double? = null,
)

/** One OHLCV bar. `d` is an ISO date (first day of the month for monthly bars). */
@Serializable
data class BarDto(
    val d: String,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Long? = null,
    /** Monthly bars only: date of the last session included. */
    val last: String? = null,
)

@Serializable
data class CompositionDto(
    val index: String,
    val asOf: String? = null,
    val constituents: List<ConstituentDto> = emptyList(),
)

@Serializable
data class ConstituentDto(
    val symbol: String,
    val name: String,
    val industry: String = "",
)
