package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.PriceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PriceCodecTest {
    @Test fun `round trips dates and closes exactly`() {
        val points = listOf(
            LocalDate.of(2007, 9, 17) to 4494.65,
            LocalDate.of(2026, 11, 13) to 25144.3,
            LocalDate.of(2026, 11, 14) to 0.05,
        )
        assertEquals(points, PriceCodec.decode(PriceCodec.encode(points)))
    }

    @Test fun `empty series stays empty`() {
        assertTrue(PriceCodec.decode(PriceCodec.encode(emptyList())).isEmpty())
    }
}
