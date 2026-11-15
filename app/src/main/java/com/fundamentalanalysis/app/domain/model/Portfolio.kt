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
)

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
