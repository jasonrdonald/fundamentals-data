package com.fundamentalanalysis.app.domain.model

/** How a page component is displayed. */
enum class Display(val label: String) {
    SHOW("Show"),
    SIMPLE("Simplified"),
    HIDE("Hide"),
}

/** Pages with customisable components, in the order they appear on the Customize page. */
enum class UiPage(val label: String) {
    NAVIGATION("Bottom navigation"),
    OVERVIEW("Overview"),
    INDEX("Index page"),
    STOCK("Stock page"),
    STOCKS("Stocks"),
    COMPARE("Compare"),
    PORTFOLIO("Portfolio"),
}

private val SHOW_HIDE = listOf(Display.SHOW, Display.HIDE)
private val ALL_MODES = listOf(Display.SHOW, Display.SIMPLE, Display.HIDE)

/**
 * Every customisable component. [modes] lists what the component supports: everything can be
 * shown or hidden, and some have a simplified form (described in [simplified]). The first mode
 * is the default.
 */
enum class UiComponent(
    val page: UiPage,
    val label: String,
    val description: String,
    val modes: List<Display> = SHOW_HIDE,
    val simplified: String? = null,
) {
    // Bottom navigation (Overview is always there).
    NAV_INDICES(UiPage.NAVIGATION, "Indices tab", "All tracked indices with search and filters"),
    NAV_STOCKS(UiPage.NAVIGATION, "Stocks tab", "Constituent stocks: sectors, rankings, distribution"),
    NAV_COMPARE(UiPage.NAVIGATION, "Compare tab", "Side-by-side index comparison"),
    NAV_REPORTS(UiPage.NAVIGATION, "Reports tab", "Index rankings, returns and classification reports"),

    // Overview
    OVERVIEW_DATA_BANNER(UiPage.OVERVIEW, "Dataset status", "Which data is loaded, with a link to the Data page"),
    OVERVIEW_PORTFOLIO(UiPage.OVERVIEW, "Your portfolio", "Value and P&L of the imported portfolio"),
    OVERVIEW_HEADLINE(UiPage.OVERVIEW, "Nifty 50 session", "Close, change and the session's open, high and low",
        ALL_MODES, simplified = "Close and change only"),
    OVERVIEW_BREADTH(UiPage.OVERVIEW, "Breadth and India VIX", "Indices up / down and the volatility index"),
    OVERVIEW_PERFORMANCE(UiPage.OVERVIEW, "Performance", "Nifty 50 returns from 1D to 5Y",
        ALL_MODES, simplified = "1D, 1M and 1Y only"),
    OVERVIEW_MOMENTUM(UiPage.OVERVIEW, "Momentum & volume", "Trend, RSI, moving averages and volume"),
    OVERVIEW_SECTORS(UiPage.OVERVIEW, "Sector performance", "Sectoral indices ranked by return"),
    OVERVIEW_INDUSTRY_MIX(UiPage.OVERVIEW, "Industry mix", "Nifty 50 constituents per industry"),
    OVERVIEW_WATCHLIST(UiPage.OVERVIEW, "Your watchlist", "Indices you starred"),

    // Index page
    INDEX_SESSION(UiPage.INDEX, "Previous session", "Open, high, low, close and change"),
    INDEX_PERFORMANCE(UiPage.INDEX, "Performance", "Returns from 1D to 5Y", ALL_MODES, simplified = "1D, 1M and 1Y only"),
    INDEX_PRICE_CHART(UiPage.INDEX, "Price chart", "Daily close with 50- and 200-day averages"),
    INDEX_MOMENTUM(UiPage.INDEX, "Momentum, volume & risk", "Trend, RSI, volatility and volume readings"),
    INDEX_MONTHLY(UiPage.INDEX, "Monthly returns", "Bar chart and table of recent months"),
    INDEX_YEARS(UiPage.INDEX, "Returns by year", "Year × month table (Nifty 50)"),
    INDEX_VALUATION(UiPage.INDEX, "Valuation by year", "P/E, P/B and dividend yield tables"),
    INDEX_VOLUME(UiPage.INDEX, "Daily volume", "Recent sessions' volume"),
    INDEX_COMPOSITION(UiPage.INDEX, "Sector & industry classification", "Constituents grouped by industry"),

    // Stock page
    STOCK_VS_INDEX(UiPage.STOCK, "Performance vs index", "Returns against the stock's own index"),
    STOCK_CHART(UiPage.STOCK, "Price vs index chart", "Month-end closes rebased to 100"),
    STOCK_FACTORS(UiPage.STOCK, "Factor profile", "Momentum, valuation, growth, quality and more",
        ALL_MODES, simplified = "The main measure of each factor"),
    STOCK_YEARS(UiPage.STOCK, "Year-by-month returns", "Monthly return table by year"),
    STOCK_ANNUAL(UiPage.STOCK, "Annual financials", "Revenue, profit, EPS and balance sheet by fiscal year",
        ALL_MODES, simplified = "Without the balance sheet columns"),
    STOCK_QUARTERLY(UiPage.STOCK, "Quarterly results", "Latest quarters"),

    // Stocks
    STOCKS_SECTORS(UiPage.STOCKS, "Sectors tab", "Stocks beating their index, by sector"),
    STOCKS_RANKINGS(UiPage.STOCKS, "Rankings tab", "Top and bottom stocks by factor"),
    STOCKS_DISTRIBUTION(UiPage.STOCKS, "Distribution tab", "Spread of returns against a normal curve"),
    STOCKS_ALL(UiPage.STOCKS, "All stocks tab", "Searchable list of every constituent"),

    // Compare
    COMPARE_RELATIVE(UiPage.COMPARE, "Relative performance", "Selected indices rebased to 100"),
    COMPARE_PERIODS(UiPage.COMPARE, "Returns by period", "Selected indices' returns per period"),
    COMPARE_ALL(UiPage.COMPARE, "All indices", "Every index's return over the range"),
    COMPARE_TABLE(UiPage.COMPARE, "Side by side", "Full comparison table"),

    // Portfolio
    PORTFOLIO_SUMMARY(UiPage.PORTFOLIO, "Summary", "Value, return, cost, latest session and price dates",
        ALL_MODES, simplified = "Value and return only"),
    PORTFOLIO_PERIODS(UiPage.PORTFOLIO, "Return period selector", "1D to All time chips (hidden = All time)"),
    PORTFOLIO_HOLDING_ROWS(UiPage.PORTFOLIO, "Holding rows", "Symbol, company and sector, value and return",
        ALL_MODES, simplified = "Symbol, value and return on one line"),
    PORTFOLIO_HEATMAP(UiPage.PORTFOLIO, "Heatmap tab", "Nifty 50 holdings by the day's move"),
    PORTFOLIO_ALLOCATION(UiPage.PORTFOLIO, "Allocation tab", "By sector, broker and index"),
    PORTFOLIO_VS_INDEX(UiPage.PORTFOLIO, "vs Index tab", "Holdings against their indices"),
    PORTFOLIO_ACTIVITY(UiPage.PORTFOLIO, "Activity tab", "Trades and charges by year, corporate actions"),
    PORTFOLIO_PRIVACY(UiPage.PORTFOLIO, "Privacy note", "Where your portfolio is kept"),
    ;

    val default: Display get() = modes.first()
}

/** The user's choices; components not mentioned use their default. */
data class UiLayout(val choices: Map<UiComponent, Display> = emptyMap()) {
    fun display(c: UiComponent): Display = choices[c]?.takeIf { it in c.modes } ?: c.default
    fun shows(c: UiComponent): Boolean = display(c) != Display.HIDE
    fun simple(c: UiComponent): Boolean = display(c) == Display.SIMPLE
    val customised: Int get() = UiComponent.entries.count { display(it) != it.default }

    /** "NAME=MODE;..." for storage; only non-default choices are kept. */
    fun encode(): String = UiComponent.entries.filter { display(it) != it.default }.joinToString(";") { "${it.name}=${display(it).name}" }

    companion object {
        fun decode(text: String?): UiLayout = UiLayout(
            text.orEmpty().split(';').mapNotNull { part ->
                val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val c = runCatching { UiComponent.valueOf(k) }.getOrNull() ?: return@mapNotNull null
                val d = runCatching { Display.valueOf(v) }.getOrNull()?.takeIf { it in c.modes } ?: return@mapNotNull null
                c to d
            }.toMap(),
        )
    }
}
