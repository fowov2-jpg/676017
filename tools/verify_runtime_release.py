#!/usr/bin/env python3
import argparse, hashlib, json, sys
from pathlib import Path

def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(8*1024*1024), b''):
            h.update(chunk)
    return h.hexdigest()

ap=argparse.ArgumentParser()
ap.add_argument('--manifest', type=Path, default=Path('manifest.json'))
ap.add_argument('--root', type=Path, default=Path('runtime'))
a=ap.parse_args()
m=json.loads(a.manifest.read_text(encoding='utf-8'))
errors=[]
required=[p for p in m.get('packs',[]) if p.get('required', True)]
calc_total=0
for p in required:
    path=a.root/p['file']
    if not path.exists():
        errors.append(f"missing: {path}")
        continue
    size=path.stat().st_size
    calc_total += size
    if size != p['compressed_bytes']:
        errors.append(f"size mismatch {p['file']}: {size} != {p['compressed_bytes']}")
    got=sha256(path)
    if got != p['sha256_compressed']:
        errors.append(f"sha256 mismatch {p['file']}: {got} != {p['sha256_compressed']}")
if calc_total != m.get('total_download_bytes'):
    errors.append(f"total_download_bytes mismatch: {calc_total} != {m.get('total_download_bytes')}")
if errors:
    print('\n'.join('ERROR '+x for x in errors), file=sys.stderr)
    raise SystemExit(1)
print(f"OK: {len(required)} packs, {calc_total} bytes, version={m.get('version')}")
