package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.BarEntity
import com.fundamentalanalysis.app.data.local.ConstituentEntity
import com.fundamentalanalysis.app.data.local.IndexEntity
import com.fundamentalanalysis.app.data.local.IndexWithBars
import com.fundamentalanalysis.app.data.local.ValuationEntity
import com.fundamentalanalysis.app.data.remote.dto.BarDto
import com.fundamentalanalysis.app.data.remote.dto.CompositionDto
import com.fundamentalanalysis.app.data.remote.dto.IndexDto
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.Constituent
import com.fundamentalanalysis.app.domain.model.IndexCategory
import com.fundamentalanalysis.app.domain.model.MarketIndex
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import java.time.LocalDate

internal const val DAILY = "D"
internal const val MONTHLY = "M"

internal fun IndexDto.toEntity() = IndexEntity(symbol, name, category, source)

internal fun IndexDto.valuationEntities(): List<ValuationEntity> =
    valuation.map { ValuationEntity(symbol, it.d, it.pe, it.pb, it.dy) }

internal fun IndexDto.barEntities(): List<BarEntity> =
    daily.map { it.toEntity(symbol, DAILY) } + monthly.map { it.toEntity(symbol, MONTHLY) }

private fun BarDto.toEntity(symbol: String, timeframe: String) =
    BarEntity(symbol, timeframe, d, o, h, l, c, v, last)

internal fun CompositionDto.toEntities(): List<ConstituentEntity> =
    constituents.map { ConstituentEntity(index, it.symbol, it.name, it.industry, asOf) }

internal fun IndexWithBars.toDomain(): MarketIndex {
    val (daily, monthly) = bars.partition { it.timeframe == DAILY }
    return MarketIndex(
        symbol = index.symbol,
        name = index.name,
        category = IndexCategory.from(index.category),
        source = index.source,
        daily = daily.map { it.toDomain() }.sortedBy { it.date },
        monthly = monthly.map { it.toDomain() }.sortedBy { it.date },
        valuation = valuation.map { ValuationPoint(LocalDate.parse(it.date), it.pe, it.pb, it.dividendYield) }.sortedBy { it.date },
    )
}

private fun BarEntity.toDomain() = Bar(
    date = LocalDate.parse(date),
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    lastSession = lastSession?.let(LocalDate::parse),
)

internal fun List<ConstituentEntity>.toComposition(): Composition? {
    if (isEmpty()) return null
    return Composition(
        indexSymbol = first().indexSymbol,
        asOf = first().asOf,
        constituents = map { Constituent(it.symbol, it.name, it.industry) },
    )
}
