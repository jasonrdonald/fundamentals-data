---
name: index-analysis
description: Analyse the Nifty indices in this project's published dataset — returns, trend and momentum, volatility, Nifty 50 valuation (P/E, P/B, dividend yield vs its own history), drawdowns, monthly seasonality, breadth and sector performance of constituents, and cross-index comparison (sector rotation, relative strength). Use when asked how an Indian index is doing, whether it looks cheap or stretched, how sectors compare, or to check what the app shows for an index.
---

# Index analysis

Reports come from `shared-data/v4/` (the dataset the app downloads). Returns, RSI, moving
averages, 52-week range and volatility are precomputed in `analytics.json` by
`tools/derive_parts.py`, a port of the app's Kotlin calculators, so they match the app.
Valuation percentiles, drawdowns, seasonality and breadth are worked out by the script from the
same data.

**Coverage**: 19 indices. These are the broad indices (Nifty 50, Next 50, 500, Midcap 150), the
sector indices (Bank, Financial Services, PSU Bank, IT, Auto, FMCG, Pharma, Metal, Energy,
Realty), the thematic ones (Infra, PSE, Consumption, MNC) and India VIX.

- **History**: the Nifty 50 has month-end history from 2007 and P/E, P/B and dividend yield from
  2012. The other indices go back to Dec 2020 and have no valuation data.
- **Breadth and sectors**: only for Nifty 50, Next 50 and Midcap 150, the indices whose
  constituents are in the dataset.

## Steps

1. **Check freshness.** Run `git pull` if the user wants the latest data, and always quote the
   report's "data as of" date. The pipeline publishes once a day after the NSE close.
2. **Run the report script** from the repo root (use `py -3` on Windows):

   ```bash
   py -3 .claude/skills/index-analysis/scripts/index_report.py "NIFTY 50"         # full report
   py -3 .claude/skills/index-analysis/scripts/index_report.py bank --years 5     # names and aliases work
   py -3 .claude/skills/index-analysis/scripts/index_report.py midcap --period M3 # breadth/sectors over 3M
   py -3 .claude/skills/index-analysis/scripts/index_report.py --compare          # all indices, by 1Y
   py -3 .claude/skills/index-analysis/scripts/index_report.py --compare IT FMCG PHARMA "NIFTY 50"
   ```

   Aliases: `nifty`, `next 50`, `midcap`, `bank` / `banknifty`, `vix`. A bare sector name such
   as `IT` or `AUTO` resolves to `NIFTY <name>`.
3. **Interpret, don't just paste.** Lead with the picture: trend and momentum together (an RSI
   above 70 in an uptrend reads differently from one in a downtrend), where valuation sits in its
   own range, how broad a move is (breadth, sectors beating the index), and seasonality as context
   rather than forecast. Then show the tables that support it. For constituent detail, hand over
   to the `stock-analysis` skill.

## Definitions (as the app uses them)

- **Returns**: 1D, 1W, 1M, 3M, 6M and 1Y use sessions (1, 5, 21, 63, 126, 252). YTD runs from
  the last close of the previous year. 3Y and 5Y are CAGRs from month-end closes.
- **Trend**: price above both the 50- and 200-session averages = Uptrend, below both =
  Downtrend, otherwise Mixed. The 50/200 crossover is shown separately.
- **RSI (14)**: 70 or above = overbought, 30 or below = oversold.
- **Volatility**: annualised standard deviation of daily returns over 1M and 1Y.
- **Valuation percentile**: the share of month-ends at or below today's value. A high P/E or
  P/B percentile is expensive against its own history; a high dividend-yield percentile is cheap.
  The z-score is the distance from the mean in standard deviations.
- **Seasonality**: completed calendar months only (the current month is excluded); "up months"
  is a hit rate, not a probability.
- **Breadth**: constituents up or down in the latest session, the count above their 200-DMA, and
  how many beat the index over the period. Sector averages are equal-weighted. The "lift" and
  "drag" names approximate contribution using full market cap, while the index itself uses
  free-float weights.

## Going further

```python
import sys; sys.path.insert(0, "tools")
import market_data as md
ds = md.Dataset()
ix = ds.index("NIFTY 50", with_history=True)  # daily bars (~300), monthly bars, valuation, metrics
ds.calendar("NIFTY 50", "PE")                 # year x month tables: RETURNS, PE, PB, DIVIDEND_YIELD
ds.sector_stats("NIFTY MIDCAP 150", "M3")     # precomputed sector aggregates
```

## Caveats to state when relevant

- End-of-day data; "latest session" is the last completed NSE session.
- Index prices come from Yahoo Finance, and Nifty 50 valuation from NSE month-end data. Short
  histories (since Dec 2020) make drawdown and seasonality figures less reliable, so say so.
- Past seasonality and valuation ranges don't predict returns. Present them as context, not
  advice, and don't recommend buying or selling.
- Never mix the user's private portfolio into these reports. It isn't in this dataset.

## Keeping it in sync

Index metrics are defined in `tools/derive_parts.py` (`index_metrics`) and the Kotlin
`IndexAnalyticsCalculator`, and parity is checked by `PublishedDatasetTest`. If either changes,
re-run this report and update the definitions above.
