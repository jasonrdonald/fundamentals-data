package com.fundamentalanalysis.app.domain.model

import com.fundamentalanalysis.app.domain.analysis.CalendarTable

/** The dataset is split into parts that load in this order (see tools/derive_parts.py). */
/**
 * How the Data page groups the published files. The dataset is split into many small files
 * (a file per month of daily prices, per year of month-end data); each belongs to one group.
 */
enum class DataPart(val key: String, val label: String) {
    /** Precomputed metrics, sector aggregates and calendar tables. */
    ANALYTICS("analytics", "Analytics"),
    /** Lists, daily prices and recent month-ends: everything loaded before the first screen. */
    CORE("core", "Recent data"),
    /** Older month-end years and financials, loaded in the background. */
    HISTORY("history", "History");

    companion object {
        /** The group of a published file, from its manifest group ("primary"/"history") or its name. */
        fun forPart(name: String, group: String? = null): DataPart = when {
            name == ANALYTICS.key -> ANALYTICS
            name == CORE.key || name == HISTORY.key -> entries.first { it.key == name }
            group == "primary" -> CORE
            group == "history" -> HISTORY
            name.startsWith("daily-") -> CORE
            else -> HISTORY
        }
    }
}

enum class DataOrigin { BUNDLED, REMOTE }

/** A group of files as stored on this device (version = the newest file's). */
data class StoredPart(
    val part: DataPart,
    val version: Int,
    val bytes: Long,
    val records: Int,
    val origin: DataOrigin,
    val asOf: String?,
    val loadedAtMillis: Long,
    val loadMillis: Long,
    val files: Int = 1,
)

/** A group of files as published (from the last manifest seen). */
data class PublishedPart(
    val part: DataPart,
    val version: Int,
    val bytes: Long,
    val records: Int,
    val description: String,
    val files: Int = 1,
    /** Files in this group that are newer than the device's copy, and their size. */
    val pendingFiles: Int = 0,
    val pendingBytes: Long = 0,
)

/** One run of the dataset pipeline that changed something (from the published manifest). */
data class PipelineRun(
    val version: Int,
    val datasetVersion: Int?,
    val generatedAt: String?,
    val asOf: String?,
    val changed: List<DataPart>,
    val failures: List<String>,
    val indices: Int,
    val stocks: Int,
)

enum class SyncEventType { CHECK, DOWNLOAD, LOAD, ERROR, RESET }

/** Something this device did with the dataset. */
data class SyncEvent(
    val atMillis: Long,
    val type: SyncEventType,
    val part: DataPart?,
    val version: Int?,
    val bytes: Long?,
    val durationMillis: Long?,
    val success: Boolean,
    val message: String,
)

/** What the background loader is doing right now. */
data class SyncProgress(
    val active: Boolean = false,
    val step: String? = null,
    val part: DataPart? = null,
)

/** Everything the Data page shows. */
data class DataStatus(
    val stored: Map<DataPart, StoredPart>,
    val published: Map<DataPart, PublishedPart>,
    val publishedVersion: Int?,
    val publishedAsOf: String?,
    val pipelineRuns: List<PipelineRun>,
    val events: List<SyncEvent>,
    /** Precomputed table name -> row count. */
    val precomputed: Map<String, Int>,
    val lastCheckedAtMillis: Long?,
    val progress: SyncProgress,
    val remoteConfigured: Boolean,
    val pipelineUrl: String?,
    /** Every history file the last manifest lists is stored. */
    val historyReady: Boolean = DataPart.HISTORY in stored,
) {
    /** Recent data and analytics are in: every screen can render. */
    val primaryReady: Boolean get() = DataPart.CORE in stored && DataPart.ANALYTICS in stored
    val asOf: String? get() = stored[DataPart.CORE]?.asOf
}

/** Lightweight availability flags for screens that gate long-range views. */
data class DataAvailability(
    val historyReady: Boolean,
    val loading: Boolean,
)

sealed interface RefreshResult {
    data class Updated(val version: Int, val parts: List<DataPart>) : RefreshResult
    data object UpToDate : RefreshResult
    data object NotConfigured : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

/** Sector aggregate for one universe and period, precomputed by the dataset pipeline. */
data class SectorStat(
    val universe: String,
    val period: Period,
    val sector: String,
    val stocks: Int,
    val measured: Int,
    val outperformers: Int,
    val averageReturn: Double?,
    val benchmarkReturn: Double?,
)

enum class CalendarKind { RETURNS, PE, PB, DIVIDEND_YIELD }

/**
 * Analysis-format data shipped ready-made with the dataset. [indices] lack the latest session and
 * monthly moves, which come from the bars; [stocks] already include their benchmark returns.
 */
data class Precomputed(
    val indices: Map<String, IndexAnalytics> = emptyMap(),
    val stocks: Map<String, StockMetrics> = emptyMap(),
    val sectors: List<SectorStat> = emptyList(),
    val calendars: Map<Pair<String, CalendarKind>, CalendarTable> = emptyMap(),
)
