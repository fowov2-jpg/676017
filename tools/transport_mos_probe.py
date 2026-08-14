#!/usr/bin/env python3
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

UA = "Mozilla/5.0 HumanRouterScheduleProbe/0.1 (+https://github.com/fowov2-jpg/676017)"
BASE = "https://transport.mos.ru"
URLS = [
    f"{BASE}/transport/schedule",
    f"{BASE}/transport/schedule/route/141509125",
    f"{BASE}/robots.txt",
]
out = Path("transport-mos-probe")
out.mkdir(exist_ok=True)
report = {}


def fetch(url):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.5",
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        },
    )
    with urllib.request.urlopen(req, timeout=35) as response:
        raw = response.read()
        return response.status, response.geturl(), response.headers, raw


def inspect_html(text):
    scripts = list(dict.fromkeys(re.findall(r'<script[^>]+src=["\']([^"\']+)', text, re.I)))
    route_links = list(dict.fromkeys(re.findall(r'href=["\']([^"\']*/transport/schedule/route/\d+[^"\']*)', text, re.I)))
    forms = []
    for match in re.finditer(r'<form\b([^>]*)>', text, re.I):
        attrs = match.group(1)
        action = re.search(r'action=["\']([^"\']+)', attrs, re.I)
        method = re.search(r'method=["\']([^"\']+)', attrs, re.I)
        forms.append({"action": action.group(1) if action else None, "method": method.group(1) if method else None})
    api_like = list(dict.fromkeys(re.findall(r'(?:https?:)?//[^"\'<> ]+|/[A-Za-z0-9_./-]*(?:api|ajax|schedule)[A-Za-z0-9_?&=./%-]*', text, re.I)))
    return {
        "scripts": scripts[:200],
        "route_links": route_links[:2000],
        "forms": forms[:100],
        "api_like_strings": api_like[:500],
        "route_id_count": len(set(re.findall(r'/transport/schedule/route/(\d+)', text))),
        "contains_schedule_words": all(word in text.lower() for word in ("маршрут", "останов")),
    }

for index, url in enumerate(URLS):
    item = {"url": url}
    try:
        started = time.time()
        status, final_url, headers, raw = fetch(url)
        item.update({
            "status": status,
            "final_url": final_url,
            "elapsed_seconds": round(time.time() - started, 3),
            "bytes": len(raw),
            "content_type": headers.get("Content-Type"),
            "cache_control": headers.get("Cache-Control"),
        })
        text = raw.decode("utf-8", "replace")
        suffix = ".txt" if "robots.txt" in url else ".html"
        (out / f"response-{index}{suffix}").write_text(text, encoding="utf-8")
        if suffix == ".html":
            item.update(inspect_html(text))
    except Exception as exc:
        item["error"] = repr(exc)
    report[str(index)] = item

# If the schedule index worked, sample a few route pages using discovered official IDs.
index_info = report.get("0", {})
sample_routes = []
for link in index_info.get("route_links", [])[:5]:
    url = urllib.parse.urljoin(BASE, link)
    try:
        status, final_url, headers, raw = fetch(url)
        text = raw.decode("utf-8", "replace")
        route_id = re.search(r'/route/(\d+)', final_url)
        sample_routes.append({
            "route_id": route_id.group(1) if route_id else None,
            "status": status,
            "bytes": len(raw),
            "stop_heading_count": len(re.findall(r'<(?:li|div|h[1-6])[^>]*>[^<]{2,80}</', text, re.I)),
            "hour_tokens": len(re.findall(r'\b(?:[01]?\d|2[0-9]):', text)),
            "minute_tokens": len(re.findall(r'>\s*[0-5]\d\s*<', text)),
        })
        time.sleep(0.4)
    except Exception as exc:
        sample_routes.append({"url": url, "error": repr(exc)})
report["sample_routes"] = sample_routes

(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
