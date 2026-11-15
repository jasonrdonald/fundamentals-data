package com.fundamentalanalysis.app.data.repository

import com.fundamentalanalysis.app.BuildConfig
import com.fundamentalanalysis.app.data.AnalyticsKind
import com.fundamentalanalysis.app.data.decodeReturns
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.WatchlistDao
import com.fundamentalanalysis.app.data.local.WatchlistEntity
import com.fundamentalanalysis.app.data.remote.dto.CalendarDto
import com.fundamentalanalysis.app.data.remote.dto.IndexMetricsDto
import com.fundamentalanalysis.app.data.remote.dto.ManifestDto
import com.fundamentalanalysis.app.data.remote.dto.SectorStatDto
import com.fundamentalanalysis.app.data.remote.dto.StockMetricsDto
import com.fundamentalanalysis.app.data.sync.DatasetSync
import com.fundamentalanalysis.app.data.sync.MetaKeys
import com.fundamentalanalysis.app.data.toCompositions
import com.fundamentalanalysis.app.data.toDomain
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.DataAvailability
import com.fundamentalanalysis.app.domain.model.DataOrigin
import com.fundamentalanalysis.app.domain.model.DataPart
import com.fundamentalanalysis.app.domain.model.DataStatus
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.PipelineRun
import com.fundamentalanalysis.app.domain.model.Precomputed
import com.fundamentalanalysis.app.domain.model.PublishedPart
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StoredPart
import com.fundamentalanalysis.app.domain.model.SyncEvent
import com.fundamentalanalysis.app.domain.model.SyncEventType
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first access to the shared dataset. Room is the single source of truth; [DatasetSync]
 * fills it progressively. All mapping runs off the main thread.
 */
private const val HEADLINE = "NIFTY 50"

@Singleton
class OfflineFirstMarketRepository @Inject constructor(
    private val dao: MarketDao,
    private val sync: DatasetSync,
    private val json: Json,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : MarketRepository {

    override fun observeIndices(): Flow<List<MarketIndex>> =
        dao.observeIndices().map { rows -> rows.map { it.toDomain() } }.flowOn(cpu)

    override fun observeCompositions(): Flow<Map<String, Composition>> =
        dao.observeConstituents().map { it.toCompositions() }.flowOn(cpu)

    override fun observeComposition(): Flow<Composition?> =
        observeCompositions().map { it[HEADLINE] }.distinctUntilChanged()

    override fun observeStocks(): Flow<List<Stock>> =
        dao.observeStocks().map { rows -> rows.map { it.toDomain() } }.flowOn(cpu)

    override fun observeStock(symbol: String): Flow<Stock?> =
        dao.observeStock(symbol).map { it?.toDomain(json) }.flowOn(cpu)

    override fun observePrecomputed(): Flow<Precomputed> = combine(
        dao.observeAnalytics(AnalyticsKind.INDEX),
        dao.observeAnalytics(AnalyticsKind.STOCK),
        dao.observeAnalytics(AnalyticsKind.BENCHMARK),
        dao.observeAnalytics(AnalyticsKind.SECTOR),
        dao.observeAnalytics(AnalyticsKind.CALENDAR),
    ) { indices, stocks, benchmarks, sectors, calendars ->
        val bench = benchmarks.associate { it.key to decodeReturns(json, it.payload) }
        val stockDtos = stocks.map { json.decodeFromString<StockMetricsDto>(it.payload) }
        Precomputed(
            indices = indices.associate { it.key to json.decodeFromString<IndexMetricsDto>(it.payload).toDomain() },
            stocks = stockDtos.associate { it.symbol to it.toDomain(bench[it.index]) },
            sectors = sectors.mapNotNull { json.decodeFromString<SectorStatDto>(it.payload).toDomain() },
            calendars = calendars.mapNotNull { json.decodeFromString<CalendarDto>(it.payload).toDomain() }.toMap(),
        )
    }.flowOn(cpu)

    override fun observeAvailability(): Flow<DataAvailability> =
        combine(dao.observeParts(), sync.progress) { parts, progress ->
            DataAvailability(historyReady = parts.any { it.name == DataPart.HISTORY.key }, loading = progress.active)
        }.distinctUntilChanged()

    override fun observeDataStatus(): Flow<DataStatus> = combine(
        dao.observeParts(),
        dao.observeEvents(100),
        dao.observeMeta(),
        dao.observeAnalyticsCounts(),
        sync.progress,
    ) { parts, events, meta, counts, progress ->
        val metaMap = meta.associate { it.key to it.value }
        val manifest = metaMap[MetaKeys.MANIFEST]?.let { runCatching { json.decodeFromString<ManifestDto>(it) }.getOrNull() }
        DataStatus(
            stored = parts.mapNotNull { p ->
                DataPart.from(p.name)?.let {
                    it to StoredPart(it, p.version, p.bytes, p.records, runCatching { DataOrigin.valueOf(p.origin) }.getOrDefault(DataOrigin.BUNDLED),
                        p.asOf, p.loadedAtMillis, p.loadMillis)
                }
            }.toMap(),
            published = manifest?.parts.orEmpty().mapNotNull { p ->
                DataPart.from(p.name)?.let { it to PublishedPart(it, p.version, p.bytes, p.records, p.description) }
            }.toMap(),
            publishedVersion = manifest?.version,
            publishedAsOf = manifest?.asOf,
            pipelineRuns = manifest?.history.orEmpty().map { r ->
                PipelineRun(r.version, r.datasetVersion, r.generatedAt, r.asOf, r.changed.mapNotNull(DataPart::from), r.failures, r.indices, r.stocks)
            },
            events = events.map { e ->
                SyncEvent(e.atMillis, runCatching { SyncEventType.valueOf(e.type) }.getOrDefault(SyncEventType.CHECK),
                    e.part?.let(DataPart::from), e.version, e.bytes, e.durationMillis, e.success, e.message)
            },
            precomputed = counts.associate { tableLabel(it.kind) to it.count },
            lastCheckedAtMillis = metaMap[MetaKeys.CHECKED_AT]?.toLongOrNull(),
            progress = progress,
            remoteConfigured = sync.remoteConfigured,
            pipelineUrl = BuildConfig.PIPELINE_URL.ifBlank { null },
        )
    }.flowOn(cpu)

    override suspend fun ensureData() = sync.ensurePrimary()
    override fun startBackgroundSync() { sync.launchBackgroundSync() }
    override suspend fun refresh(): RefreshResult = sync.refresh(force = true)
    override suspend fun reloadAll(): RefreshResult = sync.reloadAll()
    override suspend fun resetToBundled() = sync.resetToBundled()

    private fun tableLabel(kind: String) = when (kind) {
        AnalyticsKind.INDEX -> "Index metrics"
        AnalyticsKind.STOCK -> "Stock metrics"
        AnalyticsKind.BENCHMARK -> "Benchmark returns"
        AnalyticsKind.SECTOR -> "Sector aggregates"
        AnalyticsKind.CALENDAR -> "Calendar tables"
        else -> kind
    }
}

@Singleton
class LocalWatchlistRepository @Inject constructor(
    private val dao: WatchlistDao,
) : WatchlistRepository {
    override fun observeWatchlist(): Flow<Set<String>> = dao.observeSymbols().map { it.toSet() }

    override suspend fun toggle(symbol: String) {
        if (dao.contains(symbol)) dao.delete(symbol)
        else dao.insert(WatchlistEntity(symbol, System.currentTimeMillis()))
    }
}
