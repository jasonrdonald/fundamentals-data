package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.PriceCodec
import com.fundamentalanalysis.app.data.local.StockEntity
import com.fundamentalanalysis.app.data.local.StockFinancialEntity
import com.fundamentalanalysis.app.data.local.StockRows
import com.fundamentalanalysis.app.data.local.StockWithData
import com.fundamentalanalysis.app.data.remote.dto.FinancialPeriodDto
import com.fundamentalanalysis.app.data.remote.dto.StocksDto
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.FinancialPeriod
import com.fundamentalanalysis.app.domain.model.Stock
import java.time.LocalDate

private const val ANNUAL = "A"
private const val QUARTERLY = "Q"

internal fun StocksDto?.toRows(): StockRows {
    if (this == null) return StockRows()
    val monthDates = months.map(LocalDate::parse)
    val sessionDates = sessions.map(LocalDate::parse)
    fun pack(dates: List<LocalDate>, values: List<Double?>) =
        PriceCodec.encode(dates.zip(values).mapNotNull { (d, c) -> c?.let { d to it } })
    val stocks = items.map {
        StockEntity(
            it.symbol, it.index, it.name, it.industry, it.source, it.fundamentals.marketCapCr, it.fundamentals.pe, it.fundamentals.pb,
            monthlyCloses = pack(monthDates, it.mc),
            dailyCloses = pack(sessionDates, it.dc),
        )
    }
    val financials = items.flatMap { s ->
        s.fundamentals.annual.map { it.toEntity(s.symbol, ANNUAL) } + s.fundamentals.quarterly.map { it.toEntity(s.symbol, QUARTERLY) }
    }
    return StockRows(stocks, financials)
}

private fun FinancialPeriodDto.toEntity(symbol: String, period: String) =
    StockFinancialEntity(symbol, period, d, revenue, netIncome, operatingIncome, equity, debt, eps)

internal fun StockWithData.toDomain(): Stock {
    fun bars(packed: ByteArray) = PriceCodec.decode(packed).map { (d, c) -> Bar(d, c, c, c, c, null) }
    fun periods(p: String) = financials.filter { it.period == p }
        .map { FinancialPeriod(LocalDate.parse(it.date), it.revenue, it.netIncome, it.operatingIncome, it.equity, it.debt, it.eps) }
        .sortedBy { it.endDate }
    return Stock(
        symbol = stock.symbol,
        name = stock.name,
        industry = stock.industry.ifBlank { "Other" },
        indexSymbol = stock.indexSymbol,
        marketCapCr = stock.marketCapCr,
        pe = stock.pe,
        pb = stock.pb,
        monthly = bars(stock.monthlyCloses),
        daily = bars(stock.dailyCloses),
        annual = periods(ANNUAL),
        quarterly = periods(QUARTERLY),
    )
}
