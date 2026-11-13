package com.fundamentalanalysis.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.analysis.MonthlyMatrix
import com.fundamentalanalysis.app.domain.analysis.RankedRow
import com.fundamentalanalysis.app.domain.analysis.ReportBuilder
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class ReportType(val label: String) {
    RANKING("Ranking"), PERFORMANCE("Returns"), MONTHLY("Monthly"), CLASSIFICATION("Industries")
}

data class ReportsUiState(
    val loading: Boolean = true,
    val type: ReportType = ReportType.RANKING,
    val metric: Metric = Metric.RETURN_1M,
    val category: IndexCategory? = null,
    val categories: List<IndexCategory> = emptyList(),
    val ranking: List<RankedRow> = emptyList(),
    val performance: List<AnalyzedIndex> = emptyList(),
    val monthly: MonthlyMatrix = MonthlyMatrix(emptyList(), emptyList(), null),
    val composition: Composition? = null,
) {
    val title: String
        get() = when (type) {
            ReportType.RANKING -> "Nifty indices by ${metric.label}" + (category?.let { " — ${it.label}" } ?: "")
            ReportType.PERFORMANCE -> "Nifty index returns"
            ReportType.MONTHLY -> "Nifty monthly returns"
            ReportType.CLASSIFICATION -> "${composition?.indexSymbol ?: "Index"} industry classification"
        }

    fun toCsv(): String? = when (type) {
        ReportType.RANKING -> ReportBuilder.rankingCsv(ranking, metric)
        ReportType.PERFORMANCE -> ReportBuilder.performanceCsv(performance)
        ReportType.MONTHLY -> ReportBuilder.monthlyCsv(monthly)
        ReportType.CLASSIFICATION -> composition?.let(ReportBuilder::compositionCsv)
    }
}

private data class Selection(val type: ReportType, val metric: Metric, val category: IndexCategory?)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    observeIndices: ObserveAnalyzedIndicesUseCase,
    market: MarketRepository,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection(ReportType.RANKING, Metric.RETURN_1M, null))

    val state: StateFlow<ReportsUiState> = combine(observeIndices(), market.observeComposition(), selection) { all, comp, sel ->
        // India VIX measures volatility, not price performance: only rank it when asked for explicitly.
        val scoped = all.filter {
            if (sel.category == null) it.index.category != IndexCategory.VOLATILITY else it.index.category == sel.category
        }
        val equity = all.filter { it.index.category != IndexCategory.VOLATILITY }
            .sortedWith(compareBy({ it.index.category.ordinal }, { it.index.name }))
        ReportsUiState(
            loading = false,
            type = sel.type,
            metric = sel.metric,
            category = sel.category,
            categories = all.map { it.index.category }.distinct().sortedBy { it.ordinal },
            ranking = ReportBuilder.ranking(scoped, sel.metric),
            performance = equity,
            monthly = ReportBuilder.monthlyMatrix(equity, months = 12),
            composition = comp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun onTypeChange(type: ReportType) = selection.update { it.copy(type = type) }
    fun onMetricChange(metric: Metric) = selection.update { it.copy(metric = metric) }
    fun onCategoryChange(category: IndexCategory?) = selection.update { it.copy(category = category) }
}
