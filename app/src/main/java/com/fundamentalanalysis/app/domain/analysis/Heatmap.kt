package com.fundamentalanalysis.app.domain.analysis

import com.fundamentalanalysis.app.domain.model.AnalyzedStock
import com.fundamentalanalysis.app.domain.model.Position
import kotlin.math.max
import kotlin.math.min

/** A rectangle in layout units (pixels in the UI). */
data class TreeRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val area: Double get() = w * h
    fun contains(px: Double, py: Double) = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * Squarified treemap (Bruls, Huizing & van Wijk): each weight gets a rectangle whose area is
 * proportional to it, filling [bounds], with aspect ratios kept close to 1 so small tiles stay
 * readable. Returned rectangles are in the same order as [weights].
 */
object Treemap {
    fun layout(weights: List<Double>, bounds: TreeRect): List<TreeRect> {
        if (weights.isEmpty()) return emptyList()
        val total = weights.sumOf { max(it, 0.0) }
        if (total <= 0 || bounds.w <= 0 || bounds.h <= 0) return weights.map { TreeRect(bounds.x, bounds.y, 0.0, 0.0) }
        val scale = bounds.area / total
        val order = weights.indices.sortedByDescending { weights[it] }
        val areas = order.map { max(weights[it], 0.0) * scale }
        val out = arrayOfNulls<TreeRect>(weights.size)
        var rect = bounds
        var i = 0
        while (i < order.size) {
            val side = min(rect.w, rect.h)
            var end = i + 1
            while (end < order.size && worst(areas, i, end + 1, side) <= worst(areas, i, end, side)) end++
            val rowArea = (i until end).sumOf { areas[it] }
            if (rect.w >= rect.h) {
                // A column on the left, tiles stacked top to bottom.
                val colW = if (rect.h > 0) rowArea / rect.h else 0.0
                var y = rect.y
                for (k in i until end) {
                    val h = if (colW > 0) areas[k] / colW else 0.0
                    out[order[k]] = TreeRect(rect.x, y, colW, h)
                    y += h
                }
                rect = TreeRect(rect.x + colW, rect.y, max(rect.w - colW, 0.0), rect.h)
            } else {
                // A row along the top, tiles left to right.
                val rowH = if (rect.w > 0) rowArea / rect.w else 0.0
                var x = rect.x
                for (k in i until end) {
                    val w = if (rowH > 0) areas[k] / rowH else 0.0
                    out[order[k]] = TreeRect(x, rect.y, w, rowH)
                    x += w
                }
                rect = TreeRect(rect.x, rect.y + rowH, rect.w, max(rect.h - rowH, 0.0))
            }
            i = end
        }
        return out.map { it!! }
    }

    /** Worst aspect ratio of areas[from until to] laid along a side of length [side]. */
    private fun worst(areas: List<Double>, from: Int, to: Int, side: Double): Double {
        var sum = 0.0; var hi = 0.0; var lo = Double.MAX_VALUE
        for (k in from until to) { val a = areas[k]; sum += a; hi = max(hi, a); lo = min(lo, a) }
        if (sum <= 0 || lo <= 0) return Double.MAX_VALUE
        val s2 = side * side
        return max(s2 * hi / (sum * sum), (sum * sum) / (s2 * lo))
    }
}

/**
 * One tile: area from [weight] (how big the day's move was), colour from [value] (the move itself;
 * null = no data, drawn neutral). [amount] is what the tile stands for in money (market cap,
 * holding value), used only to combine moves into a group figure.
 */
data class HeatTile(
    val key: String,
    val label: String,
    val detail: String,
    val weight: Double,
    val value: Double?,
    val amount: Double = weight,
)

data class HeatGroup(val label: String, val tiles: List<HeatTile>) {
    val weight: Double get() = tiles.sumOf { it.weight }
    /** Combined move of the tiles that have one: see [combinedReturn]. */
    val value: Double? get() = combinedReturn(tiles)
}

/**
 * The move of the tiles taken together, by [HeatTile.amount] (their value now): total now / total
 * before - 1, where a tile's amount before is amount / (1 + move). For the market this is the
 * cap-weighted move; for holdings it's the day's change in rupees as a percentage.
 */
fun combinedReturn(tiles: List<HeatTile>): Double? {
    var now = 0.0
    var start = 0.0
    tiles.forEach { t ->
        val v = t.value?.takeIf { it > -1 } ?: return@forEach
        if (t.amount <= 0) return@forEach
        now += t.amount
        start += t.amount / (1 + v)
    }
    return if (start > 0) now / start - 1 else null
}

/** The heatmaps cover Nifty 50 stocks only, coloured by the latest session's change. */
const val HEATMAP_INDEX = "NIFTY 50"

/** Daily change that saturates the colour scale. */
const val HEAT_SCALE = 0.03

/** Smallest tile, as a move: flat stocks (and ones with no data) still get a small tile. */
const val MIN_TILE_MOVE = 0.0005

/** Tile area: the size of the day's move, up or down. */
fun tileSize(change: Double?): Double = maxOf(kotlin.math.abs(change ?: 0.0), MIN_TILE_MOVE)

enum class HeatGrouping(val label: String) { NONE("Stocks"), SECTOR("By sector") }

data class HeatSummary(val up: Int, val down: Int, val flat: Int, val noData: Int, val weighted: Double?)

object HeatmapBuilder {
    /** Nifty 50 constituents (others in [stocks] are ignored), sized and coloured by the latest day's change. */
    fun stocks(stocks: List<AnalyzedStock>, grouping: HeatGrouping): List<HeatGroup> {
        val tiles = stocks.filter { it.stock.indexSymbol == HEATMAP_INDEX }.map { s ->
            HeatTile(
                key = s.symbol,
                label = s.symbol,
                detail = s.stock.industry,
                weight = tileSize(s.metrics.change1d),
                value = s.metrics.change1d,
                amount = s.stock.marketCapCr ?: 0.0,
            )
        }
        return group(tiles, grouping)
    }

    /**
     * The portfolio's Nifty 50 holdings (no ETFs, funds or other stocks), sized and coloured by
     * the stock's latest day's change.
     */
    fun portfolio(positions: List<Position>, grouping: HeatGrouping): List<HeatGroup> {
        val tiles = positions
            .filter { it.value > 0 && it.tracked?.stock?.indexSymbol == HEATMAP_INDEX }
            .map { p ->
                val change = p.tracked!!.metrics.change1d
                HeatTile(p.symbol, p.symbol, p.tracked.stock.industry, tileSize(change), change, amount = p.value)
            }
        return group(tiles, grouping)
    }

    private fun group(tiles: List<HeatTile>, grouping: HeatGrouping): List<HeatGroup> = when (grouping) {
        HeatGrouping.NONE -> if (tiles.isEmpty()) emptyList() else listOf(HeatGroup("", tiles))
        HeatGrouping.SECTOR -> tiles.groupBy { it.detail.ifBlank { "Other" } }
            .map { (sector, ts) -> HeatGroup(sector, ts) }
            .sortedByDescending { it.weight }
    }.map { g -> g.copy(tiles = g.tiles.sortedByDescending { it.weight }) }

    fun summary(groups: List<HeatGroup>): HeatSummary {
        val tiles = groups.flatMap { it.tiles }
        return HeatSummary(
            up = tiles.count { (it.value ?: 0.0) > 0 },
            down = tiles.count { (it.value ?: 0.0) < 0 },
            flat = tiles.count { it.value == 0.0 },
            noData = tiles.count { it.value == null },
            weighted = combinedReturn(tiles),
        )
    }
}
