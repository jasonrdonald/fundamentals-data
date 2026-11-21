# Fundamentals: design

An Android app for analysing Nifty indices, their constituent stocks and a personal portfolio. It is offline-first: every screen reads a local database, and the network is used only to refresh that database.

## Architecture

```
GitHub Actions (daily/weekly/monthly)                  Android app
┌───────────────────────────────┐        ┌─────────────────────────────────────────┐
│ tools/build_dataset.py        │        │ UI (Compose screens + ViewModels)       │
│   Yahoo → index & stock data  │        │        ↑ Flows                          │
│   NSE   → lists, valuation    │        │ Domain (use cases, analysis, models)    │
│ tools/derive_parts.py         │        │        ↑                                │
│   → shared-data/v5/*.json     │──raw──▶│ Data: Room DB, DataStore, sync engine   │
│     manifest + 3 parts        │ GitHub │   seed copy in APK (assets/seed)        │
└───────────────────────────────┘        └─────────────────────────────────────────┘
```

Three layers in the app, wired with Hilt:

| Layer | Contents |
|---|---|
| UI | Jetpack Compose screens: Dashboard, Indices, Index detail, Compare, Heatmap, Reports, Stocks (with Portfolio chip), Stock detail, Portfolio, Data, Settings / Customize |
| Domain | Pure Kotlin: analytics calculators (returns, momentum, RSI, drawdowns, seasonality, breadth, valuation, factor ranks), use cases that combine data into screen models, repository interfaces |
| Data | Room database (v8), DataStore preferences, dataset sync engine, portfolio import/backup, on-device stock fetcher |

## Data

### The shared dataset

Built by scheduled GitHub Actions workflows and committed to `shared-data/v5/` (schema 5, read by app 1.8.0+). `shared-data/v4/` (three files) is still published for app 1.7.0 and older, and `shared-data/dataset.json` is the pipeline's own full copy.

| File | Contents | Changes | Load group |
|---|---|---|---|
| `manifest.json` | Schema version, dataset version, as-of date, `dailyFrom`, and a version, size, SHA-256 and group for each file | Every run | — |
| `analytics.json` | Precomputed index and stock metrics, sector aggregates, calendar tables | Daily | primary |
| `core.json` | Index list, constituents, stock list (Nifty 50, Next 50, Midcap 150, Wishlist) with market cap, P/E, P/B | Weekly | primary |
| `daily-YYYY-MM.json` | One month of daily index bars and stock closes (about 15 months kept) | Current month only | primary |
| `monthly-YYYY.json` | One year of month-end index bars, stock closes and Nifty 50 valuation | Current year only | primary for this and last year, else history |
| `fundamentals.json` | Annual and quarterly financials per stock | Weekly | history |

Sources used by the pipeline (not by the app): Yahoo Finance for index and stock prices and stock fundamentals; NSE archives for constituent lists and Nifty 50 valuation. Wishlist stocks come from `tools/wishlist.csv` and are measured against the Nifty 500.

### The dataset jobs

Three scheduled workflows run `build_dataset.py` in a different mode, then `derive_parts.py`, and commit `shared-data/` only if something changed. They share one concurrency group, so runs queue rather than overlap. The shared steps live in `dataset-job.yml`.

| Workflow | Mode | Schedule | Purpose |
|---|---|---|---|
| `update-dataset.yml` | `daily` | Mon–Fri 17:00 UTC (22:30 IST) | Keep prices current |
| `update-fundamentals.yml` | `weekly` | Sat 03:00 UTC | Constituent lists and fundamentals |
| `refresh-history.yml` | `full` | 1st of month 03:00 UTC | Re-download everything and reconcile |

The daily workflow can also be started by hand in any mode (the app's Data page links to it).

**What is collected** is fixed by the reference list `tools/indices.json`:

| Section | Contents | Source |
|---|---|---|
| `indices` | 19 indices: Nifty 50, Next 50, 500, Midcap 150, 13 sectoral/thematic indices, India VIX | Yahoo chart API (`^NSEI` etc.) |
| `constituents` | Nifty 50 composition (for weights/sectors) | NSE `ind_nifty50list.csv` |
| `stocks.universes` | Stock lists: Nifty 50, Next 50, Midcap 150 (NSE constituent CSVs) and the Wishlist (`tools/wishlist.csv`) | NSE archives / repo file |

Stocks are collected only for those four lists (about 300). The sectoral indices are tracked as index levels only. Index membership is refreshed weekly; a new member is backfilled the run it appears, and a stock that leaves an index drops out.

**Data by how it changes**

| Data | Changes? | Daily | Weekly | Monthly (full) |
|---|---|---|---|---|
| Index closes, past sessions | Never | — | — | Re-downloaded |
| Index closes, latest sessions | Yes (new session, late fixes) | Last few sessions appended | — | Re-downloaded |
| Stock closes, past sessions | Only on a split/bonus (Yahoo's closes are split-adjusted, not dividend-adjusted) | Overlap check; full re-download of that stock if restated | — | Re-downloaded |
| Stock closes, latest sessions | Yes | Last few sessions appended | — | Re-downloaded |
| Nifty 50 valuation, closed months | Never | Last 2 months only | — | Last 2 months only |
| Fundamentals | Quarterly results, restatements | Reused | Re-downloaded | Re-downloaded |
| Constituent lists | Rebalances (Mar/Sep) | Reused (Wishlist re-read from the repo) | Re-downloaded | Re-downloaded |

**How the daily mode works** (per index and per stock):

1. Pick the shortest Yahoo range that reaches back to the last stored session: `5d`, `1mo` or `3mo`. If the last run was more than ~80 days ago, download in full.
2. Compare the fresh closes with the stored closes for the sessions both have. A difference over 0.5% means the history was restated (a 1:10 bonus is −9%, a 1:1 split −50%), so that symbol is re-downloaded in full; its stored fundamentals are kept. The most recent stored session is allowed 5%, because it often came from Yahoo's provisional end-of-day price, which can move once the close is final.
3. Otherwise append the new sessions, recompute the months they fall in, and trim to the kept window (indices: 300 sessions, 72 months; stocks: 260 sessions, 121 months; Nifty 50: full monthly history, from Sep 2007).
4. Symbols with no stored copy are downloaded in full, with fundamentals.

Requests per run: daily about 325 small chart requests (one per index and stock); weekly about 305 (constituent lists plus one fundamentals request per stock); full about 630. Requests are spaced 0.3–0.4 s apart.

**Resilience**: if an index, a stock or a constituent list fails to download, the previous run's copy is kept and the failure is listed in the manifest. The run aborts only if the Nifty 50 cannot be fetched and no previous copy exists.

**Publishing (`derive_v5.py`)**: the dataset is split into the files above and each file's SHA-256 is compared with the last published one. Only changed files get a new version and are rewritten; files that left the window are deleted; if nothing changed (a holiday), the manifest version stays and nothing is committed. Daily data is kept from the first day of a month (`build_dataset.py`, 14 full months plus the current one), so a closed month's file never changes and the oldest is dropped whole. On a normal day `analytics`, the current month, the current year and the manifest change: about 115 KB compressed (schema 4: about 600 KB). A split or bonus re-publishes every file that holds that stock's history.

### Sync engine (`DatasetSync`)

1. **First launch:** `ensurePrimary()` loads the primary files from the copy bundled in the APK, then the history files in the background. No network is needed. Data stored by an older schema is cleared and reloaded first.
2. **After the first frame:** `refresh()` downloads the manifest and fetches only the files whose version is newer than the stored one. `PartMerger` merges month and year files into the stored series by date (a re-published file replaces those dates), and daily data before `dailyFrom` is dropped.
3. **Integrity:** each file's SHA-256 is checked against the manifest before it is stored; all primary files are written in one transaction.
4. **Caching:** OkHttp's 20 MB HTTP cache revalidates the manifest by ETag, so an unchanged manifest costs a 304.
5. **Failure:** any error is logged (Data page) and the stored data stays in use.
6. **Manual controls (Data page):** check now, reload everything, reset to the bundled copy.

### Portfolio

Holdings come from a broker backup (JSON) or holdings workbook (XLSX) that the user imports; both are parsed on the device and stored in app-private storage. Nothing about the portfolio is uploaded.

Stocks in the portfolio are matched to the dataset. A holding the dataset doesn't cover is fetched on the device from Yahoo Finance (NSE `.NS`, then BSE `.BO`) and cached in `holding-stocks.json` for 6 hours. Requests run one at a time, 300 ms apart.

## Offline mode

**Yes, the app works offline.**

- The APK ships with the full dataset (as of its release date), so even a first launch with no connection shows every index, stock and analysis.
- All screens read from Room / local files, never directly from the network.
- When offline, the update check and the holding-stock fetch fail quietly: the error is logged and the last stored data keeps being shown.
- Data simply ages until the next successful sync; the Data page shows the as-of date and the last check.

What does need a connection: newer data than the stored copy, prices for portfolio stocks outside the dataset that have never been fetched (they show as untracked until fetched), and the "run pipeline" link on the Data page, which opens GitHub in the browser.

## Network calls from the app

The only permission is `INTERNET`. There are no analytics, crash-reporting or advertising SDKs, and no authentication or API keys.

| When | Request | Host | Sends |
|---|---|---|---|
| App start, and Data page "check now" | `GET shared-data/v5/manifest.json` | raw.githubusercontent.com | Nothing beyond the request itself |
| Only when the manifest lists a newer file | `GET shared-data/v5/<file>.json` (usually analytics, the current month and the current year) | raw.githubusercontent.com | Nothing |
| App start, if portfolio holds stocks outside the dataset and their cache is over 6 h old | `GET /v8/finance/chart/{SYMBOL}.NS` (or `.BO`), then `/ws/fundamentals-timeseries/v1/finance/timeseries/{SYMBOL}` | query1.finance.yahoo.com | The ticker symbol only (no quantities or values) |
| User checks tickers in a wishlist request | `GET /v8/finance/chart/{SYMBOL}.NS` (then `.BO`, `-SM.NS`) | query1.finance.yahoo.com | The ticker only |
| User taps the pipeline link | Opens the GitHub Actions page in the browser | github.com | — |

**Offline mode** (Customize page) turns the Yahoo Finance lookups off: holdings outside the dataset then show no market data, and the app lists them with a "Request these stocks" button.

**Wishlist requests.** Under the Wishlist chip (and from the missing-stocks notes) users enter tickers. The app checks each one: format, already in the dataset or already requested, then one Yahoo chart call (an INR equity with a price; NSE, then BSE, then the SME series). The listed ones go to the developer in a pre-filled email (`wishlist.adminEmail` in `local.properties`; without it, the share sheet), and the device remembers them as "Requested" until they appear in the dataset. The developer approves by running the **Add to wishlist** workflow with the line from the email: it checks the tickers again, adds name, industry and Yahoo symbol to `tools/wishlist.csv`, commits, and starts a daily run that backfills and publishes them. Holdings are matched to the dataset by listing symbol, so broker symbols with an NSE series suffix (TECHD-SM, TMCV-BL) count as covered.

Yahoo Finance is a public but unofficial, undocumented API. It needs no key, but it can change or rate-limit without notice; the app treats it as best effort and falls back to cached values.

## Key decisions

- **Static files instead of a backend:** a public GitHub repo serves the dataset for free, with versioned, verifiable parts and no server to run.
- **Precompute on the pipeline, not the phone:** heavy analytics are done once a day; the phone only does per-stock and portfolio calculations.
- **Bundled seed:** guarantees a useful first launch and full offline use.
- **Device-side fetch only for gaps:** keeps the published dataset generic while still covering any stock a user holds.

## Build and release

- Kotlin, Compose, Hilt, Room, kotlinx.serialization, Retrofit/OkHttp; minSdk 26, targetSdk 37.
- `dataset.manifestUrl` and `dataset.pipelineUrl` in `gradle.properties` become `BuildConfig` fields.
- Release: bump the version, refresh `assets/seed` from `shared-data/v5`, run the unit tests (which also validate the published dataset), build a signed APK, publish a GitHub release.
