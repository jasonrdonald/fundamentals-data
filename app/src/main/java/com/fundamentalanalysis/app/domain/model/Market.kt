package com.fundamentalanalysis.app.domain.model

import java.time.LocalDate

enum class IndexCategory(val label: String) {
    BROAD("Broad market"),
    SECTOR("Sectoral"),
    THEMATIC("Thematic"),
    VOLATILITY("Volatility");

    companion object {
        fun from(value: String): IndexCategory = entries.firstOrNull { it.name.equals(value, true) } ?: BROAD
    }
}

/** One OHLCV bar in index points. For monthly bars [date] is the first day of the month. */
data class Bar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
    /** Monthly bars: last session included in the bar. */
    val lastSession: LocalDate? = null,
)

data class MarketIndex(
    val symbol: String,
    val name: String,
    val category: IndexCategory,
    val source: String?,
    /** Oldest -> newest completed sessions. */
    val daily: List<Bar>,
    /** Oldest -> newest calendar months; the last one may be month-to-date. */
    val monthly: List<Bar>,
    /** Month-end valuation multiples, oldest first (empty when the source has none). */
    val valuation: List<ValuationPoint> = emptyList(),
)

/** Index valuation on [date] (a month's last trading day). Dividend yield is in percent. */
data class ValuationPoint(
    val date: LocalDate,
    val pe: Double?,
    val pb: Double?,
    val dividendYield: Double?,
)

data class Constituent(val symbol: String, val name: String, val industry: String)

/** Industry classification of an index's members (NSE industry names). */
data class Composition(
    val indexSymbol: String,
    val asOf: String?,
    val constituents: List<Constituent>,
) {
    /** Industries by member count, largest first. */
    val byIndustry: List<Pair<String, List<Constituent>>>
        get() = constituents.groupBy { it.industry.ifBlank { "Other" } }
            .toList()
            .sortedWith(compareByDescending<Pair<String, List<Constituent>>> { it.second.size }.thenBy { it.first })
}
