package com.fundamentalanalysis.app.domain.repository

import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.RefreshResult
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first access to the shared dataset. The local database is the single source of truth;
 * it is filled progressively (recent data + precomputed analytics first, history after).
 */
interface MarketRepository {
    fun observeIndices(): Flow<List<MarketIndex>>
    /** Constituents by index symbol (Nifty 50 with industries, plus every stock universe). */
    fun observeCompositions(): Flow<Map<String, Composition>>
    /** The Nifty 50's constituents with their NSE industries. */
    fun observeComposition(): Flow<Composition?>
    /** Stock metadata only; use [observeStock] for price history and financials. */
    fun observeStocks(): Flow<List<com.fundamentalanalysis.app.domain.model.Stock>>
    fun observeStock(symbol: String): Flow<com.fundamentalanalysis.app.domain.model.Stock?>
    /** Analysis-format data shipped ready-made with the dataset. */
    fun observePrecomputed(): Flow<com.fundamentalanalysis.app.domain.model.Precomputed>
    fun observeAvailability(): Flow<com.fundamentalanalysis.app.domain.model.DataAvailability>
    fun observeDataStatus(): Flow<com.fundamentalanalysis.app.domain.model.DataStatus>

    /** Makes sure recent data and analytics are stored (bundled copy on first launch). */
    suspend fun ensureData()
    /** Loads history and checks for a newer published dataset, without blocking the UI. */
    fun startBackgroundSync()
    /** Checks for a newer published dataset now and downloads the parts that changed. */
    suspend fun refresh(): RefreshResult
    /** Downloads every part again. */
    suspend fun reloadAll(): RefreshResult
    /** Drops downloaded data and reloads the copy bundled with the app. */
    suspend fun resetToBundled()
}

/** Appearance and other preferences that belong to this device only. */
interface SettingsRepository {
    fun observeThemeMode(): Flow<com.fundamentalanalysis.app.domain.model.ThemeMode>
    suspend fun setThemeMode(mode: com.fundamentalanalysis.app.domain.model.ThemeMode)
}

/** Data that belongs to this device only (never shared). */
interface WatchlistRepository {
    fun observeWatchlist(): Flow<Set<String>>
    suspend fun toggle(symbol: String)
}
