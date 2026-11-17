---
name: stock-analysis
description: Analyse Nifty 50, Nifty Next 50 and Nifty Midcap 150 stocks from this project's published dataset — a single-stock report (returns vs its index, factor ranks, valuation, financials, sector peers), side-by-side comparison, screens ("top 10 Midcap 150 stocks by ROE") and sector breakdowns. Use when asked to analyse, compare, rank or screen Indian stocks covered by the app, or to check what the app shows for a stock.
---

# Stock analysis

Reports come from `shared-data/v4/` (the dataset the app downloads). Per-stock metrics are
precomputed in `analytics.json` by `tools/derive_parts.py`, a port of the app's Kotlin
calculators, so these numbers match the app. Coverage: the 250 constituents of Nifty 50,
Nifty Next 50 and Nifty Midcap 150. Each stock is benchmarked against **its own index**.

## Steps

1. **Check freshness.** Run `git pull` first if the user wants the latest data. The pipeline
   publishes once a day after the NSE close. Every report prints its "data as of" date; always
   quote it.
2. **Run the report script** from the repo root (use `py -3` on Windows; `python` isn't installed):

   ```bash
   py -3 .claude/skills/stock-analysis/scripts/stock_report.py HDFCBANK            # full report
   py -3 .claude/skills/stock-analysis/scripts/stock_report.py TCS INFY HCLTECH    # side by side
   py -3 .claude/skills/stock-analysis/scripts/stock_report.py --screen "NIFTY 50" --by roe --top 10
   py -3 .claude/skills/stock-analysis/scripts/stock_report.py --screen midcap --by pe --top 10
   py -3 .claude/skills/stock-analysis/scripts/stock_report.py --screen "NEXT 50" --by Y1 --bottom
   py -3 .claude/skills/stock-analysis/scripts/stock_report.py --sector "Information Technology" --period M3
   ```

   Symbols are NSE symbols (`BAJAJ-AUTO`, `M&M`); a unique part of the company name also works.
   `--by` takes a measure key (below) or a period (`M1 M3 M6 YTD Y1 Y3`).
3. **Interpret, don't just paste.** Lead with what stands out, e.g. a lagging 1Y return against a
   cheap P/E, momentum versus the trend, or growth versus margin. Then show the relevant tables.
   Use `tools/market_data.py` directly for anything the script doesn't cover (see below).

## Measures (as the app defines them)

| Key | Meaning | Best |
|---|---|---|
| returns `M1…Y3` | Price return; `Y3` is a CAGR | high |
| `momentum12m1m` | 12-month return excluding the latest month | high |
| `rsi14` | 14-session RSI (above 70 overbought, below 30 oversold) | context |
| `vsDma200` / `fromHigh52w` | Distance from the 200-day average / 52-week high | high |
| `volatility1y` | Annualised volatility of daily returns | low |
| `beta`, `alpha` | vs own index, up to 36 monthly returns; alpha is annualised Jensen's | low / high |
| `pe`, `pb`, `earningsYield` | Trailing; loss-makers (P/E ≤ 0) are excluded from ranks | low / low / high |
| `revenueGrowth`, `revenueCagr`, `epsGrowth`, `profitGrowth` | Latest fiscal year (CAGR over available years) | high |
| `quarterlyRevenueYoY` | Latest quarter vs the same quarter a year earlier | high |
| `roe`, `netMargin`, `debtToEquity`, `netProfitCr` | Latest fiscal year | high / high / low / high |

"Rank 3 / 50" means third best in the stock's index for that measure, where "best" follows the
column above. "pp" means percentage points (stock return minus index return).

## Going further

```python
import sys; sys.path.insert(0, "tools")
import market_data as md
ds = md.Dataset()
s = ds.stock("RELIANCE")          # metrics, ~10y month-end closes, ~1y daily closes, annual/quarterly financials
ds.stocks("NIFTY 50")             # all constituents with metrics
ds.sector_stats("NIFTY 50", "Y1") # precomputed sector aggregates (beat-the-index counts)
```

## Caveats to state when relevant

- Prices are end-of-day closes; "latest session" is the last completed NSE session, not live.
- Financials come from Yahoo Finance and can have gaps or one-off restatements (e.g. EPS halving
  after a bonus issue or merger). Flag implausible jumps instead of treating them as fact.
- Constituents are the current list, so long-run comparisons carry survivorship bias.
- This is descriptive analysis of public data, not investment advice. Don't recommend buying or
  selling; present the evidence and the trade-offs.
- Never pull the user's portfolio into an analysis. It's private, lives only on their phone, and
  isn't in this dataset.

## Keeping it in sync

A new measure has to go into `tools/derive_parts.py` (stock_metrics), the Kotlin
`StockAnalyticsCalculator` and `StockMetric`, and `PublishedDatasetTest` (parity). Then add it
to `STOCK_MEASURES` in `tools/market_data.py` so this skill shows it.
