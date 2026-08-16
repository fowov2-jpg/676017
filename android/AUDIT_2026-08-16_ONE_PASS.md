# ВремяХодом one-pass remediation — 2026-08-16

This pass starts from PR #2 head `2abe13aa0e711beed6b455b64a188bb1c9d48bee` and the API 26/35 screenshot artifacts from the previous green Android workflow.

## Remediated in code

1. UI rules now describe the actual map-first/navigation/bottom-sheet product and define stable three-state route sheets.
2. Cross-cutting UI is owned by one `VremyaHodomUiCoordinator`; the old reflection-heavy runtime patch chain is no longer installed.
3. Route-preparation art is one continuous vector scene with passenger -> stop -> ~2 second wait -> bus -> metro -> platform -> train.
4. Selected routes are overpainted per transport leg with normalized endpoints, deduplicated intermediate transit stops and distinct walking styling.
5. Route sheet drag snaps to collapsed/medium/expanded heights and caps the expanded state so the map remains visible.
6. The misleading transport switch is renamed to route-line visibility; schedule/GPS vs realtime is explicitly disclosed through `RealtimeTransitSource`.
7. Both bundled basemap styles now use OpenFreeMap/OpenMapTiles vector tiles instead of direct `tile.openstreetmap.org` raster requests; the dark style is a real dark vector palette.
8. Coverage limitations are documented as unavailable data rather than silently supported modes; no missing MCD/rail timetable is fabricated.
9. New unit/instrumentation regression tests cover the continuous scene, truthful realtime state, unified route strip and settings disclosure; the existing cold-start and API 26/API 35 suite stays active.
10. Gradle now has a release build/signing contract using protected `VH_RELEASE_*` variables, plus a production release checklist. Debug signing is never reused implicitly.

## External capability inputs that source code cannot fabricate

- Actual BUS/TRAM vehicle positions still require an authorized realtime source. Until one is configured, the product explicitly says schedule/model data and passenger GPS rather than `live`.
- Additional MCD/commuter corridors require audited timetable data before the routing engine can claim coverage.
- Production signing requires the owner's protected keystore credentials; repository/QA credentials must not substitute for them.
- OpenFreeMap public hosting has no SLA. The vector style/source boundary is now provider-safe, so a self-hosted OpenFreeMap-compatible endpoint can be substituted when an SLA/offline requirement becomes mandatory.
