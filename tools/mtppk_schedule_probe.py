#!/usr/bin/env python3
"""Discover and capture public MTPPK timetable sources.

This probe intentionally reads only public pages from the official mtppk.ru domain. It records
candidate timetable/document links and downloads public schedule files so the runtime importer can
be built against the real published format instead of guessed URLs.

MTPPK has intermittently served a certificate chain that GitHub-hosted runners cannot validate.
We always try normal certificate validation first. For this read-only public probe only, a
certificate-verification failure may be retried without verification, and the report records that
fact. No credentials, cookies or private data are ever sent by this probe.
"""
from __future__ import annotations

import hashlib
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://mtppk.ru"
START = f"{BASE}/schedule/"
UA = "HumanRouterMTPPKProbe/0.3 (+https://github.com/fowov2-jpg/676017)"
OUT = Path("mtppk-schedule-probe")
DOWNLOADS = OUT / "downloads"
OUT.mkdir(exist_ok=True)
DOWNLOADS.mkdir(exist_ok=True)
ALLOWED_HOSTS = {"mtppk.ru", "www.mtppk.ru"}
INSECURE_CONTEXT = ssl._create_unverified_context()
MAX_CAPTURE_BYTES = 25 * 1024 * 1024


def is_certificate_error(exc: BaseException) -> bool:
    reason = getattr(exc, "reason", None)
    return isinstance(exc, ssl.SSLCertVerificationError) or isinstance(reason, ssl.SSLCertVerificationError)


def open_public(req: urllib.request.Request):
    try:
        return urllib.request.urlopen(req, timeout=45), True
    except urllib.error.URLError as exc:
        if not is_certificate_error(exc):
            raise
        host = (urllib.parse.urlparse(req.full_url).hostname or "").lower()
        if host not in ALLOWED_HOSTS:
            raise
        return urllib.request.urlopen(req, timeout=45, context=INSECURE_CONTEXT), False


def fetch(url: str, limit: int | None = None):
    host = (urllib.parse.urlparse(url).hostname or "").lower()
    if host not in ALLOWED_HOSTS:
        raise ValueError(f"refusing non-MTPPK host: {host}")
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/pdf,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/octet-stream,*/*;q=0.5",
            "Accept-Language": "ru-RU,ru;q=0.9",
        },
    )
    response, tls_verified = open_public(req)
    with response:
        final_host = (urllib.parse.urlparse(response.geturl()).hostname or "").lower()
        if final_host not in ALLOWED_HOSTS:
            raise ValueError(f"refusing redirect outside MTPPK: {response.geturl()}")
        raw = response.read() if limit is None else response.read(limit)
        return {
            "status": response.status,
            "final_url": response.geturl(),
            "content_type": response.headers.get("Content-Type"),
            "content_length": response.headers.get("Content-Length"),
            "content_disposition": response.headers.get("Content-Disposition"),
            "last_modified": response.headers.get("Last-Modified"),
            "tls_verified": tls_verified,
            "bytes_read": len(raw),
            "raw": raw,
        }


def is_download_url(url: str) -> bool:
    return urllib.parse.urlparse(url).path.lower().endswith("/download.php")


def capture_name(url: str, response: dict) -> str:
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    ident = re.sub(r"[^0-9A-Za-z_-]+", "_", (query.get("id") or ["unknown"])[0])
    content_type = (response.get("content_type") or "").lower()
    disposition = response.get("content_disposition") or ""
    filename_match = re.search(r'filename\*?=(?:UTF-8\'\')?["\']?([^"\';]+)', disposition, re.I)
    if filename_match:
        supplied = urllib.parse.unquote(filename_match.group(1)).strip()
        suffix = Path(supplied).suffix.lower()
        if suffix in {".pdf", ".xls", ".xlsx", ".csv", ".zip", ".json", ".xml"}:
            return f"download_{ident}{suffix}"
    if "pdf" in content_type:
        suffix = ".pdf"
    elif "spreadsheetml" in content_type:
        suffix = ".xlsx"
    elif "ms-excel" in content_type:
        suffix = ".xls"
    elif "zip" in content_type:
        suffix = ".zip"
    else:
        suffix = ".bin"
    return f"download_{ident}{suffix}"


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
    if (parsed.hostname or "").lower() not in ALLOWED_HOSTS:
        continue
    if url not in links:
        links.append(url)

schedule_words = ("schedule", "raspis", "распис", "mcd", "мцд", "train", "poezd", "поезд")
file_exts = (".pdf", ".xls", ".xlsx", ".csv", ".zip", ".json", ".xml")
candidates = []
for url in links:
    low = urllib.parse.unquote(url).lower()
    if (
        low.endswith(file_exts)
        or "/upload/" in low
        or is_download_url(url)
        or any(word in low for word in schedule_words)
    ):
        candidates.append(url)

# Capture visible anchor labels around timetable links. This identifies MCD-3 and the effective date
# even when the URL itself is only /download.php?id=...
anchors = []
label_by_url = {}
for match in re.finditer(r'<a\b[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', html, re.I | re.S):
    href, body = match.groups()
    text = re.sub(r'<[^>]+>', ' ', body)
    text = re.sub(r'\s+', ' ', text).strip()
    url = urllib.parse.urljoin(START, href.strip())
    if text and url in candidates:
        item = {"text": text[:500], "url": url}
        anchors.append(item)
        label_by_url.setdefault(url, text[:500])

probes = []
for url in candidates[:120]:
    item = {"url": url, "label": label_by_url.get(url)}
    try:
        capture = is_download_url(url)
        response = fetch(url, limit=MAX_CAPTURE_BYTES if capture else 64 * 1024)
        raw = response.pop("raw")
        item.update(response)
        if capture:
            declared = response.get("content_length")
            declared_int = int(declared) if str(declared or "").isdigit() else None
            item["truncated"] = declared_int is not None and declared_int > len(raw)
            item["sha256"] = hashlib.sha256(raw).hexdigest()
            name = capture_name(url, response)
            (DOWNLOADS / name).write_bytes(raw)
            item["captured_file"] = f"downloads/{name}"
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
    "download_count": sum(1 for p in probes if p.get("captured_file")),
    "anchors": anchors[:40],
    "downloads": [p for p in probes if p.get("captured_file")],
}, ensure_ascii=False, indent=2))
