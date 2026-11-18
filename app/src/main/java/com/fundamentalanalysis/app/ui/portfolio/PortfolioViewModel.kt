package com.fundamentalanalysis.app.ui.portfolio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.ImportPreview
import com.fundamentalanalysis.app.domain.model.PortfolioSummary
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.repository.PortfolioRepository
import com.fundamentalanalysis.app.domain.usecase.ObservePortfolioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PortfolioTab(val label: String) { HOLDINGS("Holdings"), HEATMAP("Heatmap"), ALLOCATION("Allocation"), VS_INDEX("vs Index"), ACTIVITY("Activity") }

enum class HoldingSort(val label: String) { VALUE("Value"), PNL("P&L %"), DAY("Today"), NAME("Name") }

data class PortfolioUiState(
    val loading: Boolean = true,
    val summary: PortfolioSummary? = null,
    val brokers: List<String> = emptyList(),
    val broker: String? = null,
    val tab: PortfolioTab = PortfolioTab.HOLDINGS,
    val sort: HoldingSort = HoldingSort.VALUE,
    val holdings: List<Position> = emptyList(),
    /** Broad-market indices the tracked holdings can be compared with (symbol to name). */
    val benchmarks: List<Pair<String, String>> = emptyList(),
    /** Chosen benchmark for "All tracked holdings"; null = each stock's own index. */
    val benchmark: String? = null,
    /** "All tracked holdings" vs [benchmark] (null when comparing with own index). */
    val vsBenchmark: List<com.fundamentalanalysis.app.domain.model.IndexComparison>? = null,
    /** Holdings by index, each group sorted by [sort]. */
    val groups: List<com.fundamentalanalysis.app.domain.model.GroupSummary> = emptyList(),
)

/** Import dialog state. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Review(val preview: ImportPreview) : ImportState
    data object Saving : ImportState
    data class Failed(val message: String) : ImportState
}

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    observePortfolio: ObservePortfolioUseCase,
    observeIndices: com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase,
    private val repository: PortfolioRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val broker = savedState.getStateFlow<String?>(KEY_BROKER, null)
    private val tab = savedState.getStateFlow(KEY_TAB, PortfolioTab.HOLDINGS)
    private val sort = savedState.getStateFlow(KEY_SORT, HoldingSort.VALUE)
    private val benchmark = savedState.getStateFlow<String?>(KEY_BENCHMARK, null)
    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    val state: StateFlow<PortfolioUiState> = combine(
        combine(observePortfolio(broker), observePortfolio.brokers(), broker, tab, sort, ::Inputs),
        observeIndices(), benchmark,
    ) { (summary, brokers, b, t, s), indices, chosen ->
        val choices = indices.filter { it.index.category == com.fundamentalanalysis.app.domain.model.IndexCategory.BROAD }
            .sortedBy { BENCHMARK_ORDER.indexOf(it.symbol).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val picked = choices.firstOrNull { it.symbol == chosen }
        PortfolioUiState(
            benchmarks = choices.map { it.symbol to it.index.name },
            benchmark = picked?.symbol,
            vsBenchmark = if (picked == null || summary == null) null else
                com.fundamentalanalysis.app.domain.analysis.PortfolioAnalytics.compareWith(summary.positions.filter { it.tracked != null }, picked.analytics.returns),
            loading = false,
            summary = summary,
            brokers = brokers,
            broker = b?.takeIf { it in brokers },
            tab = t,
            sort = s,
            holdings = summary?.positions.orEmpty().sortedBy(s),
            groups = summary?.groups.orEmpty().map { g -> g.copy(positions = g.positions.sortedBy(s)) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

    fun onBroker(value: String?) { savedState[KEY_BROKER] = value }
    fun onTab(value: PortfolioTab) { savedState[KEY_TAB] = value }
    fun onSort(value: HoldingSort) { savedState[KEY_SORT] = value }
    /** null = each stock's own index. */
    fun onBenchmark(symbol: String?) { savedState[KEY_BENCHMARK] = symbol }

    /** A file was picked: parse it and show what would be imported. */
    fun onFilePicked(uri: String) {
        _import.value = ImportState.Reading
        viewModelScope.launch {
            _import.value = try {
                ImportState.Review(repository.readImportFile(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportState.Failed(e.message ?: "Couldn't read the file.")
            }
        }
    }

    fun confirmImport() {
        _import.value = ImportState.Saving
        viewModelScope.launch {
            try {
                val done = repository.confirmImport()
                _import.value = ImportState.Idle
                messages.send(
                    if (done.isHoldingsFile) "Updated holdings for ${done.holdingsUpdates.joinToString { it.broker }}: ${done.holdingsUpdates.sumOf { it.holdings }} holdings"
                    else "Imported ${done.included.joinToString { it.broker }}: ${done.included.sumOf { it.holdings }} holdings, ${done.included.sumOf { it.trades }} trades",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _import.value = ImportState.Failed(e.message ?: "Import failed.")
            }
        }
    }

    fun dismissImport() {
        repository.cancelImport()
        _import.value = ImportState.Idle
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.clear()
            savedState[KEY_BROKER] = null
            messages.send("Imported portfolio deleted from this device")
        }
    }

    private companion object {
        const val KEY_BROKER = "portfolio_broker"
        const val KEY_TAB = "portfolio_tab"
        const val KEY_SORT = "portfolio_sort"
        const val KEY_BENCHMARK = "portfolio_benchmark"
        val BENCHMARK_ORDER = listOf("NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150", "NIFTY 500")
    }
}

private data class Inputs(
    val summary: com.fundamentalanalysis.app.domain.model.PortfolioSummary?,
    val brokers: List<String>,
    val broker: String?,
    val tab: PortfolioTab,
    val sort: HoldingSort,
)

private fun List<Position>.sortedBy(sort: HoldingSort): List<Position> = when (sort) {
    HoldingSort.VALUE -> sortedByDescending { it.value }
    HoldingSort.PNL -> sortedByDescending { it.pnlPct ?: Double.NEGATIVE_INFINITY }
    HoldingSort.DAY -> sortedByDescending { it.tracked?.metrics?.change1d ?: Double.NEGATIVE_INFINITY }
    HoldingSort.NAME -> sortedBy { it.symbol }
}
