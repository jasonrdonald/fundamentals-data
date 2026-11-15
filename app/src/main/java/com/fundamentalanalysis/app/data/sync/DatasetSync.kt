package com.fundamentalanalysis.app.data.sync

import com.fundamentalanalysis.app.data.MONTHLY
import com.fundamentalanalysis.app.data.local.DatasetPartEntity
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.MetaEntity
import com.fundamentalanalysis.app.data.local.SyncEventEntity
import com.fundamentalanalysis.app.data.remote.DatasetFileSource
import com.fundamentalanalysis.app.data.remote.RemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.SeedDatasetSource
import com.fundamentalanalysis.app.data.remote.dto.AnalyticsPartDto
import com.fundamentalanalysis.app.data.remote.dto.CorePartDto
import com.fundamentalanalysis.app.data.remote.dto.HistoryPartDto
import com.fundamentalanalysis.app.data.remote.dto.ManifestDto
import com.fundamentalanalysis.app.data.remote.dto.PartMetaDto
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
import javax.inject.Inject
import javax.inject.Singleton

/** Dataset schema this build understands; newer manifests are ignored until the app is updated. */
const val SUPPORTED_SCHEMA_VERSION = 4

private val PRIMARY = setOf(DataPart.ANALYTICS.key, DataPart.CORE.key)
private const val KEEP_EVENTS = 200

internal object MetaKeys {
    const val MANIFEST = "manifest"
    const val MANIFEST_ORIGIN = "manifest_origin"
    const val CHECKED_AT = "checked_at"
}

class IntegrityException(message: String) : Exception(message)

/**
 * Progressive, incremental loading of the shared dataset.
 *
 * 1. [ensurePrimary] (blocks the first frame only on a fresh install): stores the analytics and
 *    core parts from the APK. That is enough for every screen; it takes well under a second.
 * 2. [launchBackgroundSync]: loads bundled history if missing, then checks the remote manifest and
 *    downloads only the parts whose version changed - analytics + core first, swapped in one
 *    transaction, history last.
 *
 * Every file is checked against the manifest's SHA-256 before use, and every step is logged to
 * the sync_events table for the Data page.
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

    /** Stores bundled analytics + core when missing or older than the APK's copy. */
    suspend fun ensurePrimary() = withContext(io) {
        mutex.withLock {
            val manifest = parseManifest(seed.manifest())
            val stored = dao.parts().associateBy { it.name }
            val needed = manifest.parts.filter { it.name in PRIMARY && (stored[it.name]?.version ?: 0) < it.version }
            if (dao.meta(MetaKeys.MANIFEST) == null) saveManifest(seed.manifest(), DataOrigin.BUNDLED)
            if (needed.isNotEmpty()) load(seed, needed, DataOrigin.BUNDLED)
        }
    }

    /** Runs after the first frame: bundled history, then the remote check. Never blocks the UI. */
    fun launchBackgroundSync(): Job {
        background?.takeIf { it.isActive }?.let { return it }
        return scope.launch(io) {
            mutex.withLock {
                runCatching {
                    val manifest = parseManifest(seed.manifest())
                    val stored = dao.parts().associateBy { it.name }
                    val history = manifest.parts.filter { it.name == DataPart.HISTORY.key && (stored[it.name]?.version ?: 0) < it.version }
                    if (history.isNotEmpty()) load(seed, history, DataOrigin.BUNDLED)
                }
            }
            refresh(force = false)
        }.also { background = it }
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
            load(seed, parseManifest(raw).parts, DataOrigin.BUNDLED)
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
            if (changed.isEmpty()) {
                log(SyncEventType.CHECK, null, manifest.version, raw.size.toLong(), System.currentTimeMillis() - started, true,
                    "Up to date (published v${manifest.version}, data as of ${manifest.asOf ?: "?"})")
                RefreshResult.UpToDate
            } else {
                log(SyncEventType.CHECK, null, manifest.version, raw.size.toLong(), System.currentTimeMillis() - started, true,
                    "Published v${manifest.version}: ${changed.joinToString { it.name }} to download")
                load(remote, changed, DataOrigin.REMOTE)
                RefreshResult.Updated(manifest.version, changed.mapNotNull { DataPart.from(it.name) })
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

    /** Loads [metas]: analytics + core together first (one transaction), then history. */
    private suspend fun load(source: DatasetFileSource, metas: List<PartMetaDto>, origin: DataOrigin) {
        try {
            val primary = metas.filter { it.name in PRIMARY }
            if (primary.isNotEmpty()) {
                _progress.value = SyncProgress(true, if (origin == DataOrigin.REMOTE) "Downloading latest data" else "Preparing data", DataPart.CORE)
                loadPrimary(source, primary, origin)
            }
            metas.firstOrNull { it.name == DataPart.HISTORY.key }?.let { meta ->
                _progress.value = SyncProgress(true, if (origin == DataOrigin.REMOTE) "Downloading history" else "Loading history", DataPart.HISTORY)
                loadHistory(source, meta, origin)
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

    private suspend fun loadPrimary(source: DatasetFileSource, metas: List<PartMetaDto>, origin: DataOrigin) {
        val fetched = metas.map { fetch(source, it, origin) }.associateBy { it.meta.name }
        val t0 = System.currentTimeMillis()
        val core = fetched[DataPart.CORE.key]?.let { json.decodeFromString<CorePartDto>(it.bytes.decodeToString()) }
        val analytics = fetched[DataPart.ANALYTICS.key]?.let { json.decodeFromString<AnalyticsPartDto>(it.bytes.decodeToString()) }
        val coreRows = core?.toRows(
            storedMonthly = dao.indexSeries(MONTHLY).associate { it.symbol to it.bars },
            storedStocks = dao.stockSeries().associateBy { it.symbol },
        )
        val analyticsRows = analytics?.toRows(json)
        val writeStart = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        fun entity(f: Fetched, asOf: String?, extraMillis: Long) = DatasetPartEntity(
            f.meta.name, f.meta.version, f.meta.sha256, f.bytes.size.toLong(), f.meta.records, origin.name, asOf, now, f.fetchMillis + extraMillis,
        )
        val parseMillis = writeStart - t0
        when {
            coreRows != null && analyticsRows != null -> {
                dao.applyPrimary(
                    coreRows, entity(fetched.getValue(DataPart.CORE.key), core.asOf, parseMillis),
                    analyticsRows, entity(fetched.getValue(DataPart.ANALYTICS.key), analytics.asOf, 0),
                )
            }
            coreRows != null -> dao.applyCore(coreRows, entity(fetched.getValue(DataPart.CORE.key), core.asOf, parseMillis))
            analyticsRows != null -> dao.applyAnalytics(analyticsRows, entity(fetched.getValue(DataPart.ANALYTICS.key), analytics.asOf, parseMillis))
        }
        val total = System.currentTimeMillis() - t0
        val names = fetched.values.joinToString(" + ") { "${it.meta.name} v${it.meta.version}" }
        log(SyncEventType.LOAD, fetched.keys.singleOrNull(), fetched.values.maxOf { it.meta.version }, fetched.values.sumOf { it.bytes.size.toLong() },
            total, true, "Loaded $names (${origin.label()}) · parse ${parseMillis} ms, write ${System.currentTimeMillis() - writeStart} ms")
    }

    private suspend fun loadHistory(source: DatasetFileSource, meta: PartMetaDto, origin: DataOrigin) {
        val f = fetch(source, meta, origin)
        val t0 = System.currentTimeMillis()
        val dto = json.decodeFromString<HistoryPartDto>(f.bytes.decodeToString())
        val rows = dto.toRows(
            json,
            storedMonthly = dao.indexSeries(MONTHLY).associate { it.symbol to it.bars },
            storedStocks = dao.stockSeries().associateBy { it.symbol },
        )
        val writeStart = System.currentTimeMillis()
        dao.applyHistory(
            rows,
            DatasetPartEntity(meta.name, meta.version, meta.sha256, f.bytes.size.toLong(), meta.records, origin.name, dto.asOf,
                System.currentTimeMillis(), f.fetchMillis + (writeStart - t0)),
        )
        val total = System.currentTimeMillis() - t0
        log(SyncEventType.LOAD, meta.name, meta.version, f.bytes.size.toLong(), total, true,
            "Loaded ${meta.name} v${meta.version} (${origin.label()}) · parse ${writeStart - t0} ms, write ${System.currentTimeMillis() - writeStart} ms")
    }

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
