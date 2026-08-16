#!/usr/bin/env python3
import argparse
import gzip
import hashlib
import json
import math
import re
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

OSM_REL = "https://api.openstreetmap.org/api/0.6/relation/{}"
OSM_NODES = "https://api.openstreetmap.org/api/0.6/nodes?nodes={}"
OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
UA = "HumanRouterRuntimeBuilder/0.6 (+https://github.com/fowov2-jpg/676017)"
PACK_ID = "rail-routing-graph"
MOSCOW_EXIT_BBOX = (55.10, 36.80, 56.20, 38.40)
MAX_EXIT_STATION_DISTANCE_METERS = 750.0


def fetch_xml(url: str) -> ET.Element:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as response:
        return ET.fromstring(response.read())


def fetch_overpass_json(query: str):
    payload = urllib.parse.urlencode({"data": query}).encode("utf-8")
    last_error = None
    for endpoint in OVERPASS_ENDPOINTS:
        for attempt in range(2):
            try:
                req = urllib.request.Request(
                    endpoint,
                    data=payload,
                    headers={
                        "User-Agent": UA,
                        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    },
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=75) as response:
                    return json.loads(response.read())
            except Exception as exc:
                last_error = exc
                print(f"warning: Overpass {endpoint} attempt {attempt + 1} failed: {exc}")
                time.sleep(1.5 * (attempt + 1))
    print(f"warning: metro exits unavailable from Overpass: {last_error}")
    return {"elements": []}


def parse_duration_seconds(value):
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    parts = text.split(":")
    try:
        if len(parts) == 1:
            return int(round(float(parts[0]) * 60.0))
        if len(parts) == 2:
            return int(parts[0]) * 3600 + int(parts[1]) * 60
        if len(parts) == 3:
            return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
    except ValueError:
        return None
    return None


def haversine_meters(a, b):
    r = 6_371_000.0
    p1 = math.radians(a["lat"])
    p2 = math.radians(b["lat"])
    dp = math.radians(b["lat"] - a["lat"])
    dl = math.radians(b["lon"] - a["lon"])
    q = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return max(1, int(round(2 * r * math.asin(min(1.0, math.sqrt(q))))))


def normalize_name(value):
    value = str(value or "").lower().replace("ё", "е")
    return re.sub(r"[^а-яa-z0-9]+", "", value)


def exit_display_ref(tags):
    # OSM ref is used only when a mapper actually supplied it. Never invent an exit number.
    for key in ("ref", "local_ref"):
        value = str(tags.get(key, "")).strip()
        if value and len(value) <= 12:
            return value
    return None


def build_metro_exits(stations):
    south, west, north, east = MOSCOW_EXIT_BBOX
    query = f"""
[out:json][timeout:60];
node[\"railway\"=\"subway_entrance\"]({south},{west},{north},{east});
out body;
"""
    data = fetch_overpass_json(query)
    elements = [item for item in data.get("elements", []) if item.get("type") == "node"]
    if not elements:
        return []

    station_rows = list(stations.values())
    exits = []
    for element in elements:
        tags = element.get("tags") or {}
        point = {
            "lat": float(element["lat"]),
            "lon": float(element["lon"]),
        }
        best = None
        best_distance = float("inf")
        entrance_name = normalize_name(" ".join([
            str(tags.get("name", "")),
            str(tags.get("official_name", "")),
            str(tags.get("description", "")),
            str(tags.get("exit_to", "")),
        ]))

        for station in station_rows:
            distance = haversine_meters(point, station)
            if distance > MAX_EXIT_STATION_DISTANCE_METERS:
                continue
            station_name = normalize_name(station.get("name"))
            # If the entrance itself carries the station name, strongly prefer that match.
            name_bonus = 220.0 if station_name and station_name in entrance_name else 0.0
            score = distance - name_bonus
            if score < best_distance:
                best_distance = score
                best = (station, distance)

        if best is None:
            continue
        station, real_distance = best
        ref = exit_display_ref(tags)
        name = (
            str(tags.get("exit_to") or "").strip()
            or str(tags.get("name") or "").strip()
            or str(tags.get("description") or "").strip()
        )
        exits.append({
            "osm_id": str(element["id"]),
            "station_name": station.get("name"),
            "station_osm_stop_id": station.get("osm_stop_id"),
            "ref": ref,
            "name": name or None,
            "lat": point["lat"],
            "lon": point["lon"],
            "wheelchair": tags.get("wheelchair"),
            "entrance": tags.get("entrance"),
            "distance_to_station_meters": int(round(real_distance)),
            "source": "OpenStreetMap railway=subway_entrance",
        })

    exits.sort(key=lambda item: (normalize_name(item.get("station_name")), item.get("ref") or "", item["osm_id"]))
    print(f"metro exits: fetched={len(elements)} attached={len(exits)}")
    return exits


def allocate_segment_seconds(total_seconds, distances, dwell_seconds):
    count = len(distances)
    if count == 0 or not total_seconds:
        return []
    dwell_budget = dwell_seconds * max(0, count - 1)
    running_budget = max(count, total_seconds - dwell_budget)
    total_distance = max(1, sum(distances))
    raw = []
    for i, distance in enumerate(distances):
        seconds = running_budget * distance / total_distance
        if i < count - 1:
            seconds += dwell_seconds
        raw.append(seconds)
    values = [max(1, int(math.floor(x))) for x in raw]
    delta = total_seconds - sum(values)
    order = sorted(range(count), key=lambda i: raw[i] - math.floor(raw[i]), reverse=delta > 0)
    cursor = 0
    while delta != 0 and order:
        i = order[cursor % len(order)]
        if delta > 0:
            values[i] += 1
            delta -= 1
        elif values[i] > 1:
            values[i] -= 1
            delta += 1
        cursor += 1
        if cursor > count * (abs(delta) + 2) * 2:
            break
    return values


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="runtime")
    parser.add_argument("--manifest", default="manifest.json")
    parser.add_argument("--version", default="moscow-runtime-2026-08-15-r4")
    args = parser.parse_args()

    root = Path(args.root)
    relation_file = root / "rail_osm_rail_relations.json.gz"
    if not relation_file.exists():
        raise SystemExit(f"missing {relation_file}")
    relation_meta = json.loads(gzip.decompress(relation_file.read_bytes()))

    relation_rows = []
    stop_ids = set()
    for index, meta in enumerate(relation_meta):
        relation_id = str(meta["osm_id"])
        xml = fetch_xml(OSM_REL.format(relation_id))
        relation = xml.find("relation")
        if relation is None:
            raise RuntimeError(f"relation {relation_id} missing")
        tags = {item.attrib["k"]: item.attrib["v"] for item in relation.findall("tag")}
        stops = [
            {"osm_stop_id": member.attrib["ref"], "role": member.attrib.get("role", "")}
            for member in relation.findall("member")
            if member.attrib.get("type") == "node" and member.attrib.get("role", "").startswith("stop")
        ]
        stop_ids.update(stop["osm_stop_id"] for stop in stops)
        relation_rows.append((meta, tags, stops))
        print(f"relation {index + 1}/{len(relation_meta)} {relation_id}: {len(stops)} stops, duration={tags.get('duration')}")
        time.sleep(0.20)

    nodes = {}
    ordered_ids = sorted(stop_ids, key=int)
    for start in range(0, len(ordered_ids), 80):
        chunk = ordered_ids[start:start + 80]
        xml = fetch_xml(OSM_NODES.format(",".join(chunk)))
        for node in xml.findall("node"):
            tags = {item.attrib["k"]: item.attrib["v"] for item in node.findall("tag")}
            nodes[node.attrib["id"]] = {
                "osm_stop_id": node.attrib["id"],
                "name": tags.get("name") or tags.get("official_name") or f"OSM {node.attrib['id']}",
                "lat": float(node.attrib["lat"]),
                "lon": float(node.attrib["lon"]),
            }
        print(f"nodes {min(start + len(chunk), len(ordered_ids))}/{len(ordered_ids)}")
        time.sleep(0.20)

    metro_exits = build_metro_exits(nodes)

    dwell_by_mode = {"METRO": 25, "MCC": 35, "MCD": 45}
    routes = []
    routeable_count = 0
    for meta, tags, stop_refs in relation_rows:
        stops = [nodes[item["osm_stop_id"]] for item in stop_refs if item["osm_stop_id"] in nodes]
        duration = parse_duration_seconds(tags.get("duration"))
        routeable = duration is not None and len(stops) >= 2
        distances = [haversine_meters(stops[i], stops[i + 1]) for i in range(max(0, len(stops) - 1))]
        dwell = dwell_by_mode.get(meta.get("mode"), 30)
        seconds = allocate_segment_seconds(duration, distances, dwell) if routeable else []
        if routeable:
            routeable_count += 1
        ref = meta.get("ref") or tags.get("ref")
        name = meta.get("name") or tags.get("name")
        routes.append({
            "osm_relation_id": str(meta["osm_id"]),
            "mode": meta.get("mode"),
            "ref": ref,
            "name": name,
            "display_line_name": " · ".join(part for part in (str(ref or "").strip(), str(name or "").strip()) if part),
            "from": meta.get("from") or tags.get("from"),
            "to": meta.get("to") or tags.get("to"),
            "colour": meta.get("colour") or tags.get("colour"),
            "total_duration_seconds": duration,
            "routeable": routeable,
            "timing_source": "OpenStreetMap relation duration" if routeable else None,
            "timing_confidence": 0.72 if routeable else 0.0,
            "stops": stops,
            "segment_seconds": seconds,
            "segment_distance_meters": distances,
            "segment_timing_method": "route total duration distributed by inter-stop distance plus mode dwell model" if routeable else None,
            "mode_dwell_seconds": dwell,
        })

    graph = {
        "schema": 2,
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "data_sources": [
            {"name": "OpenStreetMap", "license": "ODbL", "attribution": "© OpenStreetMap contributors"},
        ],
        "timing_notes": "Route total durations come from OSM route relations. Individual segment times are estimates constrained to each route total duration; routes without duration are disabled.",
        "exit_notes": "Metro entrance/exit coordinates come from OSM railway=subway_entrance. Exit numbers are shown only when OSM contains ref/local_ref; missing numbers are never fabricated.",
        "exits": metro_exits,
        "routes": routes,
    }
    raw = json.dumps(graph, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    compressed = gzip.compress(raw, compresslevel=9, mtime=0)
    output = root / "rail_graph.json.gz"
    output.write_bytes(compressed)

    manifest_path = Path(args.manifest)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    packs = manifest["packs"]
    old_pack = next((p for p in packs if p.get("id") == PACK_ID), None)
    base_download = int(manifest.get("total_download_bytes", 0)) - int(old_pack.get("compressed_bytes", 0) if old_pack else 0)
    base_installed = int(manifest.get("total_installed_bytes", 0)) - int(old_pack.get("raw_bytes", 0) if old_pack else 0)
    previous_margin = max(0, int(manifest.get("required_free_bytes", 0)) - int(manifest.get("total_download_bytes", 0)) - int(manifest.get("total_installed_bytes", 0)))
    packs[:] = [p for p in packs if p.get("id") != PACK_ID]
    packs.append({
        "id": PACK_ID,
        "role": "rail_routing_graph",
        "file": output.name,
        "install_as": "rail/graph.json",
        "compression": "gzip",
        "compressed_bytes": len(compressed),
        "raw_bytes": len(raw),
        "sha256_compressed": sha256(compressed),
        "sha256_raw": sha256(raw),
        "required": True,
    })
    manifest["version"] = args.version
    manifest["description"] = "Human Router Moscow runtime: BUS/TRAM timetable + OSM WALK graph + routeable METRO/MCC/MCD rail graph + metro entrance/exit geometry"
    manifest["total_download_bytes"] = base_download + len(compressed)
    manifest["total_installed_bytes"] = base_installed + len(raw)
    manifest["required_free_bytes"] = manifest["total_download_bytes"] + manifest["total_installed_bytes"] + previous_margin
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    print(json.dumps({
        "routes": len(routes),
        "routeable": routeable_count,
        "unique_stops": len(nodes),
        "metro_exits": len(metro_exits),
        "metro_exits_with_ref": sum(1 for item in metro_exits if item.get("ref")),
        "raw_bytes": len(raw),
        "compressed_bytes": len(compressed),
        "sha256_raw": sha256(raw),
        "sha256_compressed": sha256(compressed),
        "runtime_version": args.version,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
