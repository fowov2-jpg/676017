# ВремяХодом Android release gate

A production APK/AAB must pass all items below. QA debug signing is never accepted as production signing.

## Signing

Set all four secret environment variables in the protected release environment:

- `VH_RELEASE_KEYSTORE` — path to the mounted keystore file.
- `VH_RELEASE_STORE_PASSWORD` — keystore password.
- `VH_RELEASE_KEY_ALIAS` — production key alias.
- `VH_RELEASE_KEY_PASSWORD` — production key password.

The keystore itself must not be committed to Git.

## Build and verification

1. Run unit tests and Android Lint.
2. Run API 26 and API 35 instrumentation/screenshot suites.
3. Run `assembleRelease` (or the Play-target AAB task when Play publishing is introduced).
4. Verify the release artifact with `apksigner verify --verbose --print-certs`.
5. Confirm package name `app.humanrouter`, launcher label `ВремяХодом`, versionCode and versionName.
6. Install the release artifact on a clean physical device and exercise cold start, address search, manual-map points, route build, active trip, process restore, dark mode and denied-location flow.
7. Confirm runtime package SHA-256 verification and runtime-current availability.
8. Confirm map attribution is visible and no bulk/offline fetch is performed against a provider that disallows it.
9. Confirm BUS/TRAM timing is labelled schedule/model unless a verified realtime source is configured.
10. Archive the release APK/AAB checksum and source commit SHA.

## External dependencies still requiring credentials/data

- Production signing material is supplied only through protected secrets.
- A real Moscow vehicle-position feed may be enabled only after access and data terms are verified.
- New MCD/commuter corridors require audited timetable coverage; topology alone is not sufficient.
