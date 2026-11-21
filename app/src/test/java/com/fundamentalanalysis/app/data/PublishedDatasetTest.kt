package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.remote.dto.AnalyticsPartDto
import com.fundamentalanalysis.app.data.remote.dto.CorePartDto
import com.fundamentalanalysis.app.data.remote.dto.DailyPartDto
import com.fundamentalanalysis.app.data.remote.dto.MonthlyPartDto
import com.fundamentalanalysis.app.data.remote.dto.ManifestDto
import com.fundamentalanalysis.app.data.sync.DatasetSync
import com.fundamentalanalysis.app.data.sync.SUPPORTED_SCHEMA_VERSION
import com.fundamentalanalysis.app.domain.analysis.CalendarTable
import com.fundamentalanalysis.app.domain.analysis.CalendarTables
import com.fundamentalanalysis.app.domain.analysis.IndexAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.STOCK_PERIODS
import com.fundamentalanalysis.app.domain.analysis.StockAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.StockReports
import com.fundamentalanalysis.app.domain.analysis.ValuationMeasure
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.CalendarKind
import com.fundamentalanalysis.app.domain.model.IndexAnalytics
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max

/**
 * Contract and parity tests for the published partitioned dataset (shared-data/v5, also bundled
 * as the app's seed):
 * - every file parses with the app's decoder and matches the manifest exactly;
 * - loaded through the app's own sync engine, the data is complete and consistent;
 * - every precomputed number equals what the app's own Kotlin calculators produce from the raw
 *   data, so pipeline (Python) and app (Kotlin) can never silently drift apart.
 */
class PublishedDatasetTest {
    private val json = DatasetFiles.json
    private val dir = DatasetFiles.publishedDir
    private fun text(name: String) = File(dir, name).readText()

    private val manifest = json.decodeFromString<ManifestDto>(text("manifest.json"))
    private val core = json.decodeFromString<CorePartDto>(text("core.json"))
    private val analytics = json.decodeFromString<AnalyticsPartDto>(text("analytics.json"))

    /** The published files as the app stores them: loaded by DatasetSync into an in-memory DAO. */
    private val dao = FakeMarketDao().also { dao ->
        val sync = DatasetSync(dao, RemoteDir(dir, configured = false), SeedDir(dir), json, Dispatchers.Unconfined, CoroutineScope(SupervisorJob()))
        runBlocking { sync.resetToBundled() }
    }

    private val indices: Map<String, MarketIndex> =
        runBlocking { dao.observeIndices().first() }.associate { it.index.symbol to it.toDomain() }

    private val stocks: List<Stock> = core.stocks.map { s -> runBlocking { dao.observeStock(s.symbol).first()!!.toDomain(json) } }

    @Test fun `manifest describes the files exactly`() {
        assertEquals(SUPPORTED_SCHEMA_VERSION, manifest.schemaVersion)
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "manifest.json" }!!.map { it.name }.toSet()
        assertEquals("files on disk", files, manifest.parts.map { it.file }.toSet())
        manifest.parts.forEach { p ->
            val bytes = File(dir, p.file).readBytes()
            assertEquals("${p.name} size", p.bytes, bytes.size.toLong())
            assertEquals("${p.name} sha256", p.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertTrue("${p.name} version", p.version in 1..manifest.version)
            assertTrue("${p.name} group", p.group in setOf("primary", "history"))
        }
        val primary = manifest.parts.filter { it.group == "primary" }.map { it.name }.toSet()
        assertTrue("analytics and core load first", primary.containsAll(listOf("analytics", "core")))
        val months = manifest.parts.map { it.name }.filter { it.startsWith("daily-") }.map { YearMonth.parse(it.removePrefix("daily-")) }
        assertTrue("daily months must be primary", months.all { "daily-$it" in primary })
        assertEquals("daily months contiguous", (0 until months.size).map { months.first().plusMonths(it.toLong()) }, months)
        assertEquals("dailyFrom", months.first().atDay(1).toString(), manifest.dailyFrom)
        assertTrue("timeline newest first", manifest.history.zipWithNext().all { (a, b) -> a.version > b.version })
        assertEquals(manifest.version, manifest.history.first().version)
        // The bundled seed is the same publish (or an older one).
        val seed = json.decodeFromString<ManifestDto>(File(DatasetFiles.seedDir, "manifest.json").readText())
        assertTrue(seed.version <= manifest.version)
    }

    @Test fun `each daily file holds one month and each monthly file one year`() {
        manifest.parts.filter { it.name.startsWith("daily-") }.forEach { p ->
            val part = json.decodeFromString<DailyPartDto>(text(p.file))
            val month = p.name.removePrefix("daily-")
            assertEquals(month, part.month)
            assertTrue(p.name, (part.indices.flatMap { it.bars.d } + part.stocks.dates).all { it.startsWith(month) })
        }
        manifest.parts.filter { it.name.startsWith("monthly-") }.forEach { p ->
            val part = json.decodeFromString<MonthlyPartDto>(text(p.file))
            val year = p.name.removePrefix("monthly-")
            assertEquals(year.toInt(), part.year)
            val dates = part.indices.flatMap { it.bars.d } + part.valuation.flatMap { it.points.d } + part.stocks.dates
            assertTrue(p.name, dates.all { it.startsWith(year) })
        }
    }

    @Test fun `recent data is complete enough for every screen`() {
        val symbols = core.indices.map { it.symbol }
        assertTrue("NIFTY 50 missing", "NIFTY 50" in symbols)
        assertEquals("duplicate symbols", symbols.size, symbols.toSet().size)
        assertEquals(symbols.toSet(), indices.keys)
        indices.values.forEach { ix ->
            assertTrue("${ix.symbol}: too little daily history", ix.daily.size >= 253)
            listOf(ix.daily, ix.monthly).forEach { bars ->
                assertEquals("${ix.symbol}: dates not increasing", bars.map { it.date }.sorted().distinct(), bars.map { it.date })
                bars.forEach { b -> assertTrue("${ix.symbol} ${b.date}: bad OHLC", b.low <= minOf(b.open, b.close) + 1e-6 && b.high >= maxOf(b.open, b.close) - 1e-6 && b.low > 0) }
            }
            assertEquals("${ix.symbol}: monthly/daily mismatch", ix.daily.last().close, ix.monthly.last().close, 1e-6)
            assertTrue("${ix.symbol}: daily data before dailyFrom", ix.daily.first().date >= LocalDate.parse(manifest.dailyFrom))
        }
        assertEquals("stock symbols unique", core.stocks.size, core.stocks.map { it.symbol }.toSet().size)
        core.stocks.forEach { s ->
            assertTrue("${s.symbol}: benchmark ${s.index} missing", com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase.benchmarkOf(s.index) in symbols)
        }
        core.universes.forEach { u ->
            assertTrue("${u.index}: stocks per constituent", core.stocks.count { it.index == u.index } >= u.constituents.size - 3)
        }
        val comp = core.composition!!
        assertTrue(comp.constituents.isNotEmpty() && comp.constituents.all { it.industry.isNotBlank() })
    }

    @Test fun `history is complete and consistent`() {
        indices.values.forEach { ix -> assertTrue("${ix.symbol}: too little monthly history", ix.monthly.size >= 61) }
        val nifty = indices.getValue("NIFTY 50")
        assertTrue("NIFTY 50 needs 10+ years", nifty.monthly.size >= 120)
        val valuation = nifty.valuation
        assertTrue("valuation history", valuation.size >= 120)
        assertEquals("one valuation point per month", valuation.map { it.date.withDayOfMonth(1) }.distinct().size, valuation.size)
        assertTrue("implausible P/E", valuation.all { (it.pe ?: 0.0) in 5.0..60.0 })
        val wishlist = core.stocks.filter { it.index == com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase.WISHLIST }.map { it.symbol }.toSet()
        stocks.forEach { s ->
            // Wishlist stocks may be new listings (or quoted only day by day); index constituents need 60 sessions.
            val minimum = if (s.symbol in wishlist) 1 else 60
            assertTrue("${s.symbol}: too little daily history", s.daily.size >= minimum)
            assertTrue("${s.symbol}: non-positive close", (s.monthly + s.daily).all { it.close > 0 })
        }
        assertEquals("financials for every stock", core.stocks.size, dao.financials.value.size)
    }

    // ------------------------------------------------------------------ parity with the Kotlin calculators

    private fun near(label: String, expected: Double?, actual: Double?) {
        if (expected == null || actual == null || !expected.isFinite()) {
            assertEquals(label, expected?.takeIf { it.isFinite() }, actual)
        } else {
            // Files keep 9 significant digits.
            assertEquals(label, expected, actual, max(1e-9, abs(expected) * 1e-7))
        }
    }

    @Test fun `precomputed index metrics match the app calculator`() {
        assertEquals(indices.keys, analytics.indices.map { it.symbol }.toSet())
        analytics.indices.forEach { dto ->
            val pre: IndexAnalytics = dto.toDomain()
            val k = IndexAnalyticsCalculator.calculate(indices.getValue(dto.symbol))
            val s = dto.symbol
            Period.entries.forEach { p -> near("$s ${p.name}", k.returns[p], pre.returns[p]) }
            near("$s rsi", k.rsi14, pre.rsi14); near("$s dma50", k.dma50, pre.dma50); near("$s dma200", k.dma200, pre.dma200)
            near("$s vs50", k.vsDma50, pre.vsDma50); near("$s vs200", k.vsDma200, pre.vsDma200)
            near("$s hi", k.high52w, pre.high52w); near("$s lo", k.low52w, pre.low52w)
            near("$s fromHi", k.fromHigh52w, pre.fromHigh52w); near("$s fromLo", k.fromLow52w, pre.fromLow52w)
            assertEquals("$s volume", k.latestVolume, pre.latestVolume); assertEquals("$s volume date", k.latestVolumeDate, pre.latestVolumeDate)
            near("$s avgVol", k.avgVolume20, pre.avgVolume20); near("$s relVol", k.relativeVolume, pre.relativeVolume)
            near("$s volTrend", k.volumeTrend, pre.volumeTrend)
            near("$s vol1m", k.volatility1m, pre.volatility1m); near("$s vol1y", k.volatility1y, pre.volatility1y)
        }
    }

    private val benchmarks = analytics.benchmarks

    @Test fun `precomputed stock metrics match the app calculator`() {
        assertEquals(stocks.map { it.symbol }.toSet(), analytics.stocks.map { it.symbol }.toSet())
        val byIndex = indices
        analytics.stocks.forEach { dto ->
            val stock = stocks.first { it.symbol == dto.symbol }
            val pre: StockMetrics = dto.toDomain(benchmarks[dto.index])
            val k = StockAnalyticsCalculator.calculate(stock, byIndex[com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase.benchmarkOf(stock.indexSymbol)])
            val s = dto.symbol
            assertEquals("$s benchmark", stock.indexSymbol, dto.index)
            near("$s last", k.lastClose, pre.lastClose); assertEquals("$s date", k.lastDate, pre.lastDate)
            near("$s 1d", k.change1d, pre.change1d)
            STOCK_PERIODS.forEach { p ->
                near("$s ${p.name}", k.returns[p], pre.returns[p])
                near("$s bench ${p.name}", k.benchmarkReturns[p], pre.benchmarkReturns[p])
            }
            near("$s mom", k.momentum12m1m, pre.momentum12m1m); near("$s rsi", k.rsi14, pre.rsi14)
            near("$s vs200", k.vsDma200, pre.vsDma200); near("$s fromHi", k.fromHigh52w, pre.fromHigh52w)
            near("$s vol", k.volatility1y, pre.volatility1y); near("$s beta", k.beta, pre.beta); near("$s alpha", k.alpha, pre.alpha)
            near("$s pe", k.pe, pre.pe); near("$s pb", k.pb, pre.pb); near("$s ey", k.earningsYield, pre.earningsYield)
            near("$s revG", k.revenueGrowth, pre.revenueGrowth); near("$s revCagr", k.revenueCagr, pre.revenueCagr)
            near("$s epsG", k.epsGrowth, pre.epsGrowth); near("$s qYoY", k.quarterlyRevenueYoY, pre.quarterlyRevenueYoY)
            near("$s roe", k.roe, pre.roe); near("$s margin", k.netMargin, pre.netMargin); near("$s d/e", k.debtToEquity, pre.debtToEquity)
            near("$s profit", k.netProfitCr, pre.netProfitCr); near("$s profitG", k.profitGrowth, pre.profitGrowth)
        }
    }

    @Test fun `precomputed sector aggregates match the app's group-by`() {
        val analyzed = analytics.stocks.map { dto -> AnalyzedStock(stocks.first { it.symbol == dto.symbol }, dto.toDomain(benchmarks[dto.index])) }
        val stats = analytics.sectors.mapNotNull { it.toDomain() }
        val universes = analyzed.map { it.stock.indexSymbol }.toSet()
        universes.forEach { u ->
            STOCK_PERIODS.forEach { p ->
                val local = StockReports.sectorPerformance(analyzed.filter { it.stock.indexSymbol == u }, p).associateBy { it.sector }
                val pre = stats.filter { it.universe == u && it.period == p }.associateBy { it.sector }
                assertEquals("$u $p sectors", local.keys, pre.keys)
                local.forEach { (sector, l) ->
                    val r = pre.getValue(sector)
                    assertEquals("$u $p $sector stocks", l.stocks.size, r.stocks)
                    assertEquals("$u $p $sector measured", l.measured, r.measured)
                    assertEquals("$u $p $sector beat", l.outperformers, r.outperformers)
                    near("$u $p $sector avg", l.averageReturn, r.averageReturn)
                    near("$u $p $sector bench", l.benchmarkReturn, r.benchmarkReturn)
                }
            }
        }
    }

    @Test fun `precomputed calendar tables match the app's tables`() {
        val pre = analytics.calendars.mapNotNull { it.toDomain() }.toMap()
        val nifty = indices.getValue("NIFTY 50")
        val latest = nifty.daily.last().date
        assertTable("returns", CalendarTables.monthlyReturns(nifty.monthly, latest), pre.getValue("NIFTY 50" to CalendarKind.RETURNS))
        ValuationMeasure.entries.forEach { m ->
            assertTable(m.name, CalendarTables.valuation(nifty.valuation, m, latest), pre.getValue("NIFTY 50" to m.kind))
        }
    }

    private fun assertTable(label: String, expected: CalendarTable, actual: CalendarTable) {
        assertEquals("$label rows", expected.rows.map { it.year }, actual.rows.map { it.year })
        expected.rows.zip(actual.rows).forEach { (e, a) ->
            e.months.zip(a.months).forEachIndexed { i, (x, y) -> near("$label ${e.year}-${i + 1}", x, y) }
            near("$label ${e.year} year", e.yearValue, a.yearValue)
            assertEquals("$label ${e.year} partial", e.yearIsPartial, a.yearIsPartial)
        }
        expected.monthAverages.zip(actual.monthAverages).forEachIndexed { i, (x, y) -> near("$label avg ${i + 1}", x, y) }
        near("$label year avg", expected.yearAverage, actual.yearAverage)
        assertEquals("$label month to date", expected.monthToDate, actual.monthToDate)
    }
}
