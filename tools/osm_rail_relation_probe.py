#!/usr/bin/env python3
import gzip
import json
import math
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

RELEASE = "https://github.com/fowov2-jpg/676017/releases/download/runtime-v0.4.3/"
REL_FILE = "rail_osm_rail_relations.json.gz"
STATION_FILES = ["rail_osm_subway_stations.json.gz", "rail_osm_rail_stations.json.gz"]
OSM_REL = "https://api.openstreetmap.org/api/0.6/relation/{}"
OSM_NODES = "https://api.openstreetmap.org/api/0.6/nodes?nodes={}"
UA = "HumanRouterRuntimeBuilder/0.1 (https://github.com/fowov2-jpg/676017)"
out = Path("osm-rail-probe")
out.mkdir(exist_ok=True)


def download_json_gz(name):
    req = urllib.request.Request(RELEASE + name, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(gzip.decompress(r.read()))


def haversine(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


relations = download_json_gz(REL_FILE)
station_candidates = []
for name in STATION_FILES:
    station_candidates.extend(download_json_gz(name))

result = []
stop_node_ids = set()
for idx, meta in enumerate(relations):
    rid = str(meta["osm_id"])
    req = urllib.request.Request(OSM_REL.format(rid), headers={"User-Agent": UA})
    row = {"meta": meta}
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            root = ET.fromstring(r.read())
        relation = root.find("relation")
        if relation is None:
            raise RuntimeError("relation element missing")
        tags = {e.attrib["k"]: e.attrib["v"] for e in relation.findall("tag")}
        members = [
            {"type": e.attrib.get("type"), "ref": e.attrib.get("ref"), "role": e.attrib.get("role", "")}
            for e in relation.findall("member")
        ]
        stops = [m for m in members if m["type"] == "node" and m["role"].startswith("stop")]
        stop_node_ids.update(m["ref"] for m in stops)
        row.update({
            "tags": tags,
            "members": members,
            "stop_members": stops,
            "time_tags": {k: v for k, v in tags.items() if any(w in k.lower() for w in ("duration", "interval", "headway", "opening", "frequency"))},
        })
    except Exception as e:
        row["error"] = repr(e)
    result.append(row)
    print(f"{idx + 1}/{len(relations)} relation {rid}: {len(row.get('stop_members', []))} ordered stops, time={row.get('time_tags', {})}")
    time.sleep(0.25)

# Resolve all public_transport stop_position nodes in a handful of multi-node API calls.
node_details = {}
ids = sorted(stop_node_ids, key=int)
for start in range(0, len(ids), 80):
    chunk = ids[start:start + 80]
    req = urllib.request.Request(OSM_NODES.format(",".join(chunk)), headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        root = ET.fromstring(r.read())
    for node in root.findall("node"):
        tags = {e.attrib["k"]: e.attrib["v"] for e in node.findall("tag")}
        node_details[node.attrib["id"]] = {
            "osm_id": node.attrib["id"],
            "lat": float(node.attrib["lat"]),
            "lon": float(node.attrib["lon"]),
            "name": tags.get("name"),
            "ref": tags.get("ref"),
            "network": tags.get("network"),
            "operator": tags.get("operator"),
            "railway": tags.get("railway"),
            "public_transport": tags.get("public_transport"),
        }
    print(f"resolved stop nodes {min(start + len(chunk), len(ids))}/{len(ids)}")
    time.sleep(0.25)

# Attach a canonical station name from our runtime station index. This also lets us detect
# suspicious OSM stop positions: anything > 700 m from a known station is kept but marked.
for detail in node_details.values():
    best = None
    best_m = float("inf")
    for station in station_candidates:
        d = haversine(detail["lat"], detail["lon"], station["lat"], station["lon"])
        if d < best_m:
            best_m = d
            best = station
    detail["canonical_name"] = detail.get("name") or (best.get("name") if best else None)
    detail["nearest_station_osm_id"] = best.get("osm_id") if best else None
    detail["nearest_station_name"] = best.get("name") if best else None
    detail["nearest_station_meters"] = round(best_m, 1) if best else None
    detail["station_match_ok"] = bool(best and best_m <= 700.0)

sequences = []
for row in result:
    seq = []
    for member in row.get("stop_members", []):
        detail = node_details.get(member["ref"])
        if detail:
            seq.append({**detail, "role": member["role"]})
        else:
            seq.append({"osm_id": member["ref"], "role": member["role"], "missing": True})
    sequences.append({
        "osm_id": row["meta"]["osm_id"],
        "mode": row["meta"].get("mode"),
        "name": row["meta"].get("name"),
        "ref": row["meta"].get("ref"),
        "from": row["meta"].get("from"),
        "to": row["meta"].get("to"),
        "colour": row["meta"].get("colour"),
        "duration": row.get("tags", {}).get("duration"),
        "interval": row.get("tags", {}).get("interval"),
        "stops": seq,
    })

(out / "relations.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "stop_nodes.json").write_text(json.dumps(node_details, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "sequences.json").write_text(json.dumps(sequences, ensure_ascii=False, indent=2), encoding="utf-8")
summary = {
    "relations": len(result),
    "ok": sum(1 for x in result if "error" not in x),
    "errors": sum(1 for x in result if "error" in x),
    "unique_stop_nodes": len(node_details),
    "unresolved_stop_nodes": len(stop_node_ids) - len(node_details),
    "station_match_failures": sum(1 for x in node_details.values() if not x["station_match_ok"]),
    "with_duration": sum(1 for x in sequences if x.get("duration")),
    "with_interval": sum(1 for x in sequences if x.get("interval")),
    "relations_without_duration": [
        {"osm_id": x["osm_id"], "mode": x["mode"], "name": x["name"], "stops": len(x["stops"])}
        for x in sequences if not x.get("duration")
    ],
    "stop_counts": {str(x["osm_id"]): len(x["stops"]) for x in sequences},
}
(out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
