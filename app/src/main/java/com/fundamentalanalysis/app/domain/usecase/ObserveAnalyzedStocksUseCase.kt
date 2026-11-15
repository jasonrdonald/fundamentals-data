package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.di.ApplicationScope
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.analysis.StockAnalyticsCalculator
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.SectorStat
import com.fundamentalanalysis.app.domain.repository.MarketRepository
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
 * Constituent stocks of every covered index with their precomputed metrics (each relative to its
 * own index). Lists never load price history; [one] adds it for the stock page.
 */
@Singleton
class ObserveAnalyzedStocksUseCase @Inject constructor(
    private val market: MarketRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) {
    private val precomputed = market.observePrecomputed()
        .shareIn(scope, SharingStarted.WhileSubscribed(60_000), replay = 1)

    private val shared: Flow<List<AnalyzedStock>> =
        combine(market.observeStocks(), precomputed) { stocks, pre ->
            stocks.mapNotNull { s -> pre.stocks[s.symbol]?.let { AnalyzedStock(s, it) } }
        }.flowOn(dispatcher).shareIn(scope, SharingStarted.WhileSubscribed(60_000), replay = 1)

    operator fun invoke(): Flow<List<AnalyzedStock>> = shared

    /** Precomputed sector aggregates (universe x period x sector). */
    fun sectors(): Flow<List<SectorStat>> = precomputed.map { it.sectors }.distinctUntilChanged()

    /** One stock with its price history and financials (empty until the history part is loaded). */
    fun one(symbol: String): Flow<AnalyzedStock?> =
        combine(market.observeStock(symbol), precomputed, market.observeIndices()) { stock, pre, indices ->
            stock ?: return@combine null
            val metrics = pre.stocks[symbol]
                ?: StockAnalyticsCalculator.calculate(stock, indices.firstOrNull { it.symbol == stock.indexSymbol })
            AnalyzedStock(stock, metrics)
        }.flowOn(dispatcher).distinctUntilChanged()

    companion object {
        /** Display order of stock universes. */
        val UNIVERSE_ORDER = listOf("NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150")
    }
}
