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
    private val holdingStocks: com.fundamentalanalysis.app.domain.repository.HoldingStocksRepository,
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

    /**
     * Portfolio stocks outside the dataset, fetched on this device; each measured against the
     * [HOLDING_BENCHMARK] (they belong to no tracked index, so [Stock.indexSymbol] is empty).
     */
    private val holdings: Flow<List<AnalyzedStock>> =
        combine(holdingStocks.observe(), market.observeIndices()) { stocks, indices ->
            val bench = indices.firstOrNull { it.symbol == HOLDING_BENCHMARK }
            stocks.map { AnalyzedStock(it, StockAnalyticsCalculator.calculate(it, bench)) }
        }.flowOn(dispatcher).shareIn(scope, SharingStarted.WhileSubscribed(60_000), replay = 1)

    fun holdings(): Flow<List<AnalyzedStock>> = holdings

    /** Precomputed sector aggregates (universe x period x sector). */
    fun sectors(): Flow<List<SectorStat>> = precomputed.map { it.sectors }.distinctUntilChanged()

    /** One stock with its price history and financials (empty until the history part is loaded). */
    fun one(symbol: String): Flow<AnalyzedStock?> =
        combine(market.observeStock(symbol), precomputed, market.observeIndices(), holdings) { stock, pre, indices, held ->
            stock ?: return@combine held.firstOrNull { it.symbol == symbol }
            val metrics = pre.stocks[symbol]
                ?: StockAnalyticsCalculator.calculate(stock, indices.firstOrNull { it.symbol == benchmarkOf(stock.indexSymbol) })
            AnalyzedStock(stock, metrics)
        }.flowOn(dispatcher).distinctUntilChanged()

    companion object {
        /** Display order of stock universes. */
        val UNIVERSE_ORDER = listOf("NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150", WATCHLIST)

        /** The dataset's watchlist universe: stocks outside the indices above, kept in tools/watchlist.csv. */
        const val WATCHLIST = "WATCHLIST"
        const val WATCHLIST_NAME = "Watchlist"

        /** Benchmark for the watchlist and for stocks fetched on the device (tools/indices.json says the same). */
        const val HOLDING_BENCHMARK = "NIFTY 500"

        /** The index a stock of universe [indexSymbol] is measured against. */
        fun benchmarkOf(indexSymbol: String): String =
            if (indexSymbol.isEmpty() || indexSymbol == WATCHLIST) HOLDING_BENCHMARK else indexSymbol
    }
}
