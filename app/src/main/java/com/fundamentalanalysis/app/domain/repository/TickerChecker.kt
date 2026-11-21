package com.fundamentalanalysis.app.domain.repository

/** A ticker confirmed as a listed Indian equity: what a wishlist request carries. */
data class ListedTicker(
    /** Listing symbol (no series suffix), as the dataset names it. */
    val symbol: String,
    /** Yahoo Finance symbol the pipeline should use (SYMBOL.NS, SYMBOL.BO or an SME series). */
    val yahoo: String,
    val name: String,
    /** "NSE" or "BSE". */
    val exchange: String,
)

/** Checks tickers before they're requested for the wishlist (one live lookup each). */
interface TickerChecker {
    /** The listing behind [symbol], or null when it isn't a listed Indian equity. */
    suspend fun check(symbol: String): ListedTicker?
}
