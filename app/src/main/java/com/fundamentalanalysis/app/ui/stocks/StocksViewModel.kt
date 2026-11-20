package com.fundamentalanalysis.app.ui.stocks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.analysis.HoldingFilter
import com.fundamentalanalysis.app.domain.analysis.PortfolioStocks
import com.fundamentalanalysis.app.domain.analysis.RankedStock
import com.fundamentalanalysis.app.domain.model.Position
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class StocksTab(val label: String, val component: com.fundamentalanalysis.app.domain.model.UiComponent) {
    SECTORS("Sectors", com.fundamentalanalysis.app.domain.model.UiComponent.STOCKS_SECTORS),
    RANKINGS("Rankings", com.fundamentalanalysis.app.domain.model.UiComponent.STOCKS_RANKINGS),
    DISTRIBUTION("Distribution", com.fundamentalanalysis.app.domain.model.UiComponent.STOCKS_DISTRIBUTION),
    ALL("All stocks", com.fundamentalanalysis.app.domain.model.UiComponent.STOCKS_ALL),
}

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
    /** Portfolio universe only: which holdings to analyse. */
    val holdings: HoldingFilter = HoldingFilter.ALL,
) : java.io.Serializable

/** Pseudo-universe for the imported portfolio's stock holdings. */
const val PORTFOLIO_UNIVERSE = "PORTFOLIO"

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
    /** Who the stocks are judged against, e.g. "the Nifty 50" or "their own index". */
    val beatLabel: String = "",
    /** Reference return's name for the distribution, e.g. "Nifty 50". */
    val benchmarkName: String = "",
    /** Stock holdings in the portfolio (0 = no portfolio imported, no Portfolio chip). */
    val portfolioSize: Int = 0,
    val holdingCounts: Map<HoldingFilter, Int> = emptyMap(),
    /** Portfolio universe: the filtered holdings (search applied), largest first. */
    val holdings: List<Position> = emptyList(),
    /** Portfolio universe: filtered holdings with no dataset metrics, left out of the analysis tabs. */
    val unanalysed: Int = 0,
) {
    val isPortfolio: Boolean get() = selection.universe == PORTFOLIO_UNIVERSE
}

@HiltViewModel
class StocksViewModel @Inject constructor(
    observeStocks: ObserveAnalyzedStocksUseCase,
    market: com.fundamentalanalysis.app.domain.repository.MarketRepository,
    observePortfolio: com.fundamentalanalysis.app.domain.usecase.ObservePortfolioUseCase,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val selection = savedState.getStateFlow(KEY, StockSelection())

    val state: StateFlow<StocksUiState> = combine(
        observeStocks(), observeStocks.sectors(), market.observeIndices(), selection, observePortfolio(),
    ) { allStocks, sectorStats, indices, sel, portfolio ->
        val names = indices.associate { it.symbol to it.name }
        val positions = portfolio?.positions.orEmpty()
        val portfolioSize = PortfolioStocks.holdings(positions, HoldingFilter.ALL).size
        if (sel.universe == PORTFOLIO_UNIVERSE && portfolioSize > 0) {
            return@combine portfolioState(sel, positions, allStocks, names)
        }
        val universes = universesOf(allStocks, names)
        val universe = universes.firstOrNull { it.symbol == sel.universe } ?: universes.firstOrNull()
        val stocks = allStocks.filter { it.stock.indexSymbol == universe?.symbol }
        // Counts and averages come precomputed from the dataset; members are grouped locally for the lists.
        val sectors = StockReports.sectorPerformance(
            stocks, sel.period, sectorStats.filter { it.universe == universe?.symbol && it.period == sel.period },
        )
        val q = sel.query.trim()
        val benchmark = stocks.firstNotNullOfOrNull { it.metrics.benchmarkReturns[sel.period] }
        val periodReturns = stocks.mapNotNull { s -> s.metrics.returns[sel.period]?.let { s to it } }
        StocksUiState(
            distribution = com.fundamentalanalysis.app.domain.analysis.Distributions.of(
                periodReturns.map { (s, r) -> s.symbol to r }, benchmark,
            ),
            distributionRows = periodReturns.sortedByDescending { it.second },
            loading = false,
            beatLabel = "the " + universe?.name.orEmpty(),
            benchmarkName = universe?.name.orEmpty(),
            portfolioSize = portfolioSize,
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
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StocksUiState())

    /** The portfolio's stock holdings analysed like an index's constituents. */
    private fun portfolioState(
        sel: StockSelection,
        positions: List<Position>,
        allStocks: List<AnalyzedStock>,
        names: Map<String, String>,
    ): StocksUiState {
        val held = PortfolioStocks.holdings(positions, sel.holdings)
        val stocks = PortfolioStocks.analysed(held)
        // One index: compare with it. Several (or none): each stock with its own index, and the
        // distribution against the Nifty 50.
        val single = stocks.map { it.stock.indexSymbol }.distinct().singleOrNull()
        // Stocks outside the tracked indices (index symbol empty) are measured against the Nifty 500.
        val refSymbol = single?.ifEmpty { ObserveAnalyzedStocksUseCase.HOLDING_BENCHMARK } ?: "NIFTY 50"
        val refName = names[refSymbol] ?: refSymbol
        val benchmark = if (single != null) stocks.firstNotNullOfOrNull { it.metrics.benchmarkReturns[sel.period] }
        else allStocks.firstOrNull { it.stock.indexSymbol == refSymbol }?.metrics?.benchmarkReturns?.get(sel.period)
        val sectors = PortfolioStocks.sectors(stocks, sel.period)
        val periodReturns = stocks.mapNotNull { s -> s.metrics.returns[sel.period]?.let { s to it } }
        val q = sel.query.trim()
        return StocksUiState(
            loading = false,
            selection = sel,
            universes = universesOf(allStocks, names),
            indexName = "Portfolio" + if (sel.holdings == HoldingFilter.ALL) "" else " · ${sel.holdings.label}",
            universeSize = stocks.size,
            benchmarkReturn = if (single != null) benchmark else null,
            beatLabel = if (single != null) "the $refName" else "their own index",
            benchmarkName = refName,
            totalOutperformers = sectors.sumOf { it.outperformers },
            totalMeasured = sectors.sumOf { it.measured },
            sectors = sectors,
            ranking = StockReports.rank(stocks, sel.factor, sel.count, sel.top),
            distribution = com.fundamentalanalysis.app.domain.analysis.Distributions.of(periodReturns.map { (s, r) -> s.symbol to r }, benchmark),
            distributionRows = periodReturns.sortedByDescending { it.second },
            all = stocks,
            portfolioSize = PortfolioStocks.holdings(positions, HoldingFilter.ALL).size,
            holdingCounts = PortfolioStocks.counts(positions),
            holdings = held.filter {
                q.isEmpty() || it.symbol.contains(q, true) || it.sector.contains(q, true) ||
                    it.tracked?.stock?.name?.contains(q, true) == true
            },
            unanalysed = held.size - stocks.size,
        )
    }

    private fun universesOf(allStocks: List<AnalyzedStock>, names: Map<String, String>): List<Universe> {
        val order = ObserveAnalyzedStocksUseCase.UNIVERSE_ORDER
        return allStocks.groupBy { it.stock.indexSymbol }
            .map { (symbol, members) -> Universe(symbol, names[symbol] ?: symbol, members.size) }
            .sortedBy { order.indexOf(it.symbol).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

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
    fun onHoldings(filter: HoldingFilter) = update { it.copy(holdings = filter) }

    private companion object {
        const val KEY = "stock_selection"
    }
}
