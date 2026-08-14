#!/usr/bin/env python3
import gzip
import json
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

RELEASE = "https://github.com/fowov2-jpg/676017/releases/download/runtime-v0.4.3/"
REL_FILE = "rail_osm_rail_relations.json.gz"
OSM_REL = "https://api.openstreetmap.org/api/0.6/relation/{}"
UA = "HumanRouterRuntimeBuilder/0.1 (https://github.com/fowov2-jpg/676017)"
out = Path("osm-rail-probe")
out.mkdir(exist_ok=True)

req = urllib.request.Request(RELEASE + REL_FILE, headers={"User-Agent": UA})
with urllib.request.urlopen(req, timeout=30) as r:
    relations = json.loads(gzip.decompress(r.read()))

result = []
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
            {
                "type": e.attrib.get("type"),
                "ref": e.attrib.get("ref"),
                "role": e.attrib.get("role", ""),
            }
            for e in relation.findall("member")
        ]
        stop_like = [m for m in members if m["role"] in {"stop", "stop_entry_only", "stop_exit_only", "platform", "platform_entry_only", "platform_exit_only"}]
        row.update({
            "tags": tags,
            "members": members,
            "stop_like_members": stop_like,
            "time_tags": {k: v for k, v in tags.items() if any(w in k.lower() for w in ("duration", "interval", "headway", "opening", "frequency"))},
        })
    except Exception as e:
        row["error"] = repr(e)
    result.append(row)
    print(f"{idx + 1}/{len(relations)} relation {rid}: {len(row.get('members', []))} members, time={row.get('time_tags', {})}")
    time.sleep(0.35)

(out / "relations.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
summary = {
    "relations": len(result),
    "ok": sum(1 for x in result if "error" not in x),
    "errors": sum(1 for x in result if "error" in x),
    "with_time_tags": [
        {"osm_id": x["meta"]["osm_id"], "name": x["meta"].get("name"), "time_tags": x.get("time_tags")}
        for x in result if x.get("time_tags")
    ],
    "stop_like_counts": {
        str(x["meta"]["osm_id"]): len(x.get("stop_like_members", [])) for x in result
    },
}
(out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
