package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.BarCodec
import com.fundamentalanalysis.app.data.local.MetaEntity
import com.fundamentalanalysis.app.data.local.PriceCodec
import com.fundamentalanalysis.app.data.sync.DatasetSync
import com.fundamentalanalysis.app.data.sync.MetaKeys
import com.fundamentalanalysis.app.domain.model.DataPart
import com.fundamentalanalysis.app.domain.model.RefreshResult
import com.fundamentalanalysis.app.domain.model.SyncEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** The progressive loader, run against the real bundled files. */
class DatasetSyncTest {
    private val dao = FakeMarketDao()
    private val seed = SeedDir()
    private val remote = RemoteDir()
    private val sync = DatasetSync(dao, remote, seed, DatasetFiles.json, Dispatchers.Unconfined, CoroutineScope(SupervisorJob()))

    private val seedManifest = DatasetFiles.json.parseToJsonElement(File(DatasetFiles.seedDir, "manifest.json").readText()).jsonObject
    private val seedVersion = seedManifest.getValue("version").jsonPrimitive.int
    private val seedParts = seedManifest.getValue("parts").jsonArray.map { it.jsonObject }
    private fun names(group: String? = null) =
        seedParts.filter { group == null || it.getValue("group").jsonPrimitive.content == group }.map { it.getValue("name").jsonPrimitive.content }.toSet()
    private fun seedPartVersion(name: String) = seedParts.first { it.getValue("name").jsonPrimitive.content == name }.getValue("version").jsonPrimitive.int
    private val dailyMonths = names().filter { it.startsWith("daily-") }.sorted()

    private fun version(name: String) = dao.partsRows.value[name]?.version
    private fun monthlyBars(symbol: String) = dao.series.value[symbol to MONTHLY]?.bars?.let(BarCodec::decode).orEmpty()
    private fun dailyBars(symbol: String) = dao.series.value[symbol to DAILY]?.bars?.let(BarCodec::decode).orEmpty()
    private fun stockDaily(symbol: String) = dao.stockSeriesRows.value[symbol]?.dailyCloses?.let(PriceCodec::decode).orEmpty()

    @Test fun `first launch stores the primary parts only, once`() = runBlocking {
        sync.ensurePrimary()
        sync.ensurePrimary()
        assertEquals(names("primary"), dao.partsRows.value.keys)
        assertEquals("parts parsed again on second launch", 1, seed.reads["core.json"])
        assertNull("history must not block startup", seed.reads["fundamentals.json"])
        // Every screen has what it needs: indices with a year of sessions, stocks and their metrics.
        assertTrue(dao.indices.value.size >= 15)
        assertTrue(dailyBars("NIFTY 50").size >= 253)
        assertTrue(monthlyBars("NIFTY 50").size >= 13)
        assertTrue(stockDaily(dao.stocks.value.keys.first()).isNotEmpty())
        assertEquals(dao.stocks.value.size, dao.analytics.value.keys.count { it.first == AnalyticsKind.STOCK })
    }

    @Test fun `background load adds history without truncating recent months`() = runBlocking {
        remote.configured = false
        sync.ensurePrimary()
        val recent = monthlyBars("NIFTY 50").takeLast(13)
        sync.launchBackgroundSync().join()
        assertEquals(names(), dao.partsRows.value.keys)
        assertTrue("full Nifty 50 history expected", monthlyBars("NIFTY 50").size >= 120)
        assertEquals(recent, monthlyBars("NIFTY 50").takeLast(13))
        val stock = dao.stockSeriesRows.value.values.first()
        assertTrue(PriceCodec.decode(stock.monthlyCloses).size > 13)
        assertTrue(PriceCodec.decode(stock.dailyCloses).size >= 60)
        assertTrue(dao.financials.value.isNotEmpty())
        assertTrue(dao.valuation.value.containsKey("NIFTY 50"))
    }

    @Test fun `only changed parts are downloaded`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, emptyMap())
        // Same versions as stored, except the history parts (not stored yet).
        assertEquals(RefreshResult.Updated(seedVersion, listOf(DataPart.HISTORY)), sync.refresh(force = false))
        assertNull(remote.reads["core.json"])
        assertEquals(1, remote.reads["fundamentals.json"])
        assertEquals(RefreshResult.UpToDate, sync.refresh(force = false))
        assertEquals(1, remote.reads["fundamentals.json"])
        val month = dailyMonths.last()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("analytics" to seedVersion + 100, month to seedVersion + 100))
        assertEquals(RefreshResult.Updated(seedVersion + 100, listOf(DataPart.ANALYTICS, DataPart.CORE)), sync.refresh(force = false))
        assertEquals(1, remote.reads["analytics.json"])
        assertEquals(1, remote.reads["$month.json"])
        assertNull(remote.reads["core.json"])
        assertNull(remote.reads["${dailyMonths.first()}.json"])
    }

    @Test fun `a re-published month replaces stored values and keeps other months`() = runBlocking {
        sync.ensurePrimary()
        val symbol = dao.stocks.value.keys.sorted().first()
        val before = stockDaily(symbol)
        val month = dailyMonths.last()
        // As after a split: every close of [symbol] in the latest month halves.
        val dir = copyOfSeed()
        val part = DatasetFiles.json.parseToJsonElement(File(dir, "$month.json").readText()).jsonObject
        val stocks = part.getValue("stocks").jsonObject
        val items = stocks.getValue("items").jsonArray.map { it.jsonObject }.map { item ->
            if (item.getValue("symbol").jsonPrimitive.content != symbol) item
            else JsonObject(item + ("c" to JsonArray(item.getValue("c").jsonArray.map { c -> c.jsonPrimitive.doubleOrNull?.let { JsonPrimitive(it / 2) } ?: JsonNull })))
        }
        File(dir, "$month.json").writeText(JsonObject(part + ("stocks" to JsonObject(stocks + ("items" to JsonArray(items))))).toString())
        val republished = RemoteDir(dir)
        republished.manifestOverride = manifestWith(dir, mapOf(month to seedVersion + 1), rehash = setOf(month))
        val sync2 = DatasetSync(dao, republished, seed, DatasetFiles.json, Dispatchers.Unconfined, CoroutineScope(SupervisorJob()))
        assertTrue(sync2.refresh(force = true) is RefreshResult.Updated)
        val after = stockDaily(symbol)
        val prefix = month.removePrefix("daily-")
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) ->
            assertEquals(b.first, a.first)
            assertEquals("${b.first}", if (b.first.toString().startsWith(prefix)) b.second / 2 else b.second, a.second, 1e-9)
        }
    }

    @Test fun `a month that leaves the window is retired`() = runBlocking {
        sync.ensurePrimary()
        val oldest = dailyMonths.first()
        val newFrom = LocalDate.parse(dailyMonths[1].removePrefix("daily-") + "-01")
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("analytics" to seedVersion + 1), drop = setOf(oldest), dailyFrom = newFrom.toString())
        assertTrue(sync.refresh(force = false) is RefreshResult.Updated)
        assertFalse(oldest in dao.partsRows.value)
        assertTrue(dailyBars("NIFTY 50").first().date >= newFrom)
        assertTrue(dao.stockSeriesRows.value.values.all { s -> PriceCodec.decode(s.dailyCloses).all { it.first >= newFrom } })
    }

    @Test fun `checksum mismatch is rejected and data kept`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to seedVersion + 2), badSha = setOf("core"))
        val result = sync.refresh(force = true)
        assertTrue(result is RefreshResult.Failed && result.message.contains("checksum"))
        assertEquals(seedPartVersion("core"), version("core"))
        assertFalse("latest event should be the failure", dao.events.value.maxBy { it.id }.success)
    }

    @Test fun `newer schema is ignored until the app is updated`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to seedVersion + 2), schema = 6)
        assertTrue(sync.refresh(force = true) is RefreshResult.Failed)
        assertEquals(seedPartVersion("core"), version("core"))
    }

    @Test fun `data from the previous schema is cleared and reloaded`() = runBlocking {
        dao.putMeta(MetaEntity(MetaKeys.MANIFEST, """{"schemaVersion":4,"version":40,"parts":[]}"""))
        dao.upsertPart(com.fundamentalanalysis.app.data.local.DatasetPartEntity("history", 40, "", 0, 0, "REMOTE", null, 0, 0))
        sync.ensurePrimary()
        assertEquals(names("primary"), dao.partsRows.value.keys)
        assertTrue(dao.events.value.any { it.type == SyncEventType.RESET.name })
    }

    @Test fun `network failure keeps cached data`() = runBlocking {
        sync.ensurePrimary()
        remote.error = java.io.IOException("offline")
        assertTrue(sync.refresh(force = true) is RefreshResult.Failed)
        assertEquals(seedPartVersion("core"), version("core"))
    }

    @Test fun `no remote source configured`() = runBlocking {
        remote.configured = false
        assertEquals(RefreshResult.NotConfigured, sync.refresh(force = true))
    }

    @Test fun `bundled data never replaces newer downloaded data`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to seedVersion + 30, "analytics" to seedVersion + 30))
        sync.refresh(force = true)
        sync.ensurePrimary()
        assertEquals(seedVersion + 30, version("core"))
        assertEquals(1, seed.reads["core.json"])
    }

    @Test fun `reset reloads the bundled copy`() = runBlocking {
        sync.ensurePrimary()
        remote.manifestOverride = manifestWith(DatasetFiles.seedDir, mapOf("core" to seedVersion + 30, "analytics" to seedVersion + 30))
        sync.refresh(force = true)
        sync.resetToBundled()
        assertEquals(seedPartVersion("core"), version("core"))
        assertEquals(names(), dao.partsRows.value.keys)
    }

    private fun copyOfSeed(): File {
        val dir = kotlin.io.path.createTempDirectory("seed").toFile()
        DatasetFiles.seedDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        return dir
    }

    /**
     * The manifest in [dir] as a newer publish would look: some parts' versions bumped, parts
     * dropped, a wrong checksum, or the size and checksum of rewritten files ([rehash]).
     */
    private fun manifestWith(
        dir: File, bump: Map<String, Int>, badSha: Set<String> = emptySet(), schema: Int? = null,
        drop: Set<String> = emptySet(), dailyFrom: String? = null, rehash: Set<String> = emptySet(),
    ): ByteArray {
        val root = DatasetFiles.json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
        val parts = root.getValue("parts").jsonArray.map { it.jsonObject }
            .filter { it.getValue("name").jsonPrimitive.content !in drop }
            .map { o ->
                val name = o.getValue("name").jsonPrimitive.content
                JsonObject(o + buildMap<String, JsonElement> {
                    bump[name]?.let { put("version", JsonPrimitive(it)) }
                    if (name in badSha) put("sha256", JsonPrimitive("0".repeat(64)))
                    if (name in rehash) {
                        val bytes = File(dir, "$name.json").readBytes()
                        put("bytes", JsonPrimitive(bytes.size))
                        put("sha256", JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
                    }
                })
            }
        val version = (bump.values + root.getValue("version").jsonPrimitive.int).max()
        return JsonObject(root + buildMap<String, JsonElement> {
            put("parts", JsonArray(parts))
            put("version", JsonPrimitive(version))
            schema?.let { put("schemaVersion", JsonPrimitive(it)) }
            dailyFrom?.let { put("dailyFrom", JsonPrimitive(it)) }
        }).toString().encodeToByteArray()
    }
}
