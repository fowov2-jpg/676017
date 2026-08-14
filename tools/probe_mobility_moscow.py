#!/usr/bin/env python3
import csv
import io
import json
import re
import urllib.request
from pathlib import Path

CATALOG = "https://files.mobilitydatabase.org/feeds_v2.csv"
UA = "HumanRouterMobilityProbe/0.1 (+https://github.com/fowov2-jpg/676017)"
out = Path("mobility-moscow-probe")
out.mkdir(exist_ok=True)


def fetch(url: str, max_bytes: int | None = None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=45) as response:
        if max_bytes is None:
            raw = response.read()
        else:
            raw = response.read(max_bytes)
        return {
            "status": response.status,
            "final_url": response.geturl(),
            "content_type": response.headers.get("Content-Type"),
            "content_length": response.headers.get("Content-Length"),
            "etag": response.headers.get("ETag"),
            "last_modified": response.headers.get("Last-Modified"),
            "bytes_read": len(raw),
            "raw": raw,
        }


catalog_response = fetch(CATALOG)
text = catalog_response.pop("raw").decode("utf-8-sig", "replace")
(out / "feeds_v2.csv").write_text(text, encoding="utf-8")
reader = csv.DictReader(io.StringIO(text))
rows = list(reader)
headers = reader.fieldnames or []

keywords = ("moscow", "moskva", "москва", "mosgortrans", "мосгортранс", "moscow transport")
matches = []
for row in rows:
    searchable = " ".join(str(v or "") for v in row.values()).lower()
    country = " ".join(str(row.get(key, "") or "") for key in headers if "country" in key.lower()).lower()
    is_ru = country.strip() in {"ru", "rus", "russia", "russian federation"} or "russia" in searchable
    has_moscow = any(word in searchable for word in keywords)
    if has_moscow or (is_ru and any(word in searchable for word in ("55.7", "37.6"))):
        matches.append(row)

url_columns = [h for h in headers if any(token in h.lower() for token in ("latest", "direct_download", "download_url"))]
probes = []
for index, row in enumerate(matches[:30]):
    candidates = []
    for column in url_columns:
        value = (row.get(column) or "").strip()
        if value.startswith("http") and value not in candidates:
            candidates.append(value)
    for url in candidates[:3]:
        item = {
            "feed_index": index,
            "feed_id": row.get("id") or row.get("mdb_source_id"),
            "provider": row.get("provider"),
            "name": row.get("name"),
            "status": row.get("status"),
            "is_official": row.get("is_official"),
            "url": url,
        }
        try:
            response = fetch(url, max_bytes=64 * 1024)
            response.pop("raw", None)
            item.update(response)
            item["looks_like_zip"] = response["content_type"] in {
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            } or url.lower().endswith(".zip")
        except Exception as exc:
            item["error"] = repr(exc)
        probes.append(item)

report = {
    "catalog": catalog_response,
    "headers": headers,
    "total_feeds": len(rows),
    "moscow_matches": len(matches),
    "url_columns": url_columns,
    "matches": matches[:30],
    "url_probes": probes,
}
(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({
    "total_feeds": len(rows),
    "moscow_matches": len(matches),
    "url_columns": url_columns,
    "matches": [
        {
            "id": r.get("id") or r.get("mdb_source_id"),
            "provider": r.get("provider"),
            "name": r.get("name"),
            "status": r.get("status"),
            "is_official": r.get("is_official"),
            "latest": next((r.get(c) for c in url_columns if "latest" in c.lower() and r.get(c)), None),
            "direct": next((r.get(c) for c in url_columns if "direct" in c.lower() and r.get(c)), None),
        }
        for r in matches[:20]
    ],
    "url_probes": probes,
}, ensure_ascii=False, indent=2))
