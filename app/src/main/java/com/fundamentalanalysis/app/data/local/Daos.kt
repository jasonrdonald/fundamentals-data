package com.fundamentalanalysis.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Everything the core part writes, applied in one transaction. */
data class CoreRows(
    val indices: List<IndexEntity>,
    val series: List<IndexSeriesEntity>,
    val constituents: List<ConstituentEntity>,
    val stocks: List<StockEntity>,
    val stockSeries: List<StockSeriesEntity>,
)

/** Everything the history part writes. */
data class HistoryRows(
    val series: List<IndexSeriesEntity>,
    val valuation: List<IndexValuationEntity>,
    val stockSeries: List<StockSeriesEntity>,
    val financials: List<StockFinancialsEntity>,
)

data class AnalyticsCount(val kind: String, val count: Int)

@Dao
interface MarketDao {
    // ------------------------------------------------------------------ reads (observed by the UI)

    @Transaction
    @Query("SELECT * FROM indices ORDER BY symbol")
    fun observeIndices(): Flow<List<IndexWithSeries>>

    @Query("SELECT * FROM constituents ORDER BY symbol")
    fun observeConstituents(): Flow<List<ConstituentEntity>>

    /** Stock metadata only: no price history is loaded for lists. */
    @Query("SELECT * FROM stocks ORDER BY symbol")
    fun observeStocks(): Flow<List<StockEntity>>

    @Transaction
    @Query("SELECT * FROM stocks WHERE symbol = :symbol")
    fun observeStock(symbol: String): Flow<StockWithHistory?>

    @Query("SELECT * FROM analytics WHERE kind = :kind")
    fun observeAnalytics(kind: String): Flow<List<AnalyticsEntity>>

    @Query("SELECT kind, COUNT(*) AS count FROM analytics GROUP BY kind ORDER BY kind")
    fun observeAnalyticsCounts(): Flow<List<AnalyticsCount>>

    @Query("SELECT * FROM dataset_parts")
    fun observeParts(): Flow<List<DatasetPartEntity>>

    @Query("SELECT * FROM sync_events ORDER BY atMillis DESC, id DESC LIMIT :limit")
    fun observeEvents(limit: Int): Flow<List<SyncEventEntity>>

    @Query("SELECT * FROM meta")
    fun observeMeta(): Flow<List<MetaEntity>>

    // ------------------------------------------------------------------ reads (sync engine)

    @Query("SELECT * FROM dataset_parts")
    suspend fun parts(): List<DatasetPartEntity>

    @Query("SELECT * FROM index_series WHERE timeframe = :timeframe")
    suspend fun indexSeries(timeframe: String): List<IndexSeriesEntity>

    @Query("SELECT * FROM stock_series")
    suspend fun stockSeries(): List<StockSeriesEntity>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    // ------------------------------------------------------------------ writes

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertIndices(rows: List<IndexEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertIndexSeries(rows: List<IndexSeriesEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertValuation(rows: List<IndexValuationEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConstituents(rows: List<ConstituentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStocks(rows: List<StockEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStockSeries(rows: List<StockSeriesEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFinancials(rows: List<StockFinancialsEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAnalytics(rows: List<AnalyticsEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPart(part: DatasetPartEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(row: MetaEntity)
    @Insert suspend fun insertEvent(event: SyncEventEntity)

    @Query("DELETE FROM indices") suspend fun deleteIndices()
    @Query("DELETE FROM constituents") suspend fun deleteConstituents()
    @Query("DELETE FROM stocks") suspend fun deleteStocks()
    @Query("DELETE FROM analytics") suspend fun deleteAnalytics()
    @Query("DELETE FROM index_series WHERE symbol NOT IN (SELECT symbol FROM indices)") suspend fun pruneIndexSeries()
    @Query("DELETE FROM index_valuation WHERE symbol NOT IN (SELECT symbol FROM indices)") suspend fun pruneValuation()
    @Query("DELETE FROM stock_series WHERE symbol NOT IN (SELECT symbol FROM stocks)") suspend fun pruneStockSeries()
    @Query("DELETE FROM stock_financials WHERE symbol NOT IN (SELECT symbol FROM stocks)") suspend fun pruneFinancials()
    @Query("DELETE FROM sync_events WHERE id NOT IN (SELECT id FROM sync_events ORDER BY atMillis DESC, id DESC LIMIT :keep)")
    suspend fun trimEvents(keep: Int)

    @Query("DELETE FROM index_series") suspend fun deleteIndexSeries()
    @Query("DELETE FROM index_valuation") suspend fun deleteValuation()
    @Query("DELETE FROM stock_series") suspend fun deleteStockSeries()
    @Query("DELETE FROM stock_financials") suspend fun deleteFinancials()
    @Query("DELETE FROM dataset_parts") suspend fun deleteParts()

    /** Recent data: replaces the index/stock lists and their recent series. */
    @Transaction
    suspend fun applyCore(rows: CoreRows, part: DatasetPartEntity) {
        deleteIndices()
        deleteConstituents()
        deleteStocks()
        insertIndices(rows.indices)
        insertIndexSeries(rows.series)
        insertConstituents(rows.constituents)
        insertStocks(rows.stocks)
        insertStockSeries(rows.stockSeries)
        pruneIndexSeries()
        pruneValuation()
        pruneStockSeries()
        pruneFinancials()
        upsertPart(part)
    }

    @Transaction
    suspend fun applyAnalytics(rows: List<AnalyticsEntity>, part: DatasetPartEntity) {
        deleteAnalytics()
        insertAnalytics(rows)
        upsertPart(part)
    }

    /** Core and analytics of the same dataset version, swapped together so screens never mix them. */
    @Transaction
    suspend fun applyPrimary(core: CoreRows, corePart: DatasetPartEntity, analytics: List<AnalyticsEntity>, analyticsPart: DatasetPartEntity) {
        applyCore(core, corePart)
        applyAnalytics(analytics, analyticsPart)
    }

    @Transaction
    suspend fun applyHistory(rows: HistoryRows, part: DatasetPartEntity) {
        insertIndexSeries(rows.series)
        insertValuation(rows.valuation)
        insertStockSeries(rows.stockSeries)
        insertFinancials(rows.financials)
        pruneIndexSeries()
        pruneValuation()
        pruneStockSeries()
        pruneFinancials()
        upsertPart(part)
    }

    /** Drops all shared data (the watchlist and the sync log stay). */
    @Transaction
    suspend fun clearDataset() {
        deleteIndices()
        deleteConstituents()
        deleteStocks()
        deleteAnalytics()
        deleteIndexSeries()
        deleteValuation()
        deleteStockSeries()
        deleteFinancials()
        deleteParts()
    }
}

@Dao
interface WatchlistDao {
    @Query("SELECT symbol FROM watchlist")
    fun observeSymbols(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE symbol = :symbol)")
    suspend fun contains(symbol: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun delete(symbol: String)
}
