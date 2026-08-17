#!/usr/bin/env python3
import csv
import gzip
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools" / "build_surface_runtime.py"
TARGET_DATE = "2026-08-14"  # Friday
RUNTIME_BASE_URL = "https://github.com/fowov2-jpg/676017/releases/download/runtime-current/"


def write_csv(path: Path, fieldnames, rows):
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, delimiter=";")
        writer.writeheader()
        writer.writerows(rows)


def fetch_bytes(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "VremyaHodom-CI/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def test_published_address_runtime(tmp: Path):
    manifest = json.loads(fetch_bytes(RUNTIME_BASE_URL + "manifest.json"))
    packs = [pack for pack in manifest.get("packs", []) if pack.get("required", True)]
    address_pack = next(
        (
            pack
            for pack in packs
            if pack.get("install_as") == "address/address.sqlite"
            or pack.get("file") == "address_moscow.sqlite.gz"
        ),
        None,
    )
    assert address_pack is not None, "runtime-current manifest has no required Moscow address pack"
    assert address_pack.get("install_as") == "address/address.sqlite", address_pack

    filename = address_pack.get("file")
    assert filename, address_pack
    compressed = fetch_bytes(RUNTIME_BASE_URL + filename)
    expected_size = address_pack.get("compressed_bytes")
    if expected_size is not None:
        assert len(compressed) == int(expected_size), (
            f"address runtime compressed size mismatch: {len(compressed)} != {expected_size}"
        )
    expected_sha = address_pack.get("sha256_compressed")
    if expected_sha:
        actual_sha = hashlib.sha256(compressed).hexdigest()
        assert actual_sha.lower() == str(expected_sha).lower(), (
            f"address runtime sha256 mismatch: {actual_sha} != {expected_sha}"
        )

    database_path = tmp / "published-address.sqlite"
    database_path.write_bytes(gzip.decompress(compressed))
    assert database_path.stat().st_size > 0, "published address database is empty"

    database = sqlite3.connect(database_path)
    try:
        schema_row = database.execute("SELECT schema_version FROM metadata LIMIT 1").fetchone()
        assert schema_row is not None and int(schema_row[0]) == 1, f"unexpected address schema: {schema_row}"
        columns = {row[1] for row in database.execute("PRAGMA table_info(addresses)")}
        required_columns = {
            "street",
            "house",
            "district",
            "locality",
            "postcode",
            "lat",
            "lon",
            "norm_street",
            "norm_house",
        }
        assert required_columns <= columns, f"address table missing columns: {sorted(required_columns - columns)}"
        address_count = int(database.execute("SELECT COUNT(*) FROM addresses").fetchone()[0])
        assert address_count >= 10_000, f"published address index is implausibly small: {address_count}"
        located_count = int(
            database.execute(
                "SELECT COUNT(*) FROM addresses WHERE lat BETWEEN 54.7 AND 57.15 AND lon BETWEEN 35.0 AND 40.5"
            ).fetchone()[0]
        )
        assert located_count >= 10_000, f"published address index lacks Moscow-area coordinates: {located_count}"
    finally:
        database.close()

    print(f"published Moscow address runtime: OK ({address_count} rows)")


def main():
    with tempfile.TemporaryDirectory() as tmp_string:
        tmp = Path(tmp_string)
        runtime = tmp / "runtime"
        runtime.mkdir()

        routes = tmp / "60664.csv"
        calendar = tmp / "60666.csv"
        trips = tmp / "60665.csv"
        stops = tmp / "60662.csv"
        stop_times = tmp / "60661.jsonl"

        write_csv(
            routes,
            ["route_id", "route_short_name", "route_long_name", "route_type"],
            [
                {"route_id": "b1", "route_short_name": "10", "route_long_name": "Тестовый автобус", "route_type": "3"},
                {"route_id": "t1", "route_short_name": "39", "route_long_name": "Тестовый трамвай", "route_type": "0"},
                {"route_id": "rail1", "route_short_name": "D", "route_long_name": "Не наземный", "route_type": "1"},
            ],
        )
        write_csv(
            calendar,
            ["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"],
            [
                {"service_id": "active", "monday": 0, "tuesday": 0, "wednesday": 0, "thursday": 0, "friday": 1, "saturday": 0, "sunday": 0, "start_date": "2026-08-01", "end_date": "2026-08-31"},
                {"service_id": "inactive", "monday": 1, "tuesday": 1, "wednesday": 1, "thursday": 1, "friday": 0, "saturday": 1, "sunday": 1, "start_date": "2026-08-01", "end_date": "2026-08-31"},
            ],
        )
        write_csv(
            trips,
            ["route_id", "service_id", "trip_id", "trip_headsign", "direction_id"],
            [
                {"route_id": "b1", "service_id": "active", "trip_id": "bus-trip", "trip_headsign": "Центр", "direction_id": 0},
                {"route_id": "t1", "service_id": "active", "trip_id": "tram-trip", "trip_headsign": "Депо", "direction_id": 1},
                {"route_id": "b1", "service_id": "inactive", "trip_id": "inactive-trip", "trip_headsign": "Не должен попасть", "direction_id": 0},
                {"route_id": "rail1", "service_id": "active", "trip_id": "rail-trip", "trip_headsign": "Не должен попасть", "direction_id": 0},
            ],
        )
        write_csv(
            stops,
            ["stop_id", "stop_name", "lat", "lon"],
            [
                {"stop_id": 1, "stop_name": "Остановка 1", "lat": 55.7500, "lon": 37.6100},
                {"stop_id": 2, "stop_name": "Остановка 2", "lat": 55.7510, "lon": 37.6200},
                {"stop_id": 3, "stop_name": "Остановка 3", "lat": 55.7520, "lon": 37.6300},
                {"stop_id": 4, "stop_name": "Остановка 4", "lat": 55.7530, "lon": 37.6400},
            ],
        )

        rows = [
            {"trip_id": "bus-trip", "arrival_time": "08:00:00", "departure_time": "08:00:00", "stop_id": 1, "stop_sequence": 1},
            {"trip_id": "bus-trip", "arrival_time": "08:10:00", "departure_time": "08:11:00", "stop_id": 2, "stop_sequence": 2},
            {"trip_id": "bus-trip", "arrival_time": "08:20:00", "departure_time": "08:20:00", "stop_id": 3, "stop_sequence": 3},
            {"trip_id": "tram-trip", "arrival_time": "25:00:00", "departure_time": "25:00:00", "stop_id": 3, "stop_sequence": 1},
            {"trip_id": "tram-trip", "arrival_time": "25:12:00", "departure_time": "25:12:00", "stop_id": 4, "stop_sequence": 2},
            {"trip_id": "inactive-trip", "arrival_time": "09:00:00", "departure_time": "09:00:00", "stop_id": 1, "stop_sequence": 1},
            {"trip_id": "inactive-trip", "arrival_time": "09:05:00", "departure_time": "09:05:00", "stop_id": 2, "stop_sequence": 2},
            {"trip_id": "rail-trip", "arrival_time": "10:00:00", "departure_time": "10:00:00", "stop_id": 1, "stop_sequence": 1},
            {"trip_id": "rail-trip", "arrival_time": "10:04:00", "departure_time": "10:04:00", "stop_id": 2, "stop_sequence": 2},
        ]
        with stop_times.open("w", encoding="utf-8") as fh:
            for index, row in enumerate(rows, 1):
                fh.write(json.dumps({"global_id": index, "Cells": row}, ensure_ascii=False) + "\n")

        command = [
            sys.executable,
            str(BUILDER),
            "--stop-times", str(stop_times),
            "--stops", str(stops),
            "--routes", str(routes),
            "--trips", str(trips),
            "--calendar", str(calendar),
            "--date", TARGET_DATE,
            "--runtime-root", str(runtime),
            "--runtime-manifest", str(tmp / "missing-manifest.json"),
            "--skip-walk-snap",
        ]
        subprocess.run(command, cwd=ROOT, check=True)

        surface_gz = runtime / f"surface_{TARGET_DATE}.sqlite.gz"
        surface_manifest_gz = runtime / "surface_manifest.json.gz"
        assert surface_gz.exists(), "surface SQLite gzip was not generated"
        assert surface_manifest_gz.exists(), "surface manifest was not generated"

        manifest = json.loads(gzip.decompress(surface_manifest_gz.read_bytes()))
        assert manifest["service_date"] == TARGET_DATE
        assert manifest["primary_file"] == f"surface_{TARGET_DATE}.sqlite"
        assert manifest["route_count"] == 2
        assert manifest["stop_count"] == 4
        assert manifest["connection_count"] == 3

        sqlite_path = tmp / "built.sqlite"
        sqlite_path.write_bytes(gzip.decompress(surface_gz.read_bytes()))
        db = sqlite3.connect(sqlite_path)
        try:
            assert db.execute("SELECT value FROM meta WHERE key='service_date'").fetchone()[0] == TARGET_DATE
            route_modes = dict(db.execute("SELECT route_id,route_mode FROM routes"))
            assert route_modes == {"b1": "BUS", "t1": "TRAM"}, route_modes

            connections = db.execute(
                "SELECT dep,from_stop,trip_id,seq,to_stop,arr,route_id FROM connections ORDER BY dep,trip_id,seq"
            ).fetchall()
            assert connections == [
                (28_800, 1, "bus-trip", 1, 2, 29_400, "b1"),
                (29_460, 2, "bus-trip", 2, 3, 30_000, "b1"),
                (90_000, 3, "tram-trip", 1, 4, 90_720, "t1"),
            ], connections
            assert db.execute("SELECT COUNT(*) FROM connections WHERE trip_id='inactive-trip'").fetchone()[0] == 0
            assert db.execute("SELECT COUNT(*) FROM connections WHERE trip_id='rail-trip'").fetchone()[0] == 0
        finally:
            db.close()

        print("surface runtime integration test: OK")
        test_published_address_runtime(tmp)


if __name__ == "__main__":
    main()
