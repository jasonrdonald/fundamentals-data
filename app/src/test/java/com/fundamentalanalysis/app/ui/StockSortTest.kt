package com.fundamentalanalysis.app.ui

import com.fundamentalanalysis.app.ui.stocks.PORTFOLIO_UNIVERSE
import com.fundamentalanalysis.app.ui.stocks.StockSelection
import com.fundamentalanalysis.app.ui.stocks.StockSort
import com.fundamentalanalysis.app.ui.stocks.sortRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** All stocks ordering: biggest first, reversible, unknown values always last. */
class StockSortTest {
    private val rows = listOf("B" to 2.0, "A" to null, "C" to 5.0, "D" to -1.0)
    private fun sorted(sort: StockSort, reversed: Boolean) =
        sortRows(rows, sort, reversed, { it.first }) { it.second }.map { it.first }

    @Test fun `values order biggest first and reverse, with unknowns last`() {
        assertEquals(listOf("C", "B", "D", "A"), sorted(StockSort.RETURN, false))
        assertEquals(listOf("D", "B", "C", "A"), sorted(StockSort.RETURN, true))
    }

    @Test fun `names order A to Z and reverse`() {
        assertEquals(listOf("A", "B", "C", "D"), sorted(StockSort.NAME, false))
        assertEquals(listOf("D", "C", "B", "A"), sorted(StockSort.NAME, true))
    }

    @Test fun `an order the list does not offer falls back to its default`() {
        assertEquals(StockSort.MARKET_CAP, StockSelection(sort = StockSort.PNL).sortFor(portfolio = false))
        assertEquals(StockSort.VALUE, StockSelection(universe = PORTFOLIO_UNIVERSE, sort = StockSort.MARKET_CAP).sortFor(portfolio = true))
        assertEquals(StockSort.PNL, StockSelection(sort = StockSort.PNL).sortFor(portfolio = true))
    }
}
