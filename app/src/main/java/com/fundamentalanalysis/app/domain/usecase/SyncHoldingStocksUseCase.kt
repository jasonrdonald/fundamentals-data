package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.domain.analysis.PortfolioAnalytics
import com.fundamentalanalysis.app.domain.model.HoldingKind
import com.fundamentalanalysis.app.domain.model.PortfolioData
import com.fundamentalanalysis.app.domain.repository.HoldingStocksRepository
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.PortfolioRepository
import com.fundamentalanalysis.app.domain.repository.SettingsRepository
import com.fundamentalanalysis.app.domain.model.nseBaseSymbol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Keeps price history for the portfolio's stocks that the shared dataset doesn't cover. Only this
 * device asks for them, so which stocks are held never leaves the phone for the public dataset.
 */
class SyncHoldingStocksUseCase @Inject constructor(
    private val portfolio: PortfolioRepository,
    private val market: MarketRepository,
    private val repository: HoldingStocksRepository,
    private val settings: SettingsRepository,
) {
    /** Runs until cancelled, syncing whenever the holdings or the dataset's stock list change. */
    @OptIn(FlowPreview::class)
    suspend fun run() {
        combine(portfolio.observePortfolio(), market.observeStocks(), settings.observeOfflineMode()) { data, stocks, offline ->
            // Offline mode: no live lookups; holdings outside the dataset stay blank.
            if (offline) null else wanted(data, stocks.map { it.symbol }.toSet())
        }.distinctUntilChanged().debounce(2_000).collectLatest { wanted -> if (wanted != null) runCatching { repository.sync(wanted) } }
    }

    suspend fun refresh(data: PortfolioData, covered: Set<String>) {
        if (!settings.observeOfflineMode().first()) repository.sync(wanted(data, covered), force = true)
    }

    companion object {
        /** Held stocks (not funds) missing from the dataset -> their broker sector, by listing symbol. */
        fun wanted(data: PortfolioData, covered: Set<String>): Map<String, String> = data.holdings
            .filter { it.qty > 0 && it.kind == HoldingKind.STOCK && it.symbol !in covered && nseBaseSymbol(it.symbol) !in covered }
            .groupBy { nseBaseSymbol(it.symbol) }
            .mapValues { (_, rows) ->
                rows.firstNotNullOfOrNull { it.sector.ifBlank { null } }?.let(PortfolioAnalytics::normalizeSector) ?: "Unclassified"
            }
    }
}
