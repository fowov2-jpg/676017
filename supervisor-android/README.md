# AI Supervisor Android

Отдельное Android-приложение для наблюдения за рабочими сессиями AI/coding-agent по нескольким проектам. Оно не привязано к `Время Ходом`: этот репозиторий добавлен только как стартовый проект и может быть заменён или дополнен любыми `owner/repo`.

## Что уже реализовано

- мультипроектный список с активным контекстом;
- foreground service с циклом проверки каждые 60 секунд;
- единые статусы `IDLE → RUNNING → WAITING → SUSPICIOUS → STALLED → FAILED/HUNG → RECOVERING → DONE`;
- GitHub commits и GitHub Actions: run → job → текущий step;
- отдельные `warning / stalled / hard` таймауты для каждого проекта;
- автоматическая инвентаризация Gradle plugins/dependencies, GitHub Actions, npm, requirements и Docker images;
- автообнаружение признаков Vercel, Cloudflare, Supabase, Sentry, PostHog и GitHub Actions в репозитории;
- подключаемые API-интеграции Vercel / Cloudflare / Supabase / Sentry / PostHog / Custom HTTP;
- токены шифруются локально ключом из Android Keystore и никогда не должны попадать в Git;
- Accessibility watcher ограничен пакетом `com.openai.chatgpt`;
- детектор типичных ошибок соединения/дополнительной проверки в открытом ChatGPT;
- ручной и автоматический «пинатель» активного чата;
- fallback: если ChatGPT не открыт или поле ввода недоступно Accessibility, Supervisor не делает вид, что пинок отправлен, а создаёт событие `WAITING` и системное уведомление;
- локальная SQLite-хронология до 1000 событий на проект;
- автоматическое восстановление мониторинга после reboot, если монитор был включён;
- отдельные high-priority уведомления для `FAILED/HUNG`.

## Почему foreground service

Android WorkManager не предназначен для гарантированного минутного polling. Поэтому активная рабочая сессия контролируется foreground service с постоянным системным уведомлением. Сервис запускается пользователем из приложения и опрашивает источники раз в 60 секунд.

В `AndroidManifest.xml` используется foreground service type `specialUse`, потому что длительный пользовательский watchdog не соответствует `dataSync`, `mediaPlayback`, `location` и другим специализированным типам. Для публикации в Google Play потребуется отдельно проверить актуальные требования Play Console к `specialUse` и обосновать назначение сервиса.

## Быстрый старт

1. Собрать и установить debug APK из GitHub Actions `Build AI Supervisor Android` или локально.
2. Запустить приложение. Первый проект уже будет `Время Ходом` → `fowov2-jpg/676017`.
3. Для private GitHub repositories нажать `GitHub token` и сохранить fine-grained PAT с минимально необходимыми read permissions для Contents/Actions.
4. Открыть `Accessibility` и вручную включить `AI Supervisor · Chat watcher`.
5. В карточке проекта включить `Автопин ChatGPT`, если нужен автоматический recovery.
6. Нажать `Запустить` — первая проверка выполняется сразу, затем каждые 60 секунд.

## Логика зависания

Для активного проекта Supervisor объединяет наблюдаемый прогресс из:

- изменений видимого интерфейса ChatGPT;
- нового commit;
- смены GitHub Actions run/job/step;
- изменения состояния подключённого API;
- изменения inventory.

По умолчанию:

- `180 сек` без прогресса → `SUSPICIOUS`;
- `300 сек` → `STALLED` и попытка автопинка, если он включён;
- `600 сек` → `HUNG` и отдельное уведомление.

Пороги редактируются для каждого проекта. Supervisor не пытается «читать мысли» модели и не подменяет отсутствие телеметрии вымышленным статусом.

## Пинатель ChatGPT

AccessibilityService видит только открытое окно официального Android-приложения ChatGPT (`com.openai.chatgpt`). Автопин работает только когда Android действительно предоставляет редактируемый узел чата и действие отправки. Текст пинка содержит название активного проекта, репозиторий, ветку и указание проверить зависший тест/сайт/CI/verification и продолжить с последнего незавершённого шага.

Если ChatGPT закрыт, Supervisor показывает уведомление и записывает событие. Он не пытается обходить системные ограничения Android или проверки сайта.

## Service Map

GitHub для каждого проекта встроен в модель проекта. Остальные сервисы добавляются как API endpoints. Примеры:

- Vercel: `https://api.vercel.com/v6/deployments?projectId=PROJECT_ID&limit=1`
- Sentry: `https://sentry.io/api/0/projects/ORG/PROJECT/issues/?query=is:unresolved&sort=date`
- PostHog: `https://us.posthog.com/api/projects/PROJECT_ID/events/?limit=1`
- Cloudflare: подходящий endpoint Cloudflare API для Workers/Pages/zone/account;
- Supabase: Management/health endpoint, который нужен конкретному проекту;
- Custom HTTP: любой HTTPS endpoint.

Для endpoint можно сохранить Bearer token. В дальнейшем архитектура допускает отдельные OAuth/Device Flow adapters без изменения модели проектов.

## Inventory

Раз в 15 минут GitHub scanner получает recursive tree и читает до 32 dependency/config files. Сейчас распознаются:

- `build.gradle(.kts)`, `settings.gradle(.kts)`, `libs.versions.toml`;
- `.github/workflows/*.yml|yaml` (`uses: owner/action@version`);
- `package.json`;
- `requirements*.txt`, `pyproject.toml`, `Pipfile`, `poetry.lock`;
- `go.mod`, `Cargo.toml`, `pom.xml`, `composer.json`;
- `Dockerfile`;
- `wrangler.*`, `vercel.json`, `supabase/config.toml`, `sentry.properties`.

Текущая версия парсит подробные версии Gradle/npm/requirements/GitHub Actions/Docker и использует остальные файлы как признаки используемых платформ. При изменении fingerprint появляется отдельное событие `Project inventory`.

## Безопасность

- секреты не хранятся в SQLite и не коммитятся;
- Bearer tokens шифруются AES/GCM, ключ хранится в Android Keystore;
- cleartext HTTP отключён;
- AccessibilityService ограничен только пакетом ChatGPT;
- автоматические destructive actions в v0.1 не выполняются — мониторинг read-only;
- перезапуск CI, rollback/deploy/stop process следует добавлять отдельными recovery actions с явными permissions и журналированием.

## Структура

- `MainActivity.kt` — мультипроектный dashboard и настройки;
- `SupervisorService.kt` — минутный цикл, hang detector, notifications, recovery/pinger;
- `GitHubProbe.kt` — commits, Actions jobs/steps, repository inventory;
- `PluginInventory.kt` — зависимости, plugins, Actions и service discovery;
- `RemoteProbe.kt` — API adapters;
- `ChatAccessibilityService.kt` — наблюдение за ChatGPT и пинатель;
- `SupervisorDb.kt` — проекты, integrations, timeline;
- `SecretStore.kt` — Android Keystore;
- `HttpClient.kt` — минимальный HTTPS client без внешней networking-библиотеки.

## Следующие расширения

Архитектура допускает GitHub OAuth Device Flow, более глубокие Vercel/Cloudflare/Supabase/PostHog/Sentry adapters, Telegram/Push gateway, remote agent heartbeat protocol, локальный command runner для Termux/ADB и recovery policies с ручным подтверждением.
