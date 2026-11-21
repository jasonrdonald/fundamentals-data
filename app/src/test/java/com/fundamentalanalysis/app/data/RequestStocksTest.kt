package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.portfolio.YahooTickerChecker
import com.fundamentalanalysis.app.domain.repository.ListedTicker
import com.fundamentalanalysis.app.domain.usecase.RequestStocksUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wishlist requests: parsing, the approval email, and the ticker check (canned responses only). */
class RequestStocksTest {
    @Test fun `tickers are split, upper-cased and de-duplicated`() {
        assertEquals(listOf("IRCON", "CDSL", "TECHD-SM"), RequestStocksUseCase.parse(" ircon, cdsl\nIRCON;  techd-sm "))
        assertEquals(emptyList<String>(), RequestStocksUseCase.parse(" , "))
    }

    private val ircon = ListedTicker("IRCON", "IRCON.NS", "Ircon International Limited", "NSE")
    private val techd = ListedTicker("TECHD", "TECHD-SM.NS", "Techdefence Labs Solutions Limited", "NSE")

    @Test fun `the email carries the workflow input, naming non-default Yahoo symbols`() {
        assertEquals("IRCON, TECHD=TECHD-SM.NS", RequestStocksUseCase.workflowInput(listOf(ircon, techd)))
        val (subject, body) = RequestStocksUseCase.email(listOf(ircon, techd), "https://example.org/wf")
        assertEquals("Wishlist request: IRCON, TECHD", subject)
        assertTrue(body.contains("IRCON - Ircon International Limited (NSE, IRCON.NS)"))
        assertTrue(body.contains("IRCON, TECHD=TECHD-SM.NS\nhttps://example.org/wf"))
    }

    /** Answers chart calls from [listings] (Yahoo symbol -> instrument type); everything else is 404. */
    private fun checker(listings: Map<String, String>): Pair<YahooTickerChecker, MutableList<String>> {
        val asked = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val yahoo = chain.request().url.pathSegments.last()
            asked += yahoo
            val type = listings[yahoo]
            val body = if (type == null) """{"chart":{"result":null,"error":{"code":"Not Found"}}}"""
            else """{"chart":{"result":[{"meta":{"instrumentType":"$type","currency":"INR","regularMarketPrice":101.5,"longName":"$yahoo Limited"}}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (type == null) 404 else 200).message("").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return YahooTickerChecker(client, DatasetFiles.json, Dispatchers.Unconfined) to asked
    }

    @Test fun `a ticker is found on NSE, then BSE, then the SME series`() = runBlocking {
        val (c, asked) = checker(mapOf("AAA.BO" to "EQUITY", "BBB-SM.NS" to "EQUITY"))
        assertEquals(ListedTicker("AAA", "AAA.BO", "AAA.BO Limited", "BSE"), c.check("aaa"))
        assertEquals(listOf("AAA.NS", "AAA.BO"), asked)
        assertEquals("BBB-SM.NS", c.check("BBB-SM")?.yahoo)
        assertEquals("BBB", c.check("BBB-SM")?.symbol)
    }

    @Test fun `funds and unknown tickers are rejected`() = runBlocking {
        val (c, _) = checker(mapOf("GOLDBEES.NS" to "ETF"))
        assertNull(c.check("GOLDBEES"))
        assertNull(c.check("NOSUCH"))
    }
}
