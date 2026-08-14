#!/usr/bin/env python3
import json
import os
import urllib.parse
import urllib.request
from pathlib import Path

DATASETS = [60661, 60662, 60664, 60665, 60666]
UA = "HumanRouterDataMosProbe/0.1 (+https://github.com/fowov2-jpg/676017)"
API_KEY = os.environ.get("DATA_MOS_API_KEY", "").strip()
out = Path("data-mos-probe")
out.mkdir(exist_ok=True)


def request(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json,text/csv,*/*;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=35) as response:
            raw = response.read(8192)
            return {
                "status": response.status,
                "final_url": response.geturl(),
                "content_type": response.headers.get("Content-Type"),
                "content_length": response.headers.get("Content-Length"),
                "body_prefix": raw.decode("utf-8", "replace"),
            }
    except Exception as exc:
        # HTTPError also contains a useful response body.
        body = None
        status = getattr(exc, "code", None)
        try:
            body = exc.read(8192).decode("utf-8", "replace")
        except Exception:
            pass
        return {"status": status, "error": repr(exc), "body_prefix": body}

report = {"has_api_key": bool(API_KEY), "datasets": {}}
for dataset_id in DATASETS:
    params = {"$top": 1}
    if API_KEY:
        params["api_key"] = API_KEY
    encoded = urllib.parse.urlencode(params)
    urls = {
        "apidata_count": f"https://apidata.mos.ru/v1/datasets/{dataset_id}/count" + (("?api_key=" + urllib.parse.quote(API_KEY)) if API_KEY else ""),
        "apidata_row": f"https://apidata.mos.ru/v1/datasets/{dataset_id}/rows?{encoded}",
        "old_api_row": f"https://api.data.mos.ru/v1/datasets/{dataset_id}/rows?{encoded}",
        "legacy_download": f"https://data.mos.ru/datasets/download/{dataset_id}",
    }
    item = {}
    for name, url in urls.items():
        print(f"probe dataset {dataset_id} {name}", flush=True)
        item[name] = request(url)
    report["datasets"][str(dataset_id)] = item

(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
# Avoid printing a key if a secret is provided later.
print(json.dumps(report, ensure_ascii=False, indent=2).replace(API_KEY, "***") if API_KEY else json.dumps(report, ensure_ascii=False, indent=2))
