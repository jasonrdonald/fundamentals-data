package com.fundamentalanalysis.app.data.local

import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import java.nio.ByteBuffer
import java.time.LocalDate

/**
 * Packs OHLCV bars into one BLOB: epoch day (int), open/high/low/close (double), volume (long,
 * [NO_LONG] when missing) and last session (epoch day, [NO_INT] when missing) - 48 bytes a bar.
 */
object BarCodec {
    private const val BAR_BYTES = 4 + 8 * 4 + 8 + 4
    private const val NO_LONG = Long.MIN_VALUE
    private const val NO_INT = Int.MIN_VALUE

    fun encode(bars: List<Bar>): ByteArray {
        val buffer = ByteBuffer.allocate(bars.size * BAR_BYTES)
        bars.forEach { b ->
            buffer.putInt(b.date.toEpochDay().toInt())
            buffer.putDouble(b.open).putDouble(b.high).putDouble(b.low).putDouble(b.close)
            buffer.putLong(b.volume ?: NO_LONG)
            buffer.putInt(b.lastSession?.toEpochDay()?.toInt() ?: NO_INT)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<Bar> {
        val buffer = ByteBuffer.wrap(bytes)
        return List(bytes.size / BAR_BYTES) {
            val date = LocalDate.ofEpochDay(buffer.getInt().toLong())
            val o = buffer.getDouble()
            val h = buffer.getDouble()
            val l = buffer.getDouble()
            val c = buffer.getDouble()
            val v = buffer.getLong().takeIf { it != NO_LONG }
            val last = buffer.getInt().takeIf { it != NO_INT }?.let { LocalDate.ofEpochDay(it.toLong()) }
            Bar(date, o, h, l, c, v, last)
        }
    }
}

/** Packs month-end valuation points: epoch day + P/E, P/B, dividend yield (NaN when missing). */
object ValuationCodec {
    private const val POINT_BYTES = 4 + 8 * 3

    fun encode(points: List<ValuationPoint>): ByteArray {
        val buffer = ByteBuffer.allocate(points.size * POINT_BYTES)
        points.forEach { p ->
            buffer.putInt(p.date.toEpochDay().toInt())
            buffer.putDouble(p.pe ?: Double.NaN).putDouble(p.pb ?: Double.NaN).putDouble(p.dividendYield ?: Double.NaN)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<ValuationPoint> {
        val buffer = ByteBuffer.wrap(bytes)
        fun next() = buffer.getDouble().takeUnless { it.isNaN() }
        return List(bytes.size / POINT_BYTES) {
            ValuationPoint(LocalDate.ofEpochDay(buffer.getInt().toLong()), next(), next(), next())
        }
    }
}
