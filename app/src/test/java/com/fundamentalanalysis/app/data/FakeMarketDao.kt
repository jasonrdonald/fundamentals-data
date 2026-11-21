package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.AnalyticsCount
import com.fundamentalanalysis.app.data.local.AnalyticsEntity
import com.fundamentalanalysis.app.data.local.ConstituentEntity
import com.fundamentalanalysis.app.data.local.DatasetPartEntity
import com.fundamentalanalysis.app.data.local.IndexEntity
import com.fundamentalanalysis.app.data.local.IndexSeriesEntity
import com.fundamentalanalysis.app.data.local.IndexValuationEntity
import com.fundamentalanalysis.app.data.local.IndexWithSeries
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.MetaEntity
import com.fundamentalanalysis.app.data.local.StockEntity
import com.fundamentalanalysis.app.data.local.StockFinancialsEntity
import com.fundamentalanalysis.app.data.local.StockSeriesEntity
import com.fundamentalanalysis.app.data.local.StockWithHistory
import com.fundamentalanalysis.app.data.local.SyncEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory MarketDao; the transaction helpers come from the interface's default methods. */
class FakeMarketDao : MarketDao {
    val indices = MutableStateFlow(mapOf<String, IndexEntity>())
    val series = MutableStateFlow(mapOf<Pair<String, String>, IndexSeriesEntity>())
    val valuation = MutableStateFlow(mapOf<String, IndexValuationEntity>())
    val constituents = MutableStateFlow(listOf<ConstituentEntity>())
    val stocks = MutableStateFlow(mapOf<String, StockEntity>())
    val stockSeriesRows = MutableStateFlow(mapOf<String, StockSeriesEntity>())
    val financials = MutableStateFlow(mapOf<String, StockFinancialsEntity>())
    val analytics = MutableStateFlow(mapOf<Pair<String, String>, AnalyticsEntity>())
    val partsRows = MutableStateFlow(mapOf<String, DatasetPartEntity>())
    val events = MutableStateFlow(listOf<SyncEventEntity>())
    val metaRows = MutableStateFlow(mapOf<String, String>())
    private var nextEventId = 1L

    private fun joined(): List<IndexWithSeries> = indices.value.values.sortedBy { it.symbol }.map { i ->
        IndexWithSeries(i, series.value.values.filter { it.symbol == i.symbol }, listOfNotNull(valuation.value[i.symbol]))
    }

    override fun observeIndices(): Flow<List<IndexWithSeries>> = indices.map { joined() }
    override fun observeConstituents(): Flow<List<ConstituentEntity>> = constituents
    override fun observeStocks(): Flow<List<StockEntity>> = stocks.map { it.values.sortedBy { s -> s.symbol } }
    override fun observeStock(symbol: String): Flow<StockWithHistory?> = stocks.map { m ->
        m[symbol]?.let { StockWithHistory(it, listOfNotNull(stockSeriesRows.value[symbol]), listOfNotNull(financials.value[symbol])) }
    }
    override fun observeAnalytics(kind: String): Flow<List<AnalyticsEntity>> = analytics.map { m -> m.values.filter { it.kind == kind } }
    override fun observeAnalyticsCounts(): Flow<List<AnalyticsCount>> =
        analytics.map { m -> m.values.groupBy { it.kind }.map { (k, v) -> AnalyticsCount(k, v.size) } }
    override fun observeParts(): Flow<List<DatasetPartEntity>> = partsRows.map { it.values.toList() }
    override fun observeEvents(limit: Int): Flow<List<SyncEventEntity>> = events.map { it.sortedByDescending { e -> e.id }.take(limit) }
    override fun observeMeta(): Flow<List<MetaEntity>> = metaRows.map { m -> m.map { MetaEntity(it.key, it.value) } }

    override suspend fun parts(): List<DatasetPartEntity> = partsRows.value.values.toList()
    override suspend fun indexSeries(timeframe: String): List<IndexSeriesEntity> = series.value.values.filter { it.timeframe == timeframe }
    override suspend fun stockSeries(): List<StockSeriesEntity> = stockSeriesRows.value.values.toList()
    override suspend fun valuations(): List<IndexValuationEntity> = valuation.value.values.toList()
    override suspend fun meta(key: String): String? = metaRows.value[key]

    override suspend fun insertIndices(rows: List<IndexEntity>) { indices.value += rows.associateBy { it.symbol } }
    override suspend fun insertIndexSeries(rows: List<IndexSeriesEntity>) { series.value += rows.associateBy { it.symbol to it.timeframe } }
    override suspend fun insertValuation(rows: List<IndexValuationEntity>) { valuation.value += rows.associateBy { it.symbol } }
    override suspend fun insertConstituents(rows: List<ConstituentEntity>) { constituents.value += rows }
    override suspend fun insertStocks(rows: List<StockEntity>) { stocks.value += rows.associateBy { it.symbol } }
    override suspend fun insertStockSeries(rows: List<StockSeriesEntity>) { stockSeriesRows.value += rows.associateBy { it.symbol } }
    override suspend fun insertFinancials(rows: List<StockFinancialsEntity>) { financials.value += rows.associateBy { it.symbol } }
    override suspend fun insertAnalytics(rows: List<AnalyticsEntity>) { analytics.value += rows.associateBy { it.kind to it.key } }
    override suspend fun upsertPart(part: DatasetPartEntity) { partsRows.value += part.name to part }
    override suspend fun putMeta(row: MetaEntity) { metaRows.value += row.key to row.value }
    override suspend fun insertEvent(event: SyncEventEntity) { events.value += event.copy(id = nextEventId++) }

    override suspend fun deleteIndices() { indices.value = emptyMap() }
    override suspend fun deleteConstituents() { constituents.value = emptyList() }
    override suspend fun deleteStocks() { stocks.value = emptyMap() }
    override suspend fun deleteAnalytics() { analytics.value = emptyMap() }
    override suspend fun pruneIndexSeries() { series.value = series.value.filterKeys { it.first in indices.value } }
    override suspend fun pruneValuation() { valuation.value = valuation.value.filterKeys { it in indices.value } }
    override suspend fun pruneStockSeries() { stockSeriesRows.value = stockSeriesRows.value.filterKeys { it in stocks.value } }
    override suspend fun pruneFinancials() { financials.value = financials.value.filterKeys { it in stocks.value } }
    override suspend fun trimEvents(keep: Int) { events.value = events.value.sortedByDescending { it.id }.take(keep) }
    override suspend fun deleteIndexSeries() { series.value = emptyMap() }
    override suspend fun deleteValuation() { valuation.value = emptyMap() }
    override suspend fun deleteStockSeries() { stockSeriesRows.value = emptyMap() }
    override suspend fun deleteFinancials() { financials.value = emptyMap() }
    override suspend fun deleteParts() { partsRows.value = emptyMap() }
    override suspend fun deletePartsNamed(names: List<String>) { partsRows.value = partsRows.value - names.toSet() }
}
