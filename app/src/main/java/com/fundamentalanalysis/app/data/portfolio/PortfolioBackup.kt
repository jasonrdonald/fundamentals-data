package com.fundamentalanalysis.app.data.portfolio

import kotlinx.serialization.Serializable
import com.fundamentalanalysis.app.domain.model.ImportPreview
import com.fundamentalanalysis.app.domain.model.IncludedAccount
import com.fundamentalanalysis.app.domain.model.SkippedAccount
import kotlinx.serialization.json.Json

/*
 * Import template: the "superapp-portfolio" broker backup (version 1). Only Indian equity broker
 * accounts are imported (see [PortfolioBackupParser.SUPPORTED_BROKERS]); everything else in the
 * file (US brokers, mutual fund houses, crypto wallets and exchanges) is reported and skipped.
 * Imported data stays on this device.
 */

@Serializable
data class PortfolioBackupDto(
    val app: String = "",
    val version: Int = 0,
    val exportedAt: String? = null,
    val brokerAccounts: List<BackupAccountDto> = emptyList(),
    val trades: List<BackupTradeDto> = emptyList(),
    val brokerHoldings: List<BackupHoldingsDto> = emptyList(),
    val portfolioActions: List<BackupActionDto> = emptyList(),
    val portfolioNotes: List<BackupNoteDto> = emptyList(),
    val brokerFees: List<BackupFeeDto> = emptyList(),
    /** Public per-security data; only splits / bonuses are used (to rebuild costs from trades). */
    val market: List<BackupMarketDto> = emptyList(),
)

@Serializable
data class BackupMarketDto(
    val symbol: String = "",
    val splits: List<BackupSplitDto> = emptyList(),
)

/** A split or bonus: from [date], every [den] shares became [num] (a 1:1 bonus is 2/1). */
@Serializable
data class BackupSplitDto(
    val date: String = "",
    val num: Int = 0,
    val den: Int = 0,
)

@Serializable
data class BackupAccountDto(
    val id: String,
    val name: String = "",
    val broker: String = "",
    /** stock, mf or crypto. */
    val kind: String = "",
    val clientId: String = "",
    val currency: String = "",
)

@Serializable
data class BackupTradeDto(
    val id: String,
    val accountId: String,
    val symbol: String = "",
    val isin: String = "",
    val date: String = "",
    /** buy, sell (or out for transfers, not used by equity brokers). */
    val side: String = "",
    val qty: Double = 0.0,
    val price: Double = 0.0,
    val exchange: String = "",
    val tradeId: String = "",
    /** stock or mf (fund units held in demat). */
    val kind: String = "stock",
)

/** A broker-reported holdings snapshot; [id] is the account id. */
@Serializable
data class BackupHoldingsDto(
    val id: String,
    val asOf: String? = null,
    val rows: List<BackupHoldingRowDto> = emptyList(),
)

@Serializable
data class BackupHoldingRowDto(
    val symbol: String = "",
    val isin: String = "",
    val kind: String = "stock",
    val sector: String = "",
    val qty: Double = 0.0,
    val avg: Double = 0.0,
    val close: Double = 0.0,
)

@Serializable
data class BackupActionDto(
    val id: String,
    val accountId: String,
    /** e.g. allot. */
    val type: String = "",
    val symbol: String = "",
    val date: String = "",
    val qty: Double = 0.0,
    val price: Double? = null,
    /** e.g. transfer, bonus, rights. */
    val issue: String = "",
    /** in or out. */
    val direction: String = "",
)

@Serializable
data class BackupNoteDto(
    val id: String,
    val accountId: String,
    val symbol: String = "",
    val reviewed: Boolean = false,
    val comment: String = "",
)

@Serializable
data class BackupFeeDto(
    val id: String,
    val accountId: String,
    val date: String = "",
    val kind: String = "",
    val amount: Double = 0.0,
)

/** A parsed file restricted to the Indian equity accounts, with its preview. */
data class ParsedBackup(val preview: ImportPreview, val backup: PortfolioBackupDto)

class UnsupportedBackupException(message: String) : Exception(message)

object PortfolioBackupParser {
    const val TEMPLATE_APP = "superapp-portfolio"
    const val TEMPLATE_VERSION = 1

    /** Indian equity brokers this app imports. */
    val SUPPORTED_BROKERS = listOf("Zerodha", "HDFC Securities", "Aionion Capital")

    fun parse(json: Json, text: String, fileName: String?): ParsedBackup {
        val dto = try {
            json.decodeFromString<PortfolioBackupDto>(text)
        } catch (e: Exception) {
            throw UnsupportedBackupException("This file isn't a portfolio backup in the supported format (${e.message?.take(80)})")
        }
        if (dto.app != TEMPLATE_APP) {
            throw UnsupportedBackupException("Expected a \"$TEMPLATE_APP\" backup; this file is ${if (dto.app.isBlank()) "something else" else "from \"${dto.app}\""}.")
        }
        if (dto.version > TEMPLATE_VERSION) {
            throw UnsupportedBackupException("Backup format version ${dto.version} is newer than this app supports ($TEMPLATE_VERSION).")
        }
        val (accepted, rejected) = dto.brokerAccounts.partition { isIndianEquity(it) }
        val ids = accepted.map { it.id }.toSet()
        val restricted = PortfolioBackupDto(
            app = dto.app,
            version = dto.version,
            exportedAt = dto.exportedAt,
            brokerAccounts = accepted,
            trades = dto.trades.filter { it.accountId in ids },
            brokerHoldings = dto.brokerHoldings.filter { it.id in ids },
            portfolioActions = dto.portfolioActions.filter { it.accountId in ids },
            portfolioNotes = dto.portfolioNotes.filter { it.accountId in ids },
            brokerFees = dto.brokerFees.filter { it.accountId in ids },
            market = dto.brokerHoldings.filter { it.id in ids }.flatMap { h -> h.rows.map { it.symbol } }.toSet()
                .let { held -> dto.market.filter { it.symbol in held && it.splits.isNotEmpty() } },
        )
        val preview = ImportPreview(
            fileName = fileName,
            exportedAt = dto.exportedAt,
            included = accepted.map { a ->
                val snapshot = restricted.brokerHoldings.firstOrNull { it.id == a.id }
                IncludedAccount(
                    id = a.id,
                    broker = brokerName(a.broker),
                    clientId = mask(a.clientId),
                    holdings = snapshot?.rows?.size ?: 0,
                    holdingsAsOf = snapshot?.asOf,
                    trades = restricted.trades.count { it.accountId == a.id },
                    actions = restricted.portfolioActions.count { it.accountId == a.id },
                    notes = restricted.portfolioNotes.count { it.accountId == a.id },
                    fees = restricted.brokerFees.count { it.accountId == a.id },
                )
            },
            skipped = rejected.map { SkippedAccount(it.broker.ifBlank { it.name }, skipReason(it)) },
        )
        return ParsedBackup(preview, restricted)
    }

    private fun isIndianEquity(a: BackupAccountDto) =
        brokerName(a.broker) in SUPPORTED_BROKERS && a.kind.equals("stock", true) && a.currency.equals("INR", true)

    /** Canonical broker name (case and spacing tolerant). */
    fun brokerName(raw: String): String =
        SUPPORTED_BROKERS.firstOrNull { it.replace(" ", "").equals(raw.replace(" ", ""), ignoreCase = true) } ?: raw.trim()

    private fun skipReason(a: BackupAccountDto): String = when {
        a.kind.equals("crypto", true) -> "Crypto — not an Indian equity broker"
        a.kind.equals("mf", true) -> "Mutual fund account — not an equity broker"
        !a.currency.equals("INR", true) -> "${a.currency.ifBlank { "Non-INR" }} account — not Indian equity"
        else -> "Broker not supported for import (supported: ${SUPPORTED_BROKERS.joinToString()})"
    }

    fun mask(clientId: String): String =
        if (clientId.length <= 3) clientId else "•".repeat(minOf(clientId.length - 3, 5)) + clientId.takeLast(3)
}
