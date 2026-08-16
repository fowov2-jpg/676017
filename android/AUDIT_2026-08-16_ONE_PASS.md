# ВремяХодом one-pass remediation — 2026-08-16

This pass is based on PR #2 head `2abe13aa0e711beed6b455b64a188bb1c9d48bee` and the API 26/35 screenshot artifacts from the green Android workflow.

## Remediated in code

1. UI rules now describe the actual map-first/navigation/bottom-sheet product and define stable three-state route sheets.
2. Cross-cutting UI is owned by one `VremyaHodomUiCoordinator`; the old reflection-heavy runtime patch chain is no longer installed.
3. Route-preparation art is one continuous vector scene with passenger -> stop -> ~2 second wait -> bus -> metro -> platform -> train.
4. Selected routes are overpainted per transport leg with normalized endpoints, deduplicated intermediate transit stops and distinct walking styling.
5. Route sheet drag snaps to collapsed/medium/expanded heights and caps the expanded state so the map remains visible.
6. The misleading transport switch is renamed to route-line visibility; schedule/GPS vs realtime is explicitly disclosed through `RealtimeTransitSource`.
7. Dark raster treatment is strengthened so the basemap is visually dark instead of a light map under dark chrome.
8. Coverage limitations are documented as unavailable data rather than silently supported modes; no missing MCD/rail timetable is fabricated.
9. New unit/instrumentation regression tests cover the continuous scene, truthful realtime state, unified route strip and settings disclosure; existing CI still runs unit/lint/API 26/API 35 suites.
10. Gradle now has a release build/signing contract using protected `VH_RELEASE_*` variables, plus a production release checklist. Debug signing is never reused implicitly.

## External dependencies that cannot be fabricated in source code

- A public/authorized Moscow vehicle-position feed is still required before actual BUS/TRAM live markers can be enabled.
- Additional MCD/commuter timetable coverage requires audited source data.
- Production signing requires the owner's protected keystore credentials.
- The bundled OSM raster source remains an online dependency. A provider migration/self-hosting decision is still required before promising offline/SLA behavior.

These are treated as explicit capabilities/availability states rather than hidden or simulated features.
