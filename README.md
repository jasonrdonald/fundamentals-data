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
- **Data** (the storage icon on the Overview, or tap the dataset line under the title):
  - what is on the device vs what is published, and when it was last checked
  - **Check for updates**, **Download all again** and **Reset to bundled** (the watchlist and settings are kept)
  - the load order, with each part's version, size, record count, origin and load time
  - the precomputed analytics tables and their row counts
  - how the data is updated from public sources, with a link to run the pipeline on GitHub
  - an update timeline: published pipeline runs (what changed, failures) and this device's checks, downloads and loads

## Architecture
```
tools/build_dataset.py   pipeline step 1: public sources -> shared-data/dataset.json (stdlib Python)
tools/derive_parts.py    pipeline step 2: dataset.json -> shared-data/v4/ (parts + precomputed analytics)
.github/workflows/       runs both every weekday after market close and commits changes
shared-data/v4/          what the app downloads: manifest.json, analytics.json, core.json, history.json
shared-data/dataset.json the full dataset (schemaVersion 3), still read by app versions up to 1.1
app/                     Android app
  ui/       Compose screens + @HiltViewModel (StateFlow UiState per screen, built off the main thread)
  domain/   models, analytics calculators (reference implementation), use cases
  data/     Room (single source of truth), sync engine, Retrofit + kotlinx.serialization,
            bundled seed (assets/seed = a copy of shared-data/v4)
  di/       Hilt modules
```

### Loading data without freezing the app
The dataset is split into parts that load in order:

| # | Part | Size | Contents | When |
|---|---|---|---|---|
| 1 | analytics | ~230 KB | Precomputed metrics for every index and stock, benchmark returns, sector aggregates (universe × period × sector) and the Nifty 50 calendar tables | Before the first screen |
| 2 | core | ~400 KB | 300 daily sessions and the last 13 months per index, constituents, stock list with the last 13 month-end closes | Before the first screen |
| 3 | history | ~960 KB | Full monthly history, valuation history, 10 years of stock month-end closes, stock daily closes, financials | In the background |

How it works:
- **First launch** stores parts 1 and 2 from the APK (about 0.3 s on an emulator), shows the app, then loads part 3 in the background. Every screen works from parts 1 and 2. Views that need older data (3Y/5Y comparisons, 3Y–10Y stock charts, a stock's year table and financials) show "Loading older history…" until part 3 lands, then fill in by themselves.
- **Every launch** downloads only `manifest.json` (about 2 KB, revalidated with its ETag) and then only the parts whose version changed. Parts 1 and 2 are swapped in one transaction so screens never mix two versions. Each file is checked against the manifest's SHA-256 before use.
- **Precomputed analytics:** metrics, group-bys and calendar tables are calculated once by the pipeline instead of on every phone at every launch. The app's Kotlin calculators remain the reference: `PublishedDatasetTest` recomputes every precomputed number from the raw data and fails if Python and Kotlin disagree.
- **Storage:** price history is packed into one database row per series (`BarCodec`, `PriceCodec`); writing one row per bar took about 3 s for the indices alone. Lists never load price history.
- **No main-thread work:** repository mapping and every screen's state are built on background dispatchers.

Measured on the emulator (release build):

| | Before | After |
|---|---|---|
| First launch, until the Overview shows data | ~6.5 s blocked (parse 0.9 s + write 5.6 s) | ~0.3 s for parts 1–2; history follows in ~0.3 s in the background |
| Every launch | downloads and parses the full 1.5 MB dataset (~1.9 s) | one ~2 KB manifest request; parts only when they change |
| Opening Stocks | 0.5 s of stock analytics | none (precomputed) |

Data flow:
- The app is offline-first: Room is the single source of truth, filled by `DatasetSync`.
- It ignores a manifest whose `schemaVersion` is newer than it supports, so a future format change can't crash older installs.
- Upgrading from 1.1 migrates the database (schema 5 to 6) and keeps the watchlist.

Stack: Kotlin 2.4, AGP 9.5, Compose BOM 2026.09, Material 3, type-safe Navigation, Hilt, Room, DataStore, Retrofit 3, OkHttp 5. minSdk 26, target/compile 37.

## Data pipeline
Sources (open endpoints, no login or cookies):
- **Prices and volume:** Yahoo Finance's chart endpoint, `query1.finance.yahoo.com/v8/finance/chart`. It's unofficial and has no service guarantee, so if it breaks, swap the fetcher in `build_dataset.py`.
- **Constituents and industries:** NSE's published CSV, `archives.nseindia.com/content/indices/ind_nifty50list.csv`.

Run it locally:
```bash
python tools/build_dataset.py
```
```bash
python tools/derive_parts.py
```
To also refresh the copy bundled inside the app (do this before building a release):
```bash
python tools/derive_parts.py --seed-dir app/src/main/assets/seed
```

How the pipeline behaves:
- It increases `version` only when the data actually changed. The derive step gives each part its own version, so an update that only changes recent prices doesn't make phones download the history again.
- If a single index fails to download, it keeps the last good copy of that index and lists it under `failures`.
- It stops without writing anything if the Nifty 50 can't be fetched.
- [`update-dataset.yml`](.github/workflows/update-dataset.yml) runs both steps at 17:00 IST every weekday and commits any change. You can also start it by hand from the repo's Actions tab, or from the app's Data page.
- Each run that changes something is added to the manifest's `history`, which the app shows as the pipeline timeline.

Check that the published files match what the app expects. This parses them with the app's own decoder, checks sizes and checksums against the manifest, dates, OHLC consistency and history length, and compares every precomputed number with the app's calculators:
```bash
./gradlew :app:testDebugUnitTest --tests "*PublishedDatasetTest*"
```

The app reads the dataset from this repo through `dataset.manifestUrl` in `gradle.properties`; parts are fetched from the same folder:
```properties
dataset.manifestUrl=https://raw.githubusercontent.com/jasonrdonald/fundamentals-data/main/shared-data/v4/manifest.json
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
