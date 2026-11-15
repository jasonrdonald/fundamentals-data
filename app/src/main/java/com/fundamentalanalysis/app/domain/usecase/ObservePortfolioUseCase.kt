package com.fundamentalanalysis.app.domain.usecase

import com.fundamentalanalysis.app.di.DefaultDispatcher
import com.fundamentalanalysis.app.domain.analysis.PortfolioAnalytics
import com.fundamentalanalysis.app.domain.model.PortfolioSummary
import com.fundamentalanalysis.app.domain.repository.PortfolioRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The imported portfolio joined with the shared dataset: tracked stocks get the latest close,
 * NSE sector, index membership and their precomputed metrics. Null when nothing is imported.
 */
class ObservePortfolioUseCase @Inject constructor(
    private val portfolio: PortfolioRepository,
    private val stocks: ObserveAnalyzedStocksUseCase,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    /** [broker] limits the summary to one broker's accounts (null = all). */
    operator fun invoke(broker: Flow<String?> = flowOf(null)): Flow<PortfolioSummary?> =
        combine(portfolio.observePortfolio(), stocks(), broker) { data, analyzed, b ->
            if (data.isEmpty) null else PortfolioAnalytics.summarize(data.forBroker(b), analyzed.associateBy { it.symbol })
        }.flowOn(dispatcher)

    /** Brokers present in the imported data. */
    fun brokers(): Flow<List<String>> = portfolio.observePortfolio().map { d -> d.accounts.map { it.broker }.distinct() }
}
