package com.fundamentalanalysis.app.ui

import com.fundamentalanalysis.app.ui.settings.CustomizeScreen
import com.fundamentalanalysis.app.ui.navigation.CustomizeRoute
import com.fundamentalanalysis.app.ui.components.LocalUiLayout
import com.fundamentalanalysis.app.domain.model.UiComponent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fundamentalanalysis.app.ui.indices.IndicesScreen
import com.fundamentalanalysis.app.ui.compare.CompareScreen
import com.fundamentalanalysis.app.ui.dashboard.DashboardScreen
import com.fundamentalanalysis.app.ui.detail.IndexDetailScreen
import com.fundamentalanalysis.app.ui.navigation.IndicesRoute
import com.fundamentalanalysis.app.ui.navigation.IndexDetailRoute
import com.fundamentalanalysis.app.ui.navigation.CompareRoute
import com.fundamentalanalysis.app.ui.navigation.DashboardRoute
import com.fundamentalanalysis.app.ui.navigation.DataRoute
import com.fundamentalanalysis.app.ui.navigation.PortfolioRoute
import com.fundamentalanalysis.app.ui.navigation.HeatmapRoute
import com.fundamentalanalysis.app.ui.heatmap.HeatmapScreen
import com.fundamentalanalysis.app.ui.portfolio.PortfolioScreen
import com.fundamentalanalysis.app.ui.data.DataScreen
import com.fundamentalanalysis.app.ui.navigation.ReportsRoute
import com.fundamentalanalysis.app.ui.navigation.StockDetailRoute
import com.fundamentalanalysis.app.ui.navigation.StocksRoute
import com.fundamentalanalysis.app.ui.stocks.StockDetailScreen
import com.fundamentalanalysis.app.ui.stocks.StocksScreen
import androidx.compose.material.icons.automirrored.filled.ShowChart
import com.fundamentalanalysis.app.ui.reports.ReportsScreen
import kotlin.reflect.KClass

private data class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
    /** Customize page switch; null = always shown. */
    val component: UiComponent? = null,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(DashboardRoute, DashboardRoute::class, "Overview", Icons.Filled.Dashboard),
    TopLevelDestination(IndicesRoute, IndicesRoute::class, "Indices", Icons.AutoMirrored.Filled.List, UiComponent.NAV_INDICES),
    TopLevelDestination(StocksRoute, StocksRoute::class, "Stocks", Icons.AutoMirrored.Filled.ShowChart, UiComponent.NAV_STOCKS),
    TopLevelDestination(CompareRoute, CompareRoute::class, "Compare", Icons.AutoMirrored.Filled.CompareArrows, UiComponent.NAV_COMPARE),
    TopLevelDestination(ReportsRoute, ReportsRoute::class, "Reports", Icons.Filled.Assessment, UiComponent.NAV_REPORTS),
)

@Composable
fun FundamentalsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val openIndex: (String) -> Unit = { navController.navigate(IndexDetailRoute(it)) }
    val openStock: (String) -> Unit = { navController.navigate(StockDetailRoute(it)) }
    val openHeatmap: () -> Unit = { navController.navigate(HeatmapRoute) { launchSingleTop = true } }

    Scaffold(
        // Top bars live in each screen; NavigationBar pads itself for the system nav bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                val layout = LocalUiLayout.current
                topLevelDestinations.filter { d -> d.component?.let(layout::shows) ?: true }.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<DashboardRoute> {
                DashboardScreen(
                    onOpenIndex = openIndex,
                    onOpenData = { navController.navigate(DataRoute) },
                    onOpenPortfolio = { navController.navigate(PortfolioRoute) },
                    onOpenHeatmap = openHeatmap,
                    onOpenCustomize = { navController.navigate(CustomizeRoute) { launchSingleTop = true } },
                )
            }
            composable<PortfolioRoute> { PortfolioScreen(onBack = { navController.popBackStack() }, onOpenStock = openStock) }
            composable<HeatmapRoute> { HeatmapScreen(onBack = { navController.popBackStack() }, onOpenStock = openStock) }
            composable<CustomizeRoute> { CustomizeScreen(onBack = { navController.popBackStack() }) }
            composable<DataRoute> { DataScreen(onBack = { navController.popBackStack() }) }
            composable<IndicesRoute> { IndicesScreen(onOpenIndex = openIndex) }
            composable<CompareRoute> { CompareScreen(onOpenIndex = openIndex) }
            composable<ReportsRoute> { ReportsScreen(onOpenIndex = openIndex) }
            composable<StocksRoute> { StocksScreen(onOpenStock = openStock, onOpenHeatmap = openHeatmap) }
            composable<StockDetailRoute> { StockDetailScreen(onBack = { navController.popBackStack() }) }
            composable<IndexDetailRoute> { IndexDetailScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
