package com.fundamentalanalysis.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema 5 -> 6: price history moved to packed series, precomputed analytics and sync tables
 * added. Dataset tables are dropped and recreated empty (the sync engine refills them from the
 * bundled copy on the next launch); the device-local watchlist is kept.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("bars", "valuation", "stock_financials", "stocks", "constituents", "indices").forEach {
            db.execSQL("DROP TABLE IF EXISTS `$it`")
        }
        CREATE_V6.forEach { db.execSQL(it) }
    }
}

// From app/schemas/.../6.json (Room's own CREATE statements, so validation matches).
private val CREATE_V6 = listOf<String>(
    "CREATE TABLE IF NOT EXISTS `indices` (`symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT, PRIMARY KEY(`symbol`))",
    "CREATE TABLE IF NOT EXISTS `index_series` (`symbol` TEXT NOT NULL, `timeframe` TEXT NOT NULL, `bars` BLOB NOT NULL, PRIMARY KEY(`symbol`, `timeframe`))",
    "CREATE TABLE IF NOT EXISTS `index_valuation` (`symbol` TEXT NOT NULL, `points` BLOB NOT NULL, PRIMARY KEY(`symbol`))",
    "CREATE TABLE IF NOT EXISTS `constituents` (`indexSymbol` TEXT NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `industry` TEXT NOT NULL, `asOf` TEXT, PRIMARY KEY(`indexSymbol`, `symbol`))",
    "CREATE TABLE IF NOT EXISTS `stocks` (`symbol` TEXT NOT NULL, `indexSymbol` TEXT NOT NULL, `name` TEXT NOT NULL, `industry` TEXT NOT NULL, `source` TEXT, `marketCapCr` REAL, `pe` REAL, `pb` REAL, PRIMARY KEY(`symbol`))",
    "CREATE TABLE IF NOT EXISTS `stock_series` (`symbol` TEXT NOT NULL, `monthlyCloses` BLOB NOT NULL, `dailyCloses` BLOB NOT NULL, PRIMARY KEY(`symbol`))",
    "CREATE TABLE IF NOT EXISTS `stock_financials` (`symbol` TEXT NOT NULL, `annualJson` TEXT NOT NULL, `quarterlyJson` TEXT NOT NULL, PRIMARY KEY(`symbol`))",
    "CREATE TABLE IF NOT EXISTS `analytics` (`kind` TEXT NOT NULL, `key` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`kind`, `key`))",
    "CREATE TABLE IF NOT EXISTS `dataset_parts` (`name` TEXT NOT NULL, `version` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `records` INTEGER NOT NULL, `origin` TEXT NOT NULL, `asOf` TEXT, `loadedAtMillis` INTEGER NOT NULL, `loadMillis` INTEGER NOT NULL, PRIMARY KEY(`name`))",
    "CREATE TABLE IF NOT EXISTS `sync_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `atMillis` INTEGER NOT NULL, `type` TEXT NOT NULL, `part` TEXT, `version` INTEGER, `bytes` INTEGER, `durationMillis` INTEGER, `success` INTEGER NOT NULL, `message` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
)
