# Human Router UI rules

1. The map is the primary surface and must remain visible at all times on the main screen.
2. No modal or bottom sheet may permanently cover more than 25% of the map height.
3. Search controls stay in a compact top card. No large title block on the map.
4. Runtime download state uses a compact bottom status card. It disappears when complete.
5. Settings always open as a separate screen, never as a large overlay over the map.
6. Bottom navigation height is fixed and compact; it must not expand into content panels.
7. Route details may expand only after explicit user action and must be dismissible/collapsible.
8. Errors are shown inside compact cards with a retry action, never as full-screen blank states.
9. Main map actions: From, To, Build, Settings. Everything else goes to dedicated screens.
10. Keep system status/navigation insets clear so controls are not hidden under cutouts or system bars.
