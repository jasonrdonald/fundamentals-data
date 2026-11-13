package com.fundamentalanalysis.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.WatchlistRepository
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase
import com.fundamentalanalysis.app.ui.navigation.IndexDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Price chart windows, in daily sessions. */
enum class ChartRange(val label: String, val sessions: Int) {
    M3("3M", 63), M6("6M", 126), Y1("1Y", 252)
}

sealed interface IndexDetailUiState {
    data object Loading : IndexDetailUiState
    data object NotFound : IndexDetailUiState
    data class Ready(
        val index: AnalyzedIndex,
        val composition: Composition?,
        val range: ChartRange,
    ) : IndexDetailUiState
}

@HiltViewModel
class IndexDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    observeIndices: ObserveAnalyzedIndicesUseCase,
    market: MarketRepository,
    private val watchlist: WatchlistRepository,
) : ViewModel() {

    val symbol: String = savedStateHandle.toRoute<IndexDetailRoute>().symbol
    private val range = savedStateHandle.getStateFlow(KEY_RANGE, ChartRange.M6)

    val state: StateFlow<IndexDetailUiState> = combine(
        observeIndices.one(symbol),
        market.observeComposition(),
        range,
    ) { index, composition, r ->
        if (index == null) IndexDetailUiState.NotFound
        else IndexDetailUiState.Ready(index, composition?.takeIf { it.indexSymbol == symbol }, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexDetailUiState.Loading)

    fun onRangeChange(value: ChartRange) {
        savedStateHandle[KEY_RANGE] = value
    }

    fun toggleWatchlist() {
        viewModelScope.launch { watchlist.toggle(symbol) }
    }

    private companion object {
        const val KEY_RANGE = "chart_range"
    }
}
