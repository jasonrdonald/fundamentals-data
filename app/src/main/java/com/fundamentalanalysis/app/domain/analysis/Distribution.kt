package com.fundamentalanalysis.app.domain.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One histogram bar: values in [from, to) (the last bin includes its upper edge). */
data class HistogramBin(val from: Double, val to: Double, val members: List<String>) {
    val count: Int get() = members.size
    val mid: Double get() = (from + to) / 2
}

/** A band of standard deviations around the mean, e.g. "+1σ to +2σ". */
data class SigmaBand(
    val label: String,
    val fromSigma: Double,
    val toSigma: Double,
    val actual: Int,
    /** Count a normal distribution with this mean and SD would put in the band. */
    val expected: Double,
)

data class Outlier(val symbol: String, val value: Double, val zScore: Double)

/**
 * Shape of a set of returns compared with a normal distribution fitted to them (same mean and
 * standard deviation). Skew > 0 means a long right tail (a few big winners); excess kurtosis > 0
 * means fatter tails than a normal curve, i.e. more extreme moves than "normal" would predict.
 */
data class DistributionStats(
    val n: Int,
    val mean: Double,
    val median: Double,
    val sd: Double,
    val skewness: Double,
    val excessKurtosis: Double,
    val min: Pair<String, Double>,
    val max: Pair<String, Double>,
    val bins: List<HistogramBin>,
    val benchmark: Double?,
    val aboveBenchmark: Int,
    /** Percentile of the benchmark's return among the stocks (0-100). */
    val benchmarkPercentile: Double?,
    val bands: List<SigmaBand>,
    /** Stocks more than 2 SD from the mean, most extreme first. */
    val outliers: List<Outlier>,
) {
    val binWidth: Double get() = bins.firstOrNull()?.let { it.to - it.from } ?: 0.0

    /** Fitted normal curve as an expected count per bin width at [x] (to overlay the histogram). */
    fun normalCount(x: Double): Double = n * binWidth * Distributions.normalPdf(x, mean, sd)
}

object Distributions {

    fun normalPdf(x: Double, mean: Double, sd: Double): Double {
        if (sd <= 0) return 0.0
        val z = (x - mean) / sd
        return exp(-0.5 * z * z) / (sd * sqrt(2 * PI))
    }

    /** Standard normal CDF (Abramowitz-Stegun 7.1.26, error < 1.5e-7). */
    fun normalCdf(z: Double): Double {
        val t = 1 / (1 + 0.3275911 * kotlin.math.abs(z) / sqrt(2.0))
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1 - poly * exp(-(z * z) / 2)
        return if (z >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
    }

    /**
     * Builds the distribution of [values] (symbol to return). Needs at least 5 values.
     * Bin count follows the Rice rule (about 2·n^⅓, kept between 8 and 16); edges use the
     * nearest "nice" width so labels read well.
     */
    fun of(values: List<Pair<String, Double>>, benchmark: Double?): DistributionStats? {
        val data = values.filter { it.second.isFinite() }
        val n = data.size
        if (n < 5) return null
        val xs = data.map { it.second }
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean).pow(2) } / (n - 1))
        val sorted = xs.sorted()
        val median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2 else sorted[n / 2]
        val m2 = xs.sumOf { (it - mean).pow(2) } / n
        val m3 = xs.sumOf { (it - mean).pow(3) } / n
        val m4 = xs.sumOf { (it - mean).pow(4) } / n
        val skew = if (m2 > 0) m3 / m2.pow(1.5) else 0.0
        val exKurt = if (m2 > 0) m4 / (m2 * m2) - 3 else 0.0

        val bins = histogram(data)
        val z = { v: Double -> if (sd > 0) (v - mean) / sd else 0.0 }
        val edges = listOf(Double.NEGATIVE_INFINITY, -2.0, -1.0, 0.0, 1.0, 2.0, Double.POSITIVE_INFINITY)
        val labels = listOf("Below −2σ", "−2σ to −1σ", "−1σ to mean", "Mean to +1σ", "+1σ to +2σ", "Above +2σ")
        val bands = labels.indices.map { i ->
            val lo = edges[i]
            val hi = edges[i + 1]
            SigmaBand(
                label = labels[i],
                fromSigma = lo,
                toSigma = hi,
                actual = xs.count { val s = z(it); s >= lo && (s < hi || (hi.isInfinite() && s <= hi)) },
                expected = n * (normalCdf(hi) - normalCdf(lo)),
            )
        }
        return DistributionStats(
            n = n,
            mean = mean,
            median = median,
            sd = sd,
            skewness = skew,
            excessKurtosis = exKurt,
            min = data.minBy { it.second },
            max = data.maxBy { it.second },
            bins = bins,
            benchmark = benchmark,
            aboveBenchmark = benchmark?.let { b -> xs.count { it > b } } ?: 0,
            benchmarkPercentile = benchmark?.let { b -> 100.0 * xs.count { it < b } / n },
            bands = bands,
            outliers = data.map { Outlier(it.first, it.second, z(it.second)) }
                .filter { kotlin.math.abs(it.zScore) > 2 }
                .sortedByDescending { kotlin.math.abs(it.zScore) },
        )
    }

    internal fun histogram(data: List<Pair<String, Double>>): List<HistogramBin> {
        val lo = data.minOf { it.second }
        val hi = data.maxOf { it.second }
        val k = (2 * data.size.toDouble().pow(1.0 / 3)).roundToInt().coerceIn(8, 16)
        val width = niceStep(max(hi - lo, 1e-9) / k)
        val start = floor(lo / width) * width
        val count = max(1, ceil((hi - start) / width + 1e-9).toInt())
        val buckets = List(count) { mutableListOf<String>() }
        data.forEach { (sym, v) -> buckets[((v - start) / width).toInt().coerceIn(0, count - 1)].add(sym) }
        return buckets.mapIndexed { i, members -> HistogramBin(start + i * width, start + (i + 1) * width, members) }
    }

    /**
     * The 1, 2, 2.5 or 5 x 10^k step closest to [raw] on a log scale (in return units, e.g.
     * 0.05 = 5%). Nearest rather than rounding up keeps the bin count near the target.
     */
    internal fun niceStep(raw: Double): Double {
        val mag = 10.0.pow(floor(kotlin.math.log10(raw)))
        return listOf(1.0, 2.0, 2.5, 5.0, 10.0)
            .map { it * mag }
            .minBy { abs(ln(it / raw)) }
    }
}
