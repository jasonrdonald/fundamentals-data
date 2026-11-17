package com.fundamentalanalysis.app.ui.heatmap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.analysis.HEATMAP_INDEX
import com.fundamentalanalysis.app.domain.analysis.HeatGroup
import com.fundamentalanalysis.app.domain.analysis.HeatGrouping
import com.fundamentalanalysis.app.domain.analysis.HeatSummary
import com.fundamentalanalysis.app.domain.analysis.HeatmapBuilder
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class HeatSelection(
    val grouping: HeatGrouping = HeatGrouping.NONE,
) : java.io.Serializable

/** A sector's row in the table under the map. */
data class HeatGroupRow(val label: String, val stocks: Int, val up: Int, val down: Int, val weighted: Double?)

data class HeatmapUiState(
    val loading: Boolean = true,
    val selection: HeatSelection = HeatSelection(),
    val indexName: String = "Nifty 50",
    /** The index's own change in the latest session. */
    val indexChange: Double? = null,
    val groups: List<HeatGroup> = emptyList(),
    val summary: HeatSummary? = null,
    val sectorRows: List<HeatGroupRow> = emptyList(),
    val gainers: List<Pair<AnalyzedStock, Double>> = emptyList(),
    val losers: List<Pair<AnalyzedStock, Double>> = emptyList(),
    val stocks: Map<String, AnalyzedStock> = emptyMap(),
    val asOf: LocalDate? = null,
)

/** Nifty 50 constituents coloured by the latest session's change. */
@HiltViewModel
class HeatmapViewModel @Inject constructor(
    observeStocks: ObserveAnalyzedStocksUseCase,
    market: MarketRepository,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val selection = savedState.getStateFlow(KEY, HeatSelection())

    val state: StateFlow<HeatmapUiState> = combine(observeStocks(), market.observeIndices(), selection) { all, indices, sel ->
        val index = indices.firstOrNull { it.symbol == HEATMAP_INDEX }
        val stocks = all.filter { it.stock.indexSymbol == HEATMAP_INDEX }
        val groups = HeatmapBuilder.stocks(stocks, sel.grouping)
        val sectors = if (sel.grouping == HeatGrouping.SECTOR) groups else HeatmapBuilder.stocks(stocks, HeatGrouping.SECTOR)
        val measured = stocks.mapNotNull { s -> s.metrics.change1d?.let { s to it } }.sortedByDescending { it.second }
        HeatmapUiState(
            loading = false,
            selection = sel,
            indexName = index?.name ?: "Nifty 50",
            indexChange = index?.daily?.takeLast(2)?.takeIf { it.size == 2 }?.let { (a, b) -> b.close / a.close - 1 },
            groups = groups,
            summary = HeatmapBuilder.summary(groups),
            sectorRows = sectors.map { g ->
                HeatGroupRow(g.label, g.tiles.size, g.tiles.count { (it.value ?: 0.0) > 0 }, g.tiles.count { (it.value ?: 0.0) < 0 }, g.value)
            }.sortedByDescending { it.weighted ?: Double.NEGATIVE_INFINITY },
            gainers = measured.take(5).filter { it.second > 0 },
            losers = measured.takeLast(5).reversed().filter { it.second < 0 },
            stocks = stocks.associateBy { it.symbol },
            asOf = stocks.mapNotNull { it.metrics.lastDate }.maxOrNull(),
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeatmapUiState())

    private fun update(change: (HeatSelection) -> HeatSelection) {
        savedState[KEY] = change(selection.value)
    }

    fun onGrouping(g: HeatGrouping) = update { it.copy(grouping = g) }

    private companion object {
        const val KEY = "heat_selection_v3"
    }
}
