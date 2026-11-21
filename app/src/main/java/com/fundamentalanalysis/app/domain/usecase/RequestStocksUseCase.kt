package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.domain.model.nseBaseSymbol
import com.fundamentalanalysis.app.domain.repository.ListedTicker
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.SettingsRepository
import com.fundamentalanalysis.app.domain.repository.TickerChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/** What checking one ticker found. */
sealed interface TickerResult {
    val symbol: String
    data class Listed(val ticker: ListedTicker) : TickerResult { override val symbol get() = ticker.symbol }
    data class InDataset(override val symbol: String) : TickerResult
    data class AlreadyRequested(override val symbol: String) : TickerResult
    data class NotFound(override val symbol: String) : TickerResult
    data class Invalid(override val symbol: String) : TickerResult
    data class Failed(override val symbol: String, val message: String) : TickerResult
}

/**
 * Wishlist requests: the user enters tickers, each is checked (format, already covered, then one
 * live lookup), and the listed ones are emailed to the developer, who approves them by running the
 * "Add to wishlist" workflow. Requested tickers are remembered on this device until they appear in
 * the dataset; nothing changes in the app before that.
 */
class RequestStocksUseCase @Inject constructor(
    private val market: MarketRepository,
    private val checker: TickerChecker,
    private val settings: SettingsRepository,
) {
    suspend fun check(input: String): List<TickerResult> {
        val covered = market.observeStocks().first().map { it.symbol }.toSet()
        val requested = settings.observeRequestedTickers().first()
        return parse(input).map { raw ->
            val symbol = nseBaseSymbol(raw)
            when {
                !SYMBOL.matches(symbol) -> TickerResult.Invalid(raw)
                symbol in covered -> TickerResult.InDataset(symbol)
                symbol in requested -> TickerResult.AlreadyRequested(symbol)
                else -> runCatching { checker.check(raw) }.fold(
                    onSuccess = { it?.let(TickerResult::Listed) ?: TickerResult.NotFound(symbol) },
                    onFailure = { TickerResult.Failed(symbol, it.message ?: "lookup failed") },
                )
            }
        }
    }

    suspend fun markRequested(tickers: List<ListedTicker>) = settings.addRequestedTickers(tickers.map { it.symbol }.toSet())

    /** Requested tickers not in the dataset yet; ones that have arrived are forgotten. */
    fun observePending(): Flow<List<String>> =
        combine(settings.observeRequestedTickers(), market.observeStocks()) { requested, stocks ->
            val covered = stocks.map { it.symbol }.toSet()
            requested.filter { it in covered }.toSet() to requested.filterNot { it in covered }.sorted()
        }.onEach { (arrived, _) -> if (arrived.isNotEmpty()) settings.removeRequestedTickers(arrived) }
            .map { it.second }

    companion object {
        private val SYMBOL = Regex("^[A-Z0-9&][A-Z0-9&\\-]{0,19}$")

        /** Tickers separated by commas, spaces or new lines, upper-cased, without duplicates. */
        fun parse(input: String): List<String> =
            input.split(Regex("[,;\\s]+")).map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

        /** The workflow input for [tickers]: SYMBOL, or SYMBOL=YAHOO when it isn't SYMBOL.NS. */
        fun workflowInput(tickers: List<ListedTicker>): String =
            tickers.joinToString(", ") { if (it.yahoo == "${it.symbol}.NS") it.symbol else "${it.symbol}=${it.yahoo}" }

        /** Subject and body of the request email to the developer. */
        fun email(tickers: List<ListedTicker>, workflowUrl: String): Pair<String, String> {
            val subject = "Wishlist request: " + tickers.joinToString(", ") { it.symbol }
            val body = buildString {
                appendLine("Please add these stocks to the Wishlist (checked on Yahoo Finance):")
                appendLine()
                tickers.forEach { appendLine("${it.symbol} - ${it.name} (${it.exchange}, ${it.yahoo})") }
                appendLine()
                appendLine("To approve, run the \"Add to wishlist\" workflow with:")
                appendLine(workflowInput(tickers))
                if (workflowUrl.isNotBlank()) appendLine(workflowUrl)
            }
            return subject to body
        }
    }
}
