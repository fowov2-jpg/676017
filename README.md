# Human Router Runtime

Runtime data channel for the Human Router Android application.

Current runtime: `moscow-runtime-2026-08-14-r2` (v0.4.3)

The Android app downloads `manifest.json` and then the required prepared runtime packs. Raw OSM PBF and source archives are not downloaded to the phone.

Runtime contents:
- BUS/TRAM surface SQLite
- stop → OSM walk-node mapping
- OSM WALK CSR + reverse CSR + spatial grid
- METRO/MCC/MCD topology and validation metadata

Current package count: 25
Download size: ~105.6 MiB
Installed size: ~273.7 MiB
Recommended free space: ~383 MiB

Large binary packs are intended to be published as GitHub Release assets; `manifest.json` is the machine-readable update contract.
