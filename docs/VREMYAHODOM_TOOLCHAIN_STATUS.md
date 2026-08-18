# ВремяХодом — проверенный статус инструментальной связки

Дата проверки: 2026-08-18.

Этот файл фиксирует только фактически проверенное состояние. Он не заменяет `docs/VREMYAHODOM_TOOLCHAIN_CONTRACT.md`.

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
- Доступ к конкретному проектному design-file ВремяХодом — **NOT RUN**: отдельный проектный Figma-файл ещё не назначен как рабочий источник.
- Design-to-code measurements по проектному file/node — **NOT RUN**.

Ограничение: текущий Starter/View доступ имеет низкий лимит MCP read-вызовов, поэтому Figma должна использоваться экономно для стабильных design milestones; подтверждённые размеры и токены сохраняются в GitHub.

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

- API 26 emulator — **PASS** на последнем проверенном CI cycle.
- API 35 emulator — **PASS** на последнем проверенном CI cycle.
- compact / large-text / tablet portrait / tablet landscape — **PASS технически** на последнем responsive cycle.
- screenshot artifacts — **PASS по наличию**.
- соответствие утверждённым UI references — **FAIL** по фактической визуальной сверке 2026-08-18.

Важно: технический responsive PASS не перекрывает визуальный FAIL.

## Cloud real-device farm

- Firebase Test Lab / BrowserStack / аналогичная device farm через ChatGPT connector — **NOT AVAILABLE/NOT CONNECTED** в текущей связке.
- Cloud real-device PASS — **NOT RUN**.

До отдельного подключения основным реальным устройством проверки остаётся телефон пользователя, а автоматический baseline — GitHub Android Emulator.

## Итог связки

Рабочая основа сейчас:

`Figma/references -> GitHub source -> GitHub CI/emulators/screenshots -> visual comparison -> QA APK`

Дополнительная диагностика:

`Sentry (только если DSN реально настроен)` и `PostHog (только после privacy-safe Android integration)`.

Supabase не включается в продукт без отдельной утверждённой функции.

Текущий продуктовый статус: **инструментальная связка частично готова; UI gate = FAIL; APK нельзя объявлять соответствующей ТЗ до исправления UI BLOCKER-расхождений**.
