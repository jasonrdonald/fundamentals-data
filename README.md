# Fundamentals (Android)

An Android app for analysing and reporting on **Nifty indices**. Every install reads the same shared dataset, and there is no sign-in. A daily pipeline rebuilds that dataset from public index data.

## What's in the data
| Covered | Detail |
|---|---|
| Indices | Nifty 50, Next 50, 500 and Midcap 150 (broad market); Bank, Financial Services, PSU Bank, IT, Auto, FMCG, Pharma, Metal, Energy and Realty (sectoral); Infrastructure, PSE, Consumption and MNC (thematic); India VIX. Edit [tools/indices.json](tools/indices.json) to change the list. |
| Previous day | Open, high, low and close of the latest completed session, plus the previous close |
| Month-on-month | 72 months of open, high, low, close and volume, built from daily sessions |
| Daily history | 300 sessions of open, high, low, close and volume |
| Classification | Nifty 50 constituents with NSE's industry classification |
| Constituent stocks | 250 stocks: all members of the Nifty 50, Nifty Next 50 and Nifty Midcap 150 (lists from NSE). For each: 10 years of monthly prices, about a year of daily closes, trailing P/E, P/B and market cap, 4 fiscal years of financials (revenue, profit, operating income, equity, debt, EPS) and up to 5 quarters. Source: Yahoo Finance. Amounts are in ₹ crore. |
| Nifty 50 history | Full monthly history since September 2007 (other indices keep 72 months) |
| Nifty 50 valuation | Month-end P/E, P/B and dividend yield since April 2012, from NSE's official daily index archive (`archives.nseindia.com/content/indices/ind_close_all_DDMMYYYY.csv`) |

The app calculates these from the data:
- **Performance:** 1D, 1W, 1M, 3M, 6M, YTD and 1Y returns, plus 3Y and 5Y CAGR
- **Momentum:** RSI(14), position relative to the 50- and 200-day moving averages, trend, and distance from the 52-week high and low
- **Volume:** latest volume, 20-session average, relative volume, and the 3-month vs 1-year trend
- **Risk:** 1-month and 1-year volatility

Not included yet:
- **Valuation for indices other than the Nifty 50.** The same NSE archive covers them. To add one, give it a `valuation` block in `tools/indices.json`.
- **Shareholding** (promoter, FII and DII holdings). This is only published through NSE/BSE pages that sit behind bot protection.
- **5 years of quarterly results.** The open source returns only the last few quarters.

## Features
- **Overview:** the Nifty 50 headline with its previous session, returns, momentum and volume. Also market breadth, India VIX, sector performance for a chosen period, and the Nifty 50 industry mix.
- **Indices:** filter by category, sort by any metric, and star indices for your watchlist (stored on this device only).
- **Index detail:**
  - previous-session open/high/low/close and the full set of returns
  - a 3M, 6M or 1Y price chart with the 50- and 200-day moving averages
  - momentum, volume and risk readings
  - monthly returns chart and a month-on-month table
  - daily volume
  - industry classification (Nifty 50 only)
  - **Performance by year** (Nifty 50 only): one row per year and one column per month, showing month-on-month returns. The last column is the calendar-year return (year to date for the current year), and an Avg row shows seasonality.
  - **P/E history** (Nifty 50 only): the same layout with month-end P/E, switchable to P/B or dividend yield. The last column is the change over the year.

  Both tables export to CSV. The Year column stays visible when you scroll sideways.
- **Stocks** (pick Nifty 50, Next 50 or Midcap 150 at the top; each stock is compared with its own index):
  - *Sectors* view: for 1M, 3M, 6M, YTD, 1Y or 3Y, shows which stocks in each NSE sector beat their index and by how many percentage points. It also gives each sector's beat count and equal-weighted average return.
  - *Rankings* view: top or bottom 10, 20 or 30 stocks for each section:
    - momentum (12-1 month)
    - valuation (P/E; top = cheapest)
    - growth (revenue)
    - quality (ROE)
    - performance (1Y return)
    - profit (profit growth)
    - volatility (top = steadiest)
    - alpha (Jensen's alpha vs the stock's index from 36 monthly returns)

    Each section also shows supporting measures.
  - *Distribution* view: a histogram of the index's stock returns for the chosen period, with a fitted normal curve (same mean and SD) and markers for the index return and the mean. It also shows:
    - mean, median, SD, skewness and excess kurtosis, with a plain-language reading
    - how many stocks beat the index, and the index's percentile among its stocks
    - actual vs normal-expected counts in each standard-deviation band, and outliers beyond 2σ
    - every stock with its z-score (exportable as CSV)
  - *All stocks*: a searchable list.
  - *Stock page*:
    - returns vs its index, with price rebased to 100 over 1Y, 3Y, 5Y or 10Y
    - every factor measure
    - a year-by-month return table
    - annual and quarterly financials

  The Sectors and Rankings views and the year-by-month table export to CSV.
- **Appearance:** tap the theme icon on the Overview to choose System default (follows the phone), Light or Dark. The choice is saved on the device. Charts and heat tables use colours tuned for each mode.
- **Compare:** up to 3 indices:
  - price rebased to 100 over 3M, 6M, 1Y (daily) or 3Y, 5Y (month-end)
  - a returns-by-period bar chart across 3M–5Y, switchable between total and annualised for 3Y/5Y, with exact values
  - every equity index ranked for the chosen window, with the compared ones marked
  - a side-by-side metric table
- **Reports:** a ranking by any metric, a trailing-returns matrix, a 12-month heat table of monthly returns, and the industry classification. Each report exports to CSV.

## Architecture
```
tools/build_dataset.py   pipeline: public sources -> shared-data/dataset.json (stdlib Python)
.github/workflows/       runs the pipeline every weekday after market close and commits changes
shared-data/dataset.json the shared dataset (schemaVersion 2) every install downloads
app/                     Android app
  ui/       Compose screens + @HiltViewModel (StateFlow UiState per screen)
  domain/   models, pure analytics (IndexAnalyticsCalculator, ReportBuilder), use case
  data/     Room (single source of truth), DataStore (sync metadata),
            Retrofit + kotlinx.serialization, bundled seed asset
  di/       Hilt modules
```
How data flows through the app:
- The app is offline-first. On first launch it loads the bundled `seed_dataset.json` into Room.
- On every launch and on pull-to-refresh, it downloads the shared dataset. It replaces its local copy only when `version` is higher than the one it has.
- It ignores any dataset whose `schemaVersion` is newer than it supports, so a future format change can't crash older installs.

Stack: Kotlin 2.4, AGP 9.5, Compose BOM 2026.09, Material 3, type-safe Navigation, Hilt, Room, DataStore, Retrofit 3, OkHttp 5. minSdk 26, target/compile 37.

## Data pipeline
Sources (open endpoints, no login or cookies):
- **Prices and volume:** Yahoo Finance's chart endpoint, `query1.finance.yahoo.com/v8/finance/chart`. It's unofficial and has no service guarantee, so if it breaks, swap the fetcher in `build_dataset.py`.
- **Constituents and industries:** NSE's published CSV, `archives.nseindia.com/content/indices/ind_nifty50list.csv`.

Run it locally:
```bash
python tools/build_dataset.py
```
This also refreshes the dataset bundled inside the app:
```bash
python tools/build_dataset.py --seed app/src/main/assets/seed_dataset.json
```

How the pipeline behaves:
- It increases `version` only when the data actually changed.
- If a single index fails to download, it keeps the last good copy of that index and lists it under `failures`.
- It stops without writing anything if the Nifty 50 can't be fetched.
- [`update-dataset.yml`](.github/workflows/update-dataset.yml) runs it at 17:00 IST every weekday and commits any change. You can also start it by hand from the repo's Actions tab.

Check that the file matches what the app expects. This parses it with the app's own decoder and checks dates, OHLC consistency and history length:
```bash
./gradlew :app:testDebugUnitTest --tests "*RepositorySyncTest*"
```

The app reads the dataset from this repo through `dataset.url` in `gradle.properties`:
```properties
dataset.url=https://raw.githubusercontent.com/jasonrdonald/fundamentals-data/main/shared-data/dataset.json
```
This repository is public, so anyone can read the dataset. Don't commit private data.

## Build
```bash
./gradlew :app:assembleDebug
```
```bash
./gradlew :app:testDebugUnitTest
```
Requires JDK 17+ (Android Studio's bundled JBR works).
