#!/usr/bin/env python3
import csv
import gzip
import json
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools" / "build_surface_runtime.py"
TARGET_DATE = "2026-08-14"  # Friday


def write_csv(path: Path, fieldnames, rows):
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, delimiter=";")
        writer.writeheader()
        writer.writerows(rows)


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


if __name__ == "__main__":
    main()
