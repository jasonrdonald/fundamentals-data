package com.fundamentalanalysis.app.ui.components

import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.domain.model.MetricFormat
import com.fundamentalanalysis.app.domain.model.StockFormat
import com.fundamentalanalysis.app.domain.model.StockMetric
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val monthFormat = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
    private val indian = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    fun metric(metric: Metric, value: Double?): String = format(metric.format, value)

    fun format(format: MetricFormat, value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        return when (format) {
            MetricFormat.PERCENT -> percent(value)
            MetricFormat.SIGNED_PERCENT -> signedPercent(value)
            MetricFormat.NUMBER -> String.format(Locale.US, "%.1f", value)
            MetricFormat.MULTIPLE -> String.format(Locale.US, "%.2fx", value)
        }
    }

    fun percent(value: Double?): String =
        if (value == null) "—" else String.format(Locale.US, "%.1f%%", value * 100)

    /** Always carries an explicit sign so direction never depends on colour alone. */
    fun signedPercent(value: Double?): String =
        if (value == null) "—" else String.format(Locale.US, "%+.2f%%", value * 100)

    /** Index level in points, Indian digit grouping (e.g. 25,144.30). */
    fun points(value: Double?): String = if (value == null) "—" else indian.format(value)

    fun signedPoints(value: Double?): String =
        if (value == null) "—" else (if (value >= 0) "+" else "−") + indian.format(abs(value))

    /** Compact volume in Indian units (K, L = lakh, Cr = crore). */
    fun volume(value: Double?): String {
        if (value == null || value <= 0) return "—"
        return when {
            value >= 1e7 -> String.format(Locale.US, "%.2f Cr", value / 1e7)
            value >= 1e5 -> String.format(Locale.US, "%.2f L", value / 1e5)
            value >= 1e3 -> String.format(Locale.US, "%.1f K", value / 1e3)
            else -> String.format(Locale.US, "%.0f", value)
        }
    }

    fun stock(metric: StockMetric, value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        return when (metric.format) {
            StockFormat.SIGNED_PERCENT -> signedPercent(value)
            StockFormat.PERCENT -> percent(value)
            StockFormat.NUMBER -> String.format(Locale.US, "%.2f", value)
            StockFormat.MULTIPLE -> String.format(Locale.US, "%.1fx", value)
            StockFormat.CRORE -> crore(value)
            StockFormat.POINTS -> String.format(Locale.US, "%+.1f pp", value * 100)
        }
    }

    /** Share price in rupees with Indian digit grouping. */
    fun rupees(value: Double?): String = if (value == null) "—" else "₹" + indian.format(value)

    /** Amount already in crore: "₹80,775 Cr", or "₹18.13 L Cr" for lakh-crore sizes. */
    fun crore(value: Double?): String {
        if (value == null) return "—"
        val sign = if (value < 0) "−" else ""
        val a = abs(value)
        return if (a >= 100_000) String.format(Locale.US, "%s₹%.2f L Cr", sign, a / 100_000)
        else sign + "₹" + java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(a.toLong()) + " Cr"
    }

    /**
     * Compact index label for tight layouts: "Nifty Bank" -> "Bank", but names that would become
     * meaningless ("Nifty 50" -> "50", "Nifty Next 50") keep their prefix.
     */
    fun shortName(name: String): String {
        val stripped = name.removePrefix("Nifty ")
        return if (stripped == name || stripped.first().isDigit() || stripped.startsWith("Next")) name else stripped
    }

    fun day(date: LocalDate?): String = date?.format(dayFormat) ?: "—"
    fun month(date: LocalDate): String = date.format(monthFormat)

    fun dateTime(millis: Long?): String =
        if (millis == null) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
