#!/usr/bin/env python3
import gzip
import json
import sqlite3
import tempfile
import urllib.request
from pathlib import Path

URL = "https://github.com/fowov2-jpg/676017/releases/download/runtime-v0.5.0/surface_2026-08-14.sqlite.gz"
UA = "HumanRouterSurfaceAudit/0.1 (+https://github.com/fowov2-jpg/676017)"
out = Path("surface-source-audit")
out.mkdir(exist_ok=True)

req = urllib.request.Request(URL, headers={"User-Agent": UA})
with urllib.request.urlopen(req, timeout=120) as response:
    compressed = response.read()
raw = gzip.decompress(compressed)

with tempfile.NamedTemporaryFile(suffix=".sqlite") as tmp:
    tmp.write(raw)
    tmp.flush()
    db = sqlite3.connect(tmp.name)
    db.row_factory = sqlite3.Row
    tables = {}
    for row in db.execute("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name"):
        name = row["name"]
        columns = [dict(r) for r in db.execute(f"PRAGMA table_info({name})")]
        count = db.execute(f"SELECT COUNT(*) FROM {name}").fetchone()[0]
        sample = [dict(r) for r in db.execute(f"SELECT * FROM {name} LIMIT 5")]
        tables[name] = {"sql": row["sql"], "columns": columns, "count": count, "sample": sample}

    indexes = [dict(row) for row in db.execute("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index' ORDER BY tbl_name,name")]
    route_samples = [dict(r) for r in db.execute("SELECT * FROM routes ORDER BY route_id LIMIT 50")]
    connection_samples = [dict(r) for r in db.execute("SELECT * FROM connections ORDER BY dep,from_stop LIMIT 50")]

    named = {}
    route_cols = {r[1] for r in db.execute("PRAGMA table_info(routes)")}
    short_col = next((c for c in ("short_name", "route_short_name", "name") if c in route_cols), None)
    if short_col:
        for query in ("м95", "259", "м3", "39"):
            named[query] = [dict(r) for r in db.execute(f"SELECT * FROM routes WHERE {short_col}=? LIMIT 20", (query,))]

    report = {
        "compressed_bytes": len(compressed),
        "raw_bytes": len(raw),
        "tables": tables,
        "indexes": indexes,
        "route_samples": route_samples,
        "connection_samples": connection_samples,
        "named_route_samples": named,
    }
    db.close()

(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({
    "compressed_bytes": report["compressed_bytes"],
    "raw_bytes": report["raw_bytes"],
    "tables": {k: v["count"] for k, v in tables.items()},
    "indexes": len(indexes),
    "named_route_samples": named,
}, ensure_ascii=False, indent=2))
