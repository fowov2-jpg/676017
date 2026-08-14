#!/usr/bin/env python3
import json
import re
import urllib.request
from urllib.error import HTTPError, URLError
from pathlib import Path

URLS = [
    "https://www.mosmetro.ru/passengers/information/stations/18",
    "https://www.mosmetro.ru/passengers/information/stations/19",
    "https://www.mosmetro.ru/passengers/information/stations/217",
    "https://www.mosmetro.ru/app",
]
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Safari/537.36 HumanRouterResearch/0.1"
out = Path("mosmetro-probe")
out.mkdir(exist_ok=True)
report = {}

for i, url in enumerate(URLS):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru-RU,ru;q=0.9"})
    item = {"url": url}
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            text = raw.decode("utf-8", "replace")
            item["status"] = r.status
            item["final_url"] = r.geturl()
            item["bytes"] = len(raw)
            item["content_type"] = r.headers.get("Content-Type")
            (out / f"page-{i}.html").write_text(text, encoding="utf-8")

            item["scripts"] = list(dict.fromkeys(re.findall(r'<script[^>]+src=["\']([^"\']+)', text, re.I)))[:200]
            item["station_links"] = list(dict.fromkeys(re.findall(r'href=["\']([^"\']*/passengers/information/stations/[^"\']+)', text, re.I)))[:200]
            item["api_like_strings"] = list(dict.fromkeys(re.findall(r'https?://[^"\'<> ]+|/api/[^"\'<> ]+', text, re.I)))[:300]
            lowered = text.lower()
            for needle in ["расписание работы станции", "переходы со станции", "__next_data__", "api", "route", "station"]:
                item[f"contains_{needle}"] = needle.lower() in lowered

            m = re.search(r'<script[^>]+id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>', text, re.I | re.S)
            if m:
                try:
                    data = json.loads(m.group(1))
                    item["next_data_keys"] = list(data.keys()) if isinstance(data, dict) else type(data).__name__
                    (out / f"next-{i}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
                except Exception as e:
                    item["next_data_error"] = repr(e)

            # Save short readable contexts around likely transport data markers.
            contexts = []
            for marker in ["Расписание работы станции", "Переходы со станции", "first", "last", "route", "api"]:
                for match in list(re.finditer(re.escape(marker), text, re.I))[:5]:
                    a = max(0, match.start() - 500)
                    b = min(len(text), match.end() + 1500)
                    contexts.append({"marker": marker, "text": re.sub(r"\s+", " ", text[a:b])})
            item["contexts"] = contexts[:30]
    except (HTTPError, URLError, TimeoutError) as e:
        item["error"] = repr(e)
    report[str(i)] = item

(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
