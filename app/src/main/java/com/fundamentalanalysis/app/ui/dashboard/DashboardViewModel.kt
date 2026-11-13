package com.fundamentalanalysis.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.SyncStatus
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedIndicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val HEADLINE_INDEX = "NIFTY 50"
private const val VIX = "INDIA VIX"

data class DashboardUiState(
    val loading: Boolean = true,
    val headline: AnalyzedIndex? = null,
    val vix: AnalyzedIndex? = null,
    val sectors: List<AnalyzedIndex> = emptyList(),
    val sectorPeriod: Period = Period.D1,
    val advancing: Int = 0,
    val declining: Int = 0,
    val watchlist: List<AnalyzedIndex> = emptyList(),
    val composition: Composition? = null,
    val sync: SyncStatus? = null,
    val refreshing: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && headline == null
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    observeIndices: ObserveAnalyzedIndicesUseCase,
    private val repository: MarketRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val sectorPeriod = MutableStateFlow(Period.D1)
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    val state: StateFlow<DashboardUiState> = combine(
        observeIndices(),
        repository.observeComposition(),
        repository.observeSyncStatus(),
        refreshing,
        sectorPeriod,
    ) { indices, composition, sync, isRefreshing, period ->
        val equity = indices.filter { it.index.category != IndexCategory.VOLATILITY }
        val dayChanges = equity.mapNotNull { it.analytics.returns[Period.D1] }
        DashboardUiState(
            loading = false,
            headline = indices.firstOrNull { it.symbol == HEADLINE_INDEX },
            vix = indices.firstOrNull { it.symbol == VIX },
            sectors = indices.filter { it.index.category == IndexCategory.SECTOR }
                .sortedByDescending { it.analytics.returns[period] ?: Double.NEGATIVE_INFINITY },
            sectorPeriod = period,
            advancing = dayChanges.count { it > 0 },
            declining = dayChanges.count { it < 0 },
            watchlist = indices.filter { it.isWatchlisted },
            composition = composition,
            sync = sync,
            refreshing = isRefreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun onSectorPeriodChange(period: Period) {
        sectorPeriod.value = period
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val message = when (val result = repository.refresh()) {
                is RefreshResult.Updated -> "Updated to dataset v${result.version} (${result.indexCount} indices)"
                RefreshResult.UpToDate -> "Already up to date"
                RefreshResult.NotConfigured -> "No shared data source configured — showing bundled data"
                is RefreshResult.Failed -> "Refresh failed: ${result.message}"
            }
            refreshing.value = false
            messages.send(message)
        }
    }
}
