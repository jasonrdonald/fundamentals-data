#!/usr/bin/env python3
"""
Adds approved tickers to tools/wishlist.csv (the stocks outside the tracked indices).

Requests come from the app: a user enters tickers, the app checks them on Yahoo Finance and emails
them to the developer. The developer approves by running the "Add to wishlist" workflow with the
tickers, which runs this script. Each ticker is checked again here before it is added:

  * it must be an Indian equity on Yahoo Finance (NSE first, then BSE, then the NSE SME series);
  * it must not already be in an index universe or the wishlist.

The company name and sector come from Yahoo; the sector is mapped to the NSE industry names the
dataset uses (edit the CSV afterwards if a stock needs a different one). The next dataset run
backfills each new stock's history.

Usage:
  py -3 tools/add_to_wishlist.py "AAVAS, TECHD=TECHD-SM.NS"     (SYMBOL or SYMBOL=YAHOO_SYMBOL)
"""
from __future__ import annotations

import csv
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WISHLIST = ROOT / "tools" / "wishlist.csv"
CORE = ROOT / "shared-data" / "v5" / "core.json"
USER_AGENT = "fundamentals-data-pipeline/1.0 (+https://github.com/jasonrdonald/fundamentals-data)"
SYMBOL = re.compile(r"^[A-Z0-9&][A-Z0-9&\-]{0,19}$")
SERIES = {"E", "F", "BE", "BL", "BZ", "SM", "ST", "IV", "RR", "GB", "EQ", "GS", "MF"}

# Yahoo sector (and some industries) -> the NSE industry names used across the dataset.
SECTORS = {
    "Financial Services": "Financial Services",
    "Healthcare": "Healthcare",
    "Technology": "Information Technology",
    "Industrials": "Capital Goods",
    "Consumer Cyclical": "Consumer Services",
    "Consumer Defensive": "Fast Moving Consumer Goods",
    "Basic Materials": "Chemicals",
    "Energy": "Oil Gas & Consumable Fuels",
    "Utilities": "Power",
    "Communication Services": "Telecommunication",
    "Real Estate": "Realty",
}
INDUSTRY_HINTS = [
    ("Auto", "Automobile and Auto Components"),
    ("Steel", "Metals & Mining"), ("Aluminum", "Metals & Mining"), ("Copper", "Metals & Mining"),
    ("Metals", "Metals & Mining"), ("Mining", "Metals & Mining"), ("Coal", "Metals & Mining"),
    ("Building Materials", "Construction Materials"), ("Engineering & Construction", "Construction"),
    ("Textile", "Textiles"), ("Apparel", "Textiles"),
    ("Furnishings", "Consumer Durables"), ("Appliances", "Consumer Durables"), ("Electronics", "Consumer Durables"),
    ("Entertainment", "Media Entertainment & Publication"), ("Broadcasting", "Media Entertainment & Publication"),
    ("Publishing", "Media Entertainment & Publication"),
]


def get_json(url: str) -> dict | None:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def base_symbol(symbol: str) -> str:
    s = symbol.strip().upper()
    i = s.rfind("-")
    return s[:i] if i > 0 and s[i + 1:] in SERIES else s


def quote(yahoo: str) -> dict | None:
    """Chart metadata if [yahoo] is a listed Indian equity with recent prices, else None."""
    data = get_json("https://query1.finance.yahoo.com/v8/finance/chart/" + urllib.parse.quote(yahoo, safe="") + "?range=5d&interval=1d")
    result = ((data or {}).get("chart") or {}).get("result") or []
    if not result:
        return None
    meta = result[0].get("meta") or {}
    if meta.get("instrumentType") != "EQUITY" or meta.get("currency") != "INR" or not meta.get("regularMarketPrice"):
        return None
    return meta


def profile(yahoo: str) -> dict:
    data = get_json("https://query1.finance.yahoo.com/v1/finance/search?" + urllib.parse.urlencode(
        {"q": yahoo, "quotesCount": 5, "newsCount": 0}))
    return next((q for q in (data or {}).get("quotes", []) if q.get("symbol") == yahoo), {})


def industry(p: dict) -> str:
    text = p.get("industry") or ""
    for hint, name in INDUSTRY_HINTS:
        if hint.lower() in text.lower():
            return name
    return SECTORS.get(p.get("sector") or "", "Diversified")


def company(name: str) -> str:
    return re.sub(r"\s+Limited$", " Ltd.", name.strip())


def main() -> int:
    if len(sys.argv) != 2 or not sys.argv[1].strip():
        print(__doc__)
        return 2
    rows = list(csv.DictReader(WISHLIST.read_text(encoding="utf-8-sig").splitlines()))
    fields = ["Company Name", "Industry", "Symbol", "Yahoo"]
    listed = {r["Symbol"] for r in rows}
    covered = {s["symbol"] for s in json.loads(CORE.read_text(encoding="utf-8"))["stocks"]} if CORE.exists() else set()

    added, skipped = [], []
    for token in re.split(r"[,\s]+", sys.argv[1].strip()):
        if not token:
            continue
        raw, _, yahoo_given = token.partition("=")
        raw = raw.strip().upper()
        symbol = base_symbol(raw)
        if not SYMBOL.match(symbol):
            skipped.append(f"{raw}: not a valid symbol")
            continue
        if symbol in listed or symbol in covered:
            skipped.append(f"{symbol}: already in the dataset")
            continue
        candidates = [yahoo_given.strip()] if yahoo_given.strip() else [f"{symbol}.NS", f"{symbol}.BO", f"{symbol}-SM.NS"]
        found = next(((y, m) for y in candidates if (m := quote(y))), None)
        if not found:
            skipped.append(f"{symbol}: not found on Yahoo Finance as an Indian equity ({', '.join(candidates)})")
            continue
        yahoo, meta = found
        p = profile(yahoo)
        name = company(p.get("longname") or meta.get("longName") or meta.get("shortName") or symbol)
        row = {"Company Name": name, "Industry": industry(p), "Symbol": symbol, "Yahoo": "" if yahoo == f"{symbol}.NS" else yahoo}
        rows.append(row)
        listed.add(symbol)
        added.append(f"{symbol}: {name} ({row['Industry']}; {yahoo})")

    if added:
        rows.sort(key=lambda r: r["Symbol"])
        with WISHLIST.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
            w.writeheader()
            w.writerows({k: r.get(k, "") for k in fields} for r in rows)

    report = ["Added:"] + [f"- {a}" for a in added] + (["", "Skipped:"] + [f"- {s}" for s in skipped] if skipped else [])
    print("\n".join(report))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        Path(summary).write_text("## Add to wishlist\n\n" + "\n".join(report) + "\n", encoding="utf-8")
    if added:
        Path(os.environ.get("ADDED_FILE", ROOT / "build" / "wishlist-added.txt")).parent.mkdir(parents=True, exist_ok=True)
        Path(os.environ.get("ADDED_FILE", ROOT / "build" / "wishlist-added.txt")).write_text(
            ", ".join(a.split(":")[0] for a in added), encoding="utf-8")
    return 0 if added else 1


if __name__ == "__main__":
    sys.exit(main())
