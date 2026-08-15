#!/usr/bin/env python3
"""Parse official MTPPK XLSX matrix timetables into machine-readable trips.

The MTPPK public schedule workbook is a matrix: train numbers are columns and stations are rows.
This parser deliberately uses only Python's standard library so it can run in GitHub Actions without
Excel/openpyxl. It extracts only explicit published times; no stop time or service day is invented.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Optional

MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
DOC_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
TRAIN_HEADER_RE = re.compile(r"^\s*(\d{4})(?:\s+(Р|СВ))?\s*$", re.IGNORECASE)
TIME_RE = re.compile(r"^(\d{1,2}):(\d{2})(?::(\d{2}))?$")
EFFECTIVE_DATE_RE = re.compile(r"\b(?:с|c)\s+(\d{2}\.\d{2}\.\d{4})\b", re.IGNORECASE)
LEGEND_PREFIX = "условные обозначения"


def normalize_space(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def normalize_station(value: str) -> str:
    return re.sub(r"[^а-яa-z0-9]+", "", normalize_space(value).lower().replace("ё", "е"))


def column_of(cell_ref: str) -> str:
    match = re.match(r"([A-Z]+)", cell_ref)
    if not match:
        raise ValueError(f"invalid XLSX cell reference: {cell_ref}")
    return match.group(1)


def load_shared_strings(archive: zipfile.ZipFile) -> list[str]:
    path = "xl/sharedStrings.xml"
    if path not in archive.namelist():
        return []
    root = ET.fromstring(archive.read(path))
    result = []
    for item in root:
        result.append("".join(node.text or "" for node in item.iter(f"{{{MAIN_NS}}}t")))
    return result


def workbook_sheets(archive: zipfile.ZipFile) -> list[tuple[str, str]]:
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    targets = {item.attrib["Id"]: item.attrib["Target"] for item in relationships}
    sheets = workbook.find(f"{{{MAIN_NS}}}sheets")
    if sheets is None:
        return []
    result = []
    for sheet in sheets:
        rel_id = sheet.attrib[f"{{{DOC_REL_NS}}}id"]
        target = targets[rel_id].lstrip("/")
        if not target.startswith("xl/"):
            target = "xl/" + target
        result.append((sheet.attrib["name"], target))
    return result


def read_sheet_rows(
    archive: zipfile.ZipFile,
    sheet_path: str,
    shared_strings: list[str],
) -> dict[int, dict[str, tuple[Optional[str], Optional[str]]]]:
    root = ET.fromstring(archive.read(sheet_path))
    sheet_data = root.find(f"{{{MAIN_NS}}}sheetData")
    if sheet_data is None:
        return {}
    result: dict[int, dict[str, tuple[Optional[str], Optional[str]]]] = {}
    for row in sheet_data:
        row_values: dict[str, tuple[Optional[str], Optional[str]]] = {}
        for cell in row:
            ref = cell.attrib.get("r")
            if not ref:
                continue
            cell_type = cell.attrib.get("t")
            value_node = cell.find(f"{{{MAIN_NS}}}v")
            raw = value_node.text if value_node is not None else None
            if cell_type == "s" and raw is not None:
                try:
                    value = shared_strings[int(raw)]
                except (ValueError, IndexError):
                    value = raw
            elif cell_type == "inlineStr":
                value = "".join(node.text or "" for node in cell.iter(f"{{{MAIN_NS}}}t"))
            else:
                value = raw
            row_values[column_of(ref)] = (cell_type, value)
        result[int(row.attrib["r"])] = row_values
    return result


def parse_time_seconds(cell_type: Optional[str], value: Optional[str]) -> Optional[int]:
    if value is None:
        return None
    text = normalize_space(str(value))
    if not text:
        return None
    match = TIME_RE.fullmatch(text)
    if match:
        hour = int(match.group(1))
        minute = int(match.group(2))
        second = int(match.group(3) or 0)
        if hour <= 47 and minute < 60 and second < 60:
            return hour * 3600 + minute * 60 + second
        return None
    # Native Excel time values are day fractions. MTPPK publishes minute precision, so round to
    # the nearest minute to eliminate binary floating-point residue in worksheet XML.
    if cell_type is None:
        try:
            fraction = float(text)
        except ValueError:
            return None
        if 0.0 <= fraction < 2.0:
            return int(round(fraction * 24 * 60)) * 60
    return None


def detect_header_row(rows: dict[int, dict[str, tuple[Optional[str], Optional[str]]]]) -> int:
    scored = []
    for row_number, values in rows.items():
        count = 0
        for _cell_type, value in values.values():
            if value is not None and TRAIN_HEADER_RE.fullmatch(normalize_space(str(value))):
                count += 1
        if count:
            scored.append((count, -row_number, row_number))
    if not scored:
        raise ValueError("could not find train-number header row")
    return max(scored)[2]


def extract_effective_date(rows: dict[int, dict[str, tuple[Optional[str], Optional[str]]]]) -> Optional[str]:
    for row_number in sorted(rows)[:8]:
        for _cell_type, value in rows[row_number].values():
            if value is None:
                continue
            match = EFFECTIVE_DATE_RE.search(normalize_space(str(value)))
            if match:
                try:
                    return dt.datetime.strptime(match.group(1), "%d.%m.%Y").date().isoformat()
                except ValueError:
                    pass
    return None


def service_rule(flag: str) -> str:
    if flag == "Р":
        return "workdays"
    if flag == "СВ":
        return "weekends"
    return "published_default"


def passenger_flags(station: str, mode: str) -> tuple[bool, bool]:
    normalized = normalize_station(station)
    if "закрыта" in normalized:
        return False, False
    # The workbook legend explicitly marks Post Rizhsky as technical for MCD trains.
    if mode == "MCD" and normalized == "пострижский":
        return False, False
    return True, True


def classify_mode(stops: list[dict]) -> str:
    if len(stops) < 2:
        return "TRAIN"
    first = normalize_station(stops[0]["station"])
    last = normalize_station(stops[-1]["station"])

    def mcd_endpoint(value: str) -> bool:
        return value.startswith("останкино") or value == "пострижский"

    if (first == "зеленоградкрюково" and mcd_endpoint(last)) or (
        last == "зеленоградкрюково" and mcd_endpoint(first)
    ):
        return "MCD"
    return "TRAIN"


def monotonic_stops(
    rows: dict[int, dict[str, tuple[Optional[str], Optional[str]]]],
    station_rows: list[tuple[int, str]],
    column: str,
) -> list[dict]:
    stops = []
    previous_seconds: Optional[int] = None
    for row_number, station in station_rows:
        cell_type, raw_value = rows.get(row_number, {}).get(column, (None, None))
        seconds = parse_time_seconds(cell_type, raw_value)
        if seconds is None:
            continue
        absolute = seconds
        # A large backwards jump is a midnight rollover. A small backwards jump is usually a matrix
        # branch/rejoin artifact; do not invent a chronology for it. Some Excel cells already store
        # a value above 1.0 for times after midnight, while others restart at a sub-1.0 fraction.
        # Advance only the current value so an already absolute 24:xx value is never shifted twice.
        while previous_seconds is not None and absolute < previous_seconds - 6 * 3600:
            absolute += 86400
        if previous_seconds is not None and absolute < previous_seconds:
            continue
        if (
            stops
            and normalize_station(stops[-1]["station"]) == normalize_station(station)
            and stops[-1]["time_seconds"] == absolute
        ):
            continue
        stops.append(
            {
                "station": normalize_space(station),
                "time_seconds": absolute,
                "published_value": normalize_space(str(raw_value)) if raw_value is not None else None,
            }
        )
        previous_seconds = absolute
    return stops


def parse_workbook(path: Path) -> dict:
    source_bytes = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        shared_strings = load_shared_strings(archive)
        parsed_sheets = []
        all_trips = []
        for sheet_name, sheet_path in workbook_sheets(archive):
            rows = read_sheet_rows(archive, sheet_path, shared_strings)
            if not rows:
                continue
            try:
                header_row = detect_header_row(rows)
            except ValueError:
                continue
            effective_date = extract_effective_date(rows)
            headers = []
            for column, (_cell_type, value) in rows[header_row].items():
                if value is None:
                    continue
                match = TRAIN_HEADER_RE.fullmatch(normalize_space(str(value)))
                if match:
                    headers.append((column, match.group(1), (match.group(2) or "").upper()))

            station_rows = []
            for row_number in sorted(number for number in rows if number > header_row):
                station_cell = rows[row_number].get("A")
                if not station_cell or station_cell[1] is None:
                    continue
                station = normalize_space(str(station_cell[1]))
                if station.lower().startswith(LEGEND_PREFIX):
                    break
                station_rows.append((row_number, station))

            sheet_trip_count = 0
            sheet_mcd_count = 0
            for column, train_number, flag in headers:
                stops = monotonic_stops(rows, station_rows, column)
                if len(stops) < 2:
                    continue
                mode = classify_mode(stops)
                for stop in stops:
                    pickup, dropoff = passenger_flags(stop["station"], mode)
                    stop["pickup_allowed"] = pickup
                    stop["dropoff_allowed"] = dropoff
                route_key = hashlib.sha1(
                    f"{sheet_name}|{train_number}|{flag}|{stops[0]['station']}|{stops[-1]['station']}".encode(
                        "utf-8"
                    )
                ).hexdigest()[:16]
                trip = {
                    "id": f"mtppk:{route_key}",
                    "operator": "АО МТ ППК",
                    "train_number": train_number,
                    "mode": mode,
                    "service_rule": service_rule(flag),
                    "service_marker": flag or None,
                    "sheet": sheet_name,
                    "effective_from": effective_date,
                    "from": stops[0]["station"],
                    "to": stops[-1]["station"],
                    "stops": stops,
                }
                all_trips.append(trip)
                sheet_trip_count += 1
                if mode == "MCD":
                    sheet_mcd_count += 1
            parsed_sheets.append(
                {
                    "name": sheet_name,
                    "effective_from": effective_date,
                    "header_row": header_row,
                    "published_train_columns": len(headers),
                    "parsed_trips": sheet_trip_count,
                    "mcd_trips": sheet_mcd_count,
                }
            )

    return {
        "schema": 1,
        "source": {
            "name": "АО МТ ППК public timetable workbook",
            "file": path.name,
            "sha256": hashlib.sha256(source_bytes).hexdigest(),
        },
        "notes": [
            "Only explicit published workbook times are extracted; '-' and textual cross-references are not converted into stops.",
            "Р means workdays and СВ means weekends as defined in the workbook legend; published_default is retained without inventing a holiday calendar.",
            "MCD classification is conservative: only Kryukovo–Ostankino/Post Rizhsky short-turn trips are tagged MCD; other services remain TRAIN.",
            "Current temporary cancellations/short turns are not applied by this base-workbook parser and must be overlaid separately.",
        ],
        "sheets": parsed_sheets,
        "trips": all_trips,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.input.suffix.lower() != ".xlsx":
        raise SystemExit("MTPPK parser currently accepts .xlsx; legacy .xls requires a separate converter/importer")
    result = parse_workbook(args.input)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "file": args.input.name,
                "sheets": len(result["sheets"]),
                "trips": len(result["trips"]),
                "mcd_trips": sum(1 for trip in result["trips"] if trip["mode"] == "MCD"),
                "train_trips": sum(1 for trip in result["trips"] if trip["mode"] == "TRAIN"),
                "effective_dates": sorted(
                    {sheet["effective_from"] for sheet in result["sheets"] if sheet["effective_from"]}
                ),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
