#!/usr/bin/env python3
"""
Derives the app's partitioned dataset (schema 5) from shared-data/dataset.json.

Schema 4 published three files that all changed every day. Schema 5 splits the data by how
often it changes, so a normal day only rewrites a few small files and phones download only those:

  manifest.json        part list (version, size, SHA-256, load group) and the update timeline
  analytics.json       precomputed metrics, sector aggregates, calendar tables      changes daily
  core.json            index list, constituents, stock list with market cap, P/E, P/B  weekly
  daily-YYYY-MM.json   one calendar month of daily index bars and stock closes      current month daily;
                                                                                    closed months fixed
  monthly-YYYY.json    one year of month-end index bars, stock closes, valuation    current year daily;
                                                                                    closed years fixed
  fundamentals.json    annual and quarterly financials per stock                    weekly

Closed months and years only change when history is restated (a split or bonus re-adjusts that
stock's past closes) or reconciled by the monthly full refresh. Daily files cover whole months
(build_dataset.py keeps daily data from the first day of a month), so the oldest one is dropped
whole when the window moves on rather than rewritten every day.

Each part has a load group: "primary" parts (analytics, core, the daily months and the last two
years of month-end data) are enough for every screen; "history" parts load in the background.

Usage:
  py -3 tools/derive_v5.py [--dataset shared-data/dataset.json] [--out shared-data/v5]
                           [--seed-dir app/src/main/assets/seed]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from derive_parts import BAR_FIELDS, MONTH_FIELDS, build_parts, columns, encode

ROOT = Path(__file__).resolve().parent.parent
SCHEMA_VERSION = 5
HISTORY_RUNS = 120
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def aligned(dates: list[str], values: list, keep) -> tuple[list[str], list]:
    pairs = [(d, v) for d, v in zip(dates, values) if keep(d)]
    return [d for d, _ in pairs], [v for _, v in pairs]


def stock_block(dates: list[str], items: list[dict], key: str, keep) -> dict:
    """Closes of every stock for the dates [keep] selects; stocks with no close there are left out."""
    selected = [d for d in dates if keep(d)]
    rows = []
    for s in items:
        _, values = aligned(dates, s[key], keep)
        if any(v is not None for v in values):
            rows.append({"symbol": s["symbol"], "c": values})
    return {"dates": selected, "items": rows}


def build(ds: dict) -> tuple[dict[str, dict], dict[str, str], str | None]:
    """Returns parts by name, each part's load group, and the first date the daily parts cover."""
    indices = ds["indices"]
    stocks = ds.get("stocks") or {"months": [], "sessions": [], "items": []}
    months, sessions, items = stocks["months"], stocks["sessions"], stocks["items"]
    as_of = ds.get("updatedAt") or ""

    parts: dict[str, dict] = {}
    groups: dict[str, str] = {}

    analytics = build_parts(ds)["analytics"]
    analytics["schemaVersion"] = SCHEMA_VERSION
    parts["analytics"], groups["analytics"] = analytics, "primary"

    parts["core"] = {
        "schemaVersion": SCHEMA_VERSION, "part": "core",
        "indices": [{"symbol": ix["symbol"], "name": ix["name"], "category": ix.get("category", "BROAD"),
                     "source": ix.get("source")} for ix in indices],
        "composition": ds.get("composition"),
        "universes": ds.get("universes", []),
        "stocks": [{
            "symbol": s["symbol"], "name": s["name"], "industry": s.get("industry", ""), "index": s["index"],
            "source": s.get("source"),
            "marketCapCr": s.get("fundamentals", {}).get("marketCapCr"),
            "pe": s.get("fundamentals", {}).get("pe"), "pb": s.get("fundamentals", {}).get("pb"),
        } for s in items],
    }
    groups["core"] = "primary"

    # Daily months: every month any index or stock has sessions in.
    daily_months = sorted({b["d"][:7] for ix in indices for b in ix["daily"]} | {d[:7] for d in sessions})
    for month in daily_months:
        name = f"daily-{month}"
        parts[name] = {
            "schemaVersion": SCHEMA_VERSION, "part": name, "month": month,
            "indices": [{"symbol": ix["symbol"], "bars": columns([b for b in ix["daily"] if b["d"][:7] == month], BAR_FIELDS)}
                        for ix in indices if any(b["d"][:7] == month for b in ix["daily"])],
            "stocks": stock_block(sessions, items, "dc", lambda d, m=month: d[:7] == m),
        }
        groups[name] = "primary"

    # Month-end years: index bars, valuation and stock closes.
    years = sorted({b["d"][:4] for ix in indices for b in ix["monthly"]}
                   | {v["d"][:4] for ix in indices for v in ix.get("valuation", [])}
                   | {d[:4] for d in months})
    recent = {str(int(as_of[:4]) - 1), as_of[:4]} if as_of else set()
    for year in years:
        name = f"monthly-{year}"
        parts[name] = {
            "schemaVersion": SCHEMA_VERSION, "part": name, "year": int(year),
            "indices": [{"symbol": ix["symbol"], "bars": columns([b for b in ix["monthly"] if b["d"][:4] == year], MONTH_FIELDS)}
                        for ix in indices if any(b["d"][:4] == year for b in ix["monthly"])],
            "valuation": [{"symbol": ix["symbol"],
                           "points": columns([v for v in ix["valuation"] if v["d"][:4] == year], ["d", "pe", "pb", "dy"])}
                          for ix in indices if any(v["d"][:4] == year for v in ix.get("valuation", []))],
            "stocks": stock_block(months, items, "mc", lambda d, y=year: d[:4] == y),
        }
        groups[name] = "primary" if year in recent else "history"

    parts["fundamentals"] = {
        "schemaVersion": SCHEMA_VERSION, "part": "fundamentals",
        "items": [{"symbol": s["symbol"], "annual": s.get("fundamentals", {}).get("annual", []),
                   "quarterly": s.get("fundamentals", {}).get("quarterly", [])} for s in items],
    }
    groups["fundamentals"] = "history"
    daily_from = f"{daily_months[0]}-01" if daily_months else None
    return parts, groups, daily_from


def describe(name: str) -> str:
    if name == "analytics":
        return "Precomputed metrics, sector aggregates and calendar tables"
    if name == "core":
        return "Index list, constituents and stock list"
    if name == "fundamentals":
        return "Annual and quarterly financials"
    if name.startswith("daily-"):
        y, m = name[6:10], int(name[11:13])
        return f"Daily prices, {MONTHS[m - 1]} {y}"
    return f"Month-end prices and valuation, {name[8:]}"


def records(name: str, part: dict) -> int:
    """Rough row count shown in the app's data page."""
    if name == "analytics":
        return len(part["indices"]) + len(part["stocks"]) + len(part["sectors"]) + len(part["calendars"])
    if name == "core":
        return len(part["indices"]) + len(part["stocks"])
    if name == "fundamentals":
        return sum(len(s["annual"]) + len(s["quarterly"]) for s in part["items"])
    bars = sum(len(i["bars"]["d"]) for i in part["indices"]) + sum(len(v["points"]["d"]) for v in part.get("valuation", []))
    return bars + sum(sum(1 for c in s["c"] if c is not None) for s in part["stocks"]["items"])


def order(name: str) -> tuple:
    """Manifest order: primary-first reading order, then months/years oldest first."""
    fixed = {"analytics": 0, "core": 1, "fundamentals": 4}
    if name in fixed:
        return fixed[name], ""
    return (2 if name.startswith("daily-") else 3), name


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=str(ROOT / "shared-data" / "dataset.json"))
    ap.add_argument("--out", default=str(ROOT / "shared-data" / "v5"))
    ap.add_argument("--seed-dir", help="also copy the files here (the app's bundled offline data)")
    args = ap.parse_args()

    ds = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    manifest_path = out / "manifest.json"
    previous = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else None
    prev_parts = {p["name"]: p for p in (previous or {}).get("parts", [])}

    parts, groups, daily_from = build(ds)
    names = sorted(parts, key=order)
    encoded = {n: encode(parts[n]) for n in names}
    shas = {n: hashlib.sha256(encoded[n]).hexdigest() for n in names}
    changed = [n for n in names if prev_parts.get(n, {}).get("sha256") != shas[n]]
    removed = sorted(set(prev_parts) - set(names))
    version = (previous or {}).get("version", 0) + (1 if changed or removed or previous is None else 0)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    if not changed and not removed and previous is not None:
        print(f"No part changed (manifest v{version}).")
    else:
        for n in changed:
            (out / f"{n}.json").write_bytes(encoded[n])
        for stale in out.glob("*.json"):
            if stale.name != "manifest.json" and stale.stem not in parts:
                stale.unlink()
        parts_meta = [{
            "name": n, "file": f"{n}.json", "group": groups[n],
            "version": version if n in changed else prev_parts[n]["version"],
            "bytes": len(encoded[n]), "sha256": shas[n], "records": records(n, parts[n]),
            "description": describe(n),
        } for n in names]
        run = {
            "version": version, "datasetVersion": ds["version"], "generatedAt": now, "asOf": ds.get("updatedAt"),
            "changed": changed, "removed": removed, "failures": ds.get("failures", []),
            "indices": len(ds["indices"]), "stocks": len((ds.get("stocks") or {}).get("items", [])),
        }
        manifest = {
            "schemaVersion": SCHEMA_VERSION, "version": version, "datasetVersion": ds["version"],
            "asOf": ds.get("updatedAt"), "generatedAt": now, "dailyFrom": daily_from, "parts": parts_meta,
            "history": ([run] + (previous or {}).get("history", []))[:HISTORY_RUNS],
        }
        manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        size = sum(len(encoded[n]) for n in changed) / 1024
        print(f"Manifest v{version} (dataset v{ds['version']}, as of {ds.get('updatedAt')}); "
              f"{len(changed)} of {len(names)} parts changed ({size:.0f} KB): {', '.join(changed)}"
              + (f"; removed {', '.join(removed)}" if removed else ""))

    if args.seed_dir:
        seed = Path(args.seed_dir)
        seed.mkdir(parents=True, exist_ok=True)
        for stale in seed.glob("*.json"):
            stale.unlink()
        shutil.copyfile(manifest_path, seed / "manifest.json")
        for n in names:
            shutil.copyfile(out / f"{n}.json", seed / f"{n}.json")
        print(f"Copied {len(names) + 1} files to {seed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
