package com.fundamentalanalysis.app.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IndexEntity::class, IndexSeriesEntity::class, IndexValuationEntity::class, ConstituentEntity::class,
        StockEntity::class, StockSeriesEntity::class, StockFinancialsEntity::class,
        AnalyticsEntity::class, DatasetPartEntity::class, SyncEventEntity::class, MetaEntity::class,
        WatchlistEntity::class,
        PortfolioAccountEntity::class, PortfolioHoldingEntity::class, PortfolioTradeEntity::class,
        PortfolioActionEntity::class, PortfolioNoteEntity::class, PortfolioFeeEntity::class, PortfolioImportEntity::class,
    ],
    version = 7,
    exportSchema = true,
    // 6 -> 7 only adds the portfolio tables.
    autoMigrations = [AutoMigration(from = 6, to = 7)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun portfolioDao(): PortfolioDao
}
