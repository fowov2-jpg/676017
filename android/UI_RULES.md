# Human Router UI rules

1. The map is the primary surface and must remain visible at all times on the main screen.
2. No modal or persistent panel may permanently cover more than 25% of the map height.
3. Route search is a compact bottom-left drawer. It is hidden by default, opens from the left edge/handle, and closes with a left swipe.
4. Runtime download state uses a compact bottom status strip. It occupies the lowest layer while active and disappears when complete.
5. Settings always open as a separate screen, never as a large overlay over the map.
6. Main navigation is an independent compact bottom-left drawer. It is hidden by default, opens separately from the left, and closes with a left swipe.
7. Search drawer and navigation drawer must not remain open at the same time.
8. Route details may expand only after explicit user action and must be dismissible/collapsible.
9. Errors are shown inside compact status cards with a retry action, never as full-screen blank states.
10. Main map actions: From, To, Build, Settings. Everything else goes to dedicated screens or drawers.
11. Keep system status/navigation insets clear so controls are not hidden under cutouts or system bars.
12. The left-edge gesture zone must stay narrow and must not block normal map pan gestures.
13. Every drawer must remain reachable by a visible compact handle even if edge-swipe detection fails on a device.
