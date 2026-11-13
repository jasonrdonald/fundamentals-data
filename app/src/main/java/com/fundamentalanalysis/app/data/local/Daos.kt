package com.fundamentalanalysis.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** All stock rows of one dataset, inserted together. */
data class StockRows(
    val stocks: List<StockEntity> = emptyList(),
    val financials: List<StockFinancialEntity> = emptyList(),
)

@Dao
interface MarketDao {
    @Transaction
    @Query("SELECT * FROM indices ORDER BY symbol")
    fun observeAll(): Flow<List<IndexWithBars>>

    @Transaction
    @Query("SELECT * FROM indices WHERE symbol = :symbol")
    fun observe(symbol: String): Flow<IndexWithBars?>

    @Transaction
    @Query("SELECT * FROM stocks ORDER BY symbol")
    fun observeStocks(): Flow<List<StockWithData>>

    @Transaction
    @Query("SELECT * FROM stocks WHERE symbol = :symbol")
    fun observeStock(symbol: String): Flow<StockWithData?>

    @Query("DELETE FROM stocks")
    suspend fun deleteAllStocks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStocks(stocks: List<StockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockFinancials(rows: List<StockFinancialEntity>)

    @Query("SELECT * FROM constituents ORDER BY symbol")
    fun observeConstituents(): Flow<List<ConstituentEntity>>

    @Query("SELECT COUNT(*) FROM indices")
    suspend fun count(): Int

    @Query("DELETE FROM indices")
    suspend fun deleteAllIndices()

    @Query("DELETE FROM constituents")
    suspend fun deleteAllConstituents()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIndices(indices: List<IndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBars(bars: List<BarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValuation(valuation: List<ValuationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConstituents(constituents: List<ConstituentEntity>)

    /** Atomically swaps the whole shared dataset (bars cascade-delete with their index). */
    @Transaction
    suspend fun replaceAll(
        indices: List<IndexEntity>,
        bars: List<BarEntity>,
        valuation: List<ValuationEntity>,
        constituents: List<ConstituentEntity>,
        stocks: StockRows = StockRows(),
    ) {
        deleteAllIndices()
        deleteAllConstituents()
        deleteAllStocks()
        insertIndices(indices)
        insertBars(bars)
        insertValuation(valuation)
        insertConstituents(constituents)
        insertStocks(stocks.stocks)
        insertStockFinancials(stocks.financials)
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
