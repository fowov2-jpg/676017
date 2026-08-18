# ВремяХодом — проверенный статус инструментальной связки

Дата проверки: 2026-08-18.

Этот файл фиксирует только фактически проверенное состояние. Он не заменяет `docs/VREMYAHODOM_TOOLCHAIN_CONTRACT.md` и `docs/VREMYAHODOM_PRODUCT_MAP.md`.

## GitHub

- ChatGPT connector authentication — **PASS**.
- Repository `fowov2-jpg/676017` access — **PASS**.
- Read source/workflows/commits — **PASS**.
- Write files/commits — **PASS**.
- Actions jobs/logs/artifacts — **PASS**.
- Android APK build through GitHub Actions — **PASS** по ранее проверенным runs.

Роль: источник кода, CI, APK artifacts и доказательств.

## Figma

- ChatGPT/Figma authentication — **PASS**.
- Account plan: Starter, seat: View — **PASS** как факт подключения.
- Создан отдельный design-file `ВремяХодом — UI Reference & Design System` — **PASS**.
- File key: `ERXoCGpz16D4IxDp9qtnsv` — **PASS** как идентификатор созданного файла.
- Локальные оригиналы `218231–218235` повторно проверены по SHA-256 и совпали с SHA-256 из ТЗ — **PASS**.
- Загрузка этих пяти оригиналов в Figma — **NOT RUN**: текущая файловая среда не смогла разрешить `mcp.figma.com` при прямом upload POST. Нельзя считать картинки загруженными.
- Design-to-code measurements по проектным nodes — **NOT RUN**, пока reference assets не размещены в Figma и не определены стабильные nodes.

Ограничение: текущий Starter/View доступ имеет низкий лимит MCP read-вызовов, поэтому Figma используется экономно для стабильных design milestones; подтверждённые размеры и токены сохраняются в GitHub.

Роль: измеряемое представление утверждённых UI references. Не имеет права заменять пользовательские reference screenshots без явного согласования.

## Supabase

- ChatGPT/Supabase connector — **PASS**.
- Доступен проект — **PASS**.
- Статус проекта — **ACTIVE_HEALTHY** на момент проверки.
- Android SDK/backend integration ВремяХодом — **NOT RUN / НЕ ТРЕБУЕТСЯ текущим ТЗ**.

Роль: резервная backend-инфраструктура только для явно утверждённых функций. Сам факт доступности Supabase не разрешает добавлять аккаунты, облачную историю или синхронизацию.

## PostHog

- ChatGPT/PostHog connector — **PASS**.
- Доступен project — **PASS**.
- Project пока не получил ни одного приложения-события (`ingested_event=false`) — **факт**.
- Android PostHog SDK integration — **NOT RUN**.
- QA event round-trip из конкретного APK в PostHog — **NOT RUN**.

Роль: будущая privacy-safe QA/product telemetry. До первого доказанного события нельзя писать `PostHog active in APK`.

## Sentry

- Android dependency `io.sentry:sentry-android` присутствует — **PASS**.
- `VremyaHodomApp` вызывает `SentryAndroid.init` только при непустом `BuildConfig.SENTRY_DSN` — **PASS**.
- auto-init в manifest выключен намеренно — **PASS**.
- PII/screenshot/view-hierarchy отправка в текущей инициализации отключена — **PASS**.
- Наличие рабочего DSN в текущей QA build — **NOT RUN**.
- Получение реального тестового crash/error event в Sentry project — **NOT RUN**.

Роль: crash/ANR diagnostics. Наличие SDK не равно активной интеграции.

## Android emulator / screenshot pipeline

- Baseline head `9eac6220a995f18cb2d76515e89f870caf984ee9`: Build #549 — **PASS**, включая API 26 и API 35 emulator smoke.
- Baseline head `9eac6220a995f18cb2d76515e89f870caf984ee9`: Responsive #152 — **PASS**, включая compact phone, large-text, tablet portrait/landscape и GPS replay.
- screenshot artifacts — **PASS по наличию**.
- Обнаружена гонка screenshot capture с асинхронной загрузкой MapLibre: в одном и том же compact-phone artifact `home.png` мог содержать пустую карту, а следующий `home-populated.png` — уже прогруженную карту. На следующем head добавлен bounded recapture для HOME evidence; его CI должен быть проверен отдельно.
- В актуальном `home-populated.png` обнаружено заметное обрезание длинного названия `Метро «Чистые пруды»`; на следующем head разрешён двухстрочный title и добавлена instrumentation-проверка отсутствия ellipsis. Его CI/screenshot evidence должен быть проверен отдельно.
- Целостность repo-копий канонических `docs/ui-reference/218231.jpg` и `218233.jpg` — **FAIL**: фактические SHA-256 (`a201e0f7ad2a088f59e50763b6aa3eb2c72ce3e10b1b603b6e1edb0dbe6b51f3`, `c831f02e775247c9a16468f9c4a2de84ca921b219f22ac7cffe3912f8850ff55`) не совпадают с закреплёнными ТЗ (`ff504b632a687365dd38fb3bd6fced3e5bc6f7a435637a1fde3ee9bbbe1c3790`, `7f66490ed62717bcd386afff56f0c9153ed68d4ab657d86025ba2a77aa824de0`).
- История GitHub подтверждает, что неверные binary blobs уже были добавлены commit `68553f28e7c67dad3ce8aa65562a1958d9d6aecb` (`Store canonical UI reference screenshots`); в его parent этих файлов ещё нет. Это не регрессия текущего PR.
- Повторная точная визуальная сверка текущего head с 218231/218233 — **NOT RUN / BLOCKED**, пока канонические bytes с нормативными SHA-256 не восстановлены. Повреждённые `.jpg` нельзя использовать как замену эталона.
- Предыдущий зафиксированный визуальный статус продукта остаётся **FAIL** до нового валидного side-by-side PASS.

Важно: технический responsive/build PASS не перекрывает визуальный FAIL/NOT RUN. Повреждённые reference bytes нельзя молча переопределять новыми изображениями или новыми SHA.

## Cloud real-device farm

- Firebase Test Lab / BrowserStack / аналогичная device farm через ChatGPT connector — **NOT AVAILABLE/NOT CONNECTED** в текущей связке.
- Cloud real-device PASS — **NOT RUN**.

До отдельного подключения основным реальным устройством проверки остаётся телефон пользователя, а автоматический baseline — GitHub Android Emulator.

## Нормативная карта продукта

- `docs/VREMYAHODOM_PRODUCT_MAP.md` создана — **PASS**.
- Карта связывает requirement → экран/функцию → источник данных → код/API → evidence → criterion — **PASS по структуре документа**.
- Текущие UI/branding/address-runtime blockers внесены явно — **PASS**.
- Product-map PASS не означает готовность продукта: сами перечисленные BLOCKER остаются FAIL до исправления.

## Итог связки

Рабочая основа сейчас:

`утверждённые screenshots/Figma -> GitHub source -> GitHub CI/emulators/screenshots -> visual comparison -> QA APK`

Дополнительная диагностика:

`Sentry (только если DSN реально настроен)` и `PostHog (только после privacy-safe Android integration)`.

Supabase не включается в продукт без отдельной утверждённой функции.

Текущий продуктовый статус: **технический baseline Build/Responsive = PASS; текущий visual gate = FAIL/NOT RUN до проверки нового screenshot head и восстановления канонических reference bytes; APK нельзя объявлять соответствующей ТЗ**.
