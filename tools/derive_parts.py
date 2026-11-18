#!/usr/bin/env python3
"""
Derives the app's progressive-load files (schema 4) from shared-data/dataset.json.

No network access: this only reshapes and precomputes. Output (shared-data/v4/):

  manifest.json   tiny; part versions, sizes, SHA-256 and the update timeline. Apps fetch it first.
  analytics.json  precomputed "analysis format" data: per-index and per-stock metrics, benchmark
                  returns, sector group-bys (universe x period x sector) and calendar tables.
  core.json       recent data: index metadata, 300 daily sessions and the last 13 months,
                  constituents, stock metadata and the last 13 month-end closes.
  history.json    everything older: full monthly history, valuation history, 10 years of stock
                  month-end closes, a year of stock daily closes and financials.

Apps load analytics + core first (every screen works), then history in the background (enables
3Y-10Y charts, year tables and financials). dataset.json (schema 3) is left untouched for older
app versions.

The metric formulas mirror the app's Kotlin calculators (IndexAnalyticsCalculator,
StockAnalyticsCalculator, StockReports, CalendarTables); the app's AnalyticsParityTest recomputes
everything from the bundled files and fails if the two ever disagree.

Usage:
  py -3 tools/derive_parts.py [--dataset shared-data/dataset.json] [--out shared-data/v4]
                              [--seed-dir app/src/main/assets/seed]
"""
from __future__ import annotations

import argparse
import calendar
import hashlib
import json
import math
import shutil
import sys
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA_VERSION = 4
RECENT_MONTHS = 13
HISTORY_RUNS = 120
HEADLINE = "NIFTY 50"
TRADING_DAYS = 252

# Same names as the app's Period enum.
INDEX_PERIODS = {"D1": 1, "W1": 5, "M1": 21, "M3": 63, "M6": 126, "YTD": None, "Y1": 252, "Y3": None, "Y5": None}
STOCK_PERIODS = ["W1", "M1", "M3", "M6", "YTD", "Y1", "Y3"]
PART_ORDER = ["analytics", "core", "history"]
PART_INFO = {
    "analytics": "Precomputed metrics, sector aggregates and calendar tables",
    "core": "Latest 300 index sessions, last 13 months, constituents and stock list",
    "history": "Full monthly history, valuation history, stock daily closes and financials",
}


# ---------------------------------------------------------------------------- numeric helpers

def rnd(x: float | None, sig: int = 9) -> float | None:
    """Rounds to [sig] significant digits so files stay small (and stable across runs)."""
    if x is None or isinstance(x, bool):
        return x
    if not math.isfinite(x):
        return None
    return float(f"{x:.{sig}g}")


def at(values: list, i: int):
    """Kotlin getOrNull: no wrap-around for negative indices."""
    return values[i] if 0 <= i < len(values) else None


def relative(value, base):
    if value is None or base is None or base == 0:
        return None
    return value / base - 1


def ratio(a, b):
    if a is None or b is None or b == 0:
        return None
    return a / b


def growth(now, before):
    if now is None or before is None or before <= 0:
        return None
    return now / before - 1


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def sma(values: list[float], n: int) -> float | None:
    return None if len(values) < n else sum(values[-n:]) / n


def rsi(closes: list[float], n: int = 14) -> float | None:
    if len(closes) <= n:
        return None
    changes = [b - a for a, b in zip(closes, closes[1:])]
    gain = sum(max(c, 0.0) for c in changes[:n]) / n
    loss = sum(max(-c, 0.0) for c in changes[:n]) / n
    for c in changes[n:]:
        gain = (gain * (n - 1) + max(c, 0.0)) / n
        loss = (loss * (n - 1) + max(-c, 0.0)) / n
    if loss == 0.0:
        return 50.0 if gain == 0.0 else 100.0
    return 100 - 100 / (1 + gain / loss)


def annualised_volatility(closes: list[float]) -> float | None:
    if len(closes) < 3:
        return None
    logs = [math.log(b / a) if a > 0 and b > 0 else 0.0 for a, b in zip(closes, closes[1:])]
    m = sum(logs) / len(logs)
    var = sum((x - m) ** 2 for x in logs) / (len(logs) - 1)
    return math.sqrt(var) * math.sqrt(TRADING_DAYS)


def cagr(monthly: list[dict], last: float, years: int) -> float | None:
    base = at(monthly, len(monthly) - 1 - years * 12)
    if base is None:
        return None
    base = base["c"]
    if base <= 0 or last <= 0:
        return None
    return (last / base) ** (1.0 / years) - 1


def period_return(daily: list[dict], monthly: list[dict], period: str) -> float | None:
    closes = [b["c"] for b in daily]
    if not closes:
        return None
    last = closes[-1]
    if period == "YTD":
        year = daily[-1]["d"][:4]
        base = next((b["c"] for b in reversed(daily) if b["d"][:4] < year), None)
        return relative(last, base)
    if period == "Y3":
        return cagr(monthly, last, 3)
    if period == "Y5":
        return cagr(monthly, last, 5)
    n = INDEX_PERIODS[period]
    return relative(last, at(closes, len(closes) - 1 - n))


# ---------------------------------------------------------------------------- index analytics

def index_metrics(ix: dict) -> dict:
    daily, monthly = ix["daily"], ix["monthly"]
    closes = [b["c"] for b in daily]
    last = closes[-1] if closes else None
    window = daily[-TRADING_DAYS:]
    high52 = max((b["h"] for b in window), default=None)
    low52 = min((b["l"] for b in window), default=None)
    dma50, dma200 = sma(closes, 50), sma(closes, 200)
    volumes = [float(b["v"]) for b in daily if b.get("v") and b["v"] > 0]
    last_vol = next((b for b in reversed(daily) if (b.get("v") or 0) > 0), None)
    avg20 = mean(volumes[-20:])
    return {
        "symbol": ix["symbol"],
        "returns": {p: rnd(period_return(daily, monthly, p)) for p in INDEX_PERIODS},
        "rsi14": rnd(rsi(closes, 14)),
        "dma50": rnd(dma50),
        "dma200": rnd(dma200),
        "vsDma50": rnd(relative(last, dma50)),
        "vsDma200": rnd(relative(last, dma200)),
        "high52w": rnd(high52),
        "low52w": rnd(low52),
        "fromHigh52w": rnd(relative(last, high52)),
        "fromLow52w": rnd(relative(last, low52)),
        "latestVolume": last_vol["v"] if last_vol else None,
        "latestVolumeDate": last_vol["d"] if last_vol else None,
        "avgVolume20": rnd(avg20),
        "relativeVolume": rnd(last_vol["v"] / avg20) if last_vol and avg20 else None,
        "volumeTrend": rnd(ratio(mean(volumes[-63:]), mean(volumes[-TRADING_DAYS:]))),
        "volatility1m": rnd(annualised_volatility(closes[-22:])),
        "volatility1y": rnd(annualised_volatility(closes[-(TRADING_DAYS + 1):])),
    }


# ---------------------------------------------------------------------------- stock analytics

def series(dates: list[str], values: list) -> list[dict]:
    """Aligned closes -> bars (nulls dropped), like the app's PriceCodec packing."""
    return [{"d": d, "c": c} for d, c in zip(dates, values) if c is not None]


def days_between(a: str, b: str) -> int:
    return (date.fromisoformat(b) - date.fromisoformat(a)).days


def beta_alpha(stock_monthly: list[dict], bench_monthly: list[dict], months: int = 36):
    def returns(bars):
        return {b["d"]: b["c"] / a["c"] - 1 for a, b in zip(bars, bars[1:])}
    s, b = returns(stock_monthly), returns(bench_monthly)
    keys = sorted(set(s) & set(b))[-months:]
    if len(keys) < 12:
        return None, None
    pairs = [(s[k], b[k]) for k in keys]
    ms = sum(x for x, _ in pairs) / len(pairs)
    mb = sum(y for _, y in pairs) / len(pairs)
    cov = sum((x - ms) * (y - mb) for x, y in pairs)
    var_b = sum((y - mb) ** 2 for _, y in pairs)
    if var_b == 0:
        return None, None
    beta = cov / var_b
    return beta, (ms - beta * mb) * 12


def revenue_cagr(annual: list[dict]) -> float | None:
    first = next((a for a in annual if (a.get("revenue") or 0) > 0), None)
    last = next((a for a in reversed(annual) if (a.get("revenue") or 0) > 0), None)
    if first is None or last is None:
        return None
    years = days_between(first["d"], last["d"]) / 365.25
    if years < 1.5:
        return None
    return (last["revenue"] / first["revenue"]) ** (1 / years) - 1


def quarterly_yoy(quarterly: list[dict]) -> float | None:
    if not quarterly:
        return None
    latest = quarterly[-1]
    year_ago = next((q for q in quarterly if 350 <= days_between(q["d"], latest["d"]) <= 380), None)
    return growth(latest.get("revenue"), year_ago.get("revenue")) if year_ago else None


def stock_metrics(item: dict, months: list[str], sessions: list[str], bench: dict | None) -> dict:
    daily = series(sessions, item["dc"])
    monthly = series(months, item["mc"])
    closes = [b["c"] for b in daily]
    last = closes[-1] if closes else None
    f = item.get("fundamentals", {})
    annual = sorted(f.get("annual", []), key=lambda a: a["d"])
    quarterly = sorted(f.get("quarterly", []), key=lambda a: a["d"])
    la = annual[-1] if annual else {}
    pa = annual[-2] if len(annual) >= 2 else {}
    beta, alpha = beta_alpha(monthly, bench["monthly"] if bench else [])
    pe, pb = f.get("pe"), f.get("pb")
    pos = lambda v: v if v is not None and v > 0 else None  # noqa: E731
    return {
        "symbol": item["symbol"],
        "index": item["index"],
        "lastClose": rnd(last),
        "lastDate": daily[-1]["d"] if daily else None,
        "change1d": rnd(relative(last, at(closes, len(closes) - 2))),
        "returns": {p: rnd(period_return(daily, monthly, p)) for p in STOCK_PERIODS},
        "momentum12m1m": rnd(relative(closes[-22], closes[-253])) if len(closes) > 252 else None,
        "rsi14": rnd(rsi(closes, 14)),
        "vsDma200": rnd(relative(last, sma(closes, 200))),
        "fromHigh52w": rnd(relative(last, max(closes[-252:], default=None))),
        "volatility1y": rnd(annualised_volatility(closes[-253:])),
        "beta": rnd(beta),
        "alpha": rnd(alpha),
        "pe": pe,
        "pb": pb,
        "earningsYield": rnd(1 / pe) if pe is not None and pe > 0 else None,
        "revenueGrowth": rnd(growth(la.get("revenue"), pa.get("revenue"))),
        "revenueCagr": rnd(revenue_cagr(annual)),
        "epsGrowth": rnd(growth(la.get("eps"), pa.get("eps"))),
        "quarterlyRevenueYoY": rnd(quarterly_yoy(quarterly)),
        "roe": rnd(ratio(la.get("netIncome"), pos(la.get("equity")))),
        "netMargin": rnd(ratio(la.get("netIncome"), pos(la.get("revenue")))),
        "debtToEquity": rnd(ratio(la.get("debt"), pos(la.get("equity")))),
        "netProfitCr": la.get("netIncome"),
        "profitGrowth": rnd(growth(la.get("netIncome"), pa.get("netIncome"))),
    }


def sector_stats(stocks: list[dict], metrics: dict[str, dict], benchmarks: dict[str, dict]) -> list[dict]:
    """Group-by universe x period x sector, the numbers behind the app's Sectors view."""
    rows = []
    for universe in sorted({s["index"] for s in stocks}):
        members = [s for s in stocks if s["index"] == universe]
        for period in STOCK_PERIODS:
            bench = benchmarks.get(universe, {}).get(period)
            by_sector: dict[str, list[dict]] = {}
            for s in members:
                by_sector.setdefault(s.get("industry") or "Other", []).append(s)
            for sector, group in sorted(by_sector.items()):
                rets = [metrics[s["symbol"]]["returns"][period] for s in group]
                measured = [r for r in rets if r is not None]
                beat = sum(1 for r in measured if bench is not None and r - bench > 0)
                rows.append({
                    "universe": universe, "period": period, "sector": sector,
                    "stocks": len(group), "measured": len(measured), "outperformers": beat,
                    "averageReturn": rnd(mean(measured)), "benchmarkReturn": bench,
                })
    return rows


# ---------------------------------------------------------------------------- calendar tables

def last_weekday(month_start: str) -> date:
    d0 = date.fromisoformat(month_start)
    d = d0.replace(day=calendar.monthrange(d0.year, d0.month)[1])
    while d.weekday() >= 5:
        d = date.fromordinal(d.toordinal() - 1)
    return d


def is_month_end(d: date) -> bool:
    nxt = date.fromordinal(d.toordinal() + 1)
    while nxt.month == d.month:
        if nxt.weekday() < 5:
            return False
        nxt = date.fromordinal(nxt.toordinal() + 1)
    return True


def table(rows: list[list], month_avgs: list, year_avg, mtd) -> dict:
    return {"rows": rows, "monthAverages": [rnd(v) for v in month_avgs], "yearAverage": rnd(year_avg), "monthToDate": mtd}


def returns_calendar(monthly: list[dict], latest_session: str | None) -> dict:
    moves = []
    for i, b in enumerate(monthly):
        prev = monthly[i - 1] if i > 0 else None
        mtd = (i == len(monthly) - 1 and latest_session is not None and latest_session[:8] + "01" == b["d"]
               and date.fromisoformat(latest_session) < last_weekday(b["d"]))
        moves.append({"d": b["d"], "c": b["c"], "chg": relative(b["c"], prev["c"] if prev else None), "mtd": mtd})
    by_year: dict[int, list[dict]] = {}
    for m in moves:
        by_year.setdefault(int(m["d"][:4]), []).append(m)
    dec = {int(b["d"][:4]): b["c"] for b in monthly if b["d"][5:7] == "12"}
    rows = []
    for year in sorted(by_year, reverse=True):
        cells = [None] * 12
        for m in by_year[year]:
            cells[int(m["d"][5:7]) - 1] = rnd(m["chg"])
        last = max(by_year[year], key=lambda m: m["d"])
        base = dec.get(year - 1)
        rows.append([year, cells, rnd(last["c"] / base - 1) if base and base > 0 else None,
                     int(last["d"][5:7]) < 12 or last["mtd"]])
    mtd = next(([int(m["d"][:4]), int(m["d"][5:7])] for m in moves[-1:] if m["mtd"]), None)
    completed = [[None if mtd and r[0] == mtd[0] and i + 1 == mtd[1] else v for i, v in enumerate(r[1])] for r in rows]
    return table(rows, [mean([c[m] for c in completed if c[m] is not None]) for m in range(12)],
                 mean([r[2] for r in rows if not r[3] and r[2] is not None]), mtd)


def valuation_calendar(points: list[dict], key: str, latest_session: str | None) -> dict:
    pts = [p for p in points if p.get(key) is not None]
    by_year: dict[int, list[dict]] = {}
    for p in pts:
        by_year.setdefault(int(p["d"][:4]), []).append(p)
    year_end = {y: max(v, key=lambda p: p["d"]) for y, v in by_year.items()}
    rows = []
    for year in sorted(by_year, reverse=True):
        cells = [None] * 12
        for p in by_year[year]:
            cells[int(p["d"][5:7]) - 1] = p[key]
        end = year_end[year]
        base = year_end[year - 1][key] if year - 1 in year_end else None
        partial = int(end["d"][5:7]) < 12 or (latest_session is not None and end["d"] == latest_session
                                              and not is_month_end(date.fromisoformat(end["d"])))
        rows.append([year, cells, rnd(end[key] / base - 1) if base and base > 0 else None, partial])
    last = pts[-1] if pts else None
    mtd = ([int(last["d"][:4]), int(last["d"][5:7])]
           if last and latest_session and last["d"] == latest_session and not is_month_end(date.fromisoformat(last["d"]))
           else None)
    return table(rows, [mean([r[1][m] for r in rows if r[1][m] is not None]) for m in range(12)],
                 mean([r[2] for r in rows if not r[3] and r[2] is not None]), mtd)


# ---------------------------------------------------------------------------- parts

def columns(bars: list[dict], fields: list[str]) -> dict:
    """Bars -> one array per field (smaller and much faster to parse than objects)."""
    return {f: [b.get(f) for b in bars] for f in fields}


BAR_FIELDS = ["d", "o", "h", "l", "c", "v"]
MONTH_FIELDS = BAR_FIELDS + ["last"]


def build_parts(ds: dict) -> dict[str, dict]:
    indices = ds["indices"]
    by_symbol = {i["symbol"]: i for i in indices}
    stocks = ds.get("stocks") or {"months": [], "sessions": [], "items": []}
    months, sessions, items = stocks["months"], stocks["sessions"], stocks["items"]
    as_of = ds.get("updatedAt")

    benchmarks = {}
    for universe in sorted({s["index"] for s in items}):
        ix = by_symbol.get(universe)
        benchmarks[universe] = {p: rnd(period_return(ix["daily"], ix["monthly"], p)) if ix else None for p in STOCK_PERIODS}
    metrics = {s["symbol"]: stock_metrics(s, months, sessions, by_symbol.get(s["index"])) for s in items}

    calendars = []
    for ix in indices:
        latest = ix["daily"][-1]["d"] if ix["daily"] else None
        if ix["symbol"] == HEADLINE and len(ix["monthly"]) > 1:
            calendars.append({"symbol": ix["symbol"], "kind": "RETURNS", **returns_calendar(ix["monthly"], latest)})
        for kind, key in (("PE", "pe"), ("PB", "pb"), ("DIVIDEND_YIELD", "dy")):
            if any(v.get(key) is not None for v in ix.get("valuation", [])):
                calendars.append({"symbol": ix["symbol"], "kind": kind, **valuation_calendar(ix["valuation"], key, latest)})

    analytics = {
        "schemaVersion": SCHEMA_VERSION, "part": "analytics", "asOf": as_of,
        "indices": [index_metrics(ix) for ix in indices],
        "benchmarks": benchmarks,
        "stocks": list(metrics.values()),
        "sectors": sector_stats(items, metrics, benchmarks),
        "calendars": calendars,
    }

    recent_months = months[-RECENT_MONTHS:]
    core = {
        "schemaVersion": SCHEMA_VERSION, "part": "core", "asOf": as_of,
        "indices": [{
            "symbol": ix["symbol"], "name": ix["name"], "category": ix.get("category", "BROAD"), "source": ix.get("source"),
            "daily": columns(ix["daily"], BAR_FIELDS),
            "monthly": columns(ix["monthly"][-RECENT_MONTHS:], MONTH_FIELDS),
        } for ix in indices],
        "composition": ds.get("composition"),
        "universes": ds.get("universes", []),
        "stocks": {
            "months": recent_months,
            "items": [{
                "symbol": s["symbol"], "name": s["name"], "industry": s.get("industry", ""), "index": s["index"],
                "source": s.get("source"),
                "marketCapCr": s.get("fundamentals", {}).get("marketCapCr"),
                "pe": s.get("fundamentals", {}).get("pe"), "pb": s.get("fundamentals", {}).get("pb"),
                "mc": s["mc"][-RECENT_MONTHS:],
            } for s in items],
        },
    }

    history = {
        "schemaVersion": SCHEMA_VERSION, "part": "history", "asOf": as_of,
        "indices": [{
            "symbol": ix["symbol"],
            "monthly": columns(ix["monthly"], MONTH_FIELDS),
            "valuation": columns(ix.get("valuation", []), ["d", "pe", "pb", "dy"]),
        } for ix in indices],
        "stocks": {
            "months": months, "sessions": sessions,
            "items": [{
                "symbol": s["symbol"], "mc": s["mc"], "dc": s["dc"],
                "annual": s.get("fundamentals", {}).get("annual", []),
                "quarterly": s.get("fundamentals", {}).get("quarterly", []),
            } for s in items],
        },
    }
    return {"analytics": analytics, "core": core, "history": history}


def records(name: str, part: dict) -> int:
    """Rough row count shown in the app's data page."""
    if name == "analytics":
        return len(part["indices"]) + len(part["stocks"]) + len(part["sectors"]) + len(part["calendars"])
    if name == "core":
        return sum(len(i["daily"]["d"]) + len(i["monthly"]["d"]) for i in part["indices"]) + len(part["stocks"]["items"]) * (
            1 + len(part["stocks"]["months"]))
    return sum(len(i["monthly"]["d"]) + len(i["valuation"]["d"]) for i in part["indices"]) + sum(
        len(s["mc"]) + len(s["dc"]) + len(s["annual"]) + len(s["quarterly"]) for s in part["stocks"]["items"])


def encode(obj: dict) -> bytes:
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=str(ROOT / "shared-data" / "dataset.json"))
    ap.add_argument("--out", default=str(ROOT / "shared-data" / "v4"))
    ap.add_argument("--seed-dir", help="also copy the files here (the app's bundled offline data)")
    args = ap.parse_args()

    ds = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    manifest_path = out / "manifest.json"
    previous = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else None
    prev_parts = {p["name"]: p for p in (previous or {}).get("parts", [])}

    encoded = {name: encode(part) for name, part in build_parts(ds).items()}
    shas = {name: hashlib.sha256(data).hexdigest() for name, data in encoded.items()}
    changed = [n for n in PART_ORDER if prev_parts.get(n, {}).get("sha256") != shas[n]]
    version = (previous or {}).get("version", 0) + (1 if changed or previous is None else 0)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    if not changed and previous is not None:
        print(f"No part changed (manifest v{version}).")
    else:
        parts_meta = []
        for name in PART_ORDER:
            (out / f"{name}.json").write_bytes(encoded[name])
            parsed = json.loads(encoded[name])
            parts_meta.append({
                "name": name, "file": f"{name}.json",
                "version": version if name in changed else prev_parts[name]["version"],
                "bytes": len(encoded[name]), "sha256": shas[name], "records": records(name, parsed),
                "description": PART_INFO[name],
            })
        run = {
            "version": version, "datasetVersion": ds["version"], "generatedAt": now, "asOf": ds.get("updatedAt"),
            "changed": changed, "failures": ds.get("failures", []),
            "indices": len(ds["indices"]), "stocks": len((ds.get("stocks") or {}).get("items", [])),
        }
        manifest = {
            "schemaVersion": SCHEMA_VERSION, "version": version, "datasetVersion": ds["version"],
            "asOf": ds.get("updatedAt"), "generatedAt": now, "loadOrder": PART_ORDER, "parts": parts_meta,
            "history": ([run] + (previous or {}).get("history", []))[:HISTORY_RUNS],
        }
        manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        sizes = ", ".join(f"{p['name']} {p['bytes'] / 1024:.0f} KB" for p in parts_meta)
        print(f"Manifest v{version} (dataset v{ds['version']}, as of {ds.get('updatedAt')}); changed: {', '.join(changed)}; {sizes}")

    if args.seed_dir:
        seed = Path(args.seed_dir)
        seed.mkdir(parents=True, exist_ok=True)
        for name in ["manifest"] + PART_ORDER:
            shutil.copyfile(out / f"{name}.json", seed / f"{name}.json")
        print(f"Copied to {seed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
