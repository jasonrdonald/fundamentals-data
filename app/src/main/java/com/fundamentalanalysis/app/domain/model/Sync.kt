package com.fundamentalanalysis.app.domain.model

import com.fundamentalanalysis.app.domain.analysis.CalendarTable

/** The dataset is split into parts that load in this order (see tools/derive_parts.py). */
enum class DataPart(val key: String, val label: String) {
    /** Precomputed metrics, sector aggregates and calendar tables. */
    ANALYTICS("analytics", "Analytics"),
    /** Latest sessions, recent months, constituents, stock list. */
    CORE("core", "Recent data"),
    /** Full monthly history, valuation history, stock daily closes, financials. */
    HISTORY("history", "History");

    companion object {
        fun from(key: String): DataPart? = entries.firstOrNull { it.key == key }
    }
}

enum class DataOrigin { BUNDLED, REMOTE }

/** A part as stored on this device. */
data class StoredPart(
    val part: DataPart,
    val version: Int,
    val bytes: Long,
    val records: Int,
    val origin: DataOrigin,
    val asOf: String?,
    val loadedAtMillis: Long,
    val loadMillis: Long,
)

/** A part as published (from the last manifest seen). */
data class PublishedPart(
    val part: DataPart,
    val version: Int,
    val bytes: Long,
    val records: Int,
    val description: String,
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
) {
    /** Recent data and analytics are in: every screen can render. */
    val primaryReady: Boolean get() = DataPart.CORE in stored && DataPart.ANALYTICS in stored
    val historyReady: Boolean get() = DataPart.HISTORY in stored
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
