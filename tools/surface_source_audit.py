#!/usr/bin/env python3
import gzip
import json
import sqlite3
import tempfile
import urllib.request
from pathlib import Path

URL = "https://github.com/fowov2-jpg/676017/releases/download/runtime-v0.5.0/surface_2026-08-14.sqlite.gz"
UA = "HumanRouterSurfaceAudit/0.2 (+https://github.com/fowov2-jpg/676017)"
out = Path("surface-source-audit")
out.mkdir(exist_ok=True)

# Surface runtime is currently expected to contain timetable-backed city surface transport.
# Anything else must be reported explicitly instead of silently being treated as a bus.
EXPECTED_SURFACE_MODES = {"BUS", "TRAM"}
MICROMOBILITY_MARKERS = ("BIKE", "BICYCLE", "SCOOTER", "KICK_SCOOTER", "E-SCOOTER", "ЭСАМОКАТ", "САМОКАТ", "ВЕЛО")

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

    route_mode_counts = {
        (row["route_mode"] or "<NULL>"): row["count"]
        for row in db.execute(
            "SELECT route_mode,COUNT(*) AS count FROM routes GROUP BY route_mode ORDER BY count DESC,route_mode"
        )
    }
    route_type_counts = {
        str(row["route_type"]): row["count"]
        for row in db.execute(
            "SELECT route_type,COUNT(*) AS count FROM routes GROUP BY route_type ORDER BY count DESC,route_type"
        )
    }
    stop_transport_type_counts = {
        (row["transport_type"] or "<NULL>"): row["count"]
        for row in db.execute(
            "SELECT transport_type,COUNT(*) AS count FROM stops GROUP BY transport_type ORDER BY count DESC,transport_type"
        )
    }

    normalized_route_modes = {mode.upper().strip() for mode in route_mode_counts if mode != "<NULL>"}
    unexpected_surface_modes = sorted(normalized_route_modes - EXPECTED_SURFACE_MODES)
    micromobility_values = sorted(
        value
        for value in set(route_mode_counts) | set(stop_transport_type_counts)
        if any(marker in value.upper() for marker in MICROMOBILITY_MARKERS)
    )

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
        "route_mode_counts": route_mode_counts,
        "route_type_counts": route_type_counts,
        "stop_transport_type_counts": stop_transport_type_counts,
        "unexpected_surface_modes": unexpected_surface_modes,
        "micromobility_values": micromobility_values,
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
    "route_mode_counts": route_mode_counts,
    "route_type_counts": route_type_counts,
    "stop_transport_type_counts": stop_transport_type_counts,
    "unexpected_surface_modes": unexpected_surface_modes,
    "micromobility_values": micromobility_values,
    "named_route_samples": named,
}, ensure_ascii=False, indent=2))
