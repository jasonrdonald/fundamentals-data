package com.fundamentalanalysis.app.domain.repository

import com.fundamentalanalysis.app.domain.model.Stock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Price history and fundamentals for portfolio stocks the shared dataset doesn't cover. Fetched
 * on this device only (the symbols never go into the public dataset) and kept in app storage.
 */
interface HoldingStocksRepository {
    /** Cached stocks, [Stock.indexSymbol] empty (they belong to no tracked index). */
    fun observe(): Flow<List<Stock>>

    val status: StateFlow<HoldingStocksStatus>

    /**
     * Makes the cache hold exactly [wanted] (symbol -> sector): fetches symbols that are missing or
     * older than a few hours, drops symbols no longer held. [force] refetches everything.
     */
    suspend fun sync(wanted: Map<String, String>, force: Boolean = false)
}

data class HoldingStocksStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Symbols with no data at the source on the last run. */
    val failed: List<String> = emptyList(),
    val updatedAtMillis: Long? = null,
)
