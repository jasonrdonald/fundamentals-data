package com.fundamentalanalysis.app.data.sync

import com.fundamentalanalysis.app.data.DAILY
import com.fundamentalanalysis.app.data.MONTHLY
import com.fundamentalanalysis.app.data.local.AnalyticsEntity
import com.fundamentalanalysis.app.data.local.CoreLists
import com.fundamentalanalysis.app.data.local.DatasetPartEntity
import com.fundamentalanalysis.app.data.local.DatasetUpdate
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.MetaEntity
import com.fundamentalanalysis.app.data.local.StockFinancialsEntity
import com.fundamentalanalysis.app.data.local.SyncEventEntity
import com.fundamentalanalysis.app.data.remote.DatasetFileSource
import com.fundamentalanalysis.app.data.remote.RemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.SeedDatasetSource
import com.fundamentalanalysis.app.data.remote.dto.AnalyticsPartDto
import com.fundamentalanalysis.app.data.remote.dto.CorePartDto
import com.fundamentalanalysis.app.data.remote.dto.DailyPartDto
import com.fundamentalanalysis.app.data.remote.dto.FundamentalsPartDto
import com.fundamentalanalysis.app.data.remote.dto.ManifestDto
import com.fundamentalanalysis.app.data.remote.dto.MonthlyPartDto
import com.fundamentalanalysis.app.data.remote.dto.PartMetaDto
import com.fundamentalanalysis.app.data.toLists
import com.fundamentalanalysis.app.data.toRows
import com.fundamentalanalysis.app.di.ApplicationScope
import com.fundamentalanalysis.app.di.IoDispatcher
import com.fundamentalanalysis.app.domain.model.DataOrigin
import com.fundamentalanalysis.app.domain.model.DataPart
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.SyncEventType
import com.fundamentalanalysis.app.domain.model.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Dataset schema this build understands; newer manifests are ignored until the app is updated. */
const val SUPPORTED_SCHEMA_VERSION = 5

private const val KEEP_EVENTS = 200

internal object MetaKeys {
    const val MANIFEST = "manifest"
    const val MANIFEST_ORIGIN = "manifest_origin"
    const val CHECKED_AT = "checked_at"
}

class IntegrityException(message: String) : Exception(message)

/** Parts in the "primary" group are enough for every screen; the rest load in the background. */
internal val PartMetaDto.isPrimary: Boolean get() = group == "primary" || (group.isEmpty() && name in setOf("analytics", "core"))

/**
 * Progressive, incremental loading of the partitioned dataset (schema 5).
 *
 * 1. [ensurePrimary] (blocks the first frame only on a fresh install or after an app update):
 *    stores the bundled primary parts (analytics, lists, daily months, recent years) from the APK.
 * 2. [launchBackgroundSync]: loads the bundled history parts if missing, then checks the remote
 *    manifest and downloads only the parts whose version changed: primary parts first, written in
 *    one transaction, history parts last. On a normal day that is the analytics, the current month
 *    and the current year.
 *
 * Every file is checked against the manifest's SHA-256 before use, and every step is logged to
 * the sync_events table for the Data page. Data stored by an older schema is cleared and reloaded.
 */
@Singleton
class DatasetSync @Inject constructor(
    private val dao: MarketDao,
    private val remote: RemoteDatasetSource,
    private val seed: SeedDatasetSource,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()
    val remoteConfigured: Boolean get() = remote.isConfigured
    private var background: Job? = null

    /** Stores the bundled primary parts that are missing or older than the APK's copy. */
    suspend fun ensurePrimary() = withContext(io) {
        mutex.withLock {
            clearOlderSchema()
            loadBundled { it.isPrimary }
        }
    }

    /** Runs after the first frame: bundled history, then the remote check. Never blocks the UI. */
    fun launchBackgroundSync(): Job {
        background?.takeIf { it.isActive }?.let { return it }
        return scope.launch(io) {
            mutex.withLock { runCatching { loadBundled { !it.isPrimary } } }
            refresh(force = false)
        }.also { background = it }
    }

    /**
     * Loads the bundled parts [which] selects, unless the device already holds a newer published
     * version (bundled data never replaces newer downloaded data).
     */
    private suspend fun loadBundled(which: (PartMetaDto) -> Boolean) {
        val raw = seed.manifest()
        val manifest = parseManifest(raw)
        val stored = storedManifest()
        if (stored != null && stored.version > manifest.version) return
        val parts = dao.parts().associateBy { it.name }
        val needed = manifest.parts.filter { which(it) && (parts[it.name]?.version ?: 0) < it.version }
        if (stored == null || stored.version < manifest.version) saveManifest(raw, DataOrigin.BUNDLED)
        if (needed.isNotEmpty()) load(seed, needed, DataOrigin.BUNDLED, manifest, dropped(manifest, parts.keys))
    }

    /** Data written by an app that read an older schema is cleared, then reloaded from the APK. */
    private suspend fun clearOlderSchema() {
        val raw = dao.meta(MetaKeys.MANIFEST)?.takeIf { it.isNotBlank() } ?: return
        val schema = runCatching { parseManifest(raw.encodeToByteArray()).schemaVersion }.getOrNull()
        if (schema == null || schema < SUPPORTED_SCHEMA_VERSION) {
            dao.clearDataset()
            dao.putMeta(MetaEntity(MetaKeys.MANIFEST, ""))
            log(SyncEventType.RESET, null, null, null, null, true,
                "Data format updated (schema ${schema ?: "?"} to $SUPPORTED_SCHEMA_VERSION); reloading the bundled copy")
        }
    }

    /**
     * Checks the published manifest and downloads changed parts. [force] revalidates the manifest
     * with the server instead of accepting a cached copy.
     */
    suspend fun refresh(force: Boolean): RefreshResult = withContext(io) {
        if (!remote.isConfigured) return@withContext RefreshResult.NotConfigured
        mutex.withLock { syncFromRemote(force, everything = false) }
    }

    /** Downloads every part again, whatever the stored versions. */
    suspend fun reloadAll(): RefreshResult = withContext(io) {
        if (!remote.isConfigured) return@withContext RefreshResult.NotConfigured
        mutex.withLock { syncFromRemote(force = true, everything = true) }
    }

    /** Drops all shared data and reloads the copy bundled with the app. The watchlist stays. */
    suspend fun resetToBundled() = withContext(io) {
        mutex.withLock {
            dao.clearDataset()
            log(SyncEventType.RESET, null, null, null, null, true, "Cleared local data; reloading the bundled copy")
            val raw = seed.manifest()
            saveManifest(raw, DataOrigin.BUNDLED)
            val manifest = parseManifest(raw)
            load(seed, manifest.parts, DataOrigin.BUNDLED, manifest)
        }
    }

    private suspend fun syncFromRemote(force: Boolean, everything: Boolean): RefreshResult {
        val started = System.currentTimeMillis()
        _progress.value = SyncProgress(active = true, step = "Checking for updates")
        return try {
            val raw = remote.manifest(revalidate = force)
            val manifest = parseManifest(raw)
            dao.putMeta(MetaEntity(MetaKeys.CHECKED_AT, System.currentTimeMillis().toString()))
            if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                val message = "Published data uses schema ${manifest.schemaVersion}; this app reads $SUPPORTED_SCHEMA_VERSION. Update the app."
                log(SyncEventType.ERROR, null, manifest.version, null, null, false, message)
                return RefreshResult.Failed(message)
            }
            saveManifest(raw, DataOrigin.REMOTE)
            val stored = dao.parts().associateBy { it.name }
            val changed = manifest.parts.filter { everything || (stored[it.name]?.version ?: 0) < it.version }
            val removed = dropped(manifest, stored.keys)
            if (changed.isEmpty() && removed.isEmpty()) {
                log(SyncEventType.CHECK, null, manifest.version, raw.size.toLong(), System.currentTimeMillis() - started, true,
                    "Up to date (published v${manifest.version}, data as of ${manifest.asOf ?: "?"})")
                RefreshResult.UpToDate
            } else {
                val kb = changed.sumOf { it.bytes } / 1024
                log(SyncEventType.CHECK, null, manifest.version, raw.size.toLong(), System.currentTimeMillis() - started, true,
                    "Published v${manifest.version}: ${changed.size} of ${manifest.parts.size} files to download ($kb KB)" +
                        if (removed.isNotEmpty()) "; ${removed.size} retired" else "")
                load(remote, changed, DataOrigin.REMOTE, manifest, removed)
                RefreshResult.Updated(manifest.version, changed.map { DataPart.forPart(it.name, it.group) }.distinct().sorted())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "Update check failed: " + (e.message?.trim()?.ifEmpty { null } ?: e::class.simpleName.orEmpty())
            log(SyncEventType.ERROR, null, null, null, System.currentTimeMillis() - started, false, message)
            RefreshResult.Failed(message)
        } finally {
            _progress.value = SyncProgress()
        }
    }

    /** Stored parts the manifest no longer lists. */
    private fun dropped(manifest: ManifestDto, stored: Set<String>): List<String> =
        (stored - manifest.parts.map { it.name }.toSet()).sorted()

    /** Loads [metas]: the primary parts together first (one transaction), then the history parts. */
    private suspend fun load(
        source: DatasetFileSource, metas: List<PartMetaDto>, origin: DataOrigin, manifest: ManifestDto, removed: List<String> = emptyList(),
    ) {
        val (primary, history) = metas.partition { it.isPrimary }
        val remoteLoad = origin == DataOrigin.REMOTE
        try {
            if (primary.isNotEmpty() || removed.isNotEmpty()) {
                _progress.value = SyncProgress(true, if (remoteLoad) "Downloading latest data" else "Preparing data", DataPart.CORE)
                apply(source, primary, origin, manifest, removed, DataPart.CORE)
            }
            if (history.isNotEmpty()) {
                _progress.value = SyncProgress(true, if (remoteLoad) "Downloading history" else "Loading history", DataPart.HISTORY)
                apply(source, history, origin, manifest, emptyList(), DataPart.HISTORY)
            }
        } finally {
            _progress.value = SyncProgress()
        }
    }

    private class Fetched(val meta: PartMetaDto, val bytes: ByteArray, val fetchMillis: Long)

    private suspend fun fetch(source: DatasetFileSource, meta: PartMetaDto, origin: DataOrigin): Fetched {
        val t0 = System.currentTimeMillis()
        val bytes = source.file(meta.file)
        val sha = sha256(bytes)
        if (meta.sha256.isNotEmpty() && !sha.equals(meta.sha256, ignoreCase = true)) {
            throw IntegrityException("${meta.name} v${meta.version}: checksum mismatch (the published files may still be updating; try again shortly)")
        }
        val ms = System.currentTimeMillis() - t0
        if (origin == DataOrigin.REMOTE) {
            log(SyncEventType.DOWNLOAD, meta.name, meta.version, bytes.size.toLong(), ms, true, "Downloaded ${meta.name} v${meta.version}")
        }
        return Fetched(meta, bytes, ms)
    }

    /** Fetches and verifies every part first, then merges them and writes one transaction. */
    private suspend fun apply(
        source: DatasetFileSource, metas: List<PartMetaDto>, origin: DataOrigin, manifest: ManifestDto, removed: List<String>, group: DataPart,
    ) {
        val fetched = metas.map { fetch(source, it, origin) }
        val t0 = System.currentTimeMillis()
        var core: CoreLists? = null
        var analytics: List<AnalyticsEntity>? = null
        var financials: List<StockFinancialsEntity> = emptyList()
        val hasPrices = fetched.any { it.meta.name.startsWith("daily-") || it.meta.name.startsWith("monthly-") }
        val trim = manifest.dailyFrom?.takeIf { group == DataPart.CORE }?.let(LocalDate::parse)
        val merger = if (hasPrices || trim != null) {
            PartMerger(dao.indexSeries(DAILY) + dao.indexSeries(MONTHLY), dao.valuations(), dao.stockSeries())
        } else null
        for (f in fetched) {
            val text = f.bytes.decodeToString()
            val name = f.meta.name
            when {
                name == "analytics" -> analytics = json.decodeFromString<AnalyticsPartDto>(text).toRows(json)
                name == "core" -> core = json.decodeFromString<CorePartDto>(text).toLists()
                name == "fundamentals" -> financials = json.decodeFromString<FundamentalsPartDto>(text).toRows(json)
                name.startsWith("daily-") -> merger?.add(json.decodeFromString<DailyPartDto>(text))
                name.startsWith("monthly-") -> merger?.add(json.decodeFromString<MonthlyPartDto>(text))
                // Parts a newer pipeline adds are recorded but not read.
            }
        }
        trim?.let { merger?.trimDaily(it) }
        val writeStart = System.currentTimeMillis()
        val parseMillis = writeStart - t0
        dao.applyUpdate(
            DatasetUpdate(
                core = core,
                analytics = analytics,
                indexSeries = merger?.indexSeriesRows().orEmpty(),
                valuation = merger?.valuationRows().orEmpty(),
                stockSeries = merger?.stockSeriesRows().orEmpty(),
                financials = financials,
                parts = fetched.map {
                    DatasetPartEntity(it.meta.name, it.meta.version, it.meta.sha256, it.bytes.size.toLong(), it.meta.records,
                        origin.name, manifest.asOf, writeStart, it.fetchMillis + parseMillis / fetched.size)
                },
                dropParts = removed,
            ),
        )
        val names = fetched.map { it.meta.name }
        val summary = when {
            names.isEmpty() -> "nothing new; retired ${removed.size} old file(s)"
            names.size <= 3 -> names.joinToString(" + ")
            else -> "${names.size} files (${names.take(2).joinToString(", ")}, …)"
        }
        log(SyncEventType.LOAD, group.key, manifest.version, fetched.sumOf { it.bytes.size.toLong() }, System.currentTimeMillis() - t0, true,
            "Loaded $summary (${origin.label()}) · parse $parseMillis ms, write ${System.currentTimeMillis() - writeStart} ms")
    }

    private suspend fun storedManifest(): ManifestDto? =
        dao.meta(MetaKeys.MANIFEST)?.takeIf { it.isNotBlank() }?.let { runCatching { parseManifest(it.encodeToByteArray()) }.getOrNull() }

    private fun parseManifest(raw: ByteArray): ManifestDto = json.decodeFromString<ManifestDto>(raw.decodeToString())

    private suspend fun saveManifest(raw: ByteArray, origin: DataOrigin) {
        dao.putMeta(MetaEntity(MetaKeys.MANIFEST, raw.decodeToString()))
        dao.putMeta(MetaEntity(MetaKeys.MANIFEST_ORIGIN, origin.name))
    }

    private suspend fun log(
        type: SyncEventType, part: String?, version: Int?, bytes: Long?, durationMillis: Long?, success: Boolean, message: String,
    ) {
        dao.insertEvent(SyncEventEntity(0, System.currentTimeMillis(), type.name, part, version, bytes, durationMillis, success, message))
        dao.trimEvents(KEEP_EVENTS)
    }

    private fun DataOrigin.label() = if (this == DataOrigin.BUNDLED) "bundled" else "downloaded"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
