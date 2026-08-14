#!/usr/bin/env python3
import concurrent.futures
import json
import os
import time
import urllib.parse
import urllib.request
from pathlib import Path

DATASETS = [60661, 60662, 60664, 60665, 60666]
UA = "HumanRouterDataMosProbe/0.2 (+https://github.com/fowov2-jpg/676017)"
API_KEY = os.environ.get("DATA_MOS_API_KEY", "").strip()
out = Path("data-mos-probe")
out.mkdir(exist_ok=True)


def request(label_url):
    dataset_id, name, url = label_url
    started = time.time()
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json,text/csv,*/*;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=12) as response:
            raw = response.read(8192)
            result = {
                "status": response.status,
                "final_url": response.geturl(),
                "content_type": response.headers.get("Content-Type"),
                "content_length": response.headers.get("Content-Length"),
                "elapsed_seconds": round(time.time() - started, 3),
                "body_prefix": raw.decode("utf-8", "replace"),
            }
    except Exception as exc:
        body = None
        status = getattr(exc, "code", None)
        try:
            body = exc.read(8192).decode("utf-8", "replace")
        except Exception:
            pass
        result = {
            "status": status,
            "elapsed_seconds": round(time.time() - started, 3),
            "error": repr(exc),
            "body_prefix": body,
        }
    print(f"dataset {dataset_id} {name}: status={result.get('status')} elapsed={result['elapsed_seconds']} error={result.get('error')}", flush=True)
    return dataset_id, name, result


jobs = []
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
    jobs.extend((dataset_id, name, url) for name, url in urls.items())

report = {"has_api_key": bool(API_KEY), "datasets": {str(dataset_id): {} for dataset_id in DATASETS}}
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
    for dataset_id, name, result in executor.map(request, jobs):
        report["datasets"][str(dataset_id)][name] = result

(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
rendered = json.dumps(report, ensure_ascii=False, indent=2)
if API_KEY:
    rendered = rendered.replace(API_KEY, "***")
print(rendered)
