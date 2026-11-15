package com.fundamentalanalysis.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object DashboardRoute
@Serializable data object IndicesRoute
@Serializable data object StocksRoute
@Serializable data object CompareRoute
@Serializable data object ReportsRoute
@Serializable data class IndexDetailRoute(val symbol: String)
@Serializable data class StockDetailRoute(val symbol: String)
@Serializable data object DataRoute
@Serializable data object PortfolioRoute
