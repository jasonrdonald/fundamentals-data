package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.di.ApplicationScope
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.analysis.IndexAnalyticsCalculator
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.IndexAnalytics
import com.fundamentalanalysis.app.domain.model.MarketIndex
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
 * Joins the stored indices with the analytics precomputed by the dataset pipeline and the
 * device-local watchlist. Only the cheap parts (latest session, monthly moves) are derived here;
 * an index without a precomputed row falls back to the full calculator. Shared by every screen.
 */
@Singleton
class ObserveAnalyzedIndicesUseCase @Inject constructor(
    market: MarketRepository,
    watchlist: WatchlistRepository,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) {
    private val analyzed = combine(market.observeIndices(), market.observePrecomputed()) { indices, pre ->
        indices.map { index -> index to (pre.indices[index.symbol]?.let { complete(index, it) } ?: IndexAnalyticsCalculator.calculate(index)) }
    }.flowOn(dispatcher)

    private val shared: Flow<List<AnalyzedIndex>> =
        combine(analyzed, watchlist.observeWatchlist()) { rows, watched ->
            rows.map { (index, analytics) -> AnalyzedIndex(index, analytics, index.symbol in watched) }
        }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 60_000), replay = 1)

    operator fun invoke(): Flow<List<AnalyzedIndex>> = shared

    fun one(symbol: String): Flow<AnalyzedIndex?> =
        shared.map { list -> list.firstOrNull { it.symbol == symbol } }.distinctUntilChanged()

    private fun complete(index: MarketIndex, pre: IndexAnalytics) = pre.copy(
        latest = IndexAnalyticsCalculator.latestSession(index.daily),
        monthly = IndexAnalyticsCalculator.monthlyMoves(index.monthly, index.daily.lastOrNull()?.date),
    )
}
