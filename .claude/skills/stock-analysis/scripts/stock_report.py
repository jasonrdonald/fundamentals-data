"""Stock report from the published dataset, in Markdown.

    py -3 .claude/skills/stock-analysis/scripts/stock_report.py HDFCBANK
    py -3 .claude/skills/stock-analysis/scripts/stock_report.py TCS INFY HCLTECH     # side by side
    py -3 .claude/skills/stock-analysis/scripts/stock_report.py --screen "NIFTY 50" --by roe --top 10
    py -3 .claude/skills/stock-analysis/scripts/stock_report.py --sector "Information Technology"

Numbers are the app's: metrics are precomputed in analytics.json by tools/derive_parts.py.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools"))

import market_data as md  # noqa: E402
from market_data import STOCK_MEASURES, STOCK_PERIODS, PERIOD_LABELS, pct, fmt, table  # noqa: E402


def rank_in(universe: list[dict], key: str, value, higher_better: bool) -> str:
    """'3 / 50' = 3rd best of the stocks in the universe with a value (best = 1)."""
    if value is None:
        return "—"
    if key in ("pe", "pb") and value <= 0:
        return "n/a (loss)"
    vals = [s["metrics"].get(key) for s in universe]
    vals = [v for v in vals if v is not None and not (key in ("pe", "pb") and v <= 0)]
    better = sum(1 for v in vals if (v > value if higher_better else v < value))
    return f"{better + 1} / {len(vals)}"


def price_stats(s: dict) -> list[list]:
    rows = []
    daily = s["daily"]
    if daily:
        last = daily[-1]["c"]
        hi = max(daily, key=lambda b: b["c"]); lo = min(daily, key=lambda b: b["c"])
        rows += [["52W high (close)", f"{md.num(hi['c'])} on {hi['d']}", pct(last / hi["c"] - 1)],
                 ["52W low (close)", f"{md.num(lo['c'])} on {lo['d']}", pct(last / lo["c"] - 1)]]
        dd, p, t = md.max_drawdown(daily)
        if dd is not None:
            rows.append(["Max drawdown (1Y daily)", f"{p} → {t}", pct(dd)])
    monthly = s["monthly"]
    if len(monthly) > 13:
        dd, p, t = md.max_drawdown(monthly)
        if dd is not None:
            rows.append([f"Max drawdown (month-ends since {monthly[0]['d'][:7]})", f"{p[:7]} → {t[:7]}", pct(dd)])
        for years in (5, 10):
            if len(monthly) > years * 12:
                base = monthly[-1 - years * 12]["c"]
                rows.append([f"{years}Y CAGR (month-ends)", f"since {monthly[-1 - years * 12]['d'][:7]}",
                             pct((monthly[-1]["c"] / base) ** (1 / years) - 1)])
    return rows


def financials(s: dict) -> str:
    out = []
    if s["annual"]:
        rows = []
        for a in s["annual"][-5:]:
            roe = a["netIncome"] / a["equity"] if a.get("netIncome") is not None and a.get("equity") else None
            de = a["debt"] / a["equity"] if a.get("debt") is not None and a.get("equity") else None
            margin = a["netIncome"] / a["revenue"] if a.get("netIncome") is not None and a.get("revenue") else None
            rows.append([a["d"], md.crore(a.get("revenue")), md.crore(a.get("netIncome")), pct(margin, False),
                         md.num(a.get("eps")), pct(roe, False), md.num(de)])
        out += ["**Annual** (fiscal years, ₹ crore)", "",
                table(["FY end", "Revenue", "Net profit", "Margin", "EPS", "ROE", "D/E"], rows)]
    if s["quarterly"]:
        rows = [[q["d"], md.crore(q.get("revenue")), md.crore(q.get("netIncome")), md.num(q.get("eps"))] for q in s["quarterly"][-6:]]
        out += ["", "**Quarterly**", "", table(["Quarter end", "Revenue", "Net profit", "EPS"], rows)]
    return "\n".join(out) if out else "_No financials in the dataset._"


def report(ds: md.Dataset, symbol: str) -> str:
    s = ds.stock(symbol)
    m = s["metrics"]
    universe = ds.stocks(s["index"])
    peers = [p for p in universe if p["industry"] == s["industry"]]
    bench = ds.index(s["index"])["name"]
    out = [f"## {symbol} — {s['name']}", "",
           f"{s['industry']} · {bench} constituent · data as of {m.get('lastDate', ds.as_of)}", "",
           table(["", ""], [["Close", md.num(m.get("lastClose"))], ["Latest session", pct(m.get("change1d"))],
                            ["Market cap", md.crore(s.get("marketCapCr"))], ["P/E · P/B", f"{fmt(s.get('pe'), 'x')} · {fmt(s.get('pb'), 'x')}"]]),
           "", f"### Returns vs {bench}", ""]
    rows = []
    for p in STOCK_PERIODS:
        r = (m.get("returns") or {}).get(p)
        b = ds.benchmark(s["index"]).get(p)
        excess = None if r is None or b is None else r - b
        peer_rs = [x["metrics"].get("returns", {}).get(p) for x in universe]
        rows.append([PERIOD_LABELS[p], pct(r), pct(b), "—" if excess is None else f"{excess * 100:+.1f} pp",
                     rank_in([{"metrics": {"r": v}} for v in peer_rs], "r", r, True)])
    out += [table(["Period", symbol, bench, "vs index", f"Rank in {bench}"], rows), "",
            "### Factor profile", "",
            f"Rank: 1 = best in {bench} for that measure (lowest for P/E, P/B, volatility, beta, D/E).", ""]
    rows = []
    sector_n = len(peers)
    for key, (label, kind, hib) in STOCK_MEASURES.items():
        v = m.get(key)
        med = md.median([p["metrics"].get(key) for p in peers if not (key in ("pe", "pb") and (p["metrics"].get(key) or 0) <= 0)])
        rows.append([label, fmt(v, kind), rank_in(universe, key, v, hib), fmt(med, kind)])
    out += [table(["Measure", "Value", f"Rank in {bench}", f"Sector median ({sector_n})"], rows), "",
            "### Price history", "", table(["", "Detail", "Value"], price_stats(s)), "",
            "### Financials", "", financials(s), "",
            f"### Sector peers in {bench} ({s['industry']})", ""]
    rows = [[("**" + p["symbol"] + "**") if p["symbol"] == symbol else p["symbol"], md.crore(p.get("marketCapCr")),
             pct(p["metrics"].get("returns", {}).get("Y1")), fmt(p["metrics"].get("pe"), "x"),
             fmt(p["metrics"].get("roe"), "upct"), fmt(p["metrics"].get("revenueGrowth"), "pct")]
            for p in sorted(peers, key=lambda p: -(p.get("marketCapCr") or 0))]
    out += [table(["Stock", "Mcap", "1Y", "P/E", "ROE", "Rev growth"], rows)]
    return "\n".join(out)


def compare(ds: md.Dataset, symbols: list[str]) -> str:
    stocks = [ds.stock(s, with_history=False) for s in symbols]
    rows = [["Index", *[ds.index(s["index"])["name"] for s in stocks]],
            ["Sector", *[s["industry"] for s in stocks]],
            ["Market cap", *[md.crore(s.get("marketCapCr")) for s in stocks]],
            ["Latest session", *[pct(s["metrics"].get("change1d")) for s in stocks]]]
    rows += [[PERIOD_LABELS[p], *[pct(s["metrics"].get("returns", {}).get(p)) for s in stocks]] for p in STOCK_PERIODS]
    rows += [[label, *[fmt(s["metrics"].get(k), kind) for s in stocks]] for k, (label, kind, _) in STOCK_MEASURES.items()]
    return "\n".join([f"## Comparison · data as of {ds.as_of}", "", table(["", *symbols], rows)])


def screen(ds: md.Dataset, universe: str, key: str, top: int, bottom: bool) -> str:
    if key in STOCK_MEASURES:
        label, kind, hib = STOCK_MEASURES[key]
        get = lambda s: s["metrics"].get(key)  # noqa: E731
    elif key.upper() in STOCK_PERIODS:
        label, kind, hib = f"{PERIOD_LABELS[key.upper()]} return", "pct", True
        get = lambda s: s["metrics"].get("returns", {}).get(key.upper())  # noqa: E731
    else:
        raise SystemExit(f"Unknown measure '{key}'. Use one of: {', '.join([*STOCK_MEASURES, *STOCK_PERIODS])}")
    stocks = [s for s in ds.stocks(universe) if get(s) is not None and not (key in ("pe", "pb") and get(s) <= 0)]
    best_first = sorted(stocks, key=get, reverse=hib)
    picked = best_first[::-1][:top] if bottom else best_first[:top]
    rows = [[i + 1, s["symbol"], s["industry"], fmt(get(s), kind), pct(s["metrics"].get("returns", {}).get("Y1")),
             fmt(s["metrics"].get("pe"), "x"), md.crore(s.get("marketCapCr"))] for i, s in enumerate(picked)]
    side = "Bottom" if bottom else "Top"
    return "\n".join([f"## {side} {len(picked)} of {len(stocks)} {ds.index(universe)['name']} stocks by {label}",
                      f"Best = {'highest' if hib else 'lowest'}. Data as of {ds.as_of}.", "",
                      table(["#", "Stock", "Sector", label, "1Y", "P/E", "Mcap"], rows, "rllrrrr")])


def sector(ds: md.Dataset, name: str, period: str) -> str:
    out = [f"## {name} · {PERIOD_LABELS[period]} · data as of {ds.as_of}", ""]
    for universe in md.UNIVERSES:
        stats = next((s for s in ds.sector_stats(universe, period) if s["sector"].lower() == name.lower()), None)
        members = [s for s in ds.stocks(universe) if s["industry"].lower() == name.lower()]
        if not members:
            continue
        iname = ds.index(universe)["name"]
        if stats:
            out.append(f"**{iname}**: {stats['outperformers']} of {stats['measured']} beat the index · "
                       f"average {pct(stats['averageReturn'])} vs index {pct(stats['benchmarkReturn'])}")
        rows = [[s["symbol"], pct(s["metrics"].get("returns", {}).get(period)), fmt(s["metrics"].get("pe"), "x"),
                 fmt(s["metrics"].get("roe"), "upct"), md.crore(s.get("marketCapCr"))]
                for s in sorted(members, key=lambda s: -(s["metrics"].get("returns", {}).get(period) or -9))]
        out += ["", table(["Stock", PERIOD_LABELS[period], "P/E", "ROE", "Mcap"], rows), ""]
    if len(out) == 2:
        sectors = sorted({s["industry"] for s in ds.stocks()})
        raise SystemExit(f"No stocks in sector '{name}'. Sectors: {', '.join(sectors)}")
    return "\n".join(out)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("symbols", nargs="*", help="NSE symbols or company names; several = side-by-side comparison")
    ap.add_argument("--screen", metavar="INDEX", help="rank an index's constituents (NIFTY 50, NEXT 50, MIDCAP 150)")
    ap.add_argument("--by", default="Y1", help="measure for --screen: a STOCK_MEASURES key (roe, pe, momentum12m1m, …) or a period (M1 … Y3)")
    ap.add_argument("--top", type=int, default=10)
    ap.add_argument("--bottom", action="store_true", help="worst instead of best")
    ap.add_argument("--sector", help="one sector across the three universes")
    ap.add_argument("--period", default="Y1", choices=STOCK_PERIODS, help="period for --sector")
    ap.add_argument("--data", default=str(md.DEFAULT_DIR), help="dataset directory (default shared-data/v4)")
    args = ap.parse_args()
    ds = md.Dataset(args.data)
    if args.screen:
        print(screen(ds, ds.resolve_index(args.screen), args.by, args.top, args.bottom))
    elif args.sector:
        print(sector(ds, args.sector, args.period))
    elif len(args.symbols) == 1:
        print(report(ds, ds.resolve_stock(args.symbols[0])))
    elif args.symbols:
        print(compare(ds, [ds.resolve_stock(s) for s in args.symbols]))
    else:
        ap.print_help()
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
