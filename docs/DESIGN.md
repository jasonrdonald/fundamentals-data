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
│   → shared-data/v4/*.json     │──raw──▶│ Data: Room DB, DataStore, sync engine   │
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

Built by a scheduled GitHub Actions workflow (`.github/workflows/update-dataset.yml`) and committed to `shared-data/v4/`:

| File | Contents |
|---|---|
| `manifest.json` | Schema version, dataset version, as-of date, and a version + SHA-256 for each part |
| `analytics.json` | Precomputed index analytics |
| `core.json` | Indices, recent daily bars, stocks (Nifty 50, Next 50, Midcap 150, Wishlist) with fundamentals |
| `history.json` | Long monthly/daily history for charts and seasonality |

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
2. Compare the fresh closes with the stored closes for the sessions both have. A difference over 0.5% means the history was restated (a 1:10 bonus is −9%, a 1:1 split −50%), so that symbol is re-downloaded in full; its stored fundamentals are kept.
3. Otherwise append the new sessions, recompute the months they fall in, and trim to the kept window (indices: 300 sessions, 72 months; stocks: 260 sessions, 121 months; Nifty 50: full monthly history).
4. Symbols with no stored copy are downloaded in full, with fundamentals.

Requests per run: daily about 325 small chart requests (one per index and stock); weekly about 305 (constituent lists plus one fundamentals request per stock); full about 630. Requests are spaced 0.3–0.4 s apart.

**Resilience**: if an index, a stock or a constituent list fails to download, the previous run's copy is kept and the failure is listed in the manifest. The run aborts only if the Nifty 50 cannot be fetched and no previous copy exists.

**Publishing (`derive_parts.py`)**: the new dataset is split into `analytics`, `core` and `history`, and each part's SHA-256 is compared with the last published one. Only changed parts get a new part version; if nothing changed (a holiday), the manifest version stays and nothing is committed. Apps therefore download only changed parts, but a changed part is always replaced whole — the published files are full snapshots, not deltas.

### Sync engine (`DatasetSync`)

1. **First launch:** `ensurePrimary()` loads `analytics` + `core` from the copy bundled in the APK, then history in the background. No network is needed.
2. **After the first frame:** `refresh()` downloads the manifest and fetches only the parts whose version is newer than the stored one.
3. **Integrity:** each part's SHA-256 is checked against the manifest before it is stored; analytics and core are written in one transaction.
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
| App start, and Data page "check now" | `GET shared-data/v4/manifest.json` | raw.githubusercontent.com | Nothing beyond the request itself |
| Only when the manifest lists a newer part | `GET shared-data/v4/{analytics,core,history}.json` | raw.githubusercontent.com | Nothing |
| App start, if portfolio holds stocks outside the dataset and their cache is over 6 h old | `GET /v8/finance/chart/{SYMBOL}.NS` (or `.BO`), then `/ws/fundamentals-timeseries/v1/finance/timeseries/{SYMBOL}` | query1.finance.yahoo.com | The ticker symbol only (no quantities or values) |
| User taps the pipeline link | Opens the GitHub Actions page in the browser | github.com | — |

Yahoo Finance is a public but unofficial, undocumented API. It needs no key, but it can change or rate-limit without notice; the app treats it as best effort and falls back to cached values.

## Key decisions

- **Static files instead of a backend:** a public GitHub repo serves the dataset for free, with versioned, verifiable parts and no server to run.
- **Precompute on the pipeline, not the phone:** heavy analytics are done once a day; the phone only does per-stock and portfolio calculations.
- **Bundled seed:** guarantees a useful first launch and full offline use.
- **Device-side fetch only for gaps:** keeps the published dataset generic while still covering any stock a user holds.

## Build and release

- Kotlin, Compose, Hilt, Room, kotlinx.serialization, Retrofit/OkHttp; minSdk 26, targetSdk 37.
- `dataset.manifestUrl` and `dataset.pipelineUrl` in `gradle.properties` become `BuildConfig` fields.
- Release: bump the version, refresh `assets/seed` from `shared-data/v4`, run the unit tests (which also validate the published dataset), build a signed APK, publish a GitHub release.
