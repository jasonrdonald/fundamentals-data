# Shared dataset

**Don't edit these files by hand.** The [Update dataset](../.github/workflows/update-dataset.yml) workflow regenerates them every weekday after market close:
1. [`tools/build_dataset.py`](../tools/build_dataset.py) fetches public data into `dataset.json`.
2. [`tools/derive_parts.py`](../tools/derive_parts.py) splits it into the parts under `v4/` and precomputes the analytics.

## `v4/` (schemaVersion 4, read by the app from version 1.2)
Apps download `v4/manifest.json` first, then only the parts whose version changed:
```
https://raw.githubusercontent.com/jasonrdonald/fundamentals-data/main/shared-data/v4/manifest.json
```
- `manifest.json`: `version`, `asOf` (latest completed session), `loadOrder`, and for each part its `file`, `version`, `bytes`, `sha256`, `records` and `description`. `history[]` lists the pipeline runs that changed something (newest first): what changed, failures, index and stock counts.
- `analytics.json`: precomputed per-index metrics (returns 1D–5Y keyed by `D1`…`Y5`, RSI, moving averages, 52-week range, volume, volatility), per-stock metrics (returns, momentum, beta/alpha, valuation, growth, quality, profit), `benchmarks` (each universe index's returns), `sectors` (universe × period × sector: stocks, measured, outperformers, average return, benchmark return) and `calendars` (Nifty 50 returns and P/E, P/B, dividend yield by year × month; each row is `[year, [12 cells], yearValue, yearIsPartial]`).
- `core.json`: per index `daily` (300 sessions) and `monthly` (last 13 months) as column arrays (`d`, `o`, `h`, `l`, `c`, `v`, plus `last` for monthly); `composition`, `universes`, and the stock list with the last 13 month-end closes (`mc`, aligned to `stocks.months`).
- `history.json`: per index the full `monthly` series and `valuation` (`d`, `pe`, `pb`, `dy`); per stock the full `mc` (aligned to `stocks.months`), `dc` (daily closes aligned to `stocks.sessions`), `annual` and `quarterly` financials.

Numbers keep 9 significant digits. Amounts are in ₹ crore; index prices are in index points.

## `dataset.json` (schemaVersion 3)
The full dataset in one file, kept for app versions up to 1.1. `version` increases whenever the data changes.

This repository is public.
