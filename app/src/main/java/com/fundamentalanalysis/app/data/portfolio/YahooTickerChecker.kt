package com.fundamentalanalysis.app.data.portfolio

import com.fundamentalanalysis.app.di.IoDispatcher
import com.fundamentalanalysis.app.domain.model.nseBaseSymbol
import com.fundamentalanalysis.app.domain.repository.ListedTicker
import com.fundamentalanalysis.app.domain.repository.TickerChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirms a ticker on Yahoo Finance's public chart endpoint (the same check the "Add to wishlist"
 * workflow repeats): it must be an equity quoted in INR with a recent price. NSE first, then BSE,
 * then the NSE SME series. Only the ticker is sent.
 */
@Singleton
class YahooTickerChecker @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TickerChecker {

    override suspend fun check(symbol: String): ListedTicker? = withContext(io) {
        val raw = symbol.trim().uppercase()
        val base = nseBaseSymbol(raw)
        val candidates = listOfNotNull("$base.NS", "$base.BO", "$base-SM.NS", "$raw.NS".takeIf { raw != base }).distinct()
        candidates.firstNotNullOfOrNull { yahoo -> quote(yahoo)?.let { name -> ListedTicker(base, yahoo, name, if (yahoo.endsWith(".BO")) "BSE" else "NSE") } }
    }

    /** The company name if [yahoo] is a listed Indian equity, else null. */
    private fun quote(yahoo: String): String? {
        val request = Request.Builder()
            .url("https://query1.finance.yahoo.com/v8/finance/chart/$yahoo?range=5d&interval=1d")
            .header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        val meta = client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("Yahoo Finance answered HTTP ${resp.code}")
            json.parseToJsonElement(resp.body.string()).jsonObject["chart"]?.jsonObject?.get("result")
                ?.let { runCatching { it.jsonArray.firstOrNull()?.jsonObject?.get("meta")?.jsonObject }.getOrNull() }
        } ?: return null
        fun str(key: String) = meta.str(key)
        if (str("instrumentType") != "EQUITY" || str("currency") != "INR") return null
        if ((meta["regularMarketPrice"]?.jsonPrimitive?.doubleOrNull ?: 0.0) <= 0.0) return null
        return str("longName") ?: str("shortName") ?: yahoo
    }

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) FundamentalAnalysis"
    }
}
