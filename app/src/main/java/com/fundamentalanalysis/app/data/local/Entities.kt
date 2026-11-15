package com.fundamentalanalysis.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Storage layout (schema 6). Price history is packed into one BLOB per series (see BarCodec and
 * PriceCodec): one row per index/timeframe instead of one row per bar. Writing ~7,000 bar rows
 * took about 3 s on a phone emulator; the packed rows take milliseconds. Precomputed analytics
 * arrive ready-made in the dataset and are stored as-is in [AnalyticsEntity].
 */

@Entity(tableName = "indices")
data class IndexEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val category: String,
    val source: String?,
)

/** All bars of one index for one timeframe ("D" daily or "M" monthly), packed with [BarCodec]. */
@Entity(tableName = "index_series", primaryKeys = ["symbol", "timeframe"])
data class IndexSeriesEntity(
    val symbol: String,
    val timeframe: String,
    val bars: ByteArray,
)

/** Month-end valuation history of one index, packed with [ValuationCodec]. */
@Entity(tableName = "index_valuation")
data class IndexValuationEntity(
    @PrimaryKey val symbol: String,
    val points: ByteArray,
)

@Entity(tableName = "constituents", primaryKeys = ["indexSymbol", "symbol"])
data class ConstituentEntity(
    val indexSymbol: String,
    val symbol: String,
    val name: String,
    val industry: String,
    val asOf: String?,
)

/** Stock metadata only, so lists never load price history. */
@Entity(tableName = "stocks")
data class StockEntity(
    @PrimaryKey val symbol: String,
    val indexSymbol: String,
    val name: String,
    val industry: String,
    val source: String?,
    val marketCapCr: Double?,
    val pe: Double?,
    val pb: Double?,
)

/** A stock's month-end and daily closes, packed with [PriceCodec]. */
@Entity(tableName = "stock_series")
data class StockSeriesEntity(
    @PrimaryKey val symbol: String,
    val monthlyCloses: ByteArray,
    val dailyCloses: ByteArray,
)

/** Reported financials as JSON arrays of FinancialPeriodDto (INR crore; EPS in INR). */
@Entity(tableName = "stock_financials")
data class StockFinancialsEntity(
    @PrimaryKey val symbol: String,
    val annualJson: String,
    val quarterlyJson: String,
)

/**
 * Precomputed analysis rows from the dataset's analytics part, stored as JSON payloads keyed by
 * [kind] (INDEX, STOCK, BENCHMARK, SECTOR, CALENDAR) and [key].
 */
@Entity(tableName = "analytics", primaryKeys = ["kind", "key"])
data class AnalyticsEntity(
    val kind: String,
    val key: String,
    val payload: String,
)

/** Which version of each dataset part is stored locally, and how it got here. */
@Entity(tableName = "dataset_parts")
data class DatasetPartEntity(
    @PrimaryKey val name: String,
    val version: Int,
    val sha256: String,
    val bytes: Long,
    val records: Int,
    /** BUNDLED or REMOTE. */
    val origin: String,
    val asOf: String?,
    val loadedAtMillis: Long,
    /** Download (if any) + parse + database write. */
    val loadMillis: Long,
)

/** Device-side log of update checks, downloads and loads (shown as a timeline). */
@Entity(tableName = "sync_events")
data class SyncEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    /** CHECK, DOWNLOAD, LOAD, ERROR or RESET. */
    val type: String,
    val part: String?,
    val version: Int?,
    val bytes: Long?,
    val durationMillis: Long?,
    val success: Boolean,
    val message: String,
)

/** Small key/value facts about the local data (last manifest, last check time). */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Device-local favourites: kept across dataset refreshes and schema changes. */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    val addedAtMillis: Long,
)

data class IndexWithSeries(
    @Embedded val index: IndexEntity,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val series: List<IndexSeriesEntity>,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val valuation: List<IndexValuationEntity>,
)

data class StockWithHistory(
    @Embedded val stock: StockEntity,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val series: List<StockSeriesEntity>,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val financials: List<StockFinancialsEntity>,
)
