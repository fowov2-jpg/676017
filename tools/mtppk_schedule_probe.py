#!/usr/bin/env python3
"""Discover machine-readable/downloadable MTPPK timetable sources.

This probe intentionally reads only public pages from the official mtppk.ru domain. It records
candidate timetable/document links and basic response metadata so the runtime importer can be built
against the real published format instead of guessing URLs.
"""
from __future__ import annotations

import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://mtppk.ru"
START = f"{BASE}/schedule/"
UA = "HumanRouterMTPPKProbe/0.1 (+https://github.com/fowov2-jpg/676017)"
OUT = Path("mtppk-schedule-probe")
OUT.mkdir(exist_ok=True)


def fetch(url: str, limit: int | None = None):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/pdf,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/octet-stream,*/*;q=0.5",
            "Accept-Language": "ru-RU,ru;q=0.9",
        },
    )
    with urllib.request.urlopen(req, timeout=45) as response:
        raw = response.read() if limit is None else response.read(limit)
        return {
            "status": response.status,
            "final_url": response.geturl(),
            "content_type": response.headers.get("Content-Type"),
            "content_length": response.headers.get("Content-Length"),
            "content_disposition": response.headers.get("Content-Disposition"),
            "last_modified": response.headers.get("Last-Modified"),
            "bytes_read": len(raw),
            "raw": raw,
        }


page = fetch(START)
html = page.pop("raw").decode("utf-8", "replace")
(OUT / "schedule.html").write_text(html, encoding="utf-8")

links = []
for match in re.finditer(r'href=["\']([^"\']+)["\']', html, re.I):
    href = match.group(1).strip()
    if not href or href.startswith(("#", "javascript:", "mailto:", "tel:")):
        continue
    url = urllib.parse.urljoin(START, href)
    parsed = urllib.parse.urlparse(url)
    if parsed.netloc.lower() not in {"mtppk.ru", "www.mtppk.ru"}:
        continue
    if url not in links:
        links.append(url)

schedule_words = ("schedule", "raspis", "распис", "mcd", "мцд", "train", "poezd", "поезд")
file_exts = (".pdf", ".xls", ".xlsx", ".csv", ".zip", ".json", ".xml")
candidates = []
for url in links:
    low = urllib.parse.unquote(url).lower()
    if low.endswith(file_exts) or "/upload/" in low or any(word in low for word in schedule_words):
        candidates.append(url)

# Also capture visible anchor labels around timetable links. This helps identify MCD-3 vs other
# sections even when the URL itself is opaque.
anchors = []
for match in re.finditer(r'<a\b[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', html, re.I | re.S):
    href, body = match.groups()
    text = re.sub(r'<[^>]+>', ' ', body)
    text = re.sub(r'\s+', ' ', text).strip()
    url = urllib.parse.urljoin(START, href.strip())
    if text and url in candidates:
        anchors.append({"text": text[:300], "url": url})

probes = []
for url in candidates[:80]:
    item = {"url": url}
    try:
        response = fetch(url, limit=64 * 1024)
        response.pop("raw", None)
        item.update(response)
    except Exception as exc:
        item["status"] = getattr(exc, "code", None)
        item["error"] = repr(exc)
        try:
            body = exc.read(2048).decode("utf-8", "replace")
            item["error_body_prefix"] = body
        except Exception:
            pass
    probes.append(item)

report = {
    "start": START,
    "page": page,
    "link_count": len(links),
    "candidate_count": len(candidates),
    "anchors": anchors,
    "candidates": candidates,
    "probes": probes,
}
(OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({
    "page": page,
    "link_count": len(links),
    "candidate_count": len(candidates),
    "anchors": anchors[:30],
    "probes": probes[:40],
}, ensure_ascii=False, indent=2))
