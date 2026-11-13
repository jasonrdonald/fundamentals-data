package com.fundamentalanalysis.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "indices")
data class IndexEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val category: String,
    val source: String?,
)

/** OHLCV bar; [timeframe] is "D" (daily session) or "M" (calendar month). Dates are ISO strings. */
@Entity(
    tableName = "bars",
    primaryKeys = ["symbol", "timeframe", "date"],
    foreignKeys = [
        ForeignKey(
            entity = IndexEntity::class,
            parentColumns = ["symbol"],
            childColumns = ["symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("symbol")],
)
data class BarEntity(
    val symbol: String,
    val timeframe: String,
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
    val lastSession: String?,
)

@Entity(tableName = "constituents", primaryKeys = ["indexSymbol", "symbol"])
data class ConstituentEntity(
    val indexSymbol: String,
    val symbol: String,
    val name: String,
    val industry: String,
    val asOf: String?,
)

/** Month-end valuation multiples; [date] is the last trading day of the month. */
@Entity(
    tableName = "valuation",
    primaryKeys = ["symbol", "date"],
    foreignKeys = [
        ForeignKey(
            entity = IndexEntity::class,
            parentColumns = ["symbol"],
            childColumns = ["symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("symbol")],
)
data class ValuationEntity(
    val symbol: String,
    val date: String,
    val pe: Double?,
    val pb: Double?,
    val dividendYield: Double?,
)

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
    /** Month-end closes, packed with [PriceCodec]. */
    val monthlyCloses: ByteArray,
    /** Daily closes (about a year), packed with [PriceCodec]. */
    val dailyCloses: ByteArray,
)

/** Reported financials: period "A" (annual) or "Q" (quarterly). Amounts in INR crore; EPS in INR. */
@Entity(
    tableName = "stock_financials",
    primaryKeys = ["symbol", "period", "date"],
    foreignKeys = [ForeignKey(entity = StockEntity::class, parentColumns = ["symbol"], childColumns = ["symbol"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("symbol")],
)
data class StockFinancialEntity(
    val symbol: String,
    val period: String,
    val date: String,
    val revenue: Double?,
    val netIncome: Double?,
    val operatingIncome: Double?,
    val equity: Double?,
    val debt: Double?,
    val eps: Double?,
)

data class StockWithData(
    @Embedded val stock: StockEntity,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val financials: List<StockFinancialEntity>,
)

/** Device-local favourites: kept across dataset refreshes, so no foreign key. */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    val addedAtMillis: Long,
)

data class IndexWithBars(
    @Embedded val index: IndexEntity,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val bars: List<BarEntity>,
    @Relation(parentColumn = "symbol", entityColumn = "symbol")
    val valuation: List<ValuationEntity> = emptyList(),
)
