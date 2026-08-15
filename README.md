# ВРЕМЯ ХОДОМ

Runtime data channel for the «ВремяХодом» Android application.

Current runtime: `moscow-runtime-2026-08-14-r2` (v0.4.3)

The Android app downloads `manifest.json` and then the required prepared runtime packs. Raw OSM PBF and source archives are not downloaded to the phone.

Routing scope: WALK + public transport. Bicycles, scooters, private cars and river transport are intentionally excluded. A mode is enabled only when operational timing is backed by real data. See `TRANSPORT_SCOPE.md`.

Runtime contents today:
- BUS/TRAM surface SQLite
- stop → OSM walk-node mapping
- OSM WALK CSR + reverse CSR + spatial grid
- METRO/MCC topology and validation metadata
- audited MTPPK timetable asset for MCD-3 and suburban trains on the Moscow Passenger–Zelenograd-Kryukovo corridor

Current package count: 25
Download size: ~105.6 MiB
Installed size: ~273.7 MiB
Recommended free space: ~383 MiB

Large binary packs are intended to be published as GitHub Release assets; `manifest.json` is the machine-readable update contract.
