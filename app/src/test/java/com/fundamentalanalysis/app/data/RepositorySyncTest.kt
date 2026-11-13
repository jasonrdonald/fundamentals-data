package com.fundamentalanalysis.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fundamentalanalysis.app.data.local.BarEntity
import com.fundamentalanalysis.app.data.local.ConstituentEntity
import com.fundamentalanalysis.app.data.local.IndexEntity
import com.fundamentalanalysis.app.data.local.IndexWithBars
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.SyncPreferences
import com.fundamentalanalysis.app.data.remote.RemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.SeedDatasetSource
import com.fundamentalanalysis.app.data.remote.dto.BarDto
import com.fundamentalanalysis.app.data.remote.dto.DatasetDto
import com.fundamentalanalysis.app.data.remote.dto.IndexDto
import com.fundamentalanalysis.app.data.repository.OfflineFirstMarketRepository
import com.fundamentalanalysis.app.domain.model.DataOrigin
import com.fundamentalanalysis.app.domain.model.RefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class RepositorySyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao = FakeMarketDao()
    private val remote = FakeRemote()
    private val seed = FakeSeed(dataset(version = 1, "SEED"))
    private lateinit var prefs: SyncPreferences
    private lateinit var repo: OfflineFirstMarketRepository

    private fun setUp() {
        prefs = SyncPreferences(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { File(tmp.root, "sync.preferences_pb") },
        )
        repo = OfflineFirstMarketRepository(dao, remote, seed, prefs, Dispatchers.Unconfined)
    }

    @After fun tearDown() = dataStoreScope.cancel()

    private suspend fun symbols() = repo.observeIndices().first().map { it.symbol }

    @Test fun `first launch loads bundled data once`() = runBlocking {
        setUp()
        repo.ensureData()
        repo.ensureData()
        assertEquals(listOf("SEED"), symbols())
        assertEquals(DataOrigin.BUNDLED_SAMPLE, prefs.current().origin)
        assertEquals(1, prefs.current().version)
        assertEquals("bundled file parsed again on second launch", 1, seed.loads)
    }

    @Test fun `newer bundled dataset replaces older bundled data after app update`() = runBlocking {
        setUp()
        repo.ensureData()
        seed.dataset = dataset(version = 5, "NEWSEED")
        repo.ensureData()
        assertEquals(listOf("NEWSEED"), symbols())
    }

    @Test fun `refresh without configured url leaves data untouched`() = runBlocking {
        setUp()
        remote.configured = false
        repo.ensureData()
        assertEquals(RefreshResult.NotConfigured, repo.refresh())
        assertEquals(listOf("SEED"), symbols())
    }

    @Test fun `remote replaces bundled data only when its version is newer`() = runBlocking {
        setUp()
        repo.ensureData()
        remote.next = dataset(version = 1, "STALE")
        assertEquals(RefreshResult.UpToDate, repo.refresh())
        remote.next = dataset(version = 2, "AAA", "BBB")
        assertEquals(RefreshResult.Updated(2, 2), repo.refresh())
        assertEquals(listOf("AAA", "BBB"), symbols())
        assertEquals(DataOrigin.SHARED_REMOTE, prefs.current().origin)
        // Bundled data never overrides shared data afterwards.
        seed.dataset = dataset(version = 99, "SEED99")
        repo.ensureData()
        assertEquals(listOf("AAA", "BBB"), symbols())
    }

    @Test fun `unsupported schema is rejected without touching data`() = runBlocking {
        setUp()
        repo.ensureData()
        remote.next = dataset(version = 9, "FUTURE").copy(schemaVersion = 4)
        assertTrue(repo.refresh() is RefreshResult.Failed)
        assertEquals(listOf("SEED"), symbols())
    }

    @Test fun `network failure keeps cached data`() = runBlocking {
        setUp()
        repo.ensureData()
        remote.error = java.io.IOException("offline")
        assertTrue(repo.refresh() is RefreshResult.Failed)
        assertEquals(listOf("SEED"), symbols())
    }

    /** Contract test: the published file must parse with the app's decoder and be internally consistent. */
    @Test fun `published shared dataset matches app contract`() {
        val file = File("../shared-data/dataset.json").takeIf { it.exists() } ?: File("shared-data/dataset.json")
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val dataset = json.decodeFromString(DatasetDto.serializer(), file.readText())
        assertEquals("schemaVersion", 3, dataset.schemaVersion)
        assertTrue("version must be >= 1", dataset.version >= 1)
        assertTrue("NIFTY 50 missing", dataset.indices.any { it.symbol == "NIFTY 50" })
        val symbols = dataset.indices.map { it.symbol }
        assertEquals("duplicate symbols", symbols.size, symbols.toSet().size)
        dataset.indices.forEach { idx ->
            assertTrue("${idx.symbol}: too little daily history", idx.daily.size >= 253)
            assertTrue("${idx.symbol}: too little monthly history", idx.monthly.size >= 61)
            for (bars in listOf(idx.daily, idx.monthly)) {
                val dates = bars.map { LocalDate.parse(it.d) }
                assertEquals("${idx.symbol}: dates not strictly increasing", dates.sorted().distinct(), dates)
                bars.forEach { b ->
                    assertTrue("${idx.symbol} ${b.d}: bad OHLC $b", b.l <= minOf(b.o, b.c) + 1e-6 && b.h >= maxOf(b.o, b.c) - 1e-6 && b.l > 0)
                }
            }
            // The latest month must close where the latest session closed.
            assertEquals("${idx.symbol}: monthly/daily mismatch", idx.daily.last().c, idx.monthly.last().c, 1e-6)
        }
        // Nifty 50 carries long history and month-end valuation for the year-by-month tables.
        val nifty = dataset.indices.first { it.symbol == "NIFTY 50" }
        assertTrue("NIFTY 50 needs 10+ years of monthly history", nifty.monthly.size >= 120)
        assertTrue("NIFTY 50 valuation missing", nifty.valuation.size >= 120)
        val valuationDates = nifty.valuation.map { LocalDate.parse(it.d) }
        assertEquals("valuation dates not increasing", valuationDates.sorted().distinct(), valuationDates)
        assertEquals("one valuation point per month", valuationDates.map { it.withDayOfMonth(1) }.distinct().size, valuationDates.size)
        assertTrue("implausible P/E", nifty.valuation.all { (it.pe ?: 0.0) in 5.0..60.0 })
        // Constituent stocks: compact closes on shared calendars, each benchmarked to an index in the file.
        val stocks = dataset.stocks
        if (stocks != null) {
            val months = stocks.months.map { LocalDate.parse(it) }
            val sessions = stocks.sessions.map { LocalDate.parse(it) }
            assertEquals("months not increasing", months.sorted().distinct(), months)
            assertEquals("sessions not increasing", sessions.sorted().distinct(), sessions)
            assertEquals("stock symbols must be unique", stocks.items.size, stocks.items.map { it.symbol }.toSet().size)
            stocks.items.forEach { st ->
                assertTrue("${st.symbol}: benchmark ${st.index} missing", st.index in symbols)
                assertEquals("${st.symbol}: mc not aligned", months.size, st.mc.size)
                assertEquals("${st.symbol}: dc not aligned", sessions.size, st.dc.size)
                // Recent listings (IPOs) can have only a few months; the pipeline requires 60 sessions.
                assertTrue("${st.symbol}: too little monthly history", st.mc.count { it != null } >= 3)
                assertTrue("${st.symbol}: too little daily history", st.dc.count { it != null } >= 60)
                assertTrue("${st.symbol}: non-positive close", (st.mc + st.dc).filterNotNull().all { it > 0 })
            }
            dataset.universes.forEach { u ->
                assertEquals("${u.index}: one stock per constituent (allowing fetch failures)", true,
                    stocks.items.count { it.index == u.index } >= u.constituents.size - 3)
            }
        }
        dataset.composition?.let { comp ->
            assertTrue("composition empty", comp.constituents.isNotEmpty())
            assertTrue("constituent without industry", comp.constituents.all { it.industry.isNotBlank() })
        }
    }

    private fun dataset(version: Int, vararg symbols: String) = DatasetDto(
        schemaVersion = 3,
        version = version,
        updatedAt = "2026-11-13",
        indices = symbols.map { s ->
            IndexDto(
                symbol = s, name = s, category = "SECTOR",
                daily = listOf(BarDto("2026-11-12", 100.0, 101.0, 99.0, 100.5, 10), BarDto("2026-11-13", 100.5, 102.0, 100.0, 101.0, 12)),
                monthly = listOf(BarDto("2026-11-01", 100.0, 102.0, 99.0, 101.0, 22, last = "2026-11-13")),
            )
        },
    )

    private class FakeSeed(var dataset: DatasetDto) : SeedDatasetSource {
        var loads = 0
        override val version: Int get() = dataset.version
        override suspend fun load(): DatasetDto { loads++; return dataset }
    }

    private class FakeRemote : RemoteDatasetSource {
        var configured = true
        var next: DatasetDto? = null
        var error: Exception? = null
        override val isConfigured get() = configured
        override suspend fun fetch(): DatasetDto = error?.let { throw it } ?: next!!
    }

    private class FakeMarketDao : MarketDao {
        private val indices = MutableStateFlow<List<IndexEntity>>(emptyList())
        private val bars = MutableStateFlow<List<BarEntity>>(emptyList())
        private val constituents = MutableStateFlow<List<ConstituentEntity>>(emptyList())
        private val joined = MutableStateFlow<List<IndexWithBars>>(emptyList())

        private fun publish() {
            joined.value = indices.value.sortedBy { it.symbol }.map { i -> IndexWithBars(i, bars.value.filter { it.symbol == i.symbol }) }
        }

        override fun observeAll(): Flow<List<IndexWithBars>> = joined
        override fun observe(symbol: String) = joined.map { rows -> rows.firstOrNull { it.index.symbol == symbol } }
        override fun observeConstituents(): Flow<List<ConstituentEntity>> = constituents
        override suspend fun count() = indices.value.size
        override suspend fun deleteAllIndices() { indices.value = emptyList(); bars.value = emptyList(); publish() }
        override suspend fun deleteAllConstituents() { constituents.value = emptyList() }
        override suspend fun insertIndices(indices: List<IndexEntity>) { this.indices.value += indices; publish() }
        override suspend fun insertBars(bars: List<BarEntity>) { this.bars.value += bars; publish() }
        override suspend fun insertConstituents(constituents: List<ConstituentEntity>) { this.constituents.value += constituents }
        override suspend fun insertValuation(valuation: List<com.fundamentalanalysis.app.data.local.ValuationEntity>) = Unit
        override fun observeStocks() = MutableStateFlow(emptyList<com.fundamentalanalysis.app.data.local.StockWithData>())
        override fun observeStock(symbol: String) = MutableStateFlow<com.fundamentalanalysis.app.data.local.StockWithData?>(null)
        override suspend fun deleteAllStocks() = Unit
        override suspend fun insertStocks(stocks: List<com.fundamentalanalysis.app.data.local.StockEntity>) = Unit
        override suspend fun insertStockFinancials(rows: List<com.fundamentalanalysis.app.data.local.StockFinancialEntity>) = Unit
    }
}
