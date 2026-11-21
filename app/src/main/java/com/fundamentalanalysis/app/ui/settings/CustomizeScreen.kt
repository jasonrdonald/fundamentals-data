package com.fundamentalanalysis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.Display
import com.fundamentalanalysis.app.domain.model.UiComponent
import com.fundamentalanalysis.app.domain.model.UiLayout
import com.fundamentalanalysis.app.domain.model.UiPage
import com.fundamentalanalysis.app.domain.repository.SettingsRepository
import com.fundamentalanalysis.app.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomizeViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val layout: StateFlow<UiLayout> = settings.observeUiLayout()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiLayout())

    fun set(c: UiComponent, d: Display) { viewModelScope.launch { settings.setDisplay(c, d) } }
    fun reset(page: UiPage? = null) { viewModelScope.launch { settings.resetUiLayout(page) } }
}

/** Show, simplify or hide each page's components. Choices are stored on this device. */
@Composable
fun CustomizeScreen(onBack: () -> Unit, viewModel: CustomizeViewModel = hiltViewModel()) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Customize",
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = {
            if (layout.customised > 0) {
                IconButton(onClick = { confirmReset = true }) { Icon(Icons.Filled.RestartAlt, contentDescription = "Reset everything to default") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Choose what each page shows. Simplified keeps a component but trims it to the essentials. " +
                        if (layout.customised == 0) "Everything is at its default." else "${layout.customised} changed from the default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { AppearanceCard() }
            item { OfflineModeCard() }
            items(UiPage.entries, key = { it.name }) { page ->
                PageCard(page, layout, onSet = viewModel::set, onReset = { viewModel.reset(page) })
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all pages?") },
            text = { Text("Every component goes back to its default display.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.reset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PageCard(page: UiPage, layout: UiLayout, onSet: (UiComponent, Display) -> Unit, onReset: () -> Unit) {
    val components = UiComponent.entries.filter { it.page == page }
    val changed = components.count { layout.display(it) != it.default }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(page.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${components.size} components" + if (changed > 0) " · $changed changed" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (changed > 0) TextButton(onClick = onReset) { Text("Reset") }
            }
            components.forEachIndexed { i, c ->
                HorizontalDivider(Modifier.padding(vertical = if (i == 0) 8.dp else 10.dp))
                ComponentRow(c, layout.display(c)) { onSet(c, it) }
            }
        }
    }
}

@Composable
private fun ComponentRow(c: UiComponent, current: Display, onSelect: (Display) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            c.description + (c.simplified?.let { " · Simplified: $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            c.modes.forEachIndexed { i, d ->
                SegmentedButton(
                    selected = current == d,
                    onClick = { onSelect(d) },
                    shape = SegmentedButtonDefaults.itemShape(i, c.modes.size),
                ) { Text(d.label) }
            }
        }
    }
}

/** Light / dark / system theme (moved here from the Overview top bar). */
@Composable
private fun AppearanceCard(viewModel: ThemeSettingsViewModel = hiltViewModel()) {
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = com.fundamentalanalysis.app.domain.model.ThemeMode.entries
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { viewModel.select(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(if (m == com.fundamentalanalysis.app.domain.model.ThemeMode.SYSTEM) "System" else m.label) }
                }
            }
        }
    }
}
