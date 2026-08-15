#!/usr/bin/env python3
"""Build the compact Android MCD-3/commuter timetable asset from audited MTPPK data.

The input is produced by ``parse_mtppk_xlsx.py`` from the official public workbook. Coordinates
come from the verified runtime rail graph. Stops outside the Moscow/Kryukovo graph are intentionally
omitted: this asset must not pretend to route to a station whose location has not been verified.
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
from pathlib import Path


SOURCE_URL = "https://mtppk.ru/download.php?id=15765"
ALIASES = {
    "петрразумовская": "петровскоразумовская",
    # Moscow Passenger (Leningradsky terminal) is adjacent to the Three Stations MCD hub.
    "москвапассажирская": "площадьтрехвокзалов",
}


def normalized(value: str) -> str:
    return re.sub(r"[^а-яa-z0-9]+", "", value.lower().replace("ё", "е"))


def read_json(path: Path) -> dict:
    if path.suffix.lower() == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding="utf-8"))


def graph_points(graph: dict) -> dict[str, tuple[float, float]]:
    preferred: dict[str, list[tuple[float, float]]] = {}
    fallback: dict[str, list[tuple[float, float]]] = {}
    for route in graph.get("routes", []):
        target = preferred if route.get("mode") == "MCD" else fallback
        for stop in route.get("stops", []):
            key = normalized(str(stop.get("name", "")))
            if not key:
                continue
            target.setdefault(key, []).append((float(stop["lat"]), float(stop["lon"])))

    points = {}
    for key in set(preferred) | set(fallback):
        values = preferred.get(key) or fallback[key]
        points[key] = (
            round(sum(item[0] for item in values) / len(values), 7),
            round(sum(item[1] for item in values) / len(values), 7),
        )
    return points


def build(timetable: dict, graph: dict) -> dict:
    points = graph_points(graph)
    station_ids: dict[str, int] = {}
    stations: list[dict] = []

    def station_id(name: str) -> int | None:
        key = normalized(name)
        point = points.get(ALIASES.get(key, key))
        if point is None:
            return None
        existing = station_ids.get(key)
        if existing is not None:
            return existing
        ident = len(stations)
        station_ids[key] = ident
        stations.append({"id": ident, "name": name, "lat": point[0], "lon": point[1]})
        return ident

    trips = []
    for source_trip in timetable.get("trips", []):
        mode = source_trip.get("mode")
        if mode not in {"MCD", "TRAIN"}:
            continue
        stops = []
        for stop in source_trip.get("stops", []):
            ident = station_id(str(stop.get("station", "")))
            if ident is None:
                continue
            stops.append([
                ident,
                int(stop["time_seconds"]),
                1 if stop.get("pickup_allowed", True) else 0,
                1 if stop.get("dropoff_allowed", True) else 0,
            ])
        # A route needs two verified passenger stops. Consecutive duplicate coordinates/names are
        # harmless, but duplicate stop IDs in one train are not useful to the Android scan.
        compact = []
        for stop in stops:
            if compact and compact[-1][0] == stop[0] and compact[-1][1] == stop[1]:
                continue
            compact.append(stop)
        if len(compact) < 2:
            continue
        trips.append({
            "id": source_trip["id"],
            "mode": mode,
            "number": str(source_trip.get("train_number", "")),
            "service": source_trip.get("service_rule", "published_default"),
            "stops": compact,
        })

    effective = sorted(
        {sheet.get("effective_from") for sheet in timetable.get("sheets", []) if sheet.get("effective_from")}
    )
    return {
        "schema": 1,
        "source": {
            "name": timetable.get("source", {}).get("name", "АО МТ ППК public timetable workbook"),
            "url": SOURCE_URL,
            "sha256": timetable.get("source", {}).get("sha256"),
        },
        "effective_from": effective[-1] if effective else None,
        "coverage": "МЦД-3 и пригородные поезда на участке Москва-Пассажирская — Зеленоград-Крюково",
        "limitations": "Базовое опубликованное расписание; временные отмены и оперативные изменения не применены.",
        "stations": stations,
        "trips": trips,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timetable", required=True, type=Path)
    parser.add_argument("--rail-graph", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    result = build(read_json(args.timetable), read_json(args.rail_graph))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "stations": len(result["stations"]),
        "trips": len(result["trips"]),
        "mcd": sum(1 for item in result["trips"] if item["mode"] == "MCD"),
        "train": sum(1 for item in result["trips"] if item["mode"] == "TRAIN"),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
