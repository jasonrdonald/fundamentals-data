#!/usr/bin/env python3
"""Builds shared-data/dataset.json, the shared dataset the app reads, from public index data.

Sources (open endpoints, no login, no cookies):
  * Index OHLCV (daily + monthly): Yahoo Finance chart API
  * Nifty 50 constituents with NSE industry classification: NSE archives CSV

The dataset holds raw primary data only; the app computes performance, momentum and
volume analytics from it. Standard library only, so it runs anywhere Python 3.9+ exists.

Modes (--mode), each run by its own scheduled workflow:
  daily   prices only, incrementally: each index and stock asks Yahoo for the last few sessions and
          appends them to the stored copy. If the overlapping sessions disagree with what is stored
          (a split or bonus restates a stock's whole history), that one symbol is re-downloaded in
          full. Symbols with no stored copy (new index members) are backfilled. Fundamentals and
          constituent lists are reused from the stored copy.
  weekly  constituent lists and fundamentals; stored prices are reused.
  full    everything re-downloaded (10 years of prices), reconciling the stored copy with the
          source. Run monthly, and to build the dataset from scratch.

Usage:  python tools/build_dataset.py [--mode daily|weekly|full] [--out shared-data/dataset.json]
        then python tools/derive_parts.py (parts + precomputed analytics the app reads)
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

SCHEMA_VERSION = 3  # 3: compact stock price arrays on shared calendars; multiple stock universes
DAILY_BARS = 300      # ~14 months: prev day, 1Y return, 200-DMA, 52-week range
MONTHLY_BARS = 72     # 6 years of month-on-month data (5 full years + context)
USER_AGENT = "fundamentals-data-pipeline/1.0 (+https://github.com/jasonrdonald/fundamentals-data)"
ROOT = Path(__file__).resolve().parent.parent
MODES = ("daily", "weekly", "full")
# Stored and freshly fetched closes for the same session may differ by Yahoo's rounding or a late
# correction; a larger gap means the history was restated (split, bonus) and is re-downloaded.
RESTATE_TOLERANCE = 0.005
IST = timezone(timedelta(hours=5, minutes=30))


def http_get(url: str, attempts: int = 4) -> bytes:
    delay = 2.0
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read()
        except (urllib.error.URLError, TimeoutError) as e:
            status = getattr(e, "code", None)
            if attempt == attempts or status in (400, 401, 403, 404):
                raise
            print(f"  retry {attempt} for {url} ({e})", file=sys.stderr)
            time.sleep(delay)
            delay *= 2
    raise RuntimeError("unreachable")


def chart(yahoo_symbol: str, interval: str, range_: str) -> dict:
    """`range_` is a Yahoo range ("10y") or "all". Yahoo silently downgrades range=max to monthly
    bars, so full history is requested with an explicit period instead, which keeps daily bars."""
    span = (f"period1=946684800&period2={int(time.time())}" if range_ == "all" else f"range={range_}")
    url = (
        "https://query1.finance.yahoo.com/v8/finance/chart/"
        + urllib.parse.quote(yahoo_symbol, safe="")
        + f"?interval={interval}&{span}&includePrePost=false&events="
    )
    payload = json.loads(http_get(url))
    result = (payload.get("chart") or {}).get("result") or []
    if not result:
        raise ValueError(f"no chart data for {yahoo_symbol}: {payload.get('chart', {}).get('error')}")
    return result[0]


def local_date(ts: int, meta: dict) -> str:
    offset = timedelta(seconds=meta.get("gmtoffset", 19800))
    return (datetime.fromtimestamp(ts, tz=timezone.utc) + offset).strftime("%Y-%m-%d")


def session_open(yahoo_symbol: str) -> float | None:
    """Opening print of the latest session, from intraday bars."""
    r = chart(yahoo_symbol, "15m", "1d")
    opens = [o for o in r["indicators"]["quote"][0].get("open") or [] if o is not None]
    return opens[0] if opens else None


def last_session_complete(meta: dict, now: float) -> bool:
    """True when the session that produced `regularMarketTime` is over.

    `currentTradingPeriod` rolls over to the next day's session at midnight (exchange time), so
    "now is past the current period's end" alone is false overnight and on mornings before the
    close; the previous day's session must still count as finished then. A session is complete if
    it is from an earlier exchange-local day than now, or today's regular period has ended.
    """
    market_time = meta.get("regularMarketTime")
    if not market_time:
        return False
    if local_date(market_time, meta) < local_date(int(now), meta):
        return True
    end = ((meta.get("currentTradingPeriod") or {}).get("regular") or {}).get("end")
    return end is not None and now >= end


def fetch_daily(yahoo_symbol: str, range_: str = "10y") -> list[dict]:
    r = chart(yahoo_symbol, "1d", range_)
    meta = r.get("meta", {})
    quote = r["indicators"]["quote"][0]
    volumes = quote.get("volume") or []
    bars = []
    for i, ts in enumerate(r.get("timestamp") or []):
        o, h, l, c = (quote[k][i] for k in ("open", "high", "low", "close"))
        if c is None or o is None:
            continue  # holidays / missing prints
        vol = volumes[i] if i < len(volumes) else None
        bars.append({
            "d": local_date(ts, meta),
            "o": round(o, 2), "h": round(h if h is not None else max(o, c), 2),
            "l": round(l if l is not None else min(o, c), 2), "c": round(c, 2),
            "v": int(vol) if vol else None,
        })

    # Yahoo often leaves the most recent finished session empty in the series while its
    # metadata already carries the final close/high/low. Rebuild that bar once the session ended.
    market_time, price = meta.get("regularMarketTime"), meta.get("regularMarketPrice")
    if market_time and price and last_session_complete(meta, time.time()):
        day = local_date(market_time, meta)
        if not bars or bars[-1]["d"] < day:
            o = session_open(yahoo_symbol) or (bars[-1]["c"] if bars else price)
            h = meta.get("regularMarketDayHigh") or max(o, price)
            l = meta.get("regularMarketDayLow") or min(o, price)
            vol = meta.get("regularMarketVolume")
            bars.append({"d": day, "o": round(o, 2), "h": round(h, 2), "l": round(l, 2),
                         "c": round(price, 2), "v": int(vol) if vol else None})

    by_date = {b["d"]: b for b in bars}  # de-duplicate, keeping the latest copy
    return [by_date[d] for d in sorted(by_date)]


def drop_open_session(bars: list[dict]) -> list[dict]:
    """A bar dated today while the market is still open is incomplete: drop it."""
    today = datetime.now(IST)
    if bars and bars[-1]["d"] == today.strftime("%Y-%m-%d") and today.hour * 60 + today.minute < 15 * 60 + 35:
        return bars[:-1]
    return bars


def recent_range(last_stored: str) -> str | None:
    """The shortest Yahoo range that still overlaps the last stored session; None if too far back."""
    gap = (datetime.now(IST).date() - date.fromisoformat(last_stored)).days
    for days, range_ in ((4, "5d"), (25, "1mo"), (80, "3mo")):
        if gap <= days:
            return range_
    return None


def restated(stored: dict[str, float], fresh: dict[str, float]) -> bool | None:
    """True if overlapping closes disagree; None if there is no overlap to compare."""
    common = [d for d in fresh if d in stored and stored[d]]
    if not common:
        return None
    return any(abs(fresh[d] / stored[d] - 1) > RESTATE_TOLERANCE for d in common)


def monthly_from_daily(daily: list[dict]) -> list[dict]:
    """Month-on-month OHLCV aggregated from daily sessions (Yahoo's own monthly bars are unreliable)."""
    months: dict[str, list[dict]] = {}
    for b in daily:
        months.setdefault(b["d"][:7], []).append(b)
    out = []
    for key in sorted(months):
        bars = months[key]
        vols = [b["v"] for b in bars if b["v"]]
        out.append({
            "d": key + "-01",
            "o": bars[0]["o"], "h": max(b["h"] for b in bars), "l": min(b["l"] for b in bars),
            "c": bars[-1]["c"], "v": sum(vols) if vols else None,
            "sessions": len(bars), "last": bars[-1]["d"],
        })
    return out


def fetch_index(spec: dict, previous: dict | None) -> dict:
    if spec.get("nse"):
        return fetch_index_nse(spec, previous)
    full = bool(spec.get("fullHistory"))
    all_daily = drop_open_session(fetch_daily(spec["yahoo"], "all" if full else "10y"))
    monthly = monthly_from_daily(all_daily)
    if not full:
        monthly = monthly[-MONTHLY_BARS:]
    daily = all_daily[-DAILY_BARS:]
    if len(daily) < 30 or len(monthly) < 12:
        raise ValueError(f"insufficient history ({len(daily)} daily / {len(monthly)} monthly)")
    out = {
        "symbol": spec["symbol"],
        "name": spec["name"],
        "category": spec["category"],
        "source": "yahoo:" + spec["yahoo"],
        "daily": daily,
        "monthly": monthly,
    }
    if spec.get("valuation"):
        out["valuation"] = fetch_valuation(spec["valuation"], (previous or {}).get("valuation") or [], daily[-1]["d"])
        out["valuationSource"] = "NSE ind_close_all archive (month-end P/E, P/B, dividend yield)"
    return out


def update_index(spec: dict, previous: dict) -> dict | None:
    """
    Daily mode: appends the latest sessions to the stored index. None when a full download is
    needed instead (nothing stored, too long since the last run, or the stored closes disagree).
    """
    if spec.get("nse") or not previous.get("daily"):
        return None
    stored = {b["d"]: b for b in previous["daily"]}
    range_ = recent_range(max(stored))
    if range_ is None:
        return None
    fresh = drop_open_session(fetch_daily(spec["yahoo"], range_))
    check = restated({d: b["c"] for d, b in stored.items()}, {b["d"]: b["c"] for b in fresh})
    if check is not False:
        print(f"  {spec['symbol']}: {'no overlap' if check is None else 'history restated'}; downloading in full")
        return None
    merged = {**stored, **{b["d"]: b for b in fresh}}
    daily = [merged[d] for d in sorted(merged)]
    # Months the new sessions touch are re-aggregated from the stored sessions (which span them).
    touched = {b["d"][:7] for b in fresh}
    months = {m["d"][:7]: m for m in previous.get("monthly") or []}
    for m in monthly_from_daily([b for b in daily if b["d"][:7] in touched]):
        months[m["d"][:7]] = m
    monthly = [months[k] for k in sorted(months)]
    if not spec.get("fullHistory"):
        monthly = monthly[-MONTHLY_BARS:]
    out = {**previous, "name": spec["name"], "category": spec["category"],
           "daily": daily[-DAILY_BARS:], "monthly": monthly}
    if spec.get("valuation"):
        out["valuation"] = fetch_valuation(spec["valuation"], previous.get("valuation") or [], daily[-1]["d"])
    return out


# --- Month-end valuation (P/E, P/B, dividend yield) from NSE's daily index archive -------------
# One CSV per trading day lists every NSE index with its close, P/E, P/B and dividend yield.
# Files exist only for trading days, so the month-end value is the last day that has a file.

NSE_DAILY_URL = "https://archives.nseindia.com/content/indices/ind_close_all_{ddmmyyyy}.csv"


_NSE_DAY_CACHE: dict[date, list[dict] | None] = {}


def nse_day_rows(day: date) -> list[dict] | None:
    """Every index's row in NSE's archive for `day` (None on holidays / weekends). Cached per run."""
    if day not in _NSE_DAY_CACHE:
        url = NSE_DAILY_URL.format(ddmmyyyy=day.strftime("%d%m%Y"))
        try:
            text = http_get(url, attempts=2).decode("utf-8-sig", errors="replace")
            _NSE_DAY_CACHE[day] = list(csv.DictReader(io.StringIO(text)))
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            _NSE_DAY_CACHE[day] = None  # holiday / weekend: no file
        time.sleep(0.2)
    return _NSE_DAY_CACHE[day]


def _nse_row(day: date, names: list[str]) -> dict | None:
    wanted = {n.lower() for n in names}
    for row in nse_day_rows(day) or []:
        if (row.get("Index Name") or "").strip().lower() in wanted:
            return row
    return None


def _num(row: dict, key: str) -> float | None:
    try:
        v = float((row.get(key) or "").strip())
        return v if v > 0 else None
    except ValueError:
        return None


def nse_index_row(day: date, names: list[str]) -> dict | None:
    row = _nse_row(day, names)
    if row is None:
        return None
    return {"d": day.isoformat(), "pe": _num(row, "P/E"), "pb": _num(row, "P/B"), "dy": _num(row, "Div Yield")}


def nse_bar(day: date, names: list[str]) -> dict | None:
    """One session's OHLCV bar from NSE's archive. Older files print "-" for open/high/low."""
    row = _nse_row(day, names)
    c = _num(row, "Closing Index Value") if row else None
    if c is None:
        return None
    o = _num(row, "Open Index Value") or c
    h = _num(row, "High Index Value") or max(o, c)
    l = _num(row, "Low Index Value") or min(o, c)
    v = _num(row, "Volume")
    return {"d": day.isoformat(), "o": round(o, 2), "h": round(h, 2), "l": round(l, 2), "c": round(c, 2),
            "v": int(v) if v else None}


def fetch_index_nse(spec: dict, previous: dict | None) -> dict:
    """
    An index Yahoo has no history for, built from NSE's daily archive: the stored series plus the
    sessions since. The first run backfills DAILY_BARS sessions, and month ends before them, where
    a month's bar has only its closing value (open = previous month's close).
    """
    names = spec["nse"]
    today = datetime.now(IST).date()
    prev_daily = (previous or {}).get("daily") or []
    by_day = {b["d"]: b for b in prev_daily}
    start = date.fromisoformat(prev_daily[-1]["d"]) + timedelta(days=1) if prev_daily \
        else today - timedelta(days=int(DAILY_BARS * 1.5))
    day = start
    while day <= today:
        if day.weekday() < 5:
            b = nse_bar(day, names)
            if b:
                by_day[b["d"]] = b
        day += timedelta(days=1)
    daily = [by_day[d] for d in sorted(by_day)][-DAILY_BARS:]
    if len(daily) < 30:
        raise ValueError(f"insufficient history ({len(daily)} daily)")

    by_month = {m["d"][:7]: m for m in (previous or {}).get("monthly") or []}
    recent = monthly_from_daily(daily)
    for m in recent[1:]:  # the window's first month is usually partial
        by_month[m["d"][:7]] = m
    first = date.fromisoformat(recent[0]["d"])
    y, mo = first.year, first.month
    for _ in range(MONTHLY_BARS):
        y, mo = (y - 1, 12) if mo == 1 else (y, mo - 1)
        key = f"{y:04d}-{mo:02d}"
        if key in by_month:
            continue
        last = date(y + (mo == 12), mo % 12 + 1, 1) - timedelta(days=1)
        bar = None
        for back in range(12):
            d = last - timedelta(days=back)
            if d.weekday() < 5 and (bar := nse_bar(d, names)):
                break
        if bar is None:
            break  # before the index existed
        by_month[key] = {"d": key + "-01", "o": bar["c"], "h": bar["c"], "l": bar["c"], "c": bar["c"],
                         "v": None, "sessions": None, "last": bar["d"]}
    monthly = [by_month[k] for k in sorted(by_month)]
    # Month-end-only bars open at the previous month's close.
    for a, b in zip(monthly, monthly[1:]):
        if b.get("sessions") is None:
            b["o"] = a["c"]
            b["h"], b["l"] = max(b["o"], b["c"]), min(b["o"], b["c"])
    monthly = [m if m.get("sessions") is not None else {**m, "sessions": 0} for m in monthly][-MONTHLY_BARS:]
    if len(monthly) < 12:
        raise ValueError(f"insufficient history ({len(monthly)} monthly)")
    return {
        "symbol": spec["symbol"],
        "name": spec["name"],
        "category": spec["category"],
        "source": "nse:ind_close_all",
        "daily": daily,
        "monthly": monthly,
    }


def month_end_row(year: int, month: int, names: list[str], not_after: date) -> dict | None:
    """Walk back from the month's last day (or `not_after`) to the last trading day with a file."""
    last = (date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1))
    day = min(last, not_after)
    for _ in range(12):  # covers long holiday stretches
        if day.month != month:
            return None
        if day.weekday() < 5:
            row = nse_index_row(day, names)
            if row:
                return row
        day -= timedelta(days=1)
    return None


def fetch_valuation(cfg: dict, previous: list[dict], latest_session: str) -> list[dict]:
    """Month-end series, reusing stored months and re-fetching only the last two (which may still move)."""
    by_month = {v["d"][:7]: v for v in previous}
    latest = date.fromisoformat(latest_session)
    start_y, start_m = (int(x) for x in cfg["from"].split("-"))
    months = []
    y, m = start_y, start_m
    while (y, m) <= (latest.year, latest.month):
        months.append((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    refresh = set(months[-2:])
    fetched = 0
    for (y, m) in months:
        key = f"{y:04d}-{m:02d}"
        if key in by_month and (y, m) not in refresh:
            continue
        row = month_end_row(y, m, cfg["nseNames"], latest)
        fetched += 1
        if row and row.get("pe"):
            by_month[key] = row
    print(f"  valuation: {len(by_month)} months ({fetched} fetched)")
    return [by_month[k] for k in sorted(by_month)]


def fetch_constituents(cfg: dict) -> dict:
    """An NSE index list ("url") or a list kept in this repo ("file"), same columns."""
    if cfg.get("file"):
        text = (ROOT / cfg["file"]).read_text(encoding="utf-8-sig")
    else:
        text = http_get(cfg["url"]).decode("utf-8-sig")
    rows = list(csv.DictReader(io.StringIO(text)))
    constituents = [
        {"symbol": r["Symbol"].strip(), "name": r["Company Name"].strip(), "industry": r["Industry"].strip()}
        | ({"yahoo": r["Yahoo"].strip()} if (r.get("Yahoo") or "").strip() else {})
        # NSE lists placeholder rows ("DUMMY...") for pending index changes; they have no prices.
        for r in rows if r.get("Symbol") and not r["Symbol"].strip().upper().startswith("DUMMY")
    ]
    if len(constituents) < (1 if cfg.get("file") else 20):
        raise ValueError(f"unexpected constituent count {len(constituents)}")
    out = {
        "index": cfg["index"],
        "asOf": datetime.now(IST).strftime("%Y-%m-%d"),
        "source": cfg.get("url") or cfg["file"],
        "constituents": sorted(constituents, key=lambda c: c["symbol"]),
    }
    # A universe that is not itself an index (the wishlist) names the index it is measured against.
    # Its stocks may be new listings, so they need less history than index constituents.
    if cfg.get("file"):
        out["minSessions"] = 1
    for key in ("name", "benchmark"):
        if cfg.get(key):
            out[key] = cfg[key]
    return out


def load_existing(path: Path) -> dict | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        # Older schemas are still useful for reusing slowly-changing parts (e.g. valuation history).
        return data if (data.get("schemaVersion") or 0) >= 2 else None
    except (OSError, ValueError):
        return None


def content_key(dataset: dict) -> str:
    """Everything except bookkeeping fields, so unchanged data does not bump the version."""
    stripped = {k: v for k, v in dataset.items() if k not in ("version", "generatedAt", "schemaVersion")}
    comp = stripped.get("composition")
    if comp:
        stripped["composition"] = {k: v for k, v in comp.items() if k != "asOf"}
    if stripped.get("universes"):
        stripped["universes"] = [{k: v for k, v in u.items() if k != "asOf"} for u in stripped["universes"]]
    return json.dumps(stripped, sort_keys=True)


def build(config: dict, existing: dict | None, mode: str = "full") -> dict:
    previous = {i["symbol"]: i for i in (existing or {}).get("indices", [])}
    indices, failures = [], []
    for spec in config["indices"]:
        prev = previous.get(spec["symbol"])
        if mode == "weekly" and prev:
            indices.append(prev)  # prices belong to the daily job
            continue
        print(f"fetching {spec['symbol']} ({spec.get('yahoo') or 'NSE archive'})")
        try:
            updated = update_index(spec, prev) if mode == "daily" and prev else None
            indices.append(updated or fetch_index(spec, prev))
        except Exception as e:  # keep the last good copy of a single failing index
            failures.append(spec["symbol"])
            print(f"  FAILED {spec['symbol']}: {e}", file=sys.stderr)
            if spec["symbol"] in previous:
                indices.append(previous[spec["symbol"]])
        time.sleep(0.4)  # be polite to the public endpoint

    if not any(i["symbol"] == "NIFTY 50" for i in indices):
        raise SystemExit("NIFTY 50 could not be fetched and no previous copy exists; aborting")

    try:
        # Constituent lists change at rebalances; the daily job reuses the stored ones.
        if mode == "daily" and (existing or {}).get("composition"):
            composition = existing["composition"]
        else:
            composition = fetch_constituents(config["constituents"])
    except Exception as e:
        print(f"  FAILED constituents: {e}", file=sys.stderr)
        composition = (existing or {}).get("composition")

    # Stock universes: constituents of each configured index, benchmarked against that index.
    universes, stocks = [], None
    if config.get("stocks"):
        old_universes = {u["index"]: u for u in (existing or {}).get("universes") or []}
        for u in config["stocks"]["universes"]:
            try:
                # Lists kept in this repo are re-read every run (no download); NSE's only weekly/monthly.
                if mode == "daily" and u.get("url") and u["index"] in old_universes:
                    universes.append(old_universes[u["index"]])
                    continue
                universes.append(fetch_constituents(u))
            except Exception as e:
                print(f"  FAILED constituents {u['index']}: {e}", file=sys.stderr)
                if u["index"] in old_universes:
                    universes.append(old_universes[u["index"]])
        stocks = fetch_stocks(universes, (existing or {}).get("stocks"), failures, mode)

    latest = max(i["daily"][-1]["d"] for i in indices)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "version": 0,
        "updatedAt": latest,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Nifty index and constituent market data. Prices in index points / INR; financials in INR crore.",
        "indices": indices,
        "composition": composition,
        "universes": universes,
        "stocks": stocks,
        "failures": failures,
    }


# --- Constituent stocks ------------------------------------------------------------------------
STOCK_MONTHS = 121       # 10 years of month-on-month history (+1 base month)
STOCK_SESSIONS = 260     # ~1 year of daily closes: momentum, RSI, 200-DMA, volatility
CRORE = 1e7

FUNDAMENTAL_TYPES = [
    "annualTotalRevenue", "annualNetIncome", "annualOperatingIncome", "annualStockholdersEquity",
    "annualTotalDebt", "annualDilutedEPS", "quarterlyTotalRevenue", "quarterlyNetIncome",
    "quarterlyDilutedEPS", "trailingMarketCap", "trailingPeRatio", "trailingPbRatio",
]


def yahoo_symbol(nse_symbol: str) -> str:
    return nse_symbol + ".NS"


def fetch_fundamentals(yahoo: str) -> dict:
    """Annual (4y) and quarterly (up to 5q) statements plus trailing valuation, amounts in INR crore."""
    url = (
        "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"
        + urllib.parse.quote(yahoo, safe="")
        + "?type=" + ",".join(FUNDAMENTAL_TYPES)
        + f"&period1=1420070400&period2={int(time.time())}"
    )
    result = (json.loads(http_get(url)).get("timeseries") or {}).get("result") or []
    series: dict[str, dict[str, float]] = {}
    for r in result:
        t = r["meta"]["type"][0]
        series[t] = {p["asOfDate"]: p["reportedValue"]["raw"] for p in (r.get(t) or []) if p and p.get("reportedValue")}

    def latest(t: str) -> float | None:
        s = series.get(t) or {}
        return s[max(s)] if s else None

    def periods(prefix: str, fields: dict[str, tuple[str, bool]]) -> list[dict]:
        dates = sorted({d for f in fields.values() for d in (series.get(prefix + f[0]) or {})})
        rows = []
        for d in dates:
            row = {"d": d}
            for key, (t, money) in fields.items():
                v = (series.get(prefix + t) or {}).get(d)
                row[key] = None if v is None else round(v / CRORE, 2) if money else round(v, 2)
            rows.append(row)
        return rows

    cap = latest("trailingMarketCap")
    return {
        "marketCapCr": round(cap / CRORE, 1) if cap else None,
        "pe": round(latest("trailingPeRatio"), 2) if latest("trailingPeRatio") else None,
        "pb": round(latest("trailingPbRatio"), 2) if latest("trailingPbRatio") else None,
        "annual": periods("annual", {
            "revenue": ("TotalRevenue", True), "netIncome": ("NetIncome", True),
            "operatingIncome": ("OperatingIncome", True), "equity": ("StockholdersEquity", True),
            "debt": ("TotalDebt", True), "eps": ("DilutedEPS", False),
        }),
        "quarterly": periods("quarterly", {
            "revenue": ("TotalRevenue", True), "netIncome": ("NetIncome", True), "eps": ("DilutedEPS", False),
        }),
    }


def fetch_stock(constituent: dict, index: str, previous: dict | None = None, min_sessions: int = 60,
                fundamentals: dict | None = None) -> dict:
    """
    [previous] is the stock's stored copy (per-date maps): its closes are kept where Yahoo's series
    is shorter, so a listing Yahoo only quotes day by day (some SME stocks) builds up a history.
    [fundamentals], when given, are reused instead of fetched (the daily job re-downloading prices).
    """
    yahoo = constituent.get("yahoo") or yahoo_symbol(constituent["symbol"])
    all_daily = drop_open_session(fetch_daily(yahoo, "10y"))
    daily = {**((previous or {}).get("_daily") or {}), **{b["d"]: b["c"] for b in all_daily}}
    monthly = {**((previous or {}).get("_monthly") or {}), **{m["d"]: m["c"] for m in monthly_from_daily(all_daily)}}
    if len(daily) < min_sessions:
        raise ValueError(f"insufficient history ({len(daily)} sessions)")
    time.sleep(0.3)
    return {
        "symbol": constituent["symbol"],
        "name": constituent["name"],
        "industry": constituent["industry"],
        "index": index,
        "source": "yahoo:" + yahoo,
        # Expanded to the shared calendars in fetch_stocks().
        "_monthly": monthly,
        "_daily": {d: daily[d] for d in sorted(daily)[-STOCK_SESSIONS - 20:]},
        "fundamentals": fundamentals if fundamentals is not None else fetch_fundamentals(yahoo),
    }


def update_stock(constituent: dict, index: str, stored: dict, min_sessions: int) -> dict | None:
    """
    Daily mode: appends the latest closes to the stored stock (per-date maps). None when the stock
    must be downloaded in full: too long since the last run, no overlap, or restated history.
    """
    daily = dict(stored.get("_daily") or {})
    if not daily:
        return None
    range_ = recent_range(max(daily))
    if range_ is None:
        return None
    yahoo = constituent.get("yahoo") or yahoo_symbol(constituent["symbol"])
    fresh = drop_open_session(fetch_daily(yahoo, range_))
    closes = {b["d"]: b["c"] for b in fresh}
    check = restated(daily, closes)
    if check is not False:
        print(f"  {constituent['symbol']}: {'no overlap' if check is None else 'history restated'}; downloading in full")
        return None
    daily.update(closes)
    monthly = dict(stored.get("_monthly") or {})
    # [fresh] holds every session since the last stored one, so each month's last close in it is current.
    monthly.update({m["d"]: m["c"] for m in monthly_from_daily(fresh)})
    if len(daily) < min_sessions:
        raise ValueError(f"insufficient history ({len(daily)} sessions)")
    return {**stored, "symbol": constituent["symbol"], "name": constituent["name"],
            "industry": constituent["industry"], "index": index, "source": "yahoo:" + yahoo,
            "_monthly": monthly, "_daily": {d: daily[d] for d in sorted(daily)[-STOCK_SESSIONS - 20:]}}


def _expand(item: dict, months: list[str], sessions: list[str]) -> dict:
    """Old compact item -> per-date maps, so a failed stock can reuse its previous copy."""
    if "_monthly" in item:
        return item
    old_months = item.get("_months_ref") or []
    return {**item,
            "_monthly": {d: c for d, c in zip(old_months, item.get("mc") or []) if c is not None},
            "_daily": {d: c for d, c in zip(item.get("_sessions_ref") or [], item.get("dc") or []) if c is not None}}


def fetch_stocks(universes: list[dict], previous: dict | None, failures: list[str], mode: str = "full") -> dict:
    """
    Compact layout: one shared month list and one shared session list; each stock stores only
    its closes aligned to them (null before listing / when not traded). Keeps 250 stocks small.
    """
    before = {}
    if previous and previous.get("months") is not None:
        for it in previous.get("items", []):
            before[it["symbol"]] = {**it, "_months_ref": previous["months"], "_sessions_ref": previous["sessions"]}
    items = []
    for u in universes:
        for c in u["constituents"]:
            stored = _expand(before[c["symbol"]], [], []) if c["symbol"] in before else None
            min_sessions = u.get("minSessions", 60)
            # Only repo-kept lists (minSessions set) build on stored closes in a full download;
            # index lists take Yahoo's series as is.
            merge = stored if "minSessions" in u else None
            print(f"fetching stock {c['symbol']} ({u['index']}, {mode if stored else 'new'})")
            try:
                if stored is None or mode == "full":
                    items.append(fetch_stock(c, u["index"], merge, min_sessions))
                elif mode == "weekly":
                    yahoo = c.get("yahoo") or yahoo_symbol(c["symbol"])
                    items.append({**stored, "name": c["name"], "industry": c["industry"], "index": u["index"],
                                  "fundamentals": fetch_fundamentals(yahoo)})
                else:
                    items.append(update_stock(c, u["index"], stored, min_sessions)
                                 or fetch_stock(c, u["index"], None, min_sessions, stored.get("fundamentals")))
            except Exception as e:  # keep the last good copy of a failing stock
                failures.append("stock:" + c["symbol"])
                print(f"  FAILED {c['symbol']}: {e}", file=sys.stderr)
                if c["symbol"] in before:
                    items.append({**_expand(before[c["symbol"]], [], []), "industry": c["industry"], "name": c["name"], "index": u["index"]})
            time.sleep(0.3)

    months = sorted({d for it in items for d in it["_monthly"]})[-STOCK_MONTHS:]
    sessions = sorted({d for it in items for d in it["_daily"]})[-STOCK_SESSIONS:]
    out = []
    for it in items:
        mc = [it["_monthly"].get(m) for m in months]
        dc = [it["_daily"].get(d) for d in sessions]
        out.append({k: v for k, v in it.items() if not k.startswith("_") and k not in ("mc", "dc")} | {"mc": mc, "dc": dc})
    return {"currency": "INR", "months": months, "sessions": sessions, "items": out}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=MODES, default="full",
                        help="daily: recent prices only; weekly: constituents + fundamentals; full: everything")
    parser.add_argument("--config", default=str(ROOT / "tools" / "indices.json"))
    parser.add_argument("--out", default=str(ROOT / "shared-data" / "dataset.json"))
    parser.add_argument("--seed", help="also copy the result here (the app now bundles the derived parts instead; see derive_parts.py --seed-dir)")
    args = parser.parse_args()

    out = Path(args.out)
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    existing = load_existing(out)
    if args.mode != "full" and not existing:
        print(f"no stored dataset; running a full build instead of {args.mode}")
        args.mode = "full"
    dataset = build(config, existing, args.mode)

    if existing and content_key(existing) == content_key(dataset):
        print(f"no data changes; staying at version {existing['version']}")
        return 0
    # Versions must only ever increase, even across schema changes, or apps ignore the file.
    try:
        last_version = int(json.loads(out.read_text(encoding="utf-8")).get("version", 0))
    except (OSError, ValueError):
        last_version = 0
    dataset["version"] = last_version + 1

    encoded = json.dumps(dataset, separators=(",", ":"), ensure_ascii=False)
    out.write_text(encoded + "\n", encoding="utf-8")
    if args.seed:
        Path(args.seed).write_text(encoded + "\n", encoding="utf-8")
    print(f"wrote version {dataset['version']} ({len(dataset['indices'])} indices, "
          f"latest session {dataset['updatedAt']}, {len(encoded) // 1024} KB) -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
