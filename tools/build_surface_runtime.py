#!/usr/bin/env python3
"""Build Human Router BUS/TRAM runtime from Moscow open-data tables 60661/2/4/5/6.

The input reader accepts data.mos.ru-style API JSON/JSONL (rows may be wrapped in ``Cells``)
and semicolon/comma CSV exports. Stop-times are streamed into SQLite so the multi-million-row
60661 dataset is never materialized in RAM.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import math
import sqlite3
import tempfile
import zipfile
from pathlib import Path
from typing import Dict, Iterable, Iterator, Mapping, Optional

DAY_FIELDS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
SURFACE_ROLES = {"surface_db", "surface_manifest", "stop_walk_map"}
METERS_PER_E7 = 0.011132
GRID_CELL_E7 = 20_000


def unwrap(row: Mapping) -> dict:
    cells = row.get("Cells") if isinstance(row, Mapping) else None
    if isinstance(cells, Mapping):
        return dict(cells)
    return dict(row)


def open_text(path: Path):
    if path.suffix.lower() == ".gz":
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8-sig", errors="replace", newline="")
    return path.open("r", encoding="utf-8-sig", errors="replace", newline="")


def iter_rows(path: Path) -> Iterator[dict]:
    suffixes = [s.lower() for s in path.suffixes]
    if suffixes and suffixes[-1] == ".zip":
        with zipfile.ZipFile(path) as zf:
            members = [n for n in zf.namelist() if not n.endswith("/")]
            if not members:
                return
            preferred = sorted(members, key=lambda n: (not n.lower().endswith((".csv", ".jsonl", ".ndjson", ".json")), n))[0]
            raw = zf.read(preferred)
        with tempfile.NamedTemporaryFile(suffix=Path(preferred).suffix, delete=False) as tmp:
            tmp.write(raw)
            tmp_path = Path(tmp.name)
        try:
            yield from iter_rows(tmp_path)
        finally:
            tmp_path.unlink(missing_ok=True)
        return

    logical_suffix = suffixes[-2] if suffixes and suffixes[-1] == ".gz" and len(suffixes) >= 2 else (suffixes[-1] if suffixes else "")
    if logical_suffix in (".jsonl", ".ndjson"):
        with open_text(path) as fh:
            for line in fh:
                line = line.strip()
                if line:
                    yield unwrap(json.loads(line))
        return

    if logical_suffix == ".json":
        with open_text(path) as fh:
            data = json.load(fh)
        if isinstance(data, list):
            rows = data
        elif isinstance(data, dict):
            rows = data.get("_items") or data.get("items") or data.get("rows") or data.get("data") or [data]
        else:
            raise ValueError(f"Unsupported JSON root in {path}")
        for row in rows:
            if isinstance(row, Mapping):
                yield unwrap(row)
        return

    # Official exports use ';', while mirrors and test fixtures often use ','.
    with open_text(path) as fh:
        sample = fh.read(16_384)
        fh.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=";,\t,")
        except csv.Error:
            dialect = csv.excel
            dialect.delimiter = ";"
        for row in csv.DictReader(fh, dialect=dialect):
            yield unwrap(row)


def value(row: Mapping, *names, default=None):
    lower = {str(k).lower(): v for k, v in row.items()}
    for name in names:
        if name in row and row[name] not in (None, ""):
            return row[name]
        found = lower.get(name.lower())
        if found not in (None, ""):
            return found
    return default


def as_int(v, default=0):
    if v is None or v == "":
        return default
    try:
        return int(float(str(v).replace(",", ".")))
    except (TypeError, ValueError):
        return default


def parse_bool01(v) -> int:
    text = str(v).strip().lower()
    return 1 if text in {"1", "true", "yes", "да", "y"} else 0


def parse_date(v) -> Optional[dt.date]:
    if v is None or str(v).strip() == "":
        return None
    text = str(v).strip()
    for fmt in ("%Y-%m-%d", "%Y%m%d", "%d.%m.%Y", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f"):
        try:
            return dt.datetime.strptime(text[:26], fmt).date()
        except ValueError:
            pass
    try:
        return dt.date.fromisoformat(text[:10])
    except ValueError:
        return None


def parse_time_seconds(v) -> Optional[int]:
    if v is None:
        return None
    text = str(v).strip()
    if not text:
        return None
    parts = text.split(":")
    try:
        if len(parts) == 2:
            h, m = map(int, parts)
            s = 0
        elif len(parts) >= 3:
            h, m, s = int(parts[0]), int(parts[1]), int(float(parts[2]))
        else:
            return int(float(text))
        if h < 0 or m not in range(60) or s not in range(60):
            return None
        return h * 3600 + m * 60 + s
    except ValueError:
        return None


def normalize_route_type(route_type, short_name: str) -> Optional[str]:
    text = str(route_type or "").strip().upper()
    if text in {"0", "TRAM", "ТРАМВАЙ"}:
        return "TRAM"
    if text in {"3", "BUS", "АВТОБУС", "ELECTROBUS", "ЭЛЕКТРОБУС"}:
        return "BUS"
    # Current Moscow open-data exports follow GTFS 0=tram, 3=bus. If type is missing, keep common
    # surface route identifiers rather than silently classifying rail as bus.
    if not text and short_name:
        return "BUS"
    return None


def extract_geo(row: Mapping) -> tuple[Optional[float], Optional[float]]:
    for key in ("geoData", "geodata_center", "geometry"):
        raw = value(row, key)
        obj = raw
        if isinstance(raw, str):
            try:
                obj = json.loads(raw)
            except json.JSONDecodeError:
                obj = None
        if isinstance(obj, Mapping):
            coords = obj.get("coordinates")
            if isinstance(coords, list) and len(coords) >= 2:
                try:
                    lon, lat = float(coords[0]), float(coords[1])
                    return lat, lon
                except (TypeError, ValueError):
                    pass
    lat = value(row, "lat", "latitude")
    lon = value(row, "lon", "lng", "longitude")
    try:
        return float(lat), float(lon)
    except (TypeError, ValueError):
        return None, None


def load_routes(path: Path) -> Dict[str, dict]:
    result = {}
    for row in iter_rows(path):
        route_id = str(value(row, "route_id", default="")).strip()
        if not route_id:
            continue
        short_name = str(value(row, "route_short_name", "short_name", default="")).strip()
        mode = normalize_route_type(value(row, "route_type", "transport_type"), short_name)
        if mode is None:
            continue
        result[route_id] = {
            "route_id": route_id,
            "short_name": short_name or None,
            "long_name": str(value(row, "route_long_name", "long_name", default="")).strip() or None,
            "route_type": as_int(value(row, "route_type"), 3 if mode == "BUS" else 0),
            "route_mode": mode,
        }
    return result


def load_calendar(path: Path, target: dt.date) -> set[str]:
    active = set()
    weekday = DAY_FIELDS[target.weekday()]
    for row in iter_rows(path):
        service_id = str(value(row, "service_id", default="")).strip()
        if not service_id or parse_bool01(value(row, weekday)) == 0:
            continue
        start = parse_date(value(row, "start_date"))
        end = parse_date(value(row, "end_date"))
        if start and target < start:
            continue
        if end and target > end:
            continue
        active.add(service_id)
    return active


def load_trips(path: Path, active_services: set[str], routes: Dict[str, dict]) -> Dict[str, str]:
    trips = {}
    for row in iter_rows(path):
        trip_id = str(value(row, "trip_id", default="")).strip()
        route_id = str(value(row, "route_id", default="")).strip()
        service_id = str(value(row, "service_id", default="")).strip()
        if not service_id and trip_id.count("_") >= 1:
            service_id = trip_id.split("_", 2)[1]
        if trip_id and route_id in routes and service_id in active_services:
            trips[trip_id] = route_id
    return trips


def load_stops(path: Path) -> Dict[int, dict]:
    result = {}
    for row in iter_rows(path):
        stop_id = as_int(value(row, "stop_id"), -1)
        if stop_id < 0:
            continue
        lat, lon = extract_geo(row)
        if lat is None or lon is None:
            continue
        result[stop_id] = {
            "stop_id": stop_id,
            "name": str(value(row, "stop_name", "name", "StationName", default=str(stop_id))).strip() or str(stop_id),
            "lat": lat,
            "lon": lon,
        }
    return result


def create_database(path: Path, routes: Dict[str, dict], stops: Dict[int, dict], trips: Dict[str, str], target: dt.date):
    path.unlink(missing_ok=True)
    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA temp_store=FILE")
    db.executescript(
        """
        CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE routes(
          route_id TEXT PRIMARY KEY,
          short_name TEXT,
          long_name TEXT,
          route_type INTEGER,
          route_mode TEXT
        ) WITHOUT ROWID;
        CREATE TABLE stops(
          stop_id INTEGER PRIMARY KEY,
          name TEXT NOT NULL,
          lat REAL NOT NULL,
          lon REAL NOT NULL,
          transport_type TEXT
        );
        CREATE TABLE connections(
          dep INTEGER NOT NULL,
          from_stop INTEGER NOT NULL,
          trip_id TEXT NOT NULL,
          seq INTEGER NOT NULL,
          to_stop INTEGER NOT NULL,
          arr INTEGER NOT NULL,
          route_id TEXT NOT NULL,
          PRIMARY KEY(dep,from_stop,trip_id,seq)
        ) WITHOUT ROWID;
        CREATE TEMP TABLE active_trips(trip_id TEXT PRIMARY KEY, route_id TEXT NOT NULL) WITHOUT ROWID;
        CREATE TEMP TABLE stop_times(
          trip_id TEXT NOT NULL,
          seq INTEGER NOT NULL,
          stop_id INTEGER NOT NULL,
          arr INTEGER NOT NULL,
          dep INTEGER NOT NULL,
          PRIMARY KEY(trip_id,seq)
        ) WITHOUT ROWID;
        """
    )
    db.executemany(
        "INSERT INTO routes VALUES(?,?,?,?,?)",
        [(r["route_id"], r["short_name"], r["long_name"], r["route_type"], r["route_mode"]) for r in routes.values()],
    )
    db.executemany(
        "INSERT INTO stops(stop_id,name,lat,lon,transport_type) VALUES(?,?,?,?,NULL)",
        [(s["stop_id"], s["name"], s["lat"], s["lon"]) for s in stops.values()],
    )
    db.executemany("INSERT INTO active_trips VALUES(?,?)", trips.items())
    generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    db.executemany(
        "INSERT INTO meta VALUES(?,?)",
        [
            ("generated_at", generated),
            ("notes", f"Official BUS/TRAM schedule for {target.isoformat()}; no synthetic rail"),
            ("rail_model", "none"),
            ("service_date", target.isoformat()),
            ("source", "data.mos.ru 60661/60662/60664/60665/60666"),
            ("schema", "human-router-surface-v1"),
        ],
    )
    db.commit()
    return db


def ingest_stop_times(db: sqlite3.Connection, path: Path, trips: Dict[str, str]) -> tuple[int, int]:
    batch = []
    seen = 0
    accepted = 0
    for row in iter_rows(path):
        seen += 1
        trip_id = str(value(row, "trip_id", default="")).strip()
        if trip_id not in trips:
            continue
        seq = as_int(value(row, "stop_sequence", "seq"), -1)
        stop_id = as_int(value(row, "stop_id"), -1)
        arr = parse_time_seconds(value(row, "arrival_time", "arr"))
        dep = parse_time_seconds(value(row, "departure_time", "dep"))
        if arr is None:
            arr = dep
        if dep is None:
            dep = arr
        if seq < 0 or stop_id < 0 or arr is None or dep is None:
            continue
        batch.append((trip_id, seq, stop_id, arr, dep))
        accepted += 1
        if len(batch) >= 20_000:
            db.executemany("INSERT OR REPLACE INTO stop_times VALUES(?,?,?,?,?)", batch)
            db.commit()
            batch.clear()
            if accepted % 200_000 < 20_000:
                print(f"stop-times accepted={accepted:,} scanned={seen:,}", flush=True)
    if batch:
        db.executemany("INSERT OR REPLACE INTO stop_times VALUES(?,?,?,?,?)", batch)
        db.commit()
    return seen, accepted


def materialize_connections(db: sqlite3.Connection) -> int:
    db.executescript(
        """
        INSERT OR IGNORE INTO connections(dep,from_stop,trip_id,seq,to_stop,arr,route_id)
        WITH ordered AS (
          SELECT
            s.trip_id,
            s.seq,
            s.stop_id AS from_stop,
            s.dep,
            LEAD(s.stop_id) OVER (PARTITION BY s.trip_id ORDER BY s.seq) AS to_stop,
            LEAD(s.arr) OVER (PARTITION BY s.trip_id ORDER BY s.seq) AS next_arr,
            t.route_id
          FROM stop_times s
          JOIN active_trips t ON t.trip_id=s.trip_id
        )
        SELECT dep,from_stop,trip_id,seq,to_stop,next_arr,route_id
        FROM ordered
        WHERE to_stop IS NOT NULL AND next_arr IS NOT NULL AND next_arr>=dep;
        """
    )
    count = db.execute("SELECT COUNT(*) FROM connections").fetchone()[0]
    db.execute("ANALYZE")
    db.commit()
    return int(count)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def deterministic_gzip(raw: bytes) -> bytes:
    return gzip.compress(raw, compresslevel=9, mtime=0)


def write_surface_manifest(root: Path, target: dt.date, sqlite_name: str, route_count: int, stop_count: int, connection_count: int) -> tuple[Path, bytes]:
    obj = {
        "schema": 1,
        "service_date": target.isoformat(),
        "primary_file": sqlite_name,
        "route_count": route_count,
        "stop_count": stop_count,
        "connection_count": connection_count,
        "source": "data.mos.ru 60661/60662/60664/60665/60666",
        "generated_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    compressed = deterministic_gzip(raw)
    path = root / "surface_manifest.json.gz"
    path.write_bytes(compressed)
    return path, raw


def load_npy_gz(path: Path):
    import numpy as np
    with gzip.open(path, "rb") as fh:
        return np.load(fh, allow_pickle=False)


def snap_stops_to_walk(runtime_root: Path, stops: Dict[int, dict]) -> tuple[Path, int]:
    import numpy as np

    lat_e7 = load_npy_gz(runtime_root / "walk_lat_e7.npy.gz")
    lon_e7 = load_npy_gz(runtime_root / "walk_lon_e7.npy.gz")
    grid_offsets = load_npy_gz(runtime_root / "walk_grid_offsets.npy.gz").astype(np.int64, copy=False)
    grid_nodes = load_npy_gz(runtime_root / "walk_grid_nodes.npy.gz").astype(np.int64, copy=False)

    cells = {}
    for cell in range(len(grid_offsets) - 1):
        start, end = int(grid_offsets[cell]), int(grid_offsets[cell + 1])
        if start >= end:
            continue
        node = int(grid_nodes[start])
        key = (int(math.floor(int(lat_e7[node]) / GRID_CELL_E7)), int(math.floor(int(lon_e7[node]) / GRID_CELL_E7)))
        cells[key] = (start, end)

    stop_ids = []
    walk_nodes = []
    valid = []
    snap_m = []
    for index, stop in enumerate(stops.values(), 1):
        target_lat = int(round(stop["lat"] * 10_000_000.0))
        target_lon = int(round(stop["lon"] * 10_000_000.0))
        base = (math.floor(target_lat / GRID_CELL_E7), math.floor(target_lon / GRID_CELL_E7))
        lon_scale = max(0.01, math.cos(math.radians(stop["lat"])))
        best_node = -1
        best_sq = float("inf")
        # A 2 km search ring is ample for normal Moscow stops. If it fails, mark invalid rather than
        # doing a 2.25M-node brute force in the daily builder.
        for ring in range(0, 9):
            for dx in range(-ring, ring + 1):
                for dy in range(-ring, ring + 1):
                    if ring > 0 and abs(dx) != ring and abs(dy) != ring:
                        continue
                    bounds = cells.get((base[0] + dx, base[1] + dy))
                    if not bounds:
                        continue
                    start, end = bounds
                    ids = grid_nodes[start:end]
                    dlat = lat_e7[ids].astype(np.float64) - target_lat
                    dlon = (lon_e7[ids].astype(np.float64) - target_lon) * lon_scale
                    sq = dlat * dlat + dlon * dlon
                    local = int(np.argmin(sq))
                    local_sq = float(sq[local])
                    if local_sq < best_sq:
                        best_sq = local_sq
                        best_node = int(ids[local])
            if best_node >= 0 and ring >= 1:
                break

        meters = math.sqrt(best_sq) * METERS_PER_E7 if best_node >= 0 else float("inf")
        stop_ids.append(stop["stop_id"])
        walk_nodes.append(max(0, best_node))
        valid.append(1 if best_node >= 0 and meters <= 2_000 else 0)
        snap_m.append(float(meters if math.isfinite(meters) else 0.0))
        if index % 2_000 == 0:
            print(f"walk snaps {index:,}/{len(stops):,}", flush=True)

    path = runtime_root / "surface_stop_walk_nodes.npz"
    np.savez_compressed(
        path,
        stop_id=np.asarray(stop_ids, dtype=np.int64),
        walk_node=np.asarray(walk_nodes, dtype=np.uint32),
        valid=np.asarray(valid, dtype=np.uint8),
        snap_m=np.asarray(snap_m, dtype=np.float32),
    )
    return path, sum(valid)


def update_runtime_manifest(manifest_path: Path, runtime_root: Path, target: dt.date, sqlite_gz: Path, sqlite_raw: bytes, surface_manifest_gz: Path, surface_manifest_raw: bytes, stop_walk: Optional[Path]) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    old_packs = manifest.get("packs", [])
    previous_margin = max(0, int(manifest.get("required_free_bytes", 0)) - int(manifest.get("total_download_bytes", 0)) - int(manifest.get("total_installed_bytes", 0)))
    packs = [p for p in old_packs if p.get("role") not in SURFACE_ROLES]

    packs.append({
        "id": f"surface-db-{target.isoformat()}",
        "role": "surface_db",
        "file": sqlite_gz.name,
        "install_as": f"surface/surface_{target.isoformat()}.sqlite",
        "compression": "gzip",
        "compressed_bytes": sqlite_gz.stat().st_size,
        "raw_bytes": len(sqlite_raw),
        "sha256_compressed": sha256_bytes(sqlite_gz.read_bytes()),
        "sha256_raw": sha256_bytes(sqlite_raw),
        "required": True,
    })
    packs.append({
        "id": "surface-manifest",
        "role": "surface_manifest",
        "file": surface_manifest_gz.name,
        "install_as": "surface/manifest.json",
        "compression": "gzip",
        "compressed_bytes": surface_manifest_gz.stat().st_size,
        "raw_bytes": len(surface_manifest_raw),
        "sha256_compressed": sha256_bytes(surface_manifest_gz.read_bytes()),
        "sha256_raw": sha256_bytes(surface_manifest_raw),
        "required": True,
    })
    if stop_walk is not None:
        raw = stop_walk.read_bytes()
        packs.append({
            "id": "stop-walk-map",
            "role": "stop_walk_map",
            "file": stop_walk.name,
            "install_as": "surface/stop_walk_nodes.npz",
            "compression": "none",
            "compressed_bytes": len(raw),
            "raw_bytes": len(raw),
            "sha256_compressed": sha256_bytes(raw),
            "sha256_raw": sha256_bytes(raw),
            "required": True,
        })

    manifest["packs"] = packs
    manifest["version"] = f"moscow-runtime-{target.isoformat()}-surface"
    manifest["description"] = "Human Router Moscow runtime: current BUS/TRAM timetable + OSM WALK graph + routeable rail graph"
    manifest["total_download_bytes"] = sum(int(p["compressed_bytes"]) for p in packs)
    manifest["total_installed_bytes"] = sum(int(p["raw_bytes"]) for p in packs)
    manifest["required_free_bytes"] = manifest["total_download_bytes"] + manifest["total_installed_bytes"] + previous_margin
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--stop-times", required=True, type=Path, help="dataset 60661 export/API JSONL")
    parser.add_argument("--stops", required=True, type=Path, help="dataset 60662")
    parser.add_argument("--routes", required=True, type=Path, help="dataset 60664")
    parser.add_argument("--trips", required=True, type=Path, help="dataset 60665")
    parser.add_argument("--calendar", required=True, type=Path, help="dataset 60666")
    parser.add_argument("--date", required=True, type=lambda s: dt.date.fromisoformat(s))
    parser.add_argument("--runtime-root", type=Path, default=Path("runtime"))
    parser.add_argument("--runtime-manifest", type=Path, default=Path("manifest.json"))
    parser.add_argument("--skip-walk-snap", action="store_true")
    args = parser.parse_args()

    args.runtime_root.mkdir(parents=True, exist_ok=True)
    print("loading routes/calendar/trips/stops", flush=True)
    routes = load_routes(args.routes)
    services = load_calendar(args.calendar, args.date)
    trips = load_trips(args.trips, services, routes)
    stops = load_stops(args.stops)
    print(f"routes={len(routes):,} active_services={len(services):,} active_trips={len(trips):,} stops={len(stops):,}", flush=True)
    if not routes or not services or not trips or not stops:
        raise SystemExit("Official source tables produced an empty required set; refusing to publish")

    sqlite_name = f"surface_{args.date.isoformat()}.sqlite"
    sqlite_path = args.runtime_root / sqlite_name
    db = create_database(sqlite_path, routes, stops, trips, args.date)
    scanned, accepted = ingest_stop_times(db, args.stop_times, trips)
    connections = materialize_connections(db)
    db.close()
    if connections <= 0:
        raise SystemExit("No timetable connections were built; refusing to publish")

    sqlite_raw = sqlite_path.read_bytes()
    sqlite_compressed = deterministic_gzip(sqlite_raw)
    sqlite_gz = args.runtime_root / f"{sqlite_name}.gz"
    sqlite_gz.write_bytes(sqlite_compressed)
    sqlite_path.unlink()

    surface_manifest_gz, surface_manifest_raw = write_surface_manifest(
        args.runtime_root, args.date, sqlite_name, len(routes), len(stops), connections
    )

    stop_walk = None
    valid_snaps = None
    if not args.skip_walk_snap:
        required = [
            args.runtime_root / "walk_lat_e7.npy.gz",
            args.runtime_root / "walk_lon_e7.npy.gz",
            args.runtime_root / "walk_grid_offsets.npy.gz",
            args.runtime_root / "walk_grid_nodes.npy.gz",
        ]
        if any(not p.exists() for p in required):
            raise SystemExit("Walk graph packs missing; cannot rebuild stop_walk_nodes safely")
        stop_walk, valid_snaps = snap_stops_to_walk(args.runtime_root, stops)

    if args.runtime_manifest.exists():
        update_runtime_manifest(
            args.runtime_manifest,
            args.runtime_root,
            args.date,
            sqlite_gz,
            sqlite_raw,
            surface_manifest_gz,
            surface_manifest_raw,
            stop_walk,
        )

    print(json.dumps({
        "service_date": args.date.isoformat(),
        "routes": len(routes),
        "active_services": len(services),
        "active_trips": len(trips),
        "stops": len(stops),
        "stop_times_scanned": scanned,
        "stop_times_accepted": accepted,
        "connections": connections,
        "valid_walk_snaps": valid_snaps,
        "surface_gzip_bytes": sqlite_gz.stat().st_size,
        "surface_gzip_sha256": sha256_bytes(sqlite_gz.read_bytes()),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
