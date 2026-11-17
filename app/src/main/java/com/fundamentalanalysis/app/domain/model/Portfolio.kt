package com.fundamentalanalysis.app.domain.model

import java.time.LocalDate

/* The user's own imported portfolio (device-local). Amounts are INR. */

data class PortfolioAccount(
    val id: String,
    val broker: String,
    /** Masked client id. */
    val clientId: String,
    val holdingsAsOf: LocalDate?,
    val importedAtMillis: Long,
)

enum class HoldingKind(val label: String) { STOCK("Stock"), FUND("Fund / ETF") }

/** One broker's position in one security, as reported in its holdings snapshot. */
data class BrokerHolding(
    val accountId: String,
    val symbol: String,
    val isin: String,
    val kind: HoldingKind,
    val sector: String,
    val qty: Double,
    val avgPrice: Double,
    val close: Double,
)

enum class TradeSide { BUY, SELL }

data class PortfolioTrade(
    val id: String,
    val accountId: String,
    val symbol: String,
    val isin: String,
    val date: LocalDate,
    val side: TradeSide,
    val qty: Double,
    val price: Double,
    val exchange: String,
    val kind: HoldingKind,
) {
    val value: Double get() = qty * price
}

data class PortfolioAction(
    val id: String,
    val accountId: String,
    val type: String,
    val symbol: String,
    val date: LocalDate,
    val qty: Double,
    val price: Double?,
    val issue: String,
    val incoming: Boolean,
)

data class PortfolioNote(val accountId: String, val symbol: String, val reviewed: Boolean, val comment: String)

data class PortfolioFee(val accountId: String, val date: LocalDate, val kind: String, val amount: Double)

data class PortfolioImportRecord(
    val atMillis: Long,
    val fileName: String?,
    val exportedAt: String?,
    val brokers: String,
    val holdings: Int,
    val trades: Int,
)

/** Everything stored, as imported. */
data class PortfolioData(
    val accounts: List<PortfolioAccount> = emptyList(),
    val holdings: List<BrokerHolding> = emptyList(),
    val trades: List<PortfolioTrade> = emptyList(),
    val actions: List<PortfolioAction> = emptyList(),
    val notes: List<PortfolioNote> = emptyList(),
    val fees: List<PortfolioFee> = emptyList(),
    val imports: List<PortfolioImportRecord> = emptyList(),
) {
    val isEmpty: Boolean get() = accounts.isEmpty()

    /** Only [broker]'s accounts (null = everything); import history is kept. */
    fun forBroker(broker: String?): PortfolioData {
        if (broker == null) return this
        val ids = accounts.filter { it.broker == broker }.map { it.id }.toSet()
        return copy(
            accounts = accounts.filter { it.id in ids },
            holdings = holdings.filter { it.accountId in ids },
            trades = trades.filter { it.accountId in ids },
            actions = actions.filter { it.accountId in ids },
            notes = notes.filter { it.accountId in ids },
            fees = fees.filter { it.accountId in ids },
        )
    }
}

enum class PriceSource { DATASET, BROKER }

/** A broker's part of a [Position]. */
data class Lot(val broker: String, val qty: Double, val avgPrice: Double)

/** One security across every broker, valued at the freshest price available. */
data class Position(
    val symbol: String,
    val isin: String,
    val kind: HoldingKind,
    val sector: String,
    val lots: List<Lot>,
    val qty: Double,
    val invested: Double,
    val price: Double,
    val priceDate: LocalDate?,
    val priceSource: PriceSource,
    val value: Double,
    /** Today's move in rupees (tracked stocks only). */
    val dayChange: Double?,
    /** Present when the stock is one of the app's tracked index constituents. */
    val tracked: AnalyzedStock?,
    val note: String?,
) {
    val pnl: Double get() = value - invested
    val pnlPct: Double? get() = if (invested > 0) value / invested - 1 else null
    val avgPrice: Double get() = if (qty > 0) invested / qty else 0.0
}

data class Allocation(val label: String, val value: Double, val share: Double, val count: Int)

data class YearActivity(
    val year: Int,
    val bought: Double,
    val sold: Double,
    val trades: Int,
    val charges: Double,
)

/** How the tracked stocks in the portfolio did against their own index. */
data class IndexComparison(
    val period: Period,
    val measured: Int,
    val beat: Int,
    /** Value-weighted return of the measured holdings over [period] (ignores trades within it). */
    val weightedReturn: Double?,
    /** The index's own return over [period] (only for a single-index group). */
    val indexReturn: Double? = null,
)

/** Which index a holding belongs to, in display order. */
enum class IndexGroup(val label: String, val indexSymbol: String?) {
    NIFTY_50("Nifty 50", "NIFTY 50"),
    NEXT_50("Nifty Next 50", "NIFTY NEXT 50"),
    MIDCAP_150("Nifty Midcap 150", "NIFTY MIDCAP 150"),
    OTHER("Other stocks", null),
    FUNDS("Funds & ETFs", null);

    val tracked: Boolean get() = indexSymbol != null

    companion object {
        fun of(p: Position): IndexGroup {
            val symbol = p.tracked?.stock?.indexSymbol
            return entries.firstOrNull { it.indexSymbol != null && it.indexSymbol == symbol }
                ?: if (p.kind == HoldingKind.FUND) FUNDS else OTHER
        }
    }
}

/**
 * A holding's return on cost compared with what the same money would have made in a benchmark
 * index, from the holding's (value-weighted) average purchase date until the latest price.
 */
data class PurchaseComparison(
    val position: Position,
    val avgBuyDate: LocalDate,
    /** Value / invested - 1, on the broker's average cost. */
    val holdingReturn: Double,
    /** Benchmark close now / close on [avgBuyDate] - 1. */
    val benchmarkReturn: Double,
) {
    val excess: Double get() = holdingReturn - benchmarkReturn
    /** What the same invested amount would be worth in the benchmark. */
    val benchmarkValue: Double get() = position.invested * (1 + benchmarkReturn)
}

data class SincePurchase(
    val benchmark: String,
    /** Best (largest lead over the benchmark) first. */
    val rows: List<PurchaseComparison>,
    /** Holdings with no dated purchase (e.g. transfers in, or bought before the trade history). */
    val withoutHistory: List<Position>,
) {
    val beat: Int get() = rows.count { it.excess > 0 }
    val invested: Double get() = rows.sumOf { it.position.invested }
    val value: Double get() = rows.sumOf { it.position.value }
    val benchmarkValue: Double get() = rows.sumOf { it.benchmarkValue }
}

/** One index's slice of the portfolio. */
data class GroupSummary(
    val group: IndexGroup,
    /** Largest first. */
    val positions: List<Position>,
    val value: Double,
    val invested: Double,
    /** Share of the whole portfolio's value. */
    val share: Double,
    val dayChange: Double?,
    /** Empty for groups that aren't an index (other stocks, funds). */
    val vsIndex: List<IndexComparison>,
    /** Other stocks only: each holding vs the Nifty 50 over the time since it was bought. */
    val sincePurchase: SincePurchase? = null,
) {
    val pnl: Double get() = value - invested
    val pnlPct: Double? get() = if (invested > 0) value / invested - 1 else null
}

data class PortfolioSummary(
    val accounts: List<PortfolioAccount>,
    val positions: List<Position>,
    val invested: Double,
    val value: Double,
    val dayChange: Double?,
    val bySector: List<Allocation>,
    val byBroker: List<Allocation>,
    val byIndex: List<Allocation>,
    val vsIndex: List<IndexComparison>,
    /** Holdings split by index (non-empty groups, in [IndexGroup] order). */
    val groups: List<GroupSummary>,
    val activity: List<YearActivity>,
    val trades: List<PortfolioTrade>,
    val actions: List<PortfolioAction>,
    val lastImport: PortfolioImportRecord?,
    /** Latest price date used (dataset or broker snapshot). */
    val pricedAsOf: LocalDate?,
) {
    val pnl: Double get() = value - invested
    val pnlPct: Double? get() = if (invested > 0) value / invested - 1 else null
    val trackedCount: Int get() = positions.count { it.tracked != null }
    fun broker(accountId: String): String = accounts.firstOrNull { it.id == accountId }?.broker ?: "?"
}

/** What an import file contains, before anything is written. */
data class ImportPreview(
    val fileName: String?,
    val exportedAt: String?,
    val included: List<IncludedAccount>,
    val skipped: List<SkippedAccount>,
) {
    val canImport: Boolean get() = included.isNotEmpty()
}

data class IncludedAccount(
    val id: String,
    val broker: String,
    /** Client id with all but the last 3 characters masked. */
    val clientId: String,
    val holdings: Int,
    val holdingsAsOf: String?,
    val trades: Int,
    val actions: Int,
    val notes: Int,
    val fees: Int,
)

data class SkippedAccount(val broker: String, val reason: String)
