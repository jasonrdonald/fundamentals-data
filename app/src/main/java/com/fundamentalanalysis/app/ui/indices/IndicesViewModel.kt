package com.fundamentalanalysis.app.ui.indices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.repository.WatchlistRepository
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IndexFilters(
    val category: IndexCategory? = null,
    val sortBy: Metric = Metric.RETURN_1D,
    val watchlistOnly: Boolean = false,
)

data class IndicesUiState(
    val loading: Boolean = true,
    val filters: IndexFilters = IndexFilters(),
    val categories: List<IndexCategory> = emptyList(),
    val indices: List<AnalyzedIndex> = emptyList(),
)

@HiltViewModel
class IndicesViewModel @Inject constructor(
    observeIndices: ObserveAnalyzedIndicesUseCase,
    private val watchlist: WatchlistRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(IndexFilters())

    val state: StateFlow<IndicesUiState> = combine(observeIndices(), filters) { all, f ->
        val filtered = all.filter { (f.category == null || it.index.category == f.category) && (!f.watchlistOnly || it.isWatchlisted) }
        // Indices missing the sort metric (e.g. volume for VIX) go last regardless of direction.
        val (withValue, without) = filtered.partition { it.valueOf(f.sortBy) != null }
        val sorted = if (f.sortBy.higherIsBetter) withValue.sortedByDescending { it.valueOf(f.sortBy) }
        else withValue.sortedBy { it.valueOf(f.sortBy) }
        IndicesUiState(
            loading = false,
            filters = f,
            categories = all.map { it.index.category }.distinct().sortedBy { it.ordinal },
            indices = sorted + without,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndicesUiState())

    fun onCategorySelected(category: IndexCategory?) = filters.update { it.copy(category = category) }
    fun onSortChange(metric: Metric) = filters.update { it.copy(sortBy = metric) }
    fun onWatchlistOnlyChange(value: Boolean) = filters.update { it.copy(watchlistOnly = value) }
    fun toggleWatchlist(symbol: String) {
        viewModelScope.launch { watchlist.toggle(symbol) }
    }
}
