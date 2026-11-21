package com.fundamentalanalysis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.repository.SettingsRepository
import com.fundamentalanalysis.app.domain.usecase.ObservePortfolioUseCase
import com.fundamentalanalysis.app.ui.stocks.RequestStocksButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OfflineSettingsState(
    val offline: Boolean = false,
    /** Stock holdings with no market data (not in the dataset, and not looked up). */
    val missing: List<String> = emptyList(),
)

@HiltViewModel
class OfflineSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    observePortfolio: ObservePortfolioUseCase,
) : ViewModel() {
    val state: StateFlow<OfflineSettingsState> = combine(settings.observeOfflineMode(), observePortfolio()) { offline, portfolio ->
        OfflineSettingsState(
            offline,
            portfolio?.positions.orEmpty().filter { it.kind == HoldingKind.STOCK && it.tracked == null }.map { it.symbol }.sorted(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineSettingsState())

    fun setOffline(enabled: Boolean) { viewModelScope.launch { settings.setOfflineMode(enabled) } }
}

/**
 * Offline mode: the app uses only the shared dataset. Holdings outside it are not looked up on
 * Yahoo Finance and stay blank; the card lists them so they can be sent to the dataset admin.
 */
@Composable
fun OfflineModeCard(viewModel: OfflineSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Offline mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.offline) "On · no live price lookups" else "Off · holdings outside the dataset are looked up online",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.offline, onCheckedChange = viewModel::setOffline)
            }
            Text(
                "When on, the app uses only the shared dataset (bundled with the app and updated from it). Holdings that aren't " +
                    "in the dataset are not looked up on Yahoo Finance; their market data stays blank.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (state.missing.isNotEmpty()) {
                val n = state.missing.size
                Text(
                    "$n ${if (n == 1) "holding has" else "holdings have"} no market data: ${state.missing.joinToString(", ")}. " +
                        "Request ${if (n == 1) "it" else "them"} for the Wishlist; once approved, ${if (n == 1) "it works" else "they work"} offline too.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                RequestStocksButton(if (n == 1) "Request this stock" else "Request these stocks", state.missing)
            } else if (state.offline) {
                Text(
                    "Every stock holding is in the dataset, so nothing is missing offline.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
