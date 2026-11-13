package com.fundamentalanalysis.app.domain.repository

import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first access to the shared dataset. The local database is the single source of
 * truth for the UI; [refresh] pulls the latest shared dataset into it.
 */
interface MarketRepository {
    fun observeIndices(): Flow<List<MarketIndex>>
    fun observeIndex(symbol: String): Flow<MarketIndex?>
    fun observeComposition(): Flow<Composition?>
    fun observeStocks(): Flow<List<com.fundamentalanalysis.app.domain.model.Stock>>
    fun observeStock(symbol: String): Flow<com.fundamentalanalysis.app.domain.model.Stock?>
    fun observeSyncStatus(): Flow<SyncStatus>

    /** Loads the bundled dataset if the local database is empty or older than it. */
    suspend fun ensureData()
    suspend fun refresh(): RefreshResult
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
