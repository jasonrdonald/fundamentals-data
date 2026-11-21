package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.AnalyticsEntity
import com.fundamentalanalysis.app.data.local.CoreLists
import com.fundamentalanalysis.app.data.local.BarCodec
import com.fundamentalanalysis.app.data.local.ConstituentEntity
import com.fundamentalanalysis.app.data.local.IndexEntity
import com.fundamentalanalysis.app.data.local.IndexWithSeries
import com.fundamentalanalysis.app.data.local.PriceCodec
import com.fundamentalanalysis.app.data.local.StockEntity
import com.fundamentalanalysis.app.data.local.StockFinancialsEntity
import com.fundamentalanalysis.app.data.local.StockWithHistory
import com.fundamentalanalysis.app.data.local.ValuationCodec
import com.fundamentalanalysis.app.data.remote.dto.AnalyticsPartDto
import com.fundamentalanalysis.app.data.remote.dto.BarColumnsDto
import com.fundamentalanalysis.app.data.remote.dto.CalendarDto
import com.fundamentalanalysis.app.data.remote.dto.CorePartDto
import com.fundamentalanalysis.app.data.remote.dto.FinancialPeriodDto
import com.fundamentalanalysis.app.data.remote.dto.FundamentalsPartDto
import com.fundamentalanalysis.app.data.remote.dto.IndexMetricsDto
import com.fundamentalanalysis.app.data.remote.dto.SectorStatDto
import com.fundamentalanalysis.app.data.remote.dto.StockMetricsDto
import com.fundamentalanalysis.app.data.remote.dto.ValuationColumnsDto
import com.fundamentalanalysis.app.domain.analysis.CalendarTable
import com.fundamentalanalysis.app.domain.analysis.YearRow
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.CalendarKind
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.Constituent
import com.fundamentalanalysis.app.domain.model.FinancialPeriod
import com.fundamentalanalysis.app.domain.model.IndexAnalytics
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.SectorStat
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.model.StockMetrics
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

internal const val DAILY = "D"
internal const val MONTHLY = "M"

internal object AnalyticsKind {
    const val INDEX = "INDEX"
    const val STOCK = "STOCK"
    const val BENCHMARK = "BENCHMARK"
    const val SECTOR = "SECTOR"
    const val CALENDAR = "CALENDAR"
}

// ------------------------------------------------------------------------------- wire -> rows

internal fun BarColumnsDto.toBars(): List<Bar> = d.indices.map { i ->
    Bar(
        date = LocalDate.parse(d[i]),
        open = o[i], high = h[i], low = l[i], close = c[i],
        volume = v.getOrNull(i),
        lastSession = last.getOrNull(i)?.let(LocalDate::parse),
    )
}

internal fun ValuationColumnsDto.toPoints(): List<ValuationPoint> = d.indices.map { i ->
    ValuationPoint(LocalDate.parse(d[i]), pe.getOrNull(i), pb.getOrNull(i), dy.getOrNull(i))
}

/** Index, constituent and stock lists from the core part. */
internal fun CorePartDto.toLists(): CoreLists = CoreLists(
    indices = indices.map { IndexEntity(it.symbol, it.name, it.category, it.source) },
    constituents = (listOfNotNull(composition) + universes).distinctBy { it.index }.flatMap { comp ->
        comp.constituents.map { ConstituentEntity(comp.index, it.symbol, it.name, it.industry, comp.asOf) }
    },
    stocks = stocks.map { StockEntity(it.symbol, it.index, it.name, it.industry, it.source, it.marketCapCr, it.pe, it.pb) },
)

internal fun FundamentalsPartDto.toRows(json: Json): List<StockFinancialsEntity> = items.map {
    StockFinancialsEntity(it.symbol, json.encodeToString<List<FinancialPeriodDto>>(it.annual), json.encodeToString<List<FinancialPeriodDto>>(it.quarterly))
}

internal fun AnalyticsPartDto.toRows(json: Json): List<AnalyticsEntity> =
    indices.map { AnalyticsEntity(AnalyticsKind.INDEX, it.symbol, json.encodeToString<IndexMetricsDto>(it)) } +
        stocks.map { AnalyticsEntity(AnalyticsKind.STOCK, it.symbol, json.encodeToString<StockMetricsDto>(it)) } +
        benchmarks.map { (k, v) -> AnalyticsEntity(AnalyticsKind.BENCHMARK, k, json.encodeToString<Map<String, Double?>>(v)) } +
        sectors.map { AnalyticsEntity(AnalyticsKind.SECTOR, "${it.universe}|${it.period}|${it.sector}", json.encodeToString<SectorStatDto>(it)) } +
        calendars.map { AnalyticsEntity(AnalyticsKind.CALENDAR, "${it.symbol}|${it.kind}", json.encodeToString<CalendarDto>(it)) }

// ------------------------------------------------------------------------------- rows -> domain

internal fun IndexWithSeries.toDomain(): MarketIndex {
    fun bars(tf: String) = series.firstOrNull { it.timeframe == tf }?.bars?.let(BarCodec::decode).orEmpty()
    return MarketIndex(
        symbol = index.symbol,
        name = index.name,
        category = IndexCategory.from(index.category),
        source = index.source,
        daily = bars(DAILY),
        monthly = bars(MONTHLY),
        valuation = valuation.firstOrNull()?.points?.let(ValuationCodec::decode).orEmpty(),
    )
}

internal fun List<ConstituentEntity>.toCompositions(): Map<String, Composition> =
    groupBy { it.indexSymbol }.mapValues { (index, rows) ->
        Composition(index, rows.first().asOf, rows.map { Constituent(it.symbol, it.name, it.industry) })
    }

/** List row: metadata only (no price history or financials). */
internal fun StockEntity.toDomain(
    monthly: List<Bar> = emptyList(),
    daily: List<Bar> = emptyList(),
    annual: List<FinancialPeriod> = emptyList(),
    quarterly: List<FinancialPeriod> = emptyList(),
) = Stock(
    symbol = symbol, name = name, industry = industry.ifBlank { "Other" }, indexSymbol = indexSymbol,
    marketCapCr = marketCapCr, pe = pe, pb = pb,
    monthly = monthly, daily = daily, annual = annual, quarterly = quarterly,
)

internal fun StockWithHistory.toDomain(json: Json): Stock {
    fun bars(packed: ByteArray?) = packed?.let(PriceCodec::decode).orEmpty().map { (d, c) -> Bar(d, c, c, c, c, null) }
    fun periods(text: String?) = text?.let { json.decodeFromString<List<FinancialPeriodDto>>(it) }.orEmpty()
        .map { FinancialPeriod(LocalDate.parse(it.d), it.revenue, it.netIncome, it.operatingIncome, it.equity, it.debt, it.eps) }
        .sortedBy { it.endDate }
    val s = series.firstOrNull()
    val f = financials.firstOrNull()
    return stock.toDomain(bars(s?.monthlyCloses), bars(s?.dailyCloses), periods(f?.annualJson), periods(f?.quarterlyJson))
}

private fun periodMap(raw: Map<String, Double?>): Map<Period, Double?> =
    raw.mapNotNull { (k, v) -> runCatching { Period.valueOf(k) }.getOrNull()?.let { it to v } }.toMap()

/** Precomputed index metrics; the latest session and monthly moves are added from the bars. */
internal fun IndexMetricsDto.toDomain() = IndexAnalytics(
    latest = null,
    returns = Period.entries.associateWith { periodMap(returns)[it] },
    rsi14 = rsi14, dma50 = dma50, dma200 = dma200, vsDma50 = vsDma50, vsDma200 = vsDma200,
    high52w = high52w, low52w = low52w, fromHigh52w = fromHigh52w, fromLow52w = fromLow52w,
    latestVolume = latestVolume, latestVolumeDate = latestVolumeDate?.let(LocalDate::parse),
    avgVolume20 = avgVolume20, relativeVolume = relativeVolume, volumeTrend = volumeTrend,
    volatility1m = volatility1m, volatility1y = volatility1y,
    monthly = emptyList(),
)

internal fun StockMetricsDto.toDomain(benchmark: Map<String, Double?>?) = StockMetrics(
    lastClose = lastClose, lastDate = lastDate?.let(LocalDate::parse), change1d = change1d,
    returns = periodMap(returns),
    benchmarkReturns = benchmark?.let(::periodMap).orEmpty(),
    momentum12m1m = momentum12m1m, rsi14 = rsi14, vsDma200 = vsDma200, fromHigh52w = fromHigh52w,
    volatility1y = volatility1y, beta = beta, alpha = alpha,
    pe = pe, pb = pb, earningsYield = earningsYield,
    revenueGrowth = revenueGrowth, revenueCagr = revenueCagr, epsGrowth = epsGrowth, quarterlyRevenueYoY = quarterlyRevenueYoY,
    roe = roe, netMargin = netMargin, debtToEquity = debtToEquity, netProfitCr = netProfitCr, profitGrowth = profitGrowth,
)

internal fun SectorStatDto.toDomain(): SectorStat? = runCatching { Period.valueOf(period) }.getOrNull()?.let {
    SectorStat(universe, it, sector, stocks, measured, outperformers, averageReturn, benchmarkReturn)
}

internal fun CalendarDto.toDomain(): Pair<Pair<String, CalendarKind>, CalendarTable>? {
    val k = runCatching { CalendarKind.valueOf(kind) }.getOrNull() ?: return null
    val table = CalendarTable(
        rows = rows.map { r ->
            YearRow(
                year = r[0].jsonPrimitive.int,
                months = r[1].jsonArray.map { it.jsonPrimitive.doubleOrNull },
                yearValue = r[2].jsonPrimitive.doubleOrNull,
                yearIsPartial = r[3].jsonPrimitive.booleanOrNull ?: false,
            )
        },
        monthAverages = monthAverages,
        yearAverage = yearAverage,
        monthToDate = monthToDate?.takeIf { it.size == 2 }?.let { it[0] to it[1] },
    )
    return (symbol to k) to table
}

internal fun decodeReturns(json: Json, payload: String): Map<String, Double?> = json.decodeFromString<Map<String, Double?>>(payload)
