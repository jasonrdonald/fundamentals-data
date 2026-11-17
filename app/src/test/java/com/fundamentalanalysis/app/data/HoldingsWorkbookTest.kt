package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.local.PortfolioAccountEntity
import com.fundamentalanalysis.app.data.local.PortfolioHoldingEntity
import com.fundamentalanalysis.app.data.portfolio.HoldingsReconciler
import com.fundamentalanalysis.app.data.portfolio.HoldingsWorkbookParser
import com.fundamentalanalysis.app.data.portfolio.UnsupportedBackupException
import com.fundamentalanalysis.app.domain.model.CostSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The holdings workbook import, on a small synthetic .xlsx built in memory. */
class HoldingsWorkbookTest {

    /** Builds an .xlsx with these sheets; strings go through sharedStrings like Excel's own files. */
    private fun xlsx(sheets: List<Pair<String, List<List<Any?>>>>): ByteArray {
        val shared = mutableListOf<String>()
        fun cell(ref: String, v: Any?): String = when (v) {
            null -> ""
            is Number -> """<c r="$ref"><v>$v</v></c>"""
            else -> { shared += v.toString(); """<c r="$ref" t="s"><v>${shared.size - 1}</v></c>""" }
        }
        fun col(i: Int) = ('A' + i).toString()
        val sheetXml = sheets.map { (_, rows) ->
            """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
                rows.mapIndexed { r, row -> "<row r=\"${r + 1}\">" + row.mapIndexed { c, v -> cell("${col(c)}${r + 1}", v) }.joinToString("") + "</row>" }.joinToString("") +
                "</sheetData></worksheet>"
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, (name, _) -> """<sheet name="$name" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Target="worksheets/sheet${it + 1}.xml" Type="x"/>""" } + "</Relationships>")
            sheetXml.forEachIndexed { i, x -> put("xl/worksheets/sheet${i + 1}.xml", x) }
            put("xl/sharedStrings.xml", """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                shared.joinToString("") { "<si><t>${it.replace("&", "&amp;")}</t></si>" } + "</sst>")
        }
        return out.toByteArray()
    }

    private val header = listOf("Asset type", "Symbol", "Name", "Quantity", "Avg cost (₹)", "Price (₹)", "Invested (₹)")
    private fun brokerSheet(vararg rows: List<Any?>) = listOf(
        listOf("Broker A: holdings"),
        listOf("Stocks. Made Nov 18, 2026, 2:06 AM. Prices as of Nov 17, 2026, 11:36 PM, delayed."),
        header,
    ) + rows.toList()

    private val book = xlsx(listOf(
        "By broker" to listOf(listOf("Portfolio: holdings by broker")),
        "HDFC Securities" to brokerSheet(
            listOf("Stocks", "ACME", "Acme Ltd.", 120, 412.5, 900, 49500),
            listOf("Stocks", "NEWCO", "Newco Ltd.", 8, 100, 120, 800),
            listOf("ETFs", "IDXBEES", "Index ETF", 10, 0, 50, 0),
        ),
        "Zerodha" to brokerSheet(listOf("Stocks", "WIDGET", "Widget Ltd.", 40, 250, 260, 10000)),
        "Vested" to brokerSheet(listOf("US stocks", "QQQ", "Invesco QQQ", 5.5, 600, 610, 3300)),
        "Coinbase Wallet" to brokerSheet(listOf("Crypto", "BTC", "Bitcoin", 0.1, 1, 1, 0)),
    ))

    @Test fun `only supported broker sheets are read`() {
        val wb = HoldingsWorkbookParser.parse(book)
        assertEquals(listOf("HDFC Securities", "Zerodha"), wb.brokers.map { it.broker })
        assertEquals(listOf("Vested", "Coinbase Wallet"), wb.skipped.map { it.broker })
        val hdfc = wb.brokers.first()
        assertEquals("2026-11-17", hdfc.asOf)
        assertEquals(listOf("ACME", "NEWCO", "IDXBEES"), hdfc.rows.map { it.symbol })
        assertEquals(412.5, hdfc.rows.first().avgCost, 1e-9)
        assertEquals(900.0, hdfc.rows.first().price, 1e-9)
        assertTrue(hdfc.rows.last().isFund)
    }

    @Test fun `series suffixes are ignored when matching`() {
        assertEquals("TMCV", HoldingsWorkbookParser.baseSymbol("TMCV-BL"))
        assertEquals("GOLDBEES", HoldingsWorkbookParser.baseSymbol("GOLDBEES-E"))
        assertEquals("BAJAJ-AUTO", HoldingsWorkbookParser.baseSymbol("BAJAJ-AUTO"))
        assertEquals("M&M", HoldingsWorkbookParser.baseSymbol("M&M"))
    }

    @Test fun `reconciling replaces holdings and reports what changed`() {
        val accounts = listOf(PortfolioAccountEntity("h1", "HDFC Securities", "••647", "2026-11-01", 1L), PortfolioAccountEntity("z1", "Zerodha", "••254", "2026-11-01", 1L))
        val stored = listOf(
            // Partly rebuilt earlier: 110 of 120 shares at 450.
            PortfolioHoldingEntity("h1", "ACME", "INE000X01011", "stock", "Energy", 120.0, 450.0, 800.0, costedQty = 110.0, costSource = CostSource.PARTIAL.name),
            PortfolioHoldingEntity("h1", "GONE", "INE000Z01011", "stock", "", 10.0, 0.0, 5.0, costSource = CostSource.BROKER.name),
            PortfolioHoldingEntity("z1", "WIDGET-SM", "INE000T01011", "stock", "IT", 30.0, 250.0, 255.0),
        )
        val plan = HoldingsReconciler.plan(HoldingsWorkbookParser.parse(book), accounts, stored, now = 2L)

        assertEquals(listOf("h1", "z1"), plan.accounts.map { it.id })
        assertEquals("2026-11-17", plan.accounts.first().holdingsAsOf)
        val acme = plan.holdings.single { it.symbol == "ACME" }
        assertEquals("INE000X01011", acme.isin)          // kept from the stored row
        assertEquals("Energy", acme.sector)
        assertEquals(412.5, acme.avgPrice, 1e-9)
        assertEquals(CostSource.FILE.name, acme.costSource)
        assertEquals(null, acme.costedQty)                // all shares costed
        val etf = plan.holdings.single { it.symbol == "IDXBEES" }
        assertEquals("mf", etf.kind)
        assertEquals(CostSource.UNKNOWN.name, etf.costSource)
        assertEquals(0.0, etf.costedQty!!, 1e-9)
        val techd = plan.holdings.single { it.accountId == "z1" }
        assertEquals("WIDGET", techd.symbol)               // series suffix dropped
        assertEquals("INE000T01011", techd.isin)

        val hdfc = plan.updates.first()
        assertFalse(hdfc.newAccount)
        assertEquals(listOf("ACME ₹450.00 → ₹412.50"), hdfc.costChanged)
        assertEquals(listOf("IDXBEES", "NEWCO"), hdfc.added)
        assertEquals(listOf("GONE"), hdfc.removed)
        assertEquals(1, hdfc.withoutCost)
        val zerodha = plan.updates.last()
        assertEquals(listOf("WIDGET 30 → 40"), zerodha.qtyChanged)
        assertTrue(zerodha.costChanged.isEmpty())
    }

    @Test fun `a broker with nothing stored gets its own account`() {
        val plan = HoldingsReconciler.plan(HoldingsWorkbookParser.parse(book), emptyList(), emptyList(), now = 5L)
        assertEquals(listOf("holdings-file:HDFC Securities", "holdings-file:Zerodha"), plan.accounts.map { it.id })
        assertTrue(plan.updates.all { it.newAccount })
    }

    @Test(expected = UnsupportedBackupException::class)
    fun `a zip that isn't a workbook is rejected`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("readme.txt")); it.write("hi".toByteArray()); it.closeEntry() }
        HoldingsWorkbookParser.parse(out.toByteArray())
    }
}
