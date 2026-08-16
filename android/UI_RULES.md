# ВремяХодом UI rules

1. The MapLibre map is the primary full-screen surface and remains visible behind the main controls.
2. A compact floating search card sits at the top. It expands to explicit origin and destination fields only while editing.
3. The small Home, Work and Nearby actions sit directly below search and never dominate the map.
4. Current-location and settings actions are independent touch targets of at least 48dp.
5. Nearby content uses a compact interactive bottom sheet with real stops/stations, routes and explicitly labelled schedule or distance data.
6. Bottom navigation is always stateful: Map, Routes, Transport and Favorites each open a working state and preserve selection.
7. Route results show alternatives, effective filters and synchronized map selection; an active trip replaces them with its timeline until the trip is finished.
8. Route and active-trip sheets have exactly three stable heights: collapsed, medium and expanded. Even expanded state must leave a meaningful part of the map visible.
9. Runtime, map, location, search and routing errors use compact Russian-language status cards with a useful next action.
10. Both origin and destination can be selected manually from the current map center; location permission is never required for a manual route.
11. System bars, cutouts, gesture navigation and the IME must not cover controls. Route fit padding accounts for top overlays and bottom sheets.
12. Settings use the same surfaces, spacing, colors and rounded geometry as the map UI; every visible switch persists and changes behavior.
13. Journey loading is one continuous scene: passenger -> stop -> approximately two-second wait -> bus -> metro entrance -> platform -> train. It must never be represented as unrelated frame swaps.
14. Route geometry is drawn per leg with transport-aware colors. Walking is visually distinct, intermediate transit stops are deduplicated, and origin/destination markers are never reused as stop markers.
15. Realtime status is explicit. Scheduled/modelled data must never be labelled live; GPS of the user and realtime position of a vehicle are different concepts in the UI.
16. No production screen may contain fabricated stops, routes, exits, vehicle positions or ETAs. Deterministic fixtures are debug-build-only and exist solely for UI automation.
17. Cross-cutting UI behavior has one lifecycle coordinator. Production code must not invoke MainActivity private members through reflection or discover controls by matching visible text.
18. Light and dark surfaces must remain visually coherent with the basemap. Any external map source must keep required attribution visible and have a documented fallback/migration path.
