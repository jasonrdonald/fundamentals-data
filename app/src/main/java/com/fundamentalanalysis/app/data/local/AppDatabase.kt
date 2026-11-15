package com.fundamentalanalysis.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IndexEntity::class, IndexSeriesEntity::class, IndexValuationEntity::class, ConstituentEntity::class,
        StockEntity::class, StockSeriesEntity::class, StockFinancialsEntity::class,
        AnalyticsEntity::class, DatasetPartEntity::class, SyncEventEntity::class, MetaEntity::class,
        WatchlistEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao
    abstract fun watchlistDao(): WatchlistDao
}
