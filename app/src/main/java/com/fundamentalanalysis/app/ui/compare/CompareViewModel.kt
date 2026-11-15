package com.fundamentalanalysis.app.ui.compare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Comparison windows. Up to a year uses daily closes; 3Y and 5Y use month-end closes.
 * [period] is the matching trailing-return period.
 */
enum class CompareRange(val label: String, val period: Period, val sessions: Int?, val months: Int?) {
    M3("3M", Period.M3, 63, null),
    M6("6M", Period.M6, 126, null),
    Y1("1Y", Period.Y1, 252, null),
    Y3("3Y", Period.Y3, null, 36),
    Y5("5Y", Period.Y5, null, 60);

    val years: Int? get() = months?.div(12)
}

/** How multi-year returns are shown: cumulative over the window, or per year (CAGR). */
enum class ReturnMode(val label: String) { TOTAL("Total"), ANNUALISED("Annualised") }

/**
 * Return over [range] as shown in charts. Short windows are always cumulative; 3Y/5Y follow
 * [mode] (the stored figure is a CAGR).
 */
fun AnalyzedIndex.returnFor(range: CompareRange, mode: ReturnMode): Double? {
    val r = analytics.returns[range.period] ?: return null
    val years = range.years ?: return r
    return if (mode == ReturnMode.ANNUALISED) r else Math.pow(1 + r, years.toDouble()) - 1
}

data class CompareUiState(
    val loading: Boolean = true,
    val all: List<AnalyzedIndex> = emptyList(),
    val selected: List<AnalyzedIndex> = emptyList(),
    val range: CompareRange = CompareRange.Y1,
    val mode: ReturnMode = ReturnMode.TOTAL,
    /** Every equity index (VIX excluded), for the all-indices chart. */
    val equityIndices: List<AnalyzedIndex> = emptyList(),
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
    private val mode = savedState.getStateFlow(KEY_MODE, ReturnMode.TOTAL)

    val state: StateFlow<CompareUiState> = combine(observeIndices(), selection, range, mode) { all, picked, r, m ->
        val bySymbol = all.associateBy { it.symbol }
        val symbols = picked ?: DEFAULT_SELECTION.filter { it in bySymbol }
        CompareUiState(
            loading = false,
            all = all.sortedWith(compareBy({ it.index.category.ordinal }, { it.index.name })),
            selected = symbols.mapNotNull { bySymbol[it] },
            range = r,
            mode = m,
            equityIndices = all.filter { it.index.category != IndexCategory.VOLATILITY },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareUiState())

    fun add(symbol: String) {
        val current = state.value.selected.map { it.symbol }
        if (symbol !in current && current.size < MAX_SELECTED) savedState[KEY_SELECTION] = current + symbol
    }

    fun remove(symbol: String) {
        savedState[KEY_SELECTION] = state.value.selected.map { it.symbol } - symbol
    }

    fun onModeChange(value: ReturnMode) {
        savedState[KEY_MODE] = value
    }

    fun onRangeChange(value: CompareRange) {
        savedState[KEY_RANGE] = value
    }

    companion object {
        const val MAX_SELECTED = 3
        private val DEFAULT_SELECTION = listOf("NIFTY 50", "NIFTY BANK")
        private const val KEY_SELECTION = "compare_selection"
        private const val KEY_RANGE = "compare_range"
        private const val KEY_MODE = "compare_mode"
    }
}
