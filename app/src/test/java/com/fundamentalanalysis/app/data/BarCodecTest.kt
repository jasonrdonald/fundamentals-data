package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.BarCodec
import com.fundamentalanalysis.app.data.local.ValuationCodec
import com.fundamentalanalysis.app.domain.model.Bar
import com.fundamentalanalysis.app.domain.model.ValuationPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BarCodecTest {
    @Test fun `bars round trip including missing volume and last session`() {
        val bars = listOf(
            Bar(LocalDate.of(2007, 9, 1), 4494.65, 4500.0, 4400.5, 4480.1, null, null),
            Bar(LocalDate.of(2026, 11, 1), 23222.3, 25424.35, 23207.05, 25144.3, 3_429_200, LocalDate.of(2026, 11, 13)),
        )
        assertEquals(bars, BarCodec.decode(BarCodec.encode(bars)))
    }

    @Test fun `valuation round trips with missing values`() {
        val points = listOf(ValuationPoint(LocalDate.of(2012, 4, 30), 18.5, null, 1.42), ValuationPoint(LocalDate.of(2026, 10, 31), null, 3.6, null))
        assertEquals(points, ValuationCodec.decode(ValuationCodec.encode(points)))
    }

    @Test fun `merging keeps the older series and lets the newer one win where they overlap`() {
        fun bar(m: Int, c: Double) = Bar(LocalDate.of(2026, m, 1), c, c, c, c, null)
        val older = listOf(bar(1, 1.0), bar(2, 2.0), bar(3, 3.0))
        val newer = listOf(bar(3, 30.0), bar(4, 40.0))
        assertEquals(listOf(bar(1, 1.0), bar(2, 2.0), bar(3, 30.0), bar(4, 40.0)), mergeByDate(older, newer) { it.date })
        assertEquals(older, mergeByDate(older, emptyList()) { it.date })
    }
}
