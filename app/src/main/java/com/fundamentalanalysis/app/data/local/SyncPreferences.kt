package com.fundamentalanalysis.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fundamentalanalysis.app.domain.model.DataOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SyncMetadata(
    val origin: DataOrigin,
    val version: Int,
    val updatedAt: String?,
    val lastSyncedAtMillis: Long?,
)

/** Remembers which dataset version is currently stored locally. */
@Singleton
class SyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val metadata: Flow<SyncMetadata> = dataStore.data.map { prefs ->
        SyncMetadata(
            origin = prefs[ORIGIN]?.let { runCatching { DataOrigin.valueOf(it) }.getOrNull() } ?: DataOrigin.NONE,
            version = prefs[VERSION] ?: 0,
            updatedAt = prefs[UPDATED_AT],
            lastSyncedAtMillis = prefs[LAST_SYNCED],
        )
    }

    suspend fun current(): SyncMetadata = metadata.first()

    suspend fun save(origin: DataOrigin, version: Int, updatedAt: String?, syncedAtMillis: Long) {
        dataStore.edit { prefs ->
            prefs[ORIGIN] = origin.name
            prefs[VERSION] = version
            if (updatedAt != null) prefs[UPDATED_AT] = updatedAt else prefs.remove(UPDATED_AT)
            prefs[LAST_SYNCED] = syncedAtMillis
        }
    }

    suspend fun touch(syncedAtMillis: Long) {
        dataStore.edit { it[LAST_SYNCED] = syncedAtMillis }
    }

    private companion object {
        val ORIGIN = stringPreferencesKey("dataset_origin")
        val VERSION = intPreferencesKey("dataset_version")
        val UPDATED_AT = stringPreferencesKey("dataset_updated_at")
        val LAST_SYNCED = longPreferencesKey("last_synced_at")
    }
}
