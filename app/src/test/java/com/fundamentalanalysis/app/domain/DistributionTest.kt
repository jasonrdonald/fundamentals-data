package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.analysis.Distributions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DistributionTest {

    @Test fun `normal cdf matches known values`() {
        assertEquals(0.5, Distributions.normalCdf(0.0), 1e-7)
        assertEquals(0.841345, Distributions.normalCdf(1.0), 1e-5)
        assertEquals(0.022750, Distributions.normalCdf(-2.0), 1e-5)
    }

    @Test fun `moments of a symmetric sample`() {
        val values = listOf(-0.2, -0.1, 0.0, 0.1, 0.2).mapIndexed { i, v -> "S$i" to v }
        val d = Distributions.of(values, benchmark = 0.05)!!
        assertEquals(0.0, d.mean, 1e-12)
        assertEquals(0.0, d.median, 1e-12)
        assertEquals(0.0, d.skewness, 1e-12)
        assertEquals(0.158114, d.sd, 1e-6) // sample SD
        assertEquals(2, d.aboveBenchmark)
        assertEquals(60.0, d.benchmarkPercentile!!, 1e-9)
    }

    @Test fun `long right tail gives positive skew and an outlier`() {
        val values = (List(30) { 0.0 } + List(10) { 0.05 } + listOf(1.5)).mapIndexed { i, v -> "S$i" to v }
        val d = Distributions.of(values, null)!!
        assertTrue(d.skewness > 2)
        assertTrue(d.excessKurtosis > 0)
        assertEquals("S40", d.outliers.first().symbol)
    }

    @Test fun `histogram covers every value with nice edges`() {
        val rnd = Random(7)
        val values = List(150) { "S$it" to rnd.nextGaussian() * 0.2 + 0.05 }
        val d = Distributions.of(values, 0.02)!!
        assertEquals(150, d.bins.sumOf { it.count })
        assertTrue(d.bins.first().from <= values.minOf { it.second })
        assertTrue(d.bins.last().to >= values.maxOf { it.second })
        val width = d.binWidth
        assertEquals(width, Distributions.niceStep(width), 1e-12)
        // Sigma bands also cover everyone, and a normal-ish sample lands near the expected counts.
        assertEquals(150, d.bands.sumOf { it.actual })
        val middle = d.bands.filter { it.fromSigma >= -1 && it.toSigma <= 1 }.sumOf { it.actual }
        assertTrue("about 68% within 1 SD, got $middle", middle in 90..115)
    }

    @Test fun `nice step picks the nearest 1-2-2_5-5 multiple`() {
        assertEquals(0.05, Distributions.niceStep(0.043), 1e-12)
        assertEquals(0.05, Distributions.niceStep(0.07), 1e-12)
        assertEquals(0.1, Distributions.niceStep(0.08), 1e-12)
        assertEquals(0.02, Distributions.niceStep(0.021), 1e-12)
    }

    @Test fun `fifty stocks get a readable number of bins`() {
        val values = List(50) { "S$it" to (-0.32 + 0.92 * it / 49) }
        val bins = Distributions.of(values, null)!!.bins.size
        assertTrue("got $bins bins", bins in 8..12)
    }

    @Test fun `too few values gives no distribution`() {
        assertNull(Distributions.of(listOf("A" to 0.1, "B" to 0.2), null))
    }
}
