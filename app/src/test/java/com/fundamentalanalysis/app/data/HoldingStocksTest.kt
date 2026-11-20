package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.portfolio.YahooStockSource
import com.fundamentalanalysis.app.data.portfolio.toDomain
import com.fundamentalanalysis.app.domain.model.BrokerHolding
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.PortfolioData
import com.fundamentalanalysis.app.domain.usecase.SyncHoldingStocksUseCase
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic symbols and canned responses only; nothing goes to the network. */
class HoldingStocksTest {
    private fun holding(symbol: String, kind: HoldingKind = HoldingKind.STOCK, qty: Double = 10.0, sector: String = "FINANCIAL SERVICES") =
        BrokerHolding("acct", symbol, "", kind, sector, qty, 100.0, 110.0)

    @Test fun `only held stocks the dataset lacks are fetched`() {
        val data = PortfolioData(holdings = listOf(
            holding("AAA"), // in the dataset
            holding("BBB"),
            holding("CCC", kind = HoldingKind.FUND),
            holding("DDD", qty = 0.0), // sold
            holding("EEE", sector = ""),
        ))
        val wanted = SyncHoldingStocksUseCase.wanted(data, covered = setOf("AAA"))
        assertEquals(mapOf("BBB" to "Financial Services", "EEE" to "Unclassified"), wanted)
    }

    @Test fun `month ends are each month's last close`() {
        val daily = listOf("2026-01-02" to 10.0, "2026-01-30" to 11.0, "2026-02-02" to 12.0, "2026-02-27" to 13.0)
        assertEquals(listOf("2026-01-01" to 11.0, "2026-02-01" to 13.0), YahooStockSource.monthEnds(daily))
    }

    /** A client answering the chart and fundamentals calls from canned JSON; [nse] = whether the .NS listing exists. */
    private fun client(nse: Boolean, sessions: Int): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val body = when {
            "/chart/" in url && (".NS" in url) != nse -> null
            "/chart/" in url -> chart(sessions)
            "timeseries" in url -> FUNDAMENTALS
            else -> null
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200).message("")
            .body((body ?: "{}").toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun chart(n: Int): String {
        val zone = ZoneId.of("Asia/Kolkata")
        val start = LocalDate.of(2025, 1, 6)
        val stamps = (0 until n).map { start.plusDays(it.toLong()).atTime(9, 15).atZone(zone).toEpochSecond() }
        val closes = (0 until n).map { 100.0 + it }
        return """{"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Kolkata","longName":"Example Ltd"},
            "timestamp":$stamps,"indicators":{"quote":[{"close":${closes.mapIndexed { i, c -> if (i == 3) "null" else "$c" }}}]}}]}}"""
    }

    @Test fun `chart and fundamentals become a stock like the dataset's`() {
        val source = YahooStockSource(client(nse = true, sessions = 300), Json { ignoreUnknownKeys = true })
        val cached = source.fetch("XYZ", "Healthcare", now = 0L)!!
        assertEquals("yahoo:XYZ.NS", cached.source)
        assertEquals("Example Ltd", cached.name)
        assertEquals(YahooStockSource.DAILY_SESSIONS, cached.daily.size) // 299 closes (one null), last 260 kept
        assertEquals(399.0, cached.daily.last().second, 0.0)
        val stock = cached.toDomain()
        assertEquals("", stock.indexSymbol)
        assertEquals("Healthcare", stock.industry)
        assertEquals(1234.5, stock.marketCapCr!!, 1e-9)
        assertEquals(25.5, stock.pe!!, 1e-9)
        assertEquals(2, stock.annual.size)
        assertEquals(500.0, stock.annual.last().revenue!!, 1e-9) // 5e9 INR = 500 crore
        assertEquals(12.34, stock.annual.last().eps!!, 1e-9)
        assertEquals(LocalDate.of(2025, 1, 1), stock.monthly.first().date)
    }

    @Test fun `falls back to the BSE listing, and gives up when neither exists`() {
        val bse = YahooStockSource(client(nse = false, sessions = 40), Json { ignoreUnknownKeys = true }).fetch("XYZ", "Retail", 0L)!!
        assertEquals("yahoo:XYZ.BO", bse.source)
        val none = YahooStockSource(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build(), Json).fetch("XYZ", "Retail", 0L)
        assertNull(none)
    }

    private companion object {
        val FUNDAMENTALS = """{"timeseries":{"result":[
            {"meta":{"type":["trailingMarketCap"]},"trailingMarketCap":[{"asOfDate":"2026-11-19","reportedValue":{"raw":12345000000}}]},
            {"meta":{"type":["trailingPeRatio"]},"trailingPeRatio":[{"asOfDate":"2026-11-19","reportedValue":{"raw":25.5}}]},
            {"meta":{"type":["annualTotalRevenue"]},"annualTotalRevenue":[
                {"asOfDate":"2025-03-31","reportedValue":{"raw":4000000000}},{"asOfDate":"2026-03-31","reportedValue":{"raw":5000000000}}]},
            {"meta":{"type":["annualDilutedEPS"]},"annualDilutedEPS":[{"asOfDate":"2026-03-31","reportedValue":{"raw":12.344}}]},
            {"meta":{"type":["quarterlyNetIncome"]},"quarterlyNetIncome":[null]}
        ]}}"""
    }
}
