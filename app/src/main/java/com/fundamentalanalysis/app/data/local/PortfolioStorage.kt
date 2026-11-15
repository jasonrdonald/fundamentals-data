package com.fundamentalanalysis.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/*
 * The user's own portfolio, imported from a broker backup file. Device-local only: never synced,
 * never sent anywhere, and untouched by dataset updates or "Reset to bundled".
 */

@Entity(tableName = "portfolio_accounts")
data class PortfolioAccountEntity(
    @PrimaryKey val id: String,
    val broker: String,
    /** Masked client id (last 3 characters only). */
    val clientId: String,
    val holdingsAsOf: String?,
    val importedAtMillis: Long,
)

@Entity(tableName = "portfolio_holdings", primaryKeys = ["accountId", "isin", "symbol"])
data class PortfolioHoldingEntity(
    val accountId: String,
    val symbol: String,
    val isin: String,
    /** stock or mf (fund units held in demat). */
    val kind: String,
    val sector: String,
    val qty: Double,
    val avgPrice: Double,
    /** Broker's last close on [PortfolioAccountEntity.holdingsAsOf]. */
    val close: Double,
)

@Entity(tableName = "portfolio_trades")
data class PortfolioTradeEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val symbol: String,
    val isin: String,
    val date: String,
    val side: String,
    val qty: Double,
    val price: Double,
    val exchange: String,
    val kind: String,
)

/** Corporate actions and off-market movements (e.g. allotments, transfers in/out). */
@Entity(tableName = "portfolio_actions")
data class PortfolioActionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val type: String,
    val symbol: String,
    val date: String,
    val qty: Double,
    val price: Double?,
    val issue: String,
    val direction: String,
)

@Entity(tableName = "portfolio_notes")
data class PortfolioNoteEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val symbol: String,
    val reviewed: Boolean,
    val comment: String,
)

/** Brokerage, STT, exchange charges, stamp duty, GST, etc. */
@Entity(tableName = "portfolio_fees")
data class PortfolioFeeEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val date: String,
    val kind: String,
    val amount: Double,
)

/** One completed import, for "last imported" and the import history. */
@Entity(tableName = "portfolio_imports")
data class PortfolioImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val fileName: String?,
    val exportedAt: String?,
    /** e.g. "Zerodha, HDFC Securities". */
    val brokers: String,
    val holdings: Int,
    val trades: Int,
)

data class PortfolioRows(
    val accounts: List<PortfolioAccountEntity>,
    val holdings: List<PortfolioHoldingEntity>,
    val trades: List<PortfolioTradeEntity>,
    val actions: List<PortfolioActionEntity>,
    val notes: List<PortfolioNoteEntity>,
    val fees: List<PortfolioFeeEntity>,
)

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolio_accounts ORDER BY broker") fun observeAccounts(): Flow<List<PortfolioAccountEntity>>
    @Query("SELECT * FROM portfolio_holdings") fun observeHoldings(): Flow<List<PortfolioHoldingEntity>>
    @Query("SELECT * FROM portfolio_trades ORDER BY date DESC, id DESC") fun observeTrades(): Flow<List<PortfolioTradeEntity>>
    @Query("SELECT * FROM portfolio_actions ORDER BY date DESC") fun observeActions(): Flow<List<PortfolioActionEntity>>
    @Query("SELECT * FROM portfolio_notes") fun observeNotes(): Flow<List<PortfolioNoteEntity>>
    @Query("SELECT * FROM portfolio_fees") fun observeFees(): Flow<List<PortfolioFeeEntity>>
    @Query("SELECT * FROM portfolio_imports ORDER BY atMillis DESC LIMIT 20") fun observeImports(): Flow<List<PortfolioImportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAccounts(rows: List<PortfolioAccountEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHoldings(rows: List<PortfolioHoldingEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTrades(rows: List<PortfolioTradeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertActions(rows: List<PortfolioActionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertNotes(rows: List<PortfolioNoteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFees(rows: List<PortfolioFeeEntity>)
    @Insert suspend fun insertImport(row: PortfolioImportEntity)

    @Query("DELETE FROM portfolio_accounts WHERE id IN (:ids)") suspend fun deleteAccounts(ids: List<String>)
    @Query("DELETE FROM portfolio_holdings WHERE accountId IN (:ids)") suspend fun deleteHoldings(ids: List<String>)
    @Query("DELETE FROM portfolio_trades WHERE accountId IN (:ids)") suspend fun deleteTrades(ids: List<String>)
    @Query("DELETE FROM portfolio_actions WHERE accountId IN (:ids)") suspend fun deleteActions(ids: List<String>)
    @Query("DELETE FROM portfolio_notes WHERE accountId IN (:ids)") suspend fun deleteNotes(ids: List<String>)
    @Query("DELETE FROM portfolio_fees WHERE accountId IN (:ids)") suspend fun deleteFees(ids: List<String>)

    @Query("DELETE FROM portfolio_accounts") suspend fun clearAccounts()
    @Query("DELETE FROM portfolio_holdings") suspend fun clearHoldings()
    @Query("DELETE FROM portfolio_trades") suspend fun clearTrades()
    @Query("DELETE FROM portfolio_actions") suspend fun clearActions()
    @Query("DELETE FROM portfolio_notes") suspend fun clearNotes()
    @Query("DELETE FROM portfolio_fees") suspend fun clearFees()
    @Query("DELETE FROM portfolio_imports") suspend fun clearImports()

    /** Replaces everything stored for the imported accounts; other accounts are left alone. */
    @Transaction
    suspend fun replaceAccounts(rows: PortfolioRows, record: PortfolioImportEntity) {
        val ids = rows.accounts.map { it.id }
        deleteAccounts(ids); deleteHoldings(ids); deleteTrades(ids); deleteActions(ids); deleteNotes(ids); deleteFees(ids)
        insertAccounts(rows.accounts)
        insertHoldings(rows.holdings)
        insertTrades(rows.trades)
        insertActions(rows.actions)
        insertNotes(rows.notes)
        insertFees(rows.fees)
        insertImport(record)
    }

    @Transaction
    suspend fun clearAll() {
        clearAccounts(); clearHoldings(); clearTrades(); clearActions(); clearNotes(); clearFees(); clearImports()
    }
}
