package com.fundamentalanalysis.app.data.portfolio

import android.content.Context
import com.fundamentalanalysis.app.di.IoDispatcher
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.FinancialPeriod
import com.fundamentalanalysis.app.domain.model.Stock
import com.fundamentalanalysis.app.domain.repository.HoldingStocksRepository
import com.fundamentalanalysis.app.domain.repository.HoldingStocksStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One cached stock, in the same shape as the dataset's stocks (amounts in INR crore). */
@Serializable
internal data class CachedStock(
    val symbol: String,
    val name: String,
    val sector: String,
    val source: String,
    val fetchedAt: Long,
    /** Daily closes: ISO date -> close. */
    val daily: List<Pair<String, Double>>,
    /** Month (first day, ISO) -> last close of the month. */
    val monthly: List<Pair<String, Double>>,
    val marketCapCr: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    val annual: List<CachedPeriod> = emptyList(),
    val quarterly: List<CachedPeriod> = emptyList(),
)

@Serializable
internal data class CachedPeriod(
    val d: String,
    val revenue: Double? = null,
    val netIncome: Double? = null,
    val operatingIncome: Double? = null,
    val equity: Double? = null,
    val debt: Double? = null,
    val eps: Double? = null,
)

internal fun CachedStock.toDomain(): Stock {
    fun bars(rows: List<Pair<String, Double>>) = rows.map { (d, c) -> Bar(LocalDate.parse(d), c, c, c, c, null) }
    fun periods(rows: List<CachedPeriod>) = rows.map {
        FinancialPeriod(LocalDate.parse(it.d), it.revenue, it.netIncome, it.operatingIncome, it.equity, it.debt, it.eps)
    }.sortedBy { it.endDate }
    return Stock(symbol, name, sector, "", marketCapCr, pe, pb, bars(monthly), bars(daily), periods(annual), periods(quarterly))
}

/**
 * Yahoo Finance's public chart and fundamentals endpoints (the same ones the dataset pipeline uses),
 * called from the device. NSE listing first, then BSE.
 */
internal class YahooStockSource(private val client: OkHttpClient, private val json: Json) {

    fun fetch(symbol: String, sector: String, now: Long): CachedStock? {
        for (suffix in listOf(".NS", ".BO")) {
            val chart = chart(symbol + suffix) ?: continue
            val (name, closes) = chart
            if (closes.size < 20) continue
            return CachedStock(
                symbol = symbol, name = name ?: symbol, sector = sector, source = "yahoo:$symbol$suffix", fetchedAt = now,
                daily = closes.takeLast(DAILY_SESSIONS),
                monthly = monthEnds(closes).takeLast(MONTHS),
            ).let { base -> runCatching { withFundamentals(base, symbol + suffix) }.getOrDefault(base) }
        }
        return null
    }

    /** Company name and daily closes (oldest first), or null when the symbol isn't listed there. */
    private fun chart(yahoo: String): Pair<String?, List<Pair<String, Double>>>? {
        val root = get("https://query1.finance.yahoo.com/v8/finance/chart/$yahoo?interval=1d&range=10y") ?: return null
        val result = root.obj("chart")?.arr("result")?.firstOrNull()?.jsonObject ?: return null
        val meta = result.obj("meta")
        val zone = runCatching { ZoneId.of(meta?.str("exchangeTimezoneName") ?: "Asia/Kolkata") }.getOrDefault(ZoneId.of("Asia/Kolkata"))
        val stamps = result.arr("timestamp").orEmpty()
        val close = result.obj("indicators")?.arr("quote")?.firstOrNull()?.jsonObject?.arr("close").orEmpty()
        val byDay = sortedMapOf<String, Double>()
        stamps.forEachIndexed { i, t ->
            val c = close.getOrNull(i)?.num() ?: return@forEachIndexed
            val ts = t.jsonPrimitive.longOrNull ?: return@forEachIndexed
            byDay[Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()] = Math.round(c * 100) / 100.0
        }
        // Today's bar is incomplete until NSE closes (15:30 IST); the dataset leaves it out too.
        val nowIst = java.time.ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        if (nowIst.hour * 60 + nowIst.minute < 15 * 60 + 35) byDay.remove(nowIst.toLocalDate().toString())
        val name = meta?.str("longName") ?: meta?.str("shortName")
        return name to byDay.map { it.key to it.value }
    }

    private fun withFundamentals(base: CachedStock, yahoo: String): CachedStock {
        val types = listOf(
            "annualTotalRevenue", "annualNetIncome", "annualOperatingIncome", "annualStockholdersEquity",
            "annualTotalDebt", "annualDilutedEPS", "quarterlyTotalRevenue", "quarterlyNetIncome",
            "quarterlyDilutedEPS", "trailingMarketCap", "trailingPeRatio", "trailingPbRatio",
        )
        val url = "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/$yahoo" +
            "?type=${types.joinToString(",")}&period1=1420070400&period2=${System.currentTimeMillis() / 1000}"
        val results = get(url)?.obj("timeseries")?.arr("result").orEmpty()
        val series = mutableMapOf<String, Map<String, Double>>()
        for (r in results) {
            val o = r.jsonObject
            val type = o.obj("meta")?.arr("type")?.firstOrNull()?.jsonPrimitive?.content ?: continue
            series[type] = o.arr(type).orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val d = po.str("asOfDate") ?: return@mapNotNull null
                val v = po.obj("reportedValue")?.get("raw")?.num() ?: return@mapNotNull null
                d to v
            }.toMap()
        }
        fun latest(t: String) = series[t]?.maxByOrNull { it.key }?.value
        fun r2(v: Double) = Math.round(v * 100) / 100.0
        fun crore(v: Double?) = v?.let { r2(it / CRORE) }
        fun periods(prefix: String, eps: Boolean, full: Boolean): List<CachedPeriod> {
            val keys = listOf("TotalRevenue", "NetIncome", "DilutedEPS") + if (full) listOf("OperatingIncome", "StockholdersEquity", "TotalDebt") else emptyList()
            val dates = keys.flatMap { series[prefix + it]?.keys.orEmpty() }.toSortedSet()
            return dates.map { d ->
                fun v(k: String) = series[prefix + k]?.get(d)
                CachedPeriod(
                    d = d, revenue = crore(v("TotalRevenue")), netIncome = crore(v("NetIncome")),
                    operatingIncome = if (full) crore(v("OperatingIncome")) else null,
                    equity = if (full) crore(v("StockholdersEquity")) else null,
                    debt = if (full) crore(v("TotalDebt")) else null,
                    eps = if (eps) v("DilutedEPS")?.let(::r2) else null,
                )
            }
        }
        return base.copy(
            marketCapCr = latest("trailingMarketCap")?.let { Math.round(it / CRORE * 10) / 10.0 },
            pe = latest("trailingPeRatio")?.let(::r2),
            pb = latest("trailingPbRatio")?.let(::r2),
            annual = periods("annual", eps = true, full = true),
            quarterly = periods("quarterly", eps = true, full = false),
        )
    }

    private fun get(url: String): JsonObject? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return json.parseToJsonElement(resp.body.string()).jsonObject
        }
    }

    companion object {
        /** Same windows as the dataset's stocks. */
        const val DAILY_SESSIONS = 260
        const val MONTHS = 121
        private const val CRORE = 1e7
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) FundamentalAnalysis"

        /** Last close of each calendar month, keyed by the month's first day. */
        fun monthEnds(daily: List<Pair<String, Double>>): List<Pair<String, Double>> =
            daily.groupBy { it.first.substring(0, 7) }.toSortedMap().map { (m, rows) -> "$m-01" to rows.last().second }
    }
}

private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
private fun JsonObject.arr(key: String): JsonArray? = (this[key] as? JsonArray)
private fun JsonObject.str(key: String): String? = (this[key]?.takeIf { it !is JsonNull })?.jsonPrimitive?.content
private fun JsonElement.num(): Double? = (this as? JsonNull)?.let { null } ?: runCatching { jsonPrimitive.doubleOrNull }.getOrNull()

/** Cache in app-private storage (holding-stocks.json); fetched sequentially, politely spaced. */
@Singleton
class LocalHoldingStocksRepository @Inject constructor(
    @ApplicationContext context: Context,
    client: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) : HoldingStocksRepository {
    private val file = File(context.filesDir, "holding-stocks.json")
    private val source = YahooStockSource(client, json)
    private val mutex = Mutex()
    private val cache = MutableStateFlow<Map<String, CachedStock>?>(null)
    private val _status = MutableStateFlow(HoldingStocksStatus())
    override val status: StateFlow<HoldingStocksStatus> = _status.asStateFlow()

    override fun observe(): Flow<List<Stock>> = kotlinx.coroutines.flow.flow {
        if (cache.value == null) {
            val loaded = withContext(io) { load() }
            cache.compareAndSet(null, loaded)
        }
        emitAll(cache.filterNotNull().map { c -> c.values.sortedBy { it.symbol }.map { it.toDomain() } })
    }

    private fun load(): Map<String, CachedStock> = runCatching {
        json.decodeFromString<List<CachedStock>>(file.readText()).associateBy { it.symbol }
    }.getOrDefault(emptyMap())

    override suspend fun sync(wanted: Map<String, String>, force: Boolean) = mutex.withLock {
        withContext(io) {
            val now = System.currentTimeMillis()
            val current = (cache.value ?: load()).filterKeys { it in wanted }.toMutableMap()
            val due = wanted.keys.filter { s -> force || current[s].let { it == null || now - it.fetchedAt > STALE_MILLIS } }.sorted()
            val failed = mutableListOf<String>()
            _status.value = _status.value.copy(running = due.isNotEmpty(), done = 0, total = due.size)
            due.forEachIndexed { i, symbol ->
                val fetched = runCatching { source.fetch(symbol, wanted.getValue(symbol), now) }.getOrNull()
                if (fetched != null) current[symbol] = fetched else if (symbol !in current) failed += symbol
                _status.value = _status.value.copy(done = i + 1)
                delay(300)
            }
            // Sector comes from the broker; keep it current without refetching.
            current.replaceAll { s, c -> c.copy(sector = wanted[s] ?: c.sector) }
            if (due.isNotEmpty() || current.size != (cache.value?.size ?: -1)) {
                runCatching { file.writeText(json.encodeToString(current.values.toList())) }
            }
            cache.value = current
            _status.value = HoldingStocksStatus(
                running = false, done = due.size, total = due.size, failed = failed,
                updatedAtMillis = current.values.maxOfOrNull { it.fetchedAt },
            )
        }
    }

    private companion object {
        const val STALE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
