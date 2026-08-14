#!/usr/bin/env python3
import gzip
import json
import pathlib
import urllib.request

BASE = "https://github.com/fowov2-jpg/676017/releases/download/runtime-v0.4.3/"
FILES = [
    "rail_manifest.json.gz",
    "rail_official_lines_2278.json.gz",
    "rail_osm_subway_stations.json.gz",
    "rail_osm_rail_relations.json.gz",
    "rail_osm_rail_stations.json.gz",
    "rail_mosmetro_scheme_station_index.json.gz",
]
TIME_WORDS = ("time", "duration", "minute", "second", "interval", "travel", "schedule", "departure", "arrival", "wait")

out = pathlib.Path("rail-audit")
out.mkdir(exist_ok=True)
report = {}


def walk(value, path="$", found=None, depth=0):
    if found is None:
        found = []
    if depth > 8 or len(found) > 500:
        return found
    if isinstance(value, dict):
        for k, v in value.items():
            p = f"{path}.{k}"
            if any(w in k.lower() for w in TIME_WORDS):
                found.append({"path": p, "sample": v if isinstance(v, (str, int, float, bool, type(None))) else type(v).__name__})
            walk(v, p, found, depth + 1)
    elif isinstance(value, list):
        for i, v in enumerate(value[:200]):
            walk(v, f"{path}[{i}]", found, depth + 1)
    return found

for name in FILES:
    target = out / name
    urllib.request.urlretrieve(BASE + name, target)
    raw = gzip.decompress(target.read_bytes())
    json_name = name[:-3]
    (out / json_name).write_bytes(raw)
    data = json.loads(raw)
    item = {
        "bytes": len(raw),
        "root_type": type(data).__name__,
        "time_like_fields": walk(data),
    }
    if isinstance(data, dict):
        item["root_keys"] = list(data.keys())
        item["root_sizes"] = {k: len(v) for k, v in data.items() if isinstance(v, (list, dict))}
        item["samples"] = {}
        for k, v in data.items():
            if isinstance(v, list) and v:
                item["samples"][k] = v[:2]
            elif isinstance(v, dict):
                item["samples"][k] = dict(list(v.items())[:2])
            elif isinstance(v, (str, int, float, bool, type(None))):
                item["samples"][k] = v
    elif isinstance(data, list):
        item["count"] = len(data)
        item["samples"] = data[:3]
    report[name] = item

(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
