package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.AnalyzedIndex
import com.fundamentalanalysis.app.domain.model.Composition
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.model.Period
import java.time.LocalDate

data class RankedRow(val rank: Int, val index: AnalyzedIndex, val value: Double)

/** Month-on-month % change per index for a shared set of months (a heat table). */
data class MonthlyMatrix(
    val months: List<LocalDate>,
    val rows: List<Pair<AnalyzedIndex, List<Double?>>>,
    val monthToDate: LocalDate?,
)

object ReportBuilder {

    /** Best-first for the metric's direction; indices without a value are excluded. */
    fun ranking(indices: List<AnalyzedIndex>, metric: Metric): List<RankedRow> {
        val withValues = indices.mapNotNull { i -> i.valueOf(metric)?.let { i to it } }
        val sorted = if (metric.higherIsBetter) withValues.sortedByDescending { it.second }
        else withValues.sortedBy { it.second }
        return sorted.mapIndexed { i, (index, v) -> RankedRow(i + 1, index, v) }
    }

    fun monthlyMatrix(indices: List<AnalyzedIndex>, months: Int = 12): MonthlyMatrix {
        val allMonths = indices.flatMap { i -> i.analytics.monthly.map { it.month } }.distinct().sorted().takeLast(months)
        val rows = indices.map { idx ->
            val byMonth = idx.analytics.monthly.associateBy { it.month }
            idx to allMonths.map { byMonth[it]?.changePct }
        }
        val mtd = indices.flatMap { it.analytics.monthly }.firstOrNull { it.isMonthToDate }?.month
        return MonthlyMatrix(allMonths, rows, mtd)
    }

    val performancePeriods = listOf(Period.D1, Period.W1, Period.M1, Period.M3, Period.M6, Period.YTD, Period.Y1, Period.Y3, Period.Y5)

    fun rankingCsv(rows: List<RankedRow>, metric: Metric): String = buildString {
        appendLine("rank,symbol,name,category,${csv(metric.label)}")
        rows.forEach { r ->
            appendLine(listOf(r.rank.toString(), r.index.symbol, r.index.index.name, r.index.index.category.label, r.value.toString()).joinToString(",") { csv(it) })
        }
    }

    fun performanceCsv(indices: List<AnalyzedIndex>): String = buildString {
        appendLine((listOf("symbol", "name", "category", "last_session", "close") + performancePeriods.map { it.label }).joinToString(",") { csv(it) })
        indices.forEach { i ->
            val s = i.analytics.latest
            appendLine(
                (listOf(i.symbol, i.index.name, i.index.category.label, s?.date?.toString().orEmpty(), s?.close?.toString().orEmpty()) +
                    performancePeriods.map { p -> i.analytics.returns[p]?.toString().orEmpty() }).joinToString(",") { csv(it) },
            )
        }
    }

    fun monthlyCsv(matrix: MonthlyMatrix): String = buildString {
        appendLine((listOf("symbol") + matrix.months.map { it.toString().substring(0, 7) }).joinToString(",") { csv(it) })
        matrix.rows.forEach { (idx, values) ->
            appendLine((listOf(idx.symbol) + values.map { it?.toString().orEmpty() }).joinToString(",") { csv(it) })
        }
    }

    fun compositionCsv(composition: Composition): String = buildString {
        appendLine("index,symbol,name,industry")
        composition.constituents.forEach { c ->
            appendLine(listOf(composition.indexSymbol, c.symbol, c.name, c.industry).joinToString(",") { csv(it) })
        }
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
