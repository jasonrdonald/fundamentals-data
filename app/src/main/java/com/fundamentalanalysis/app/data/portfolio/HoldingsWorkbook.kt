package com.fundamentalanalysis.app.data.portfolio

import com.fundamentalanalysis.app.domain.model.SkippedAccount
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Import template 2: the "SuperApp portfolio" holdings workbook (.xlsx). It has one sheet per
 * broker account with Symbol, Quantity, Avg cost and Price columns. Only the sheets of the
 * supported Indian equity brokers are read; every other sheet is listed as skipped.
 */

/** One row of a broker sheet. [avgCost] and [price] are in rupees. */
data class WorkbookHolding(
    val symbol: String,
    val name: String,
    val assetType: String,
    val qty: Double,
    val avgCost: Double,
    val price: Double,
) {
    /** Funds, ETFs, commodity and debt ETFs are units, not listed company stocks. */
    val isFund: Boolean get() = assetType.lowercase().let { t -> listOf("fund", "etf", "commodit", "debt").any { it in t } }
}

data class WorkbookBroker(val broker: String, val asOf: String?, val rows: List<WorkbookHolding>)

data class HoldingsWorkbook(val brokers: List<WorkbookBroker>, val skipped: List<SkippedAccount>)

object HoldingsWorkbookParser {
    /** Summary sheets that aren't a broker account. */
    private val SUMMARY_SHEETS = setOf("by broker", "by asset type", "all holdings", "consolidated")
    private val PRICES_AS_OF = Regex("""Prices as of ([A-Z][a-z]{2} \d{1,2}, \d{4})""")
    private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    /** "TMCV-BL" -> "TMCV"; "BAJAJ-AUTO" stays as it is. */
    fun baseSymbol(symbol: String): String = com.fundamentalanalysis.app.domain.model.nseBaseSymbol(symbol)

    fun isWorkbook(bytes: ByteArray): Boolean = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun parse(bytes: ByteArray): HoldingsWorkbook {
        val sheets = try {
            readSheets(bytes)
        } catch (e: Exception) {
            throw UnsupportedBackupException("This spreadsheet couldn't be read (${e.message?.take(80)}).")
        }
        val brokers = mutableListOf<WorkbookBroker>()
        val skipped = mutableListOf<SkippedAccount>()
        sheets.forEach { (name, rows) ->
            if (name.trim().lowercase() in SUMMARY_SHEETS) return@forEach
            val broker = PortfolioBackupParser.brokerName(name)
            if (broker !in PortfolioBackupParser.SUPPORTED_BROKERS) {
                skipped += SkippedAccount(name.trim(), "Not an Indian equity broker in this app (supported: ${PortfolioBackupParser.SUPPORTED_BROKERS.joinToString()})")
                return@forEach
            }
            brokers += brokerSheet(broker, rows)
        }
        if (brokers.isEmpty() && skipped.isEmpty()) {
            throw UnsupportedBackupException("Expected a holdings workbook with one sheet per broker (Symbol, Quantity, Avg cost, Price).")
        }
        return HoldingsWorkbook(brokers, skipped)
    }

    private fun brokerSheet(broker: String, rows: List<List<Any?>>): WorkbookBroker {
        val asOf = rows.asSequence().flatten().filterIsInstance<String>()
            .firstNotNullOfOrNull { PRICES_AS_OF.find(it)?.groupValues?.get(1) }
            ?.let { runCatching { LocalDate.parse(it, DATE).toString() }.getOrNull() }
        val headerAt = rows.indexOfFirst { r -> r.any { (it as? String)?.trim() == "Symbol" } && r.any { (it as? String)?.startsWith("Quantity") == true } }
        if (headerAt < 0) throw UnsupportedBackupException("The $broker sheet has no Symbol / Quantity header row.")
        val header = rows[headerAt].map { (it as? String)?.trim().orEmpty() }
        fun col(prefix: String) = header.indexOfFirst { it.startsWith(prefix) }
        val cSymbol = col("Symbol"); val cName = col("Name"); val cType = col("Asset type")
        val cQty = col("Quantity"); val cAvg = col("Avg cost"); val cPrice = col("Price")
        if (cAvg < 0 || cPrice < 0) throw UnsupportedBackupException("The $broker sheet has no Avg cost / Price columns.")
        val holdings = rows.drop(headerAt + 1).mapNotNull { r ->
            val symbol = (r.getOrNull(cSymbol) as? String)?.trim().orEmpty()
            val qty = (r.getOrNull(cQty) as? Number)?.toDouble() ?: return@mapNotNull null
            if (symbol.isBlank() || qty <= 0) return@mapNotNull null
            WorkbookHolding(
                symbol = symbol,
                name = (r.getOrNull(cName) as? String).orEmpty(),
                assetType = (r.getOrNull(cType) as? String).orEmpty(),
                qty = qty,
                avgCost = (r.getOrNull(cAvg) as? Number)?.toDouble() ?: 0.0,
                price = (r.getOrNull(cPrice) as? Number)?.toDouble() ?: 0.0,
            )
        }
        return WorkbookBroker(broker, asOf, holdings)
    }

    // ------------------------------------------------------------------------- minimal .xlsx reader

    /** Sheet name -> rows of cell values (String, Double or null), in workbook order. */
    internal fun readSheets(bytes: ByteArray): List<Pair<String, List<List<Any?>>>> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e -> if (!e.isDirectory) files[e.name] = zip.readBytes() }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { xml ->
            elements(dom(xml).documentElement, "si").map { si -> elements(si, "t").joinToString("") { it.textContent } }
        }.orEmpty()
        val rels = elements(dom(files.getValue("xl/_rels/workbook.xml.rels")).documentElement, "Relationship")
            .associate { it.getAttribute("Id") to it.getAttribute("Target") }
        return elements(dom(files.getValue("xl/workbook.xml")).documentElement, "sheet").map { sheet ->
            val target = rels.getValue(sheet.getAttributeNS(REL_NS, "id").ifBlank { sheet.getAttribute("r:id") })
            val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
            sheet.getAttribute("name") to rows(files.getValue(path), shared)
        }
    }

    private fun rows(xml: ByteArray, shared: List<String>): List<List<Any?>> =
        elements(dom(xml).documentElement, "row").map { row ->
            val cells = sortedMapOf<Int, Any?>()
            elements(row, "c").forEach { c ->
                val v = elements(c, "v").firstOrNull()?.textContent
                cells[column(c.getAttribute("r"))] = when (c.getAttribute("t")) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> elements(c, "t").joinToString("") { it.textContent }
                    "str" -> v
                    "b" -> v == "1"
                    else -> v?.toDoubleOrNull()
                }
            }
            if (cells.isEmpty()) emptyList() else (0..cells.lastKey()).map { cells[it] }
        }

    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun column(ref: String): Int = ref.takeWhile { it.isLetter() }.fold(0) { n, ch -> n * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1

    private fun dom(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Spreadsheets never need external entities; refuse them.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    /** Descendant elements with this local name. */
    private fun elements(parent: Element, local: String): List<Element> {
        val nodes = parent.getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
