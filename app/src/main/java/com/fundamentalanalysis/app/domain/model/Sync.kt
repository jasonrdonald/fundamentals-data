package com.fundamentalanalysis.app.domain.model

enum class DataOrigin { NONE, BUNDLED_SAMPLE, SHARED_REMOTE }

data class SyncStatus(
    val origin: DataOrigin,
    val datasetVersion: Int,
    val datasetUpdatedAt: String?,
    val lastSyncedAtMillis: Long?,
    val remoteConfigured: Boolean,
)

sealed interface RefreshResult {
    data class Updated(val version: Int, val indexCount: Int) : RefreshResult
    data object UpToDate : RefreshResult
    data object NotConfigured : RefreshResult
    data class Failed(val message: String) : RefreshResult
}
