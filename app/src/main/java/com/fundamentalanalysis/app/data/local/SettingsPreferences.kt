package com.fundamentalanalysis.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fundamentalanalysis.app.di.SettingsStore
import com.fundamentalanalysis.app.domain.model.Display
import com.fundamentalanalysis.app.domain.model.ThemeMode
import com.fundamentalanalysis.app.domain.model.UiComponent
import com.fundamentalanalysis.app.domain.model.UiLayout
import com.fundamentalanalysis.app.domain.model.UiPage
import com.fundamentalanalysis.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local user preferences (never part of the shared dataset). */
@Singleton
class SettingsPreferences @Inject constructor(
    @SettingsStore private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override fun observeThemeMode(): Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME] = mode.name }
    }

    override fun observeUiLayout(): Flow<UiLayout> = dataStore.data.map { UiLayout.decode(it[UI_LAYOUT]) }

    override suspend fun setDisplay(component: UiComponent, display: Display) {
        dataStore.edit { prefs ->
            val current = UiLayout.decode(prefs[UI_LAYOUT])
            prefs[UI_LAYOUT] = UiLayout(current.choices + (component to display)).encode()
        }
    }

    override suspend fun resetUiLayout(page: UiPage?) {
        dataStore.edit { prefs ->
            val current = UiLayout.decode(prefs[UI_LAYOUT])
            prefs[UI_LAYOUT] = if (page == null) "" else UiLayout(current.choices.filterKeys { it.page != page }).encode()
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        /** "COMPONENT=DISPLAY;..." with only the non-default choices. */
        val UI_LAYOUT = stringPreferencesKey("ui_layout")
    }
}
