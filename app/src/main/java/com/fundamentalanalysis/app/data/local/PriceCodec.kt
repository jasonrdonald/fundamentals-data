package com.fundamentalanalysis.app.data.local

import java.nio.ByteBuffer
import java.time.LocalDate

/**
 * Packs a date/close series into one compact blob (4-byte epoch day + 8-byte close per point).
 * Storing a whole stock's history in one column keeps the database at one row per stock instead
 * of one row per price, which made loading 250 stocks tens of times slower.
 */
object PriceCodec {
    private const val POINT_BYTES = Int.SIZE_BYTES + Double.SIZE_BYTES

    fun encode(points: List<Pair<LocalDate, Double>>): ByteArray {
        val buffer = ByteBuffer.allocate(points.size * POINT_BYTES)
        points.forEach { (date, close) ->
            buffer.putInt(date.toEpochDay().toInt())
            buffer.putDouble(close)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<Pair<LocalDate, Double>> {
        val buffer = ByteBuffer.wrap(bytes)
        return List(bytes.size / POINT_BYTES) {
            LocalDate.ofEpochDay(buffer.getInt().toLong()) to buffer.getDouble()
        }
    }
}
