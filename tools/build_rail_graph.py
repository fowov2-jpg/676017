#!/usr/bin/env python3
import argparse
import gzip
import hashlib
import json
import math
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

OSM_REL = "https://api.openstreetmap.org/api/0.6/relation/{}"
OSM_NODES = "https://api.openstreetmap.org/api/0.6/nodes?nodes={}"
UA = "HumanRouterRuntimeBuilder/0.5 (+https://github.com/fowov2-jpg/676017)"
PACK_ID = "rail-routing-graph"


def fetch_xml(url: str) -> ET.Element:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as response:
        return ET.fromstring(response.read())


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
    parser.add_argument("--version", default="moscow-runtime-2026-08-14-r3")
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
        routes.append({
            "osm_relation_id": str(meta["osm_id"]),
            "mode": meta.get("mode"),
            "ref": meta.get("ref"),
            "name": meta.get("name") or tags.get("name"),
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
        "schema": 1,
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "data_sources": [
            {"name": "OpenStreetMap", "license": "ODbL", "attribution": "© OpenStreetMap contributors"},
        ],
        "timing_notes": "Route total durations come from OSM route relations. Individual segment times are estimates constrained to each route total duration; routes without duration are disabled.",
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
    manifest["description"] = "Human Router Moscow runtime: BUS/TRAM timetable + OSM WALK graph + routeable METRO/MCC/MCD rail graph"
    manifest["total_download_bytes"] = base_download + len(compressed)
    manifest["total_installed_bytes"] = base_installed + len(raw)
    manifest["required_free_bytes"] = manifest["total_download_bytes"] + manifest["total_installed_bytes"] + previous_margin
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    print(json.dumps({
        "routes": len(routes),
        "routeable": routeable_count,
        "unique_stops": len(nodes),
        "raw_bytes": len(raw),
        "compressed_bytes": len(compressed),
        "sha256_raw": sha256(raw),
        "sha256_compressed": sha256(compressed),
        "runtime_version": args.version,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
