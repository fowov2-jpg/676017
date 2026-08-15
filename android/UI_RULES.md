# ВремяХодом UI rules

1. The MapLibre map is the primary full-screen surface and remains visible behind the main controls.
2. A compact floating search card sits at the top. It expands to explicit origin and destination fields only while editing.
3. The small Home, Work and Nearby actions sit directly below search and never dominate the map.
4. Current-location and settings actions are independent 48dp floating controls.
5. Nearby content uses a compact interactive bottom sheet with real stops/stations, routes and schedule or distance data.
6. Bottom navigation is always stateful: Map, Routes, Transport and Favorites each open a working state and preserve selection.
7. Route results show alternatives, effective filters and synchronized map selection; an active trip replaces them with its timeline until the trip is finished.
8. Runtime, map, location, search and routing errors use compact Russian-language status cards with a useful next action.
9. Both origin and destination can be selected manually from the current map center; location permission is never required for a manual route.
10. System bars, cutouts, gesture navigation and the IME must not cover controls. Route fit padding accounts for top overlays and bottom sheets.
11. Settings use the same surfaces, spacing, colors and rounded geometry as the map UI; every visible switch persists and changes behavior.
12. No production screen may contain fabricated stops, routes or ETAs. Deterministic fixtures are debug-build-only and exist solely for UI automation.
