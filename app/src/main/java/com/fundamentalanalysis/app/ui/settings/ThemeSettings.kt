package com.fundamentalanalysis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fundamentalanalysis.app.domain.model.ThemeMode
import com.fundamentalanalysis.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settings.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun select(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }
}

/** Top-bar action that opens the appearance picker. */
@Composable
fun ThemeAction(viewModel: ThemeSettingsViewModel = hiltViewModel()) {
    var open by remember { mutableStateOf(false) }
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.BrightnessMedium, contentDescription = "Appearance: ${mode.label}")
    }
    if (open) {
        ThemeDialog(current = mode, onSelect = { viewModel.select(it); open = false }, onDismiss = { open = false })
    }
}

@Composable
private fun ThemeDialog(current: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == current, onClick = { onSelect(mode) }, role = Role.RadioButton)
                            .padding(vertical = 10.dp),
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            if (mode == ThemeMode.SYSTEM) {
                                Text(
                                    "Follows your phone's light or dark setting",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
