package com.fundamentalanalysis.app.ui.compare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Comparison windows: daily closes for 1Y, month-end closes for 5Y. */
enum class CompareRange(val label: String) { Y1("1 year"), Y5("5 years") }

data class CompareUiState(
    val loading: Boolean = true,
    val all: List<AnalyzedIndex> = emptyList(),
    val selected: List<AnalyzedIndex> = emptyList(),
    val range: CompareRange = CompareRange.Y1,
) {
    val canAddMore: Boolean get() = selected.size < CompareViewModel.MAX_SELECTED
}

@HiltViewModel
class CompareViewModel @Inject constructor(
    observeIndices: ObserveAnalyzedIndicesUseCase,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Null until the user edits the selection; defaults to the broad market vs banks. */
    private val selection = savedState.getStateFlow<List<String>?>(KEY_SELECTION, null)
    private val range = savedState.getStateFlow(KEY_RANGE, CompareRange.Y1)

    val state: StateFlow<CompareUiState> = combine(observeIndices(), selection, range) { all, picked, r ->
        val bySymbol = all.associateBy { it.symbol }
        val symbols = picked ?: DEFAULT_SELECTION.filter { it in bySymbol }
        CompareUiState(
            loading = false,
            all = all.sortedWith(compareBy({ it.index.category.ordinal }, { it.index.name })),
            selected = symbols.mapNotNull { bySymbol[it] },
            range = r,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareUiState())

    fun add(symbol: String) {
        val current = state.value.selected.map { it.symbol }
        if (symbol !in current && current.size < MAX_SELECTED) savedState[KEY_SELECTION] = current + symbol
    }

    fun remove(symbol: String) {
        savedState[KEY_SELECTION] = state.value.selected.map { it.symbol } - symbol
    }

    fun onRangeChange(value: CompareRange) {
        savedState[KEY_RANGE] = value
    }

    companion object {
        const val MAX_SELECTED = 3
        private val DEFAULT_SELECTION = listOf("NIFTY 50", "NIFTY BANK")
        private const val KEY_SELECTION = "compare_selection"
        private const val KEY_RANGE = "compare_range"
    }
}
