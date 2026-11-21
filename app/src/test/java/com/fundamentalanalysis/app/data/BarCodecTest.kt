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
}
