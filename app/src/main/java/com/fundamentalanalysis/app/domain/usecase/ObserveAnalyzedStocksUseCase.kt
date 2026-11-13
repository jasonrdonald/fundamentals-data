package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.di.ApplicationScope
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.analysis.StockAnalyticsCalculator
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
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
 * Constituent stocks of every covered index, each analysed against its own index (Nifty 50
 * stocks vs the Nifty 50, Midcap 150 stocks vs the Midcap 150, ...). Shared by all screens.
 */
@Singleton
class ObserveAnalyzedStocksUseCase @Inject constructor(
    market: MarketRepository,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) {
    private val shared: Flow<List<AnalyzedStock>> =
        combine(market.observeStocks(), market.observeIndices()) { stocks, indices ->
            val bySymbol = indices.associateBy { it.symbol }
            stocks.map { AnalyzedStock(it, StockAnalyticsCalculator.calculate(it, bySymbol[it.indexSymbol])) }
        }.flowOn(dispatcher).shareIn(scope, SharingStarted.WhileSubscribed(60_000), replay = 1)

    operator fun invoke(): Flow<List<AnalyzedStock>> = shared

    fun one(symbol: String): Flow<AnalyzedStock?> =
        shared.map { list -> list.firstOrNull { it.symbol == symbol } }.distinctUntilChanged()

    companion object {
        /** Display order of stock universes. */
        val UNIVERSE_ORDER = listOf("NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150")
    }
}
