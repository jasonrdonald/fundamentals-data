"""Read the published dataset (shared-data/v4) for analysis outside the app.

The app shows the same numbers: per-stock and per-index metrics come precomputed in
analytics.json (built by tools/derive_parts.py, a port of the Kotlin calculators), so reports
built on this module agree with what users see. Price series are decoded from the columnar
format in core.json / history.json into lists of dicts.

Only public market data is read here. The user's imported portfolio lives on their phone and
is never part of this dataset.
"""
from __future__ import annotations

import json
import math
from datetime import date
from functools import cached_property
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "shared-data" / "v4"

#: Universes with constituent stocks, in display order.
UNIVERSES = ["NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150", "NIFTY SMALLCAP 250", "NIFTY MICROCAP 250"]
INDEX_PERIODS = ["D1", "W1", "M1", "M3", "M6", "YTD", "Y1", "Y3", "Y5"]
STOCK_PERIODS = ["W1", "M1", "M3", "M6", "YTD", "Y1", "Y3"]
PERIOD_LABELS = {"D1": "1D", "W1": "1W", "M1": "1M", "M3": "3M", "M6": "6M", "YTD": "YTD",
                 "Y1": "1Y", "Y3": "3Y CAGR", "Y5": "5Y CAGR"}

#: Stock measures as the app defines them (StockMetric in domain/model/Stocks.kt):
#: key -> (label, format, higher_is_better). Formats: pct = signed %, upct = unsigned %,
#: num, x = multiple, cr = INR crore.
STOCK_MEASURES = {
    "momentum12m1m": ("12-1M momentum", "pct", True),
    "rsi14": ("RSI (14)", "num", True),
    "vsDma200": ("vs 200-DMA", "pct", True),
    "fromHigh52w": ("From 52W high", "pct", True),
    "volatility1y": ("Volatility (1Y)", "upct", False),
    "beta": ("Beta", "num", False),
    "alpha": ("Alpha (ann.)", "pct", True),
    "pe": ("P/E", "x", False),
    "pb": ("P/B", "x", False),
    "earningsYield": ("Earnings yield", "upct", True),
    "revenueGrowth": ("Revenue growth (FY)", "pct", True),
    "revenueCagr": ("Revenue CAGR", "pct", True),
    "epsGrowth": ("EPS growth (FY)", "pct", True),
    "quarterlyRevenueYoY": ("Revenue YoY (qtr)", "pct", True),
    "roe": ("ROE", "upct", True),
    "netMargin": ("Net margin", "upct", True),
    "debtToEquity": ("Debt / equity", "num", False),
    "netProfitCr": ("Net profit (FY, Cr)", "cr", True),
    "profitGrowth": ("Profit growth (FY)", "pct", True),
}


def bars(cols: dict | None) -> list[dict]:
    """Columnar {"d": [...], "c": [...], ...} -> [{"d": ..., "c": ...}, ...]."""
    if not cols:
        return []
    keys = list(cols)
    return [dict(zip(keys, row)) for row in zip(*(cols[k] for k in keys))]


def closes(dates: list[str], values: list) -> list[dict]:
    return [{"d": d, "c": v} for d, v in zip(dates, values) if v is not None]


# ----------------------------------------------------------------------------- formatting

def pct(x: float | None, signed: bool = True, digits: int = 2) -> str:
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "—"
    return f"{x * 100:+.{digits}f}%" if signed else f"{x * 100:.{digits}f}%"


def num(x: float | None, digits: int = 2) -> str:
    return "—" if x is None else f"{x:,.{digits}f}"


def crore(x: float | None) -> str:
    if x is None:
        return "—"
    return f"₹{x / 1e5:,.2f} L Cr" if abs(x) >= 1e5 else f"₹{x:,.0f} Cr"


def fmt(value, kind: str) -> str:
    return {"pct": lambda v: pct(v), "upct": lambda v: pct(v, signed=False), "num": num, "x": lambda v: "—" if v is None else f"{v:.1f}x",
            "cr": crore}.get(kind, num)(value)


def table(headers: list[str], rows: list[list], align: str | None = None) -> str:
    """Markdown table. [align] is one char per column: l or r (default: first l, rest r)."""
    align = align or ("l" + "r" * (len(headers) - 1))
    sep = ["---:" if a == "r" else "---" for a in align]
    out = ["| " + " | ".join(headers) + " |", "| " + " | ".join(sep) + " |"]
    out += ["| " + " | ".join("" if c is None else str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


# ----------------------------------------------------------------------------- statistics

def percentile_rank(values: list[float], x: float) -> float | None:
    """Share of [values] at or below [x] (0..1)."""
    vs = [v for v in values if v is not None]
    return sum(1 for v in vs if v <= x) / len(vs) if vs else None


def median(values: list[float]) -> float | None:
    vs = sorted(v for v in values if v is not None)
    if not vs:
        return None
    m = len(vs) // 2
    return vs[m] if len(vs) % 2 else (vs[m - 1] + vs[m]) / 2


def max_drawdown(series: list[dict]) -> tuple[float | None, str | None, str | None]:
    """Largest peak-to-trough fall in a close series: (drawdown, peak date, trough date)."""
    peak = None; peak_d = None; worst = 0.0; span = (None, None)
    for b in series:
        if peak is None or b["c"] > peak:
            peak, peak_d = b["c"], b["d"]
        dd = b["c"] / peak - 1
        if dd < worst:
            worst, span = dd, (peak_d, b["d"])
    return (worst if span[0] else None), span[0], span[1]


# ----------------------------------------------------------------------------- dataset

class Dataset:
    """Lazy view over the published parts. History (~1 MB) is read only when needed."""

    def __init__(self, directory: Path | str = DEFAULT_DIR):
        self.dir = Path(directory)

    def _read(self, name: str) -> dict:
        return json.loads((self.dir / f"{name}.json").read_text(encoding="utf-8"))

    @cached_property
    def manifest(self) -> dict: return self._read("manifest")
    @cached_property
    def analytics(self) -> dict: return self._read("analytics")
    @cached_property
    def core(self) -> dict: return self._read("core")
    @cached_property
    def history(self) -> dict: return self._read("history")

    @property
    def as_of(self) -> str: return self.manifest.get("asOf", "?")

    # ---- indices

    @cached_property
    def index_symbols(self) -> list[str]:
        return [i["symbol"] for i in self.core["indices"]]

    def resolve_index(self, query: str) -> str:
        q = query.strip().upper().replace("_", " ")
        aliases = {"NIFTY": "NIFTY 50", "NIFTY50": "NIFTY 50", "NEXT 50": "NIFTY NEXT 50", "NIFTYNEXT50": "NIFTY NEXT 50",
                   "JUNIOR": "NIFTY NEXT 50", "MIDCAP": "NIFTY MIDCAP 150", "MIDCAP 150": "NIFTY MIDCAP 150",
                   "BANKNIFTY": "NIFTY BANK", "BANK NIFTY": "NIFTY BANK", "VIX": "INDIA VIX"}
        q = aliases.get(q, q)
        for candidate in (q, "NIFTY " + q):
            if candidate in self.index_symbols:
                return candidate
        hits = [s for s in self.index_symbols if q in s or q in self.index(s)["name"].upper()]
        if len(hits) == 1:
            return hits[0]
        raise SystemExit(f"Unknown or ambiguous index '{query}'. Known: {', '.join(self.index_symbols)}")

    def index(self, symbol: str, with_history: bool = False) -> dict:
        """Name, category, daily bars (~300 sessions), monthly bars, metrics; plus full
        monthly history and valuation (PE/PB/DY month-ends) when [with_history]."""
        core = next(i for i in self.core["indices"] if i["symbol"] == symbol)
        out = {
            "symbol": symbol, "name": core["name"], "category": core["category"], "source": core.get("source"),
            "daily": bars(core.get("daily")), "monthly": bars(core.get("monthly")),
            "metrics": next((m for m in self.analytics["indices"] if m["symbol"] == symbol), {}),
            "valuation": [],
        }
        if with_history:
            h = next((i for i in self.history["indices"] if i["symbol"] == symbol), None)
            if h:
                full = bars(h.get("monthly"))
                recent = {b["d"] for b in out["monthly"]}
                out["monthly"] = [b for b in full if b["d"] not in recent] + out["monthly"]
                out["valuation"] = bars(h.get("valuation"))
        return out

    def calendar(self, symbol: str, kind: str = "RETURNS") -> dict | None:
        return next((c for c in self.analytics["calendars"] if c["symbol"] == symbol and c["kind"] == kind), None)

    def sector_stats(self, universe: str, period: str) -> list[dict]:
        return [s for s in self.analytics["sectors"] if s["universe"] == universe and s["period"] == period]

    def benchmark(self, universe: str) -> dict:
        return self.analytics["benchmarks"].get(universe, {})

    # ---- stocks

    @cached_property
    def _stock_rows(self) -> dict[str, dict]:
        return {s["symbol"]: s for s in self.core["stocks"]["items"]}

    @cached_property
    def _stock_metrics(self) -> dict[str, dict]:
        return {s["symbol"]: s for s in self.analytics["stocks"]}

    def stocks(self, universe: str | None = None) -> list[dict]:
        """Every covered stock (or one universe's): core fields merged with precomputed metrics."""
        out = []
        for sym, row in self._stock_rows.items():
            if universe and row["index"] != universe:
                continue
            out.append({**{k: v for k, v in row.items() if k != "mc"}, "metrics": self._stock_metrics.get(sym, {})})
        return out

    def resolve_stock(self, query: str) -> str:
        q = query.strip().upper()
        if q in self._stock_rows:
            return q
        hits = [s for s, r in self._stock_rows.items() if q in r["name"].upper()]
        if len(hits) == 1:
            return hits[0]
        if hits:
            raise SystemExit(f"'{query}' matches several stocks: {', '.join(hits[:12])}")
        raise SystemExit(f"'{query}' isn't a Nifty 50 / Next 50 / Midcap 150 constituent in this dataset.")

    def stock(self, symbol: str, with_history: bool = True) -> dict:
        """One stock: core fields, metrics, recent month-end closes and, with history, ~10 years
        of month-end closes, ~1 year of daily closes and annual / quarterly financials."""
        row = self._stock_rows[symbol]
        out = {**{k: v for k, v in row.items() if k != "mc"}, "metrics": self._stock_metrics.get(symbol, {}),
               "monthly": closes(self.core["stocks"]["months"], row.get("mc", [])),
               "daily": [], "annual": [], "quarterly": []}
        if with_history:
            hs = self.history["stocks"]
            h = next((s for s in hs["items"] if s["symbol"] == symbol), None)
            if h:
                full = closes(hs["months"], h.get("mc", []))
                recent = {b["d"] for b in out["monthly"]}
                out["monthly"] = [b for b in full if b["d"] not in recent] + out["monthly"]
                out["daily"] = closes(hs["sessions"], h.get("dc", []))
                out["annual"] = h.get("annual", [])
                out["quarterly"] = h.get("quarterly", [])
        return out


def years_between(a: str, b: str) -> float:
    return (date.fromisoformat(b) - date.fromisoformat(a)).days / 365.25
