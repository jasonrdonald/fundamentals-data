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
    /** Which page components are shown, simplified or hidden. */
    fun observeUiLayout(): Flow<com.fundamentalanalysis.app.domain.model.UiLayout>
    suspend fun setDisplay(component: com.fundamentalanalysis.app.domain.model.UiComponent, display: com.fundamentalanalysis.app.domain.model.Display)
    suspend fun resetUiLayout(page: com.fundamentalanalysis.app.domain.model.UiPage? = null)
    /** Offline mode: no live price lookups for holdings outside the dataset (they stay blank). */
    fun observeOfflineMode(): Flow<Boolean>
    suspend fun setOfflineMode(enabled: Boolean)
    /** Tickers this device asked to have added to the wishlist, until they appear in the dataset. */
    fun observeRequestedTickers(): Flow<Set<String>>
    suspend fun addRequestedTickers(symbols: Set<String>)
    suspend fun removeRequestedTickers(symbols: Set<String>)
}

/** Data that belongs to this device only (never shared). */
interface WatchlistRepository {
    fun observeWatchlist(): Flow<Set<String>>
    suspend fun toggle(symbol: String)
}

/** The user's own portfolio, imported from a broker backup file and kept on this device only. */
interface PortfolioRepository {
    fun observePortfolio(): Flow<com.fundamentalanalysis.app.domain.model.PortfolioData>
    /** Reads and checks a backup file (a content URI) without storing anything. */
    suspend fun readImportFile(uri: String): com.fundamentalanalysis.app.domain.model.ImportPreview
    /** Stores the file read last, replacing data previously imported for the same accounts. */
    suspend fun confirmImport(): com.fundamentalanalysis.app.domain.model.ImportPreview
    fun cancelImport()
    /** Deletes the imported portfolio from this device. */
    suspend fun clear()
}
