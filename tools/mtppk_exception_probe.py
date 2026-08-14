#!/usr/bin/env python3
"""Capture recent official MTPPK timetable-change notices as structured raw tables.

Base XLSX timetables are not sufficient for date-accurate routing because MTPPK publishes temporary
cancellations, short turns and changed times in news notices. This collector keeps the official
notice URL, title and table rows without guessing operational semantics. A later overlay builder can
apply only changes whose date expressions are parsed with confidence.
"""
from __future__ import annotations

import hashlib
import html as html_lib
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://mtppk.ru"
SCHEDULE = f"{BASE}/schedule/"
UA = "HumanRouterMTPPKExceptions/0.1 (+https://github.com/fowov2-jpg/676017)"
OUT = Path("mtppk-schedule-probe")
PAGES = OUT / "exceptions-pages"
OUT.mkdir(exist_ok=True)
PAGES.mkdir(exist_ok=True)
ALLOWED_HOSTS = {"mtppk.ru", "www.mtppk.ru"}
INSECURE_CONTEXT = ssl._create_unverified_context()
MAX_INDEX_PAGES = 5
MAX_NOTICES = 36


def normalize_text(value: str) -> str:
    value = re.sub(r"<script\b.*?</script>|<style\b.*?</style>", " ", value, flags=re.I | re.S)
    value = re.sub(r"<br\s*/?>", "\n", value, flags=re.I)
    value = re.sub(r"<[^>]+>", " ", value)
    value = html_lib.unescape(value)
    return re.sub(r"[ \t\r\f\v]+", " ", value).replace(" \n", "\n").strip()


def is_certificate_error(exc: BaseException) -> bool:
    reason = getattr(exc, "reason", None)
    return isinstance(exc, ssl.SSLCertVerificationError) or isinstance(reason, ssl.SSLCertVerificationError)


def fetch(url: str) -> tuple[bytes, dict]:
    host = (urllib.parse.urlparse(url).hostname or "").lower()
    if host not in ALLOWED_HOSTS:
        raise ValueError(f"refusing non-MTPPK host: {host}")
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,*/*;q=0.5",
            "Accept-Language": "ru-RU,ru;q=0.9",
        },
    )
    try:
        response = urllib.request.urlopen(request, timeout=45)
        tls_verified = True
    except urllib.error.URLError as exc:
        if not is_certificate_error(exc):
            raise
        response = urllib.request.urlopen(request, timeout=45, context=INSECURE_CONTEXT)
        tls_verified = False
    with response:
        final_host = (urllib.parse.urlparse(response.geturl()).hostname or "").lower()
        if final_host not in ALLOWED_HOSTS:
            raise ValueError(f"refusing redirect outside MTPPK: {response.geturl()}")
        raw = response.read()
        return raw, {
            "status": response.status,
            "final_url": response.geturl(),
            "content_type": response.headers.get("Content-Type"),
            "last_modified": response.headers.get("Last-Modified"),
            "tls_verified": tls_verified,
            "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
        }


def extract_news_links(index_html: str) -> list[dict]:
    result = []
    seen = set()
    for match in re.finditer(r'<a\b[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', index_html, re.I | re.S):
        href, body = match.groups()
        label = normalize_text(body)
        low = label.lower().replace("ё", "е")
        if "распис" not in low or not ("ленинград" in low or "мцд" in low):
            continue
        url = urllib.parse.urljoin(SCHEDULE, href.strip())
        parsed = urllib.parse.urlparse(url)
        if (parsed.hostname or "").lower() not in ALLOWED_HOSTS or "/news/" not in parsed.path:
            continue
        if url in seen:
            continue
        seen.add(url)
        result.append({"url": url, "index_label": label[:700]})
    return result


def extract_title(page_html: str) -> str | None:
    for tag in ("h1", "h2"):
        match = re.search(fr'<{tag}\b[^>]*>(.*?)</{tag}>', page_html, re.I | re.S)
        if match:
            value = normalize_text(match.group(1))
            if value:
                return value
    return None


def extract_tables(page_html: str) -> list[dict]:
    tables = []
    for table_index, table_match in enumerate(re.finditer(r'<table\b[^>]*>(.*?)</table>', page_html, re.I | re.S)):
        table_html = table_match.group(1)
        rows = []
        for row_match in re.finditer(r'<tr\b[^>]*>(.*?)</tr>', table_html, re.I | re.S):
            row_html = row_match.group(1)
            cells = []
            for cell_match in re.finditer(r'<t[dh]\b[^>]*>(.*?)</t[dh]>', row_html, re.I | re.S):
                cells.append(normalize_text(cell_match.group(1)))
            if any(cells):
                rows.append(cells)
        if not rows:
            continue
        prefix = page_html[max(0, table_match.start() - 1800):table_match.start()]
        headings = [
            normalize_text(item)
            for item in re.findall(
                r'<(?:h2|h3|h4|strong|b)\b[^>]*>(.*?)</(?:h2|h3|h4|strong|b)>',
                prefix,
                re.I | re.S,
            )
        ]
        tables.append(
            {
                "index": table_index,
                "context": [item for item in headings[-4:] if item][-4:],
                "rows": rows,
            }
        )
    return tables


def main() -> None:
    notices_by_url: dict[str, dict] = {}
    index_meta = []
    for page in range(1, MAX_INDEX_PAGES + 1):
        url = SCHEDULE if page == 1 else f"{SCHEDULE}?PAGEN_2={page}"
        try:
            raw, meta = fetch(url)
        except Exception as exc:
            index_meta.append({"url": url, "error": repr(exc)})
            break
        text = raw.decode("utf-8", "replace")
        (PAGES / f"index-{page}.html").write_text(text, encoding="utf-8")
        links = extract_news_links(text)
        index_meta.append({"url": url, **meta, "matching_notices": len(links)})
        before = len(notices_by_url)
        for item in links:
            notices_by_url.setdefault(item["url"], item)
        if page > 1 and len(notices_by_url) == before:
            break
        if len(notices_by_url) >= MAX_NOTICES:
            break

    notices = []
    for number, item in enumerate(list(notices_by_url.values())[:MAX_NOTICES], start=1):
        try:
            raw, meta = fetch(item["url"])
            text = raw.decode("utf-8", "replace")
            (PAGES / f"notice-{number:02d}.html").write_text(text, encoding="utf-8")
            tables = extract_tables(text)
            notices.append(
                {
                    **item,
                    "title": extract_title(text),
                    "page": meta,
                    "tables": tables,
                    "table_row_count": sum(len(table["rows"]) for table in tables),
                }
            )
        except Exception as exc:
            notices.append({**item, "error": repr(exc), "tables": [], "table_row_count": 0})

    result = {
        "schema": 1,
        "source": "АО МТ ППК public schedule/news pages",
        "notes": [
            "These are raw official change notices; date ranges and cancellation/short-turn semantics are intentionally not guessed here.",
            "The base timetable must not be considered date-accurate until applicable notices are overlaid.",
        ],
        "index_pages": index_meta,
        "notices": notices,
    }
    (OUT / "exceptions_raw.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "index_pages": len(index_meta),
                "notices": len(notices),
                "notices_with_tables": sum(1 for item in notices if item.get("tables")),
                "table_rows": sum(item.get("table_row_count", 0) for item in notices),
                "titles": [item.get("title") or item.get("index_label") for item in notices[:12]],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
