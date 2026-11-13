package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.di.ApplicationScope
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.analysis.IndexAnalyticsCalculator
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins the shared dataset with the device-local watchlist and computes all analytics.
 * The result is computed once and shared by every screen (replayed to new subscribers), so
 * switching tabs does not re-read the database or recompute analytics.
 */
@Singleton
class ObserveAnalyzedIndicesUseCase @Inject constructor(
    market: MarketRepository,
    watchlist: WatchlistRepository,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) {
    // Analytics depend only on market data; recompute them only when that changes.
    private val analyzed = market.observeIndices()
        .map { indices -> indices.map { it to IndexAnalyticsCalculator.calculate(it) } }
        .flowOn(dispatcher)

    private val shared: Flow<List<AnalyzedIndex>> =
        combine(analyzed, watchlist.observeWatchlist()) { rows, watched ->
            rows.map { (index, analytics) -> AnalyzedIndex(index, analytics, index.symbol in watched) }
        }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 60_000), replay = 1)

    operator fun invoke(): Flow<List<AnalyzedIndex>> = shared

    fun one(symbol: String): Flow<AnalyzedIndex?> =
        shared.map { list -> list.firstOrNull { it.symbol == symbol } }.distinctUntilChanged()
}
