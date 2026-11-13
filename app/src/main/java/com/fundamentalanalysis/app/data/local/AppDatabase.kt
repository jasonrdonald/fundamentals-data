package com.fundamentalanalysis.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IndexEntity::class, BarEntity::class, ValuationEntity::class, ConstituentEntity::class, WatchlistEntity::class,
        StockEntity::class, StockFinancialEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao
    abstract fun watchlistDao(): WatchlistDao
}
