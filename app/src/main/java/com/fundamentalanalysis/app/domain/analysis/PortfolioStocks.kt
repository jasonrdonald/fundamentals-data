package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.IndexGroup
import com.fundamentalanalysis.app.domain.model.Period
import com.fundamentalanalysis.app.domain.model.Position
import com.fundamentalanalysis.app.domain.usecase.ObserveAnalyzedStocksUseCase

/** Which of the portfolio's stock holdings to analyse on the Stocks page. Funds and ETFs are never included. */
enum class HoldingFilter(val label: String, val group: IndexGroup?) {
    ALL("All", null),
    NIFTY_50("Nifty 50", IndexGroup.NIFTY_50),
    NEXT_50("Next 50", IndexGroup.NEXT_50),
    MIDCAP_150("Midcap 150", IndexGroup.MIDCAP_150),
    UNCATEGORIZED("Uncategorized", IndexGroup.OTHER),
}

/** The portfolio's stock holdings treated like an index's constituents. */
object PortfolioStocks {
    /** Stock holdings matching [filter], largest value first. */
    fun holdings(positions: List<Position>, filter: HoldingFilter): List<Position> = positions
        .filter { it.kind == HoldingKind.STOCK && (filter.group == null || IndexGroup.of(it) == filter.group) }
        .sortedByDescending { it.value }

    fun counts(positions: List<Position>): Map<HoldingFilter, Int> =
        HoldingFilter.entries.associateWith { holdings(positions, it).size }

    /** Holdings with dataset metrics (tracked index stocks); the rest can't be ranked or charted. */
    fun analysed(holdings: List<Position>): List<AnalyzedStock> = holdings.mapNotNull { it.tracked }

    /**
     * Sector breakdown of [stocks] for [period]. Each stock is judged against its own index; when
     * the stocks come from more than one index, a sector has no single benchmark return.
     */
    fun sectors(stocks: List<AnalyzedStock>, period: Period): List<SectorPerformance> {
        fun bench(s: AnalyzedStock) = ObserveAnalyzedStocksUseCase.benchmarkOf(s.stock.indexSymbol)
        val mixed = stocks.map(::bench).distinct().size > 1
        return StockReports.sectorPerformance(stocks, period).map { s ->
            if (mixed && s.stocks.map(::bench).distinct().size > 1) s.copy(benchmarkReturn = null) else s
        }
    }
}
