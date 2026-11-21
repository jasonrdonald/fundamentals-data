package com.fundamentalanalysis.app.data.sync

import com.fundamentalanalysis.app.data.DAILY
import com.fundamentalanalysis.app.data.MONTHLY
import com.fundamentalanalysis.app.data.local.BarCodec
import com.fundamentalanalysis.app.data.local.IndexSeriesEntity
import com.fundamentalanalysis.app.data.local.IndexValuationEntity
import com.fundamentalanalysis.app.data.local.PriceCodec
import com.fundamentalanalysis.app.data.local.StockSeriesEntity
import com.fundamentalanalysis.app.data.local.ValuationCodec
import com.fundamentalanalysis.app.data.remote.dto.ClosesDto
import com.fundamentalanalysis.app.data.remote.dto.DailyPartDto
import com.fundamentalanalysis.app.data.remote.dto.MonthlyPartDto
import com.fundamentalanalysis.app.data.toBars
import com.fundamentalanalysis.app.data.toPoints
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import java.time.LocalDate
import java.util.TreeMap

/**
 * Merges month and year parts into the series stored on the device, in memory, so a load of many
 * parts decodes and re-encodes each series once. A part's values replace stored values for the
 * same dates (a re-published month after a split corrects them); other dates are kept, so parts
 * can arrive in any order.
 */
internal class PartMerger(
    indexSeries: List<IndexSeriesEntity>,
    valuations: List<IndexValuationEntity>,
    stockSeries: List<StockSeriesEntity>,
) {
    private val indexDaily = HashMap<String, TreeMap<LocalDate, Bar>>()
    private val indexMonthly = HashMap<String, TreeMap<LocalDate, Bar>>()
    private val valuation = HashMap<String, TreeMap<LocalDate, ValuationPoint>>()
    private val stockDaily = HashMap<String, TreeMap<LocalDate, Double>>()
    private val stockMonthly = HashMap<String, TreeMap<LocalDate, Double>>()

    private val dirtyIndex = HashSet<Pair<String, String>>()
    private val dirtyValuation = HashSet<String>()
    private val dirtyStocks = HashSet<String>()

    init {
        for (row in indexSeries) {
            val target = if (row.timeframe == DAILY) indexDaily else indexMonthly
            target[row.symbol] = BarCodec.decode(row.bars).associateByTo(TreeMap()) { it.date }
        }
        for (row in valuations) valuation[row.symbol] = ValuationCodec.decode(row.points).associateByTo(TreeMap()) { it.date }
        for (row in stockSeries) {
            stockMonthly[row.symbol] = PriceCodec.decode(row.monthlyCloses).toMap(TreeMap())
            stockDaily[row.symbol] = PriceCodec.decode(row.dailyCloses).toMap(TreeMap())
        }
    }

    fun add(part: DailyPartDto) {
        for (ix in part.indices) {
            indexDaily.getOrPut(ix.symbol) { TreeMap() }.putAll(ix.bars.toBars().associateBy { it.date })
            dirtyIndex += ix.symbol to DAILY
        }
        addCloses(part.stocks, stockDaily)
    }

    fun add(part: MonthlyPartDto) {
        for (ix in part.indices) {
            indexMonthly.getOrPut(ix.symbol) { TreeMap() }.putAll(ix.bars.toBars().associateBy { it.date })
            dirtyIndex += ix.symbol to MONTHLY
        }
        for (v in part.valuation) {
            valuation.getOrPut(v.symbol) { TreeMap() }.putAll(v.points.toPoints().associateBy { it.date })
            dirtyValuation += v.symbol
        }
        addCloses(part.stocks, stockMonthly)
    }

    private fun addCloses(closes: ClosesDto, target: HashMap<String, TreeMap<LocalDate, Double>>) {
        val dates = closes.dates.map(LocalDate::parse)
        for (s in closes.items) {
            val series = target.getOrPut(s.symbol) { TreeMap() }
            dates.zip(s.c).forEach { (d, c) -> if (c != null) series[d] = c }
            dirtyStocks += s.symbol
        }
    }

    /** Drops daily data before [from] (the oldest month still published). */
    fun trimDaily(from: LocalDate) {
        for ((symbol, bars) in indexDaily) {
            val old = bars.headMap(from)
            if (old.isNotEmpty()) { old.clear(); dirtyIndex += symbol to DAILY }
        }
        for ((symbol, closes) in stockDaily) {
            val old = closes.headMap(from)
            if (old.isNotEmpty()) { old.clear(); dirtyStocks += symbol }
        }
    }

    fun indexSeriesRows(): List<IndexSeriesEntity> = dirtyIndex.map { (symbol, tf) ->
        val bars = (if (tf == DAILY) indexDaily else indexMonthly)[symbol]?.values?.toList().orEmpty()
        IndexSeriesEntity(symbol, tf, BarCodec.encode(bars))
    }

    fun valuationRows(): List<IndexValuationEntity> = dirtyValuation.map { symbol ->
        IndexValuationEntity(symbol, ValuationCodec.encode(valuation[symbol]?.values?.toList().orEmpty()))
    }

    fun stockSeriesRows(): List<StockSeriesEntity> = dirtyStocks.map { symbol ->
        StockSeriesEntity(
            symbol,
            PriceCodec.encode(stockMonthly[symbol]?.map { it.key to it.value }.orEmpty()),
            PriceCodec.encode(stockDaily[symbol]?.map { it.key to it.value }.orEmpty()),
        )
    }
}
