package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.BarCodec
import com.fundamentalanalysis.app.data.local.PriceCodec
import com.fundamentalanalysis.app.data.sync.DatasetSync
import com.fundamentalanalysis.app.domain.model.DataPart
import com.fundamentalanalysis.app.domain.model.RefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The progressive loader, run against the real bundled files. */
class DatasetSyncTest {
    private val dao = FakeMarketDao()
    private val seed = SeedDir()
    private val remote = RemoteDir()
    private val sync = DatasetSync(dao, remote, seed, DatasetFiles.json, Dispatchers.Unconfined, CoroutineScope(SupervisorJob()))

    private fun version(part: DataPart) = dao.partsRows.value[part.key]?.version
    private fun monthlyBars(symbol: String) = dao.series.value[symbol to MONTHLY]?.bars?.let(BarCodec::decode).orEmpty()

    @Test fun `first launch stores analytics and recent data only, once`() = runBlocking {
        sync.ensurePrimary()
        sync.ensurePrimary()
        assertEquals(setOf("analytics", "core"), dao.partsRows.value.keys)
        assertEquals("parts parsed again on second launch", 1, seed.reads["core.json"])
        assertNull("history must not block startup", seed.reads["history.json"])
        // Every screen has what it needs: indices with a year of sessions, stocks and their metrics.
        assertTrue(dao.indices.value.size >= 15)
        assertTrue(dao.series.value.getValue("NIFTY 50" to DAILY).bars.size / 48 >= 253)
        assertEquals(13, monthlyBars("NIFTY 50").size)
        assertEquals(dao.stocks.value.size, dao.analytics.value.keys.count { it.first == AnalyticsKind.STOCK })
    }

    @Test fun `background load adds history without truncating recent months`() = runBlocking {
        remote.configured = false
        sync.ensurePrimary()
        sync.launchBackgroundSync().join()
        assertEquals(setOf("analytics", "core", "history"), dao.partsRows.value.keys)
        assertTrue("full Nifty 50 history expected", monthlyBars("NIFTY 50").size >= 120)
        val stock = dao.stockSeriesRows.value.values.first()
        assertTrue(PriceCodec.decode(stock.monthlyCloses).size > 13)
        assertTrue(PriceCodec.decode(stock.dailyCloses).size >= 60)
        assertTrue(dao.financials.value.isNotEmpty())
        assertTrue(dao.valuation.value.containsKey("NIFTY 50"))
    }

    @Test fun `recent data arriving after history keeps the full series`() = runBlocking {
        remote.configured = false
        sync.ensurePrimary()
        sync.launchBackgroundSync().join()
        val full = monthlyBars("NIFTY 50")
        remote.configured = true
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to 50))
        val result = sync.refresh(force = true)
        assertEquals(RefreshResult.Updated(50, listOf(DataPart.CORE)), result)
        assertEquals(full, monthlyBars("NIFTY 50"))
        assertEquals(50, version(DataPart.CORE))
    }

    @Test fun `only changed parts are downloaded`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, emptyMap())
        // Same versions as stored, except history (not stored yet).
        assertEquals(RefreshResult.Updated(1, listOf(DataPart.HISTORY)), sync.refresh(force = false))
        assertNull(remote.reads["core.json"])
        assertEquals(1, remote.reads["history.json"])
        assertEquals(RefreshResult.UpToDate, sync.refresh(force = false))
        assertEquals(1, remote.reads["history.json"])
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("analytics" to 7))
        assertEquals(RefreshResult.Updated(7, listOf(DataPart.ANALYTICS)), sync.refresh(force = false))
        assertEquals(1, remote.reads["analytics.json"])
        assertNull(remote.reads["core.json"])
    }

    @Test fun `checksum mismatch is rejected and data kept`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to 9), badSha = setOf("core"))
        val result = sync.refresh(force = true)
        assertTrue(result is RefreshResult.Failed && result.message.contains("checksum"))
        assertEquals(1, version(DataPart.CORE))
        assertFalse("latest event should be the failure", dao.events.value.maxBy { it.id }.success)
    }

    @Test fun `newer schema is ignored until the app is updated`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to 9), schema = 5)
        assertTrue(sync.refresh(force = true) is RefreshResult.Failed)
        assertEquals(1, version(DataPart.CORE))
    }

    @Test fun `network failure keeps cached data`() = runBlocking {
        sync.ensurePrimary()
        remote.error = java.io.IOException("offline")
        assertTrue(sync.refresh(force = true) is RefreshResult.Failed)
        assertEquals(1, version(DataPart.CORE))
    }

    @Test fun `no remote source configured`() = runBlocking {
        remote.configured = false
        assertEquals(RefreshResult.NotConfigured, sync.refresh(force = true))
    }

    @Test fun `bundled data never replaces newer downloaded data`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to 30, "analytics" to 30))
        sync.refresh(force = true)
        sync.ensurePrimary()
        assertEquals(30, version(DataPart.CORE))
        assertEquals(1, seed.reads["core.json"])
    }

    @Test fun `reset reloads the bundled copy`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to 30, "analytics" to 30))
        sync.refresh(force = true)
        sync.resetToBundled()
        assertEquals(1, version(DataPart.CORE))
        assertEquals(setOf("analytics", "core", "history"), dao.partsRows.value.keys)
    }
}
