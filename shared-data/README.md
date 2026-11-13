# Shared dataset

Every install of the app downloads `dataset.json` from:

```
https://raw.githubusercontent.com/jasonrdonald/fundamentals-data/main/shared-data/dataset.json
```

**Don't edit it by hand.** [`tools/build_dataset.py`](../tools/build_dataset.py) generates it, and the [Update dataset](../.github/workflows/update-dataset.yml) workflow re-runs that script every weekday after market close.

Format (`schemaVersion` 2):
- `version`: increases whenever the data changes. Apps replace their copy when it does.
- `updatedAt`: the date of the latest completed session.
- `indices[]`: one entry per index, with `symbol`, `name` and `category`, plus:
  - `daily[]`: the last 300 sessions
  - `monthly[]`: 72 months built from daily sessions
  
  Each bar has the fields `d` (date), `o`, `h`, `l`, `c` and `v` (volume, or null if the index doesn't report it).
- `composition`: Nifty 50 constituents with their NSE industry.
- `failures`: indices that failed to update on the last run (their previous data is kept).

Prices are in index points. This repository is public.
