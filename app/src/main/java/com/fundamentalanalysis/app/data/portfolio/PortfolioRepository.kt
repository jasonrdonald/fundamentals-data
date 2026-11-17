package com.fundamentalanalysis.app.data.portfolio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fundamentalanalysis.app.data.local.PortfolioAccountEntity
import com.fundamentalanalysis.app.data.local.PortfolioActionEntity
import com.fundamentalanalysis.app.data.local.PortfolioDao
import com.fundamentalanalysis.app.data.local.PortfolioFeeEntity
import com.fundamentalanalysis.app.data.local.PortfolioHoldingEntity
import com.fundamentalanalysis.app.data.local.PortfolioImportEntity
import com.fundamentalanalysis.app.data.local.PortfolioNoteEntity
import com.fundamentalanalysis.app.data.local.PortfolioRows
import com.fundamentalanalysis.app.data.local.PortfolioTradeEntity
import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.di.IoDispatcher
import com.fundamentalanalysis.app.domain.model.BrokerHolding
import com.fundamentalanalysis.app.domain.model.CostSource
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.ImportPreview
import com.fundamentalanalysis.app.domain.model.PortfolioAccount
import com.fundamentalanalysis.app.domain.model.PortfolioAction
import com.fundamentalanalysis.app.domain.model.PortfolioData
import com.fundamentalanalysis.app.domain.model.PortfolioFee
import com.fundamentalanalysis.app.domain.model.PortfolioImportRecord
import com.fundamentalanalysis.app.domain.model.PortfolioNote
import com.fundamentalanalysis.app.domain.model.PortfolioTrade
import com.fundamentalanalysis.app.domain.model.TradeSide
import com.fundamentalanalysis.app.domain.repository.PortfolioRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Imported portfolio, stored only in this device's database. */
@Singleton
class LocalPortfolioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PortfolioDao,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : PortfolioRepository {

    /** The last file read, kept until the user confirms or picks another. */
    @Volatile private var pending: ParsedBackup? = null
    @Volatile private var pendingHoldings: Pair<HoldingsPlan, ImportPreview>? = null

    override fun observePortfolio(): Flow<PortfolioData> = combine(
        dao.observeAccounts(), dao.observeHoldings(), dao.observeTrades(), dao.observeActions(),
        combine(dao.observeNotes(), dao.observeFees(), dao.observeImports()) { n, f, i -> Triple(n, f, i) },
    ) { accounts, holdings, trades, actions, (notes, fees, imports) ->
        PortfolioData(
            accounts = accounts.map { PortfolioAccount(it.id, it.broker, it.clientId, it.holdingsAsOf.toDate(), it.importedAtMillis) },
            holdings = holdings.map { h ->
                val source = runCatching { CostSource.valueOf(h.costSource) }.getOrDefault(CostSource.BROKER)
                // Rows imported before costs were rebuilt: a broker average of 0 means unknown, not free.
                val unknown = source == CostSource.BROKER && h.avgPrice <= 0
                BrokerHolding(
                    h.accountId, h.symbol, h.isin, kindOf(h.kind, h.isin), h.sector, h.qty, h.avgPrice, h.close,
                    costedQty = if (unknown) 0.0 else h.costedQty ?: h.qty,
                    costSource = if (unknown) CostSource.UNKNOWN else source,
                )
            },
            trades = trades.mapNotNull { t ->
                val side = when (t.side.lowercase()) { "buy" -> TradeSide.BUY; "sell" -> TradeSide.SELL; else -> return@mapNotNull null }
                PortfolioTrade(t.id, t.accountId, t.symbol, t.isin, t.date.toDate() ?: return@mapNotNull null, side, t.qty, t.price, t.exchange, kindOf(t.kind, t.isin))
            },
            actions = actions.mapNotNull { a ->
                PortfolioAction(a.id, a.accountId, a.type, a.symbol, a.date.toDate() ?: return@mapNotNull null, a.qty, a.price, a.issue, a.direction.equals("in", true))
            },
            notes = notes.map { PortfolioNote(it.accountId, it.symbol, it.reviewed, it.comment) },
            fees = fees.mapNotNull { f -> PortfolioFee(f.accountId, f.date.toDate() ?: return@mapNotNull null, f.kind, f.amount) },
            imports = imports.map { PortfolioImportRecord(it.atMillis, it.fileName, it.exportedAt, it.brokers, it.holdings, it.trades) },
        )
    }.flowOn(cpu)

    override suspend fun readImportFile(uri: String): ImportPreview = withContext(io) {
        val parsed = Uri.parse(uri)
        val name = context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
            ?: throw UnsupportedBackupException("Couldn't open the file.")
        pending = null
        pendingHoldings = null
        if (HoldingsWorkbookParser.isWorkbook(bytes)) {
            // Holdings workbook: compare with what's stored, write nothing yet.
            val workbook = HoldingsWorkbookParser.parse(bytes)
            val plan = HoldingsReconciler.plan(workbook, dao.accounts(), dao.holdings(), System.currentTimeMillis())
            val preview = ImportPreview(
                fileName = name,
                exportedAt = workbook.brokers.firstNotNullOfOrNull { it.asOf },
                included = emptyList(),
                skipped = workbook.skipped,
                holdingsUpdates = plan.updates,
            )
            pendingHoldings = plan to preview
            return@withContext preview
        }
        PortfolioBackupParser.parse(json, bytes.decodeToString(), name).also { pending = it }.preview
    }

    override suspend fun confirmImport(): ImportPreview = withContext(io) {
        pendingHoldings?.let { (plan, preview) ->
            val now = System.currentTimeMillis()
            dao.replaceHoldings(
                plan.accounts, plan.holdings,
                PortfolioImportEntity(
                    atMillis = now, fileName = preview.fileName, exportedAt = preview.exportedAt,
                    brokers = plan.updates.joinToString { it.broker }, holdings = plan.holdings.size, trades = 0,
                ),
            )
            pendingHoldings = null
            return@withContext preview
        }
        val parsed = pending ?: throw IllegalStateException("Pick a file first.")
        // Accounts created by a holdings workbook give way to the backup's real accounts.
        val fileAccounts = parsed.backup.brokerAccounts.map { "holdings-file:${PortfolioBackupParser.brokerName(it.broker)}" }
        dao.replaceAccounts(parsed.toRows(System.currentTimeMillis()), parsed.record(System.currentTimeMillis()), fileAccounts)
        pending = null
        parsed.preview
    }

    override fun cancelImport() {
        pending = null
        pendingHoldings = null
    }

    override suspend fun clear() = withContext(io) { dao.clearAll() }

    private fun String?.toDate(): LocalDate? = this?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Fund units (INF ISINs or "mf" rows) vs listed stocks. */
    private fun kindOf(kind: String, isin: String) =
        if (kind.equals("mf", true) || isin.startsWith("INF")) HoldingKind.FUND else HoldingKind.STOCK
}

internal fun ParsedBackup.toRows(now: Long): PortfolioRows {
    val b = backup
    val snapshots = b.brokerHoldings.associateBy { it.id }
    val splits = b.market.associate { it.symbol to it.splits }
    return PortfolioRows(
        accounts = b.brokerAccounts.map { a ->
            PortfolioAccountEntity(a.id, PortfolioBackupParser.brokerName(a.broker), PortfolioBackupParser.mask(a.clientId), snapshots[a.id]?.asOf, now)
        },
        holdings = b.brokerHoldings.flatMap { h ->
            // One row per security; a snapshot listing the same security twice is merged.
            h.rows.groupBy { it.isin to it.symbol }.map { (_, rows) ->
                val qty = rows.sumOf { it.qty }
                val merged = rows.first().copy(qty = qty, avg = if (qty > 0) rows.sumOf { it.qty * it.avg } / qty else rows.first().avg)
                // Brokers that export no average price get one rebuilt from the account's own trades.
                val cost = CostBasis.of(merged, h.id, b.trades, b.portfolioActions, splits[merged.symbol].orEmpty())
                PortfolioHoldingEntity(
                    accountId = h.id, symbol = merged.symbol, isin = merged.isin, kind = merged.kind,
                    sector = merged.sector, qty = qty,
                    avgPrice = cost.avgPrice,
                    close = merged.close,
                    costedQty = cost.costedQty.takeIf { cost.source != CostSource.BROKER },
                    costSource = cost.source.name,
                )
            }
        },
        trades = b.trades.map { PortfolioTradeEntity(it.id, it.accountId, it.symbol, it.isin, it.date, it.side, it.qty, it.price, it.exchange, it.kind) },
        actions = b.portfolioActions.map { PortfolioActionEntity(it.id, it.accountId, it.type, it.symbol, it.date, it.qty, it.price, it.issue, it.direction) },
        notes = b.portfolioNotes.map { PortfolioNoteEntity(it.id, it.accountId, it.symbol, it.reviewed, it.comment) },
        fees = b.brokerFees.map { PortfolioFeeEntity(it.id, it.accountId, it.date, it.kind, it.amount) },
    )
}

internal fun ParsedBackup.record(now: Long) = PortfolioImportEntity(
    atMillis = now,
    fileName = preview.fileName,
    exportedAt = preview.exportedAt,
    brokers = preview.included.joinToString { it.broker },
    holdings = preview.included.sumOf { it.holdings },
    trades = preview.included.sumOf { it.trades },
)
