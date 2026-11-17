"""Index report from the published dataset, in Markdown.

    py -3 .claude/skills/index-analysis/scripts/index_report.py "NIFTY 50"
    py -3 .claude/skills/index-analysis/scripts/index_report.py "NIFTY BANK" --years 10
    py -3 .claude/skills/index-analysis/scripts/index_report.py --compare            # every index
    py -3 .claude/skills/index-analysis/scripts/index_report.py --compare "NIFTY IT" "NIFTY BANK" "NIFTY 50"

Return and momentum figures are the app's (precomputed in analytics.json by tools/derive_parts.py);
valuation percentiles, drawdowns, seasonality and breadth are worked out here from the same data.
"""
from __future__ import annotations

import argparse
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools"))

import market_data as md  # noqa: E402
from market_data import INDEX_PERIODS, PERIOD_LABELS, pct, num, table  # noqa: E402

MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def monthly_returns(monthly: list[dict], as_of: str) -> list[tuple[str, float]]:
    """(month, return) for completed months: the bar for the as-of month is month-to-date."""
    out = []
    for a, b in zip(monthly, monthly[1:]):
        if b["d"][:7] == as_of[:7] and (b.get("last") or "") < as_of[:7] + "-28":
            continue
        out.append((b["d"], b["c"] / a["c"] - 1))
    return out


def header(ix: dict, as_of: str) -> list[str]:
    d = ix["daily"]
    last, prev = (d[-1], d[-2]) if len(d) >= 2 else (d[-1] if d else None, None)
    out = [f"## {ix['name']} ({ix['symbol']})", "", f"{ix['category'].title()} index · data as of {as_of}", ""]
    if last:
        rows = [["Close", f"{num(last['c'])} on {last['d']}"]]
        if prev:
            rows.append(["Change", f"{num(last['c'] - prev['c'])} ({pct(last['c'] / prev['c'] - 1)})"])
        if last.get("o") is not None:
            rows.append(["Open · High · Low", f"{num(last['o'])} · {num(last['h'])} · {num(last['l'])}"])
        out += [table(["", ""], rows), ""]
    return out


def returns_section(ix: dict, nifty: dict | None) -> list[str]:
    r = ix["metrics"].get("returns", {})
    nr = (nifty or {}).get("metrics", {}).get("returns", {}) if nifty and nifty["symbol"] != ix["symbol"] else {}
    headers = ["Period", ix["name"]] + (["Nifty 50", "vs Nifty 50"] if nr else [])
    rows = []
    for p in INDEX_PERIODS:
        row = [PERIOD_LABELS[p], pct(r.get(p))]
        if nr:
            a, b = r.get(p), nr.get(p)
            row += [pct(b), "—" if a is None or b is None else f"{(a - b) * 100:+.1f} pp"]
        rows.append(row)
    return ["### Returns", "", table(headers, rows), ""]


def momentum_section(ix: dict) -> list[str]:
    m = ix["metrics"]
    if not m:
        return []
    # Same rule as the app (IndexAnalytics.trend): price above both averages = up, below both = down.
    a, b = m.get("vsDma50"), m.get("vsDma200")
    trend = "—" if a is None or b is None else "Uptrend" if a > 0 and b > 0 else "Downtrend" if a < 0 and b < 0 else "Mixed"
    cross = "—"
    if m.get("dma50") and m.get("dma200"):
        cross = "50-DMA above 200-DMA" if m["dma50"] > m["dma200"] else "50-DMA below 200-DMA"
    rsi = m.get("rsi14")
    rsi_note = "" if rsi is None else (" · overbought" if rsi >= 70 else " · oversold" if rsi <= 30 else "")
    rows = [["Trend (price vs 50/200-DMA)", trend], ["Averages", cross],
            ["RSI (14)", f"{num(rsi, 1)}{rsi_note}"],
            ["vs 50-DMA", f"{pct(m.get('vsDma50'))} (50-DMA {num(m.get('dma50'))})"],
            ["vs 200-DMA", f"{pct(m.get('vsDma200'))} (200-DMA {num(m.get('dma200'))})"],
            ["52W high / low", f"{num(m.get('high52w'))} / {num(m.get('low52w'))}"],
            ["From 52W high / low", f"{pct(m.get('fromHigh52w'))} / {pct(m.get('fromLow52w'))}"],
            ["Volatility 1M / 1Y (ann.)", f"{pct(m.get('volatility1m'), False)} / {pct(m.get('volatility1y'), False)}"]]
    if m.get("relativeVolume") is not None:
        rows.append(["Volume vs 20-day avg", f"{m['relativeVolume']:.2f}x (latest {m.get('latestVolumeDate')})"])
    return ["### Momentum and risk", "", table(["", ""], rows), ""]


def valuation_section(ix: dict, years: int) -> list[str]:
    v = ix["valuation"]
    if not v:
        return []
    rows = []
    for key, label in (("pe", "P/E"), ("pb", "P/B"), ("dy", "Dividend yield %")):
        series = [p[key] for p in v if p.get(key) is not None]
        if not series:
            continue
        now = series[-1]
        recent = series[-years * 12:]
        z = (now - statistics.mean(series)) / statistics.pstdev(series) if len(series) > 2 and statistics.pstdev(series) else None
        rows.append([label, num(now), num(md.median(series)), f"{num(min(series))} – {num(max(series))}",
                     pct(md.percentile_rank(series, now), False, 0), pct(md.percentile_rank(recent, now), False, 0),
                     "—" if z is None else f"{z:+.1f}"])
    return ["### Valuation", "",
            f"Month-end history since {v[0]['d'][:7]}. Percentile = share of months at or below today's level "
            f"(high P/E percentile = expensive vs its own history; high dividend-yield percentile = cheap).", "",
            table(["Measure", "Now", "Median", "Range", "Percentile (all)", f"Percentile ({years}Y)", "z-score"], rows), ""]


def drawdown_section(ix: dict, as_of: str) -> list[str]:
    mo = [b for b in ix["monthly"] if b.get("c") is not None]
    if len(mo) < 13:
        return []
    peak = max(mo, key=lambda b: b["c"])
    last = ix["daily"][-1]["c"] if ix["daily"] else mo[-1]["c"]
    dd, p, t = md.max_drawdown(mo)
    rows = [[f"High since {mo[0]['d'][:7]} (month-end close)", f"{num(peak['c'])} ({peak['d'][:7]})", pct(last / peak["c"] - 1)]]
    if dd is not None:
        rows.append([f"Worst drawdown since {mo[0]['d'][:7]} (month-ends)", f"{p[:7]} → {t[:7]}", pct(dd)])
    since = str(int(as_of[:4]) - 10) + as_of[4:7]
    if mo[0]["d"] < since:
        dd10, p10, t10 = md.max_drawdown([b for b in mo if b["d"] >= since])
        if dd10 is not None:
            rows.append(["Worst drawdown, last 10 years", f"{p10[:7]} → {t10[:7]}", pct(dd10)])
    return ["### Drawdowns", "", table(["", "When", "Value"], rows), ""]


def seasonality_section(ix: dict, as_of: str, years: int) -> list[str]:
    rets = monthly_returns(ix["monthly"], as_of)
    if len(rets) < 24:
        return []
    cutoff = str(int(as_of[:4]) - years)
    recent = [(d, r) for d, r in rets if d[:4] > cutoff]
    by_month = defaultdict(list)
    for d, r in recent:
        by_month[int(d[5:7]) - 1].append(r)
    rows = [[MONTHS[i], pct(statistics.mean(rs)), pct(md.median(rs)), f"{sum(r > 0 for r in rs)} / {len(rs)}",
             pct(max(rs)), pct(min(rs))] for i, rs in sorted(by_month.items())]
    by_year = defaultdict(list)
    for d, r in rets:
        by_year[d[:4]].append(r)
    # Year returns from year-end closes; the current year runs to the latest close (month to date included).
    year_end = {}
    for bar in ix["monthly"]:
        year_end[bar["d"][:4]] = bar["c"]
    if ix["daily"]:
        year_end[ix["daily"][-1]["d"][:4]] = ix["daily"][-1]["c"]
    yrows = []
    for y in sorted(year_end)[-years:]:
        prev = year_end.get(str(int(y) - 1))
        if prev is None:
            continue
        label = y + (" (to date)" if y == as_of[:4] else "")
        yrows.append([label, pct(year_end[y] / prev - 1), f"{sum(r > 0 for r in by_year[y])} / {len(by_year[y])}"])
    return ["### Seasonality", "",
            f"Completed months over the last {years} years ({recent[0][0][:7]} to {recent[-1][0][:7]}).", "",
            table(["Month", "Average", "Median", "Up months", "Best", "Worst"], rows), "",
            "**Calendar years** (up months counts completed months only)", "", table(["Year", "Return", "Up months"], yrows), ""]


def breadth_section(ds: md.Dataset, symbol: str, period: str) -> list[str]:
    if symbol not in md.UNIVERSES:
        return []
    stocks = ds.stocks(symbol)
    m = lambda s, k: s["metrics"].get(k)  # noqa: E731
    up = sum(1 for s in stocks if (m(s, "change1d") or 0) > 0)
    down = sum(1 for s in stocks if (m(s, "change1d") or 0) < 0)
    above = [s for s in stocks if m(s, "vsDma200") is not None]
    bench = ds.benchmark(symbol)
    beat = [s for s in stocks if s["metrics"].get("returns", {}).get(period) is not None]
    nbeat = sum(1 for s in beat if s["metrics"]["returns"][period] > (bench.get(period) or 0))
    rows = [["Latest session", f"{up} up · {down} down"],
            ["Above 200-DMA", f"{sum(1 for s in above if m(s, 'vsDma200') > 0)} of {len(above)}"],
            ["RSI above 70 / below 30", f"{sum(1 for s in stocks if (m(s, 'rsi14') or 0) > 70)} / {sum(1 for s in stocks if (m(s, 'rsi14') or 100) < 30)}"],
            [f"Beat the index over {PERIOD_LABELS[period]}", f"{nbeat} of {len(beat)}"]]
    # Rough contribution to the latest session: market cap x change (the index itself is free-float weighted).
    total_cap = sum(s.get("marketCapCr") or 0 for s in stocks)
    contrib = sorted(((s["symbol"], (s.get("marketCapCr") or 0) / total_cap * (m(s, "change1d") or 0)) for s in stocks),
                     key=lambda x: x[1])
    lift = ", ".join(f"{s} ({c * 100:+.2f} pp)" for s, c in reversed(contrib[-5:]) if c > 0)
    drag = ", ".join(f"{s} ({c * 100:+.2f} pp)" for s, c in contrib[:5] if c < 0)
    sectors = sorted(ds.sector_stats(symbol, period), key=lambda s: -(s["averageReturn"] or -9))
    srows = [[s["sector"], s["stocks"], f"{s['outperformers']} / {s['measured']}", pct(s["averageReturn"])] for s in sectors]
    return ["### Breadth and constituents", "", table(["", ""], rows), "",
            f"Biggest lift in the latest session: {lift or '—'}  ",
            f"Biggest drag: {drag or '—'}  ",
            "_(approximate: weighted by full market cap; the index uses free-float weights)_", "",
            f"**Sectors over {PERIOD_LABELS[period]}** (equal-weighted average return; index {pct(bench.get(period))})", "",
            table(["Sector", "Stocks", "Beat index", "Average"], srows), ""]


def report(ds: md.Dataset, symbol: str, years: int, period: str) -> str:
    ix = ds.index(symbol, with_history=True)
    nifty = ds.index("NIFTY 50") if symbol != "NIFTY 50" and ix["category"] != "VOLATILITY" else None
    parts = header(ix, ds.as_of) + returns_section(ix, nifty) + momentum_section(ix) + valuation_section(ix, years) \
        + drawdown_section(ix, ds.as_of) + seasonality_section(ix, ds.as_of, years) + breadth_section(ds, symbol, period)
    return "\n".join(parts)


def compare(ds: md.Dataset, symbols: list[str]) -> str:
    rows = []
    nifty = ds.index("NIFTY 50")["metrics"].get("returns", {})
    for s in symbols:
        ix = ds.index(s)
        r = ix["metrics"].get("returns", {})
        rs = None if r.get("Y1") is None or nifty.get("Y1") is None else (r["Y1"] - nifty["Y1"]) * 100
        rows.append([ix["name"], ix["category"].title(), *[pct(r.get(p)) for p in ["D1", "M1", "M3", "YTD", "Y1", "Y3"]],
                     "—" if rs is None else f"{rs:+.1f} pp", num(ix["metrics"].get("rsi14"), 0), pct(ix["metrics"].get("vsDma200"))])
    rows.sort(key=lambda r: -float(r[6].rstrip("%")) if r[6] != "—" else 9e9)
    return "\n".join([f"## Index comparison · data as of {ds.as_of}", "", "Sorted by 1Y return.", "",
                      table(["Index", "Type", "1D", "1M", "3M", "YTD", "1Y", "3Y CAGR", "1Y vs Nifty 50", "RSI", "vs 200-DMA"], rows,
                            "ll" + "r" * 9)])


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("index", nargs="?", help='index symbol or name, e.g. "NIFTY 50", "bank", "midcap", "vix"')
    ap.add_argument("--compare", nargs="*", metavar="INDEX", help="returns table for these indices (all when none given)")
    ap.add_argument("--years", type=int, default=10, help="window for seasonality and recent valuation percentile")
    ap.add_argument("--period", default="Y1", choices=["M1", "M3", "M6", "YTD", "Y1", "Y3"], help="period for breadth and sectors")
    ap.add_argument("--data", default=str(md.DEFAULT_DIR), help="dataset directory (default shared-data/v4)")
    args = ap.parse_args()
    ds = md.Dataset(args.data)
    if args.compare is not None:
        syms = [ds.resolve_index(s) for s in args.compare] or [s for s in ds.index_symbols if s != "INDIA VIX"]
        print(compare(ds, syms))
    elif args.index:
        print(report(ds, ds.resolve_index(args.index), args.years, args.period))
    else:
        ap.print_help()
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
