package com.fundamentalanalysis.app.ui.stocks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.analysis.RankedStock
import com.fundamentalanalysis.app.domain.analysis.SectorPerformance
import com.fundamentalanalysis.app.domain.analysis.StockReports
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.StockFactor
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class StocksTab(val label: String) { SECTORS("Sectors"), RANKINGS("Rankings"), DISTRIBUTION("Distribution"), ALL("All stocks") }

/** Periods offered for "beat the Nifty" sector analysis. */
val SECTOR_PERIODS = listOf(Period.M1, Period.M3, Period.M6, Period.YTD, Period.Y1, Period.Y3)

val RANK_SIZES = listOf(10, 20, 30)

/** A stock universe: the constituents of one index. */
data class Universe(val symbol: String, val name: String, val size: Int)

data class StockSelection(
    val universe: String = "NIFTY 50",
    val tab: StocksTab = StocksTab.SECTORS,
    val period: Period = Period.Y1,
    val factor: StockFactor = StockFactor.MOMENTUM,
    val count: Int = 10,
    val top: Boolean = true,
    val query: String = "",
) : java.io.Serializable

data class StocksUiState(
    val loading: Boolean = true,
    val selection: StockSelection = StockSelection(),
    val benchmarkReturn: Double? = null,
    val totalOutperformers: Int = 0,
    val totalMeasured: Int = 0,
    val sectors: List<SectorPerformance> = emptyList(),
    val ranking: List<RankedStock> = emptyList(),
    val all: List<AnalyzedStock> = emptyList(),
    val universes: List<Universe> = emptyList(),
    /** Display name of the selected index (the benchmark). */
    val indexName: String = "",
    val universeSize: Int = 0,
    /** Spread of the selected period's returns across the index's stocks, vs a normal curve. */
    val distribution: com.fundamentalanalysis.app.domain.analysis.DistributionStats? = null,
    /** Return and z-score per stock for the distribution export and list. */
    val distributionRows: List<Pair<AnalyzedStock, Double>> = emptyList(),
)

@HiltViewModel
class StocksViewModel @Inject constructor(
    observeStocks: ObserveAnalyzedStocksUseCase,
    market: com.fundamentalanalysis.app.domain.repository.MarketRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val selection = savedState.getStateFlow(KEY, StockSelection())

    val state: StateFlow<StocksUiState> = combine(observeStocks(), market.observeIndices(), selection) { allStocks, indices, sel ->
        val names = indices.associate { it.symbol to it.name }
        val order = ObserveAnalyzedStocksUseCase.UNIVERSE_ORDER
        val universes = allStocks.groupBy { it.stock.indexSymbol }
            .map { (symbol, members) -> Universe(symbol, names[symbol] ?: symbol, members.size) }
            .sortedBy { order.indexOf(it.symbol).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val universe = universes.firstOrNull { it.symbol == sel.universe } ?: universes.firstOrNull()
        val stocks = allStocks.filter { it.stock.indexSymbol == universe?.symbol }
        val sectors = StockReports.sectorPerformance(stocks, sel.period)
        val q = sel.query.trim()
        val benchmark = stocks.firstNotNullOfOrNull { it.metrics.benchmarkReturns[sel.period] }
        val periodReturns = stocks.mapNotNull { s -> s.metrics.returns[sel.period]?.let { s to it } }
        StocksUiState(
            distribution = com.fundamentalanalysis.app.domain.analysis.Distributions.of(
                periodReturns.map { (s, r) -> s.symbol to r }, benchmark,
            ),
            distributionRows = periodReturns.sortedByDescending { it.second },
            loading = false,
            selection = sel.copy(universe = universe?.symbol ?: sel.universe),
            universes = universes,
            indexName = universe?.name.orEmpty(),
            universeSize = stocks.size,
            benchmarkReturn = stocks.firstNotNullOfOrNull { it.metrics.benchmarkReturns[sel.period] },
            totalOutperformers = sectors.sumOf { it.outperformers },
            totalMeasured = sectors.sumOf { it.measured },
            sectors = sectors,
            ranking = StockReports.rank(stocks, sel.factor, sel.count, sel.top),
            all = stocks
                .filter { q.isEmpty() || it.symbol.contains(q, true) || it.stock.name.contains(q, true) || it.stock.industry.contains(q, true) }
                .sortedByDescending { it.stock.marketCapCr ?: 0.0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StocksUiState())

    private fun update(change: (StockSelection) -> StockSelection) {
        savedState[KEY] = change(selection.value)
    }

    fun onUniverse(symbol: String) = update { it.copy(universe = symbol) }
    fun onTab(tab: StocksTab) = update { it.copy(tab = tab) }
    fun onPeriod(period: Period) = update { it.copy(period = period) }
    fun onFactor(factor: StockFactor) = update { it.copy(factor = factor) }
    fun onCount(count: Int) = update { it.copy(count = count) }
    fun onTop(top: Boolean) = update { it.copy(top = top) }
    fun onQuery(query: String) = update { it.copy(query = query) }

    private companion object {
        const val KEY = "stock_selection"
    }
}
