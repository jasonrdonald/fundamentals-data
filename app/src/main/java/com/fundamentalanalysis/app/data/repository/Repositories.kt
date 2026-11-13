package com.fundamentalanalysis.app.data.repository

import com.fundamentalanalysis.app.data.barEntities
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.SyncPreferences
import com.fundamentalanalysis.app.data.local.WatchlistDao
import com.fundamentalanalysis.app.data.local.WatchlistEntity
import com.fundamentalanalysis.app.data.remote.RemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.SeedDatasetSource
import com.fundamentalanalysis.app.data.remote.dto.DatasetDto
import com.fundamentalanalysis.app.data.toComposition
import com.fundamentalanalysis.app.data.toDomain
import com.fundamentalanalysis.app.data.toEntities
import com.fundamentalanalysis.app.data.toEntity
import com.fundamentalanalysis.app.data.toRows
import com.fundamentalanalysis.app.data.valuationEntities
import com.fundamentalanalysis.app.di.IoDispatcher
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.DataOrigin
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.SyncStatus
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Dataset schema this build understands; newer files are ignored until the app is updated. */
const val SUPPORTED_SCHEMA_VERSION = 3

@Singleton
class OfflineFirstMarketRepository @Inject constructor(
    private val dao: MarketDao,
    private val remote: RemoteDatasetSource,
    private val seed: SeedDatasetSource,
    private val syncPreferences: SyncPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MarketRepository {

    private val writeLock = Mutex()

    override fun observeIndices(): Flow<List<MarketIndex>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeIndex(symbol: String): Flow<MarketIndex?> =
        dao.observe(symbol).map { it?.toDomain() }

    override fun observeComposition(): Flow<Composition?> =
        dao.observeConstituents().map { it.toComposition() }

    override fun observeStocks(): Flow<List<com.fundamentalanalysis.app.domain.model.Stock>> =
        dao.observeStocks().map { rows -> rows.map { it.toDomain() } }

    override fun observeStock(symbol: String): Flow<com.fundamentalanalysis.app.domain.model.Stock?> =
        dao.observeStock(symbol).map { it?.toDomain() }

    override fun observeSyncStatus(): Flow<SyncStatus> = syncPreferences.metadata.map {
        SyncStatus(
            origin = it.origin,
            datasetVersion = it.version,
            datasetUpdatedAt = it.updatedAt,
            lastSyncedAtMillis = it.lastSyncedAtMillis,
            remoteConfigured = remote.isConfigured,
        )
    }

    override suspend fun ensureData() = withContext(io) {
        writeLock.withLock {
            val current = syncPreferences.current()
            val empty = dao.count() == 0
            // Remote data is refreshed separately; only bundled data can be superseded by an app
            // update. The version check avoids parsing the bundled file on every launch.
            if (empty || (current.origin != DataOrigin.SHARED_REMOTE && seed.version > current.version)) {
                val bundled = seed.load()
                if (bundled.schemaVersion == SUPPORTED_SCHEMA_VERSION && (empty || bundled.version > current.version)) {
                    store(bundled, DataOrigin.BUNDLED_SAMPLE)
                }
            }
        }
    }

    override suspend fun refresh(): RefreshResult = withContext(io) {
        if (!remote.isConfigured) return@withContext RefreshResult.NotConfigured
        try {
            val dataset = remote.fetch()
            if (dataset.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                return@withContext RefreshResult.Failed(
                    "Shared dataset uses schema ${dataset.schemaVersion}; this app supports $SUPPORTED_SCHEMA_VERSION. Update the app.",
                )
            }
            writeLock.withLock {
                val current = syncPreferences.current()
                if (dataset.version <= current.version && dao.count() > 0) {
                    syncPreferences.touch(System.currentTimeMillis())
                    RefreshResult.UpToDate
                } else {
                    store(dataset, DataOrigin.SHARED_REMOTE)
                    RefreshResult.Updated(dataset.version, dataset.indices.size)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RefreshResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private suspend fun store(dataset: DatasetDto, origin: DataOrigin) {
        dao.replaceAll(
            indices = dataset.indices.map { it.toEntity() },
            bars = dataset.indices.flatMap { it.barEntities() },
            valuation = dataset.indices.flatMap { it.valuationEntities() },
            constituents = dataset.composition?.toEntities().orEmpty(),
            stocks = dataset.stocks.toRows(),
        )
        syncPreferences.save(origin, dataset.version, dataset.updatedAt, System.currentTimeMillis())
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
