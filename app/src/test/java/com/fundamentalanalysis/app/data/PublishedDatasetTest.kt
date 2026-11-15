package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.remote.dto.AnalyticsPartDto
import com.fundamentalanalysis.app.data.remote.dto.CorePartDto
import com.fundamentalanalysis.app.data.remote.dto.HistoryPartDto
import com.fundamentalanalysis.app.data.remote.dto.ManifestDto
import com.fundamentalanalysis.app.data.sync.SUPPORTED_SCHEMA_VERSION
import com.fundamentalanalysis.app.domain.analysis.CalendarTable
import com.fundamentalanalysis.app.domain.analysis.CalendarTables
import com.fundamentalanalysis.app.domain.analysis.IndexAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.STOCK_PERIODS
import com.fundamentalanalysis.app.domain.analysis.StockAnalyticsCalculator
import com.fundamentalanalysis.app.domain.analysis.StockReports
import com.fundamentalanalysis.app.domain.analysis.ValuationMeasure
import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.CalendarKind
import com.fundamentalanalysis.app.domain.model.FinancialPeriod
import com.fundamentalanalysis.app.domain.model.IndexAnalytics
import com.fundamentalanalysis.app.domain.model.IndexCategory
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
import kotlin.math.abs
import kotlin.math.max

/**
 * Contract and parity tests for the published progressive dataset (shared-data/v4, also bundled
 * as the app's seed):
 * - every file parses with the app's decoder, matches the manifest and is internally consistent;
 * - every precomputed number equals what the app's own Kotlin calculators produce from the raw
 *   data, so pipeline (Python) and app (Kotlin) can never silently drift apart.
 */
class PublishedDatasetTest {
    private val json = DatasetFiles.json
    private val dir = DatasetFiles.publishedDir
    private fun text(name: String) = File(dir, name).readText()

    private val manifest = json.decodeFromString<ManifestDto>(text("manifest.json"))
    private val core = json.decodeFromString<CorePartDto>(text("core.json"))
    private val history = json.decodeFromString<HistoryPartDto>(text("history.json"))
    private val analytics = json.decodeFromString<AnalyticsPartDto>(text("analytics.json"))

    @Test fun `manifest describes the files exactly`() {
        assertEquals(SUPPORTED_SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals(listOf("analytics", "core", "history"), manifest.loadOrder)
        manifest.parts.forEach { p ->
            val bytes = File(dir, p.file).readBytes()
            assertEquals("${p.name} size", p.bytes, bytes.size.toLong())
            assertEquals("${p.name} sha256", p.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertTrue("${p.name} version", p.version in 1..manifest.version)
        }
        assertTrue("timeline newest first", manifest.history.zipWithNext().all { (a, b) -> a.version > b.version })
        assertEquals(manifest.version, manifest.history.first().version)
        // The bundled seed is the same publish (or an older one).
        val seed = json.decodeFromString<ManifestDto>(File(DatasetFiles.seedDir, "manifest.json").readText())
        assertTrue(seed.version <= manifest.version)
    }

    @Test fun `recent data is complete enough for every screen`() {
        val symbols = core.indices.map { it.symbol }
        assertTrue("NIFTY 50 missing", "NIFTY 50" in symbols)
        assertEquals("duplicate symbols", symbols.size, symbols.toSet().size)
        core.indices.forEach { ix ->
            val daily = ix.daily.toBars()
            val monthly = ix.monthly.toBars()
            assertTrue("${ix.symbol}: too little daily history", daily.size >= 253)
            assertTrue("${ix.symbol}: recent months", monthly.size in 2..13)
            listOf(daily, monthly).forEach { bars ->
                assertEquals("${ix.symbol}: dates not increasing", bars.map { it.date }.sorted().distinct(), bars.map { it.date })
                bars.forEach { b -> assertTrue("${ix.symbol} ${b.date}: bad OHLC", b.low <= minOf(b.open, b.close) + 1e-6 && b.high >= maxOf(b.open, b.close) - 1e-6 && b.low > 0) }
            }
            assertEquals("${ix.symbol}: monthly/daily mismatch", daily.last().close, monthly.last().close, 1e-6)
        }
        val months = core.stocks.months.map(LocalDate::parse)
        assertEquals("stock symbols unique", core.stocks.items.size, core.stocks.items.map { it.symbol }.toSet().size)
        core.stocks.items.forEach { s ->
            assertTrue("${s.symbol}: benchmark ${s.index} missing", s.index in symbols)
            assertEquals("${s.symbol}: mc not aligned", months.size, s.mc.size)
        }
        core.universes.forEach { u ->
            assertTrue("${u.index}: stocks per constituent", core.stocks.items.count { it.index == u.index } >= u.constituents.size - 3)
        }
        val comp = core.composition!!
        assertTrue(comp.constituents.isNotEmpty() && comp.constituents.all { it.industry.isNotBlank() })
    }

    @Test fun `history is complete and consistent with recent data`() {
        history.indices.forEach { ix ->
            val monthly = ix.monthly.toBars()
            val recent = core.indices.first { it.symbol == ix.symbol }.monthly.toBars()
            assertTrue("${ix.symbol}: too little monthly history", monthly.size >= 61)
            assertEquals("${ix.symbol}: history must end where recent months end", recent.last(), monthly.last())
        }
        val nifty = history.indices.first { it.symbol == "NIFTY 50" }
        assertTrue("NIFTY 50 needs 10+ years", nifty.monthly.d.size >= 120)
        val valuation = nifty.valuation.toPoints()
        assertTrue("valuation history", valuation.size >= 120)
        assertEquals("one valuation point per month", valuation.map { it.date.withDayOfMonth(1) }.distinct().size, valuation.size)
        assertTrue("implausible P/E", valuation.all { (it.pe ?: 0.0) in 5.0..60.0 })
        history.stocks.items.forEach { s ->
            assertEquals("${s.symbol}: mc", history.stocks.months.size, s.mc.size)
            assertEquals("${s.symbol}: dc", history.stocks.sessions.size, s.dc.size)
            assertTrue("${s.symbol}: too little daily history", s.dc.count { it != null } >= 60)
            assertTrue("${s.symbol}: non-positive close", (s.mc + s.dc).filterNotNull().all { it > 0 })
        }
    }

    // ------------------------------------------------------------------ parity with the Kotlin calculators

    private val indices: Map<String, MarketIndex> = core.indices.associate { ix ->
        val h = history.indices.first { it.symbol == ix.symbol }
        ix.symbol to MarketIndex(
            ix.symbol, ix.name, IndexCategory.from(ix.category), ix.source,
            daily = ix.daily.toBars(), monthly = h.monthly.toBars(), valuation = h.valuation.toPoints(),
        )
    }

    private val stocks: List<Stock> = core.stocks.items.map { c ->
        val h = history.stocks.items.first { it.symbol == c.symbol }
        fun bars(dates: List<String>, closes: List<Double?>) =
            dates.zip(closes).mapNotNull { (d, v) -> v?.let { Bar(LocalDate.parse(d), it, it, it, it, null) } }
        fun periods(list: List<com.fundamentalanalysis.app.data.remote.dto.FinancialPeriodDto>) =
            list.map { FinancialPeriod(LocalDate.parse(it.d), it.revenue, it.netIncome, it.operatingIncome, it.equity, it.debt, it.eps) }.sortedBy { it.endDate }
        Stock(
            c.symbol, c.name, c.industry.ifBlank { "Other" }, c.index, c.marketCapCr, c.pe, c.pb,
            monthly = bars(history.stocks.months, h.mc), daily = bars(history.stocks.sessions, h.dc),
            annual = periods(h.annual), quarterly = periods(h.quarterly),
        )
    }

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
            val k = StockAnalyticsCalculator.calculate(stock, byIndex[stock.indexSymbol])
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
