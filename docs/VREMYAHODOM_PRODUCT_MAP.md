# ВремяХодом — нормативная карта продукта

Этот документ является обязательным нормативным дополнением к:

- `docs/VREMYAHODOM_SPEC.md`;
- `docs/VREMYAHODOM_UI_QUALITY_CONTRACT.md`;
- `docs/VREMYAHODOM_TOOLCHAIN_CONTRACT.md`;
- `docs/CHANGE_CHECKLIST.md`.

Он нужен для того, чтобы любое изменение можно было проверить по цепочке **требование → экран/функция → источник данных → код/API → тест/evidence → критерий готовности**. Если элемент не подтверждён ТЗ, кодом или достоверным источником, его нельзя додумывать.

## 1. Статусы

Используются следующие статусы текущего состояния реализации:

- `IMPLEMENTED` — соответствующая логика существует в текущем коде; это не означает автоматический продуктовый или визуальный PASS.
- `PARTIAL` — реализована только часть обязательного поведения либо поведение существенно расходится с эталоном.
- `GAP` — обязательная часть ТЗ отсутствует либо текущая реализация ей противоречит.
- `NOT VERIFIED` — код/ресурс может существовать, но фактического доказательства текущей сборки недостаточно.
- `BLOCKER` — GAP/FAIL, который запрещает называть APK готовой.

`CI green` не меняет `GAP`, `PARTIAL`, `NOT VERIFIED` или визуальный `BLOCKER` на PASS.

## 2. Неизменяемые продуктовые границы

Название продукта: **ВремяХодом**.

Разрешённые режимы маршрутизации:

- WALK;
- BUS;
- TRAM;
- METRO;
- MCC;
- MCD;
- TRAIN.

Не поддерживаются и не должны появляться как маршрутные режимы без нового явного решения пользователя:

- велосипед/велошеринг;
- самокат/электросамокат;
- такси;
- автомобиль/каршеринг;
- речной транспорт.

Наличие соответствующей иконки в дизайнерском наборе не даёт права добавлять функцию.

## 3. Главная матрица продукта

| Область | Обязательное поведение | Текущий владелец/код | Источник данных | Проверка/evidence | Текущий статус |
|---|---|---|---|---|---|
| Launcher / branding | На устройстве точное имя `ВремяХодом`, согласованная иконка ВХ | Android manifest, `strings.xml`, mipmap launcher resources | локальные ресурсы APK | `aapt` + фактический launcher screenshot | **BLOCKER**: строка приложения сейчас `Время ходом`; точное визуальное совпадение launcher icon не доказано |
| Главный экран | Карта — основной фон; поиск, Дом/Работа/Рядом, map controls, заполненная карточка Рядом, bottom nav | `activity_main.xml`, `ResponsiveProductUi`, `ReferenceProductUiV2` | MapLibre style + runtime Nearby | Android screenshot рядом с `218231` и `218233` | **BLOCKER**: фактический экран существенно отличается от утверждённых референсов |
| Поиск A/B | Любой поддержанный адрес Москвы; точный дом выше центроида; честный `не найден` | `FastSearchController`, `FastAddressResolver`, `OfflineAddressIndex` | offline `runtime/address/address.sqlite` → Android Geocoder → Photon fallback | address regression corpus + offline test + network fallback tests | `IMPLEMENTED` архитектурно, но published address-pack и полный corpus должны быть доказаны для release |
| Fast route | Первый пригодный маршрут ≤ 2000 ms; полный refinement не блокирует first paint | `FastRoutePlanner`, `FastMeetRouter`, `HumanRouterEngine` | установленный runtime | performance instrumentation + сохранённые latency samples | `IMPLEMENTED`; обязателен фактический latency PASS конкретной build |
| Маршруты | WALK + допустимый общественный транспорт, time-dependent transfers, несколько альтернатив | `HumanRouterEngine`, surface/rail routers, ranker | WALK graph, surface runtime, rail graph/timetable | golden routes + API26/35 instrumentation | `PARTIAL/NOT VERIFIED` по полноте московских golden-route сценариев |
| Варианты маршрута | Крупное время, прибытие, пешие/транспортные сегменты, пересадки, альтернативы | `renderPlanResult`, `renderFilteredRoutes`, route filters/cards | результат routing engine | screenshot рядом с `218235` + interaction tests | **BLOCKER**: текущая композиция и плотность карточек не соответствуют `218235` |
| Карта маршрута | A/B, линия маршрута, остановки, карта остаётся видимой | MapLibre route sources/layers | геометрия RouteCandidate / WALK graph | screenshot + route map smoke | `IMPLEMENTED`, визуальное соответствие требует повторной проверки |
| Рядом | Конкретные остановки/станции, режимы, линии, расстояние и опубликованное ближайшее отправление там, где оно достоверно | `NearbyRepository`, `MainActivity.renderNearby` | surface schedule + rail graph + MTPPK timetable | populated-state instrumentation + screenshot `218231/218233` | функциональная логика `IMPLEMENTED`; **UI BLOCKER** пока normal populated-state не совпадает с референсом |
| Transport tab | Показывает транспорт рядом без выдуманного realtime vehicle tracking | navigation + `NearbyRepository` | локальный runtime | transit tests + populated screenshot | `IMPLEMENTED/PARTIAL` по UI |
| Favorites | Сохранить A/B, открыть заново, удалить | `FavoriteRoutesStore`, `renderFavorites` | локальные preferences | instrumentation | `IMPLEMENTED`; облачный backend не требуется ТЗ |
| Дом / Работа | Быстрый переход к сохранённому адресу | local preferences + quick actions | локальные данные пользователя | interaction test/manual | `IMPLEMENTED`, UX хранения должен соответствовать утверждённому дизайну |
| Настройки | Только реально работающие функции | `configureSettings`, `AppPreferences` | локальные preferences/runtime updater | каждый switch проверяется по фактическому эффекту | функционально `IMPLEMENTED`; **UI BLOCKER**: side-sheet существенно отличается от `218232` |
| Dark theme | Реально меняет theme/map style | AppPreferences + AppCompat + map style | локальные ресурсы | dark/rotation UI smoke | `IMPLEMENTED` |
| Show stops | Реально скрывает/показывает stop markers | `renderNearbyMarkers`, map layer visibility | Nearby runtime | settings interaction | `IMPLEMENTED` |
| Show route | Реально скрывает/показывает route layers | `applyLayerPreferences` | route geometry | settings interaction | `IMPLEMENTED` |
| Less walking | Меняет routing preferences и пересчитывает маршрут | AppPreferences + HumanRouterEngine | routing runtime | route preference test | `IMPLEMENTED` |
| Avoid transfers | Меняет routing preferences и пересчитывает маршрут | AppPreferences + HumanRouterEngine | routing runtime | route preference test | `IMPLEMENTED` |
| Runtime update | Установка/обновление данных Москвы атомарно и проверяемо | RuntimeInstaller/Worker, MainActivity runtime flow | GitHub `runtime-current` release | checksum/schema/runtime smoke | `IMPLEMENTED`; address pack должен быть отдельным обязательным release gate |
| Active trip | Транспорт/направление, GPS progress, ближайшие этапы/остановки, предупреждение выхода, завершение | `renderActiveTrip`, `PassengerGpsProgressCoordinator`, `TripProgressDetailBinder` | реальный пассажирский GPS + RouteCandidate | GPS replay + screenshot `218234` + foreground test | логика GPS/progress существует; **BLOCKER** по визуальной структуре `218234` и необходима проверка real-device behavior |
| Foreground notification | Постоянный статус активной поездки, ETA, завершение; предложения replan | `TripNavigationService` | GPS + routing engine | Android notification/service instrumentation/manual real-device | `IMPLEMENTED`, real-device notification UX `NOT VERIFIED` |
| Replan | Не навязывать; предложить подтверждённо лучший маршрут | `TripNavigationService`, `ReplanPolicy` | routing engine + GPS | policy/unit/instrumentation | `IMPLEMENTED`, требуется golden validation |
| Location permission | Разрешить / повторить / открыть settings / выбрать точку на карте | `LocationStateMachine`, MainActivity | Android LocationManager | permission-state instrumentation | `IMPLEMENTED` |
| Map point fallback | A/B можно выбрать центром карты без выдуманных координат | MainActivity map selection | фактическая camera target | interaction test | `IMPLEMENTED` |
| Error/loading states | Честно показывать недоступность данных/карты/адреса, не выдавать fixture за normal-state | MainActivity | фактические ошибки runtime/network | negative tests | `IMPLEMENTED/PARTIAL`; normal populated-state проверяется отдельно |
| Sentry | Только crash/ANR diagnostics, без PII и без зависимости продукта | `VremyaHodomApp`, Sentry Android SDK | опциональный DSN | тестовое событие конкретного release | SDK `IMPLEMENTED`, статус active = `NOT VERIFIED` пока нет доказанного события |
| PostHog | Только агрегированная продуктовая аналитика без адресов/GPS | сейчас Android SDK не подтверждён | — | принятое QA event | `NOT IN PRODUCT / NOT VERIFIED`; нельзя считать подключённым к APK по одному ChatGPT-коннектору |
| Supabase | Только если появится явно утверждённая backend-функция | сейчас продуктовый runtime не должен зависеть от него | — | schema/RLS/advisor только после утверждения функции | `AVAILABLE INFRASTRUCTURE`, не часть продукта |
| Figma | Измеряемая копия утверждённых UI reference, не новый источник функций | Figma project file | утверждённые screenshots | node/screenshot measurements | файл создан; содержимое дизайн-системы ещё `NOT VERIFIED/NOT COMPLETE` |

## 4. Экран 1 — главный экран / карта

### Требование

Источник визуальной истины: `218231` и `218233`.

Обязательные элементы normal populated-state:

1. карта Москвы как основной фон;
2. верхний поиск `Куда едем?`;
3. `Дом`, `Работа`, `Рядом`;
4. controls карты;
5. карточка `Рядом` с реальными доступными остановками/станциями;
6. нижняя навигация `Карта / Маршруты / Транспорт / Избранное`;
7. белые плавающие поверхности с согласованной геометрией;
8. никакой огромной пустой области вместо карты/контента.

### Фактическая реализация

`activity_main.xml` содержит MapLibre `MapView`, search panel, quick actions, location/settings buttons, nearby sheet и bottom nav. `NearbyRepository` формирует nearby places из установленного surface/rail runtime.

### Release criterion

`PASS` разрешён только после фактического Android screenshot normal populated-state, открытого рядом с `218231/218233`, без BLOCKER-различий.

Текущий результат: **FAIL/BLOCKER по UI**.

## 5. Экран 2 — поиск адреса

### Нормативный provider order

1. `runtime/address/address.sqlite`;
2. Android `Geocoder`, если доступен;
3. Photon network fallback.

`FastAddressResolver` реализует именно этот порядок: offline index имеет приоритет; Android Geocoder и Photon запускаются как fallback с bounded deadline и ranking точного дома.

`FastSearchController` устанавливается активным lifecycle owner и использует `FastAddressResolver` для live suggestions. `FastRoutePlanner` использует тот же resolver при построении A/B.

### Обязательные сценарии corpus

- обычный дом;
- корпус;
- строение;
- владение;
- дом с буквой;
- нумерованная улица;
- сокращения `ул.`, `пр-т`, `корп`, `стр`, `вл`;
- `ё/е`, пробелы и пунктуация;
- запрос с `Москва` и без города;
- неизвестный адрес → честный `не найден`.

### GAP, который нельзя скрывать

Наличие `OfflineAddressIndex` в коде недостаточно. Для release должно быть доказано, что опубликованный `runtime-current` действительно содержит `runtime/address/address.sqlite` с валидной schema/checksum и что CI блокирует исчезновение этого pack.

До такого evidence адресный release gate не считается закрытым.

## 6. Экран 3 — варианты маршрута

Источник истины: `218235`.

Обязательно:

- несколько альтернатив;
- крупное суммарное время;
- время прибытия;
- пешие участки;
- линии/номера транспорта;
- пересадки;
- понятные цели оптимизации;
- карта остаётся видимой;
- карточки не обрезают ключевой текст.

Код формирует цели `FASTEST`, `RELIABLE`, `LESS_WALKING`, `FEWER_TRANSFERS`, а UI умеет фильтры fastest/less walking/no transfers/metro/surface.

Однако наличие данных и interaction tests не доказывает визуальное соответствие. По фактическому screenshot текущая реализация существенно расходится с `218235`.

Текущий результат: **FAIL/BLOCKER по UI**.

## 7. Экран 4 — активная поездка

Источник истины: `218234`.

Обязательное состояние:

- текущий транспорт и направление;
- следующий/ближайшие этапы и остановки;
- явное предупреждение о выходе;
- время/условие до пересадки;
- видимый прогресс;
- `Завершить поездку`;
- синхронный foreground status.

Фактический код не ограничен таймерной заглушкой: `PassengerGpsProgressCoordinator` принимает реальные `LocationManager` samples и публикует их в `TripProgressState`; `TripProgressDetailBinder` умеет состояния `APPROACH / WAITING / ONBOARD / ALIGHTING / TRANSFER / FINAL_WALK / FINISHED / OFF_ROUTE`, в том числе тексты `Выходите на следующей` и `Выход через N остановок`.

Это подтверждает существование механизма, но не визуальный PASS. Текущая композиция active-trip существенно отличается от `218234`.

Текущий результат: функциональная база **IMPLEMENTED**, UI **FAIL/BLOCKER**; real-device GPS/notification UX — `NOT VERIFIED`.

## 8. Экран 5 — настройки

Источник истины: `218232`.

Разрешены только функции, у которых есть фактический эффект:

- показывать остановки;
- показывать маршрут;
- тёмная тема;
- меньше ходьбы;
- избегать пересадок;
- версия приложения/data runtime;
- проверка обновления данных.

Текущий код действительно связывает switches с map layers/preferences/routing engine и runtime updater. Поэтому удалять эти функции ради визуального совпадения нельзя; нужно привести их композицию к эталону.

Текущий результат: функционально **IMPLEMENTED**, UI **FAIL/BLOCKER**.

## 9. Транспортные данные и запрет выдумок

### Surface BUS/TRAM

`NearbyRepository` и routing engine читают опубликованный runtime schedule. `nextDepartureEpochSec` показывается только когда дата service schedule соответствует текущей дате; иначе код не должен выдавать случайное время как realtime.

### MCD/TRAIN

Используются только опубликованные данные МТППК на утверждённом проверенном участке Москва-Пассажирская — Зеленоград-Крюково. Расширять сеть без эквивалентно проверенного расписания запрещено.

### METRO/MCC

Допустимы только существующие routing datasets и явно обозначенная неопределённость. Нельзя создавать вымышленные departure times.

### Realtime

Приложение не является системой слежения за транспортными средствами. GPS — позиция пассажира. Нельзя визуально выдавать пассажирский GPS или статические runtime coordinates за realtime vehicle tracking.

## 10. Fast-first-result contract

`FastRoutePlanner` задаёт:

- shared A/B geocode budget: `780 ms`;
- fast preview budget: `820 ms`;
- first-result target: `<= 2000 ms`.

Fast preview показывается до полного `planOptions()`, а exact multimodal alternatives выполняются как refinement.

Release PASS требует фактического latency artifact конкретной build. Увеличение timeout вместо исправления причины регрессии запрещено.

## 11. Локальные данные пользователя

Сейчас локально допустимо хранить:

- Home/Work;
- выбранную вкладку;
- UI/routing preferences;
- Favorites;
- Active trip state.

Это не даёт права автоматически включать Supabase cloud sync или аккаунты. Такие функции требуют отдельного изменения основного ТЗ.

## 12. Observability / privacy

### Sentry

Разрешён crash/ANR monitoring при наличии DSN. В текущем приложении Sentry настроен с `isSendDefaultPii=false`, screenshot/view hierarchy отключены, normal app flow не зависит от Sentry.

Запрещено отправлять:

- адресные строки;
- точные A/B coordinates;
- GPS trace;
- полный маршрут пользователя.

### PostHog

До фактического подключения Android SDK и получения тестового QA event PostHog считается только доступным внешним инструментом. Наличие ChatGPT-коннектора не является evidence интеграции APK.

Даже после подключения запрещены raw address/GPS/route contents. Разрешены технические события и числовые latency/error categories.

## 13. Обязательные доказательства по каждому экрану

Для UI-экрана нельзя поставить PASS без всех пунктов:

1. reference определён;
2. APK собран из текущего commit;
3. фактический screenshot получен из этой APK;
4. reference и screenshot реально открыты рядом;
5. проверены композиция, доля карты, typography, icons, spacing, sheets и содержимое;
6. все заметные различия перечислены;
7. каждый BLOCKER исправлен;
8. после исправления получен новый screenshot;
9. normal populated-state не заменён fixture/loading/empty-state;
10. соответствующий interaction test остаётся зелёным.

## 14. Обязательные release blockers на текущем этапе

До устранения следующих пунктов APK нельзя называть готовой:

1. **Главный экран** — существенное визуальное расхождение с `218231/218233`.
2. **Настройки** — существенное визуальное расхождение с `218232`.
3. **Варианты маршрута** — существенное визуальное расхождение с `218235`.
4. **Активная поездка** — существенное визуальное расхождение с `218234`.
5. **Брендинг** — строка приложения должна быть точно `ВремяХодом`, без пробела.
6. **Launcher icon** — должно быть доказано фактическое совпадение с согласованной иконкой, а не только наличие mipmap resource.
7. **Address runtime** — опубликованный address pack и CI checksum/schema gate должны иметь фактический PASS.
8. **UI icon system** — должна быть приведена к согласованному набору outline/pin icons без добавления запрещённых транспортных функций.
9. **Populated-state** — главный `Рядом` должен быть проверен с реальными runtime data, а не только loading/empty fixture.
10. **Real-device evidence** — launcher, MapLibre GPU rendering, touch/IME и foreground notification должны быть отмечены PASS либо честным NOT RUN; emulator PASS не отменяет ошибку на реальном телефоне.

## 15. Приоритет исправлений

Порядок определяется продуктовой зависимостью, а не удобством кода:

1. исправить exact branding и доказать launcher icon;
2. привести главный экран к `218231/218233`;
3. привести settings side-sheet к `218232`;
4. привести route-options к `218235`;
5. привести active-trip к `218234`, сохранив GPS logic;
6. привести транспортные и навигационные иконки к утверждённой системе;
7. подтвердить published address runtime + corpus;
8. выполнить полный UI screenshot regression;
9. повторить functional CI/API26/API35/responsive/GPS gates;
10. только после этого формировать новый QA APK.

## 16. Правило изменения этой карты

Новый экран, функция, backend, telemetry event, provider данных или transport mode нельзя добавлять молча.

Изменение допускается только если:

1. есть явное новое требование пользователя;
2. обновлён соответствующий нормативный документ;
3. описан источник данных и privacy boundary;
4. определён тест/evidence;
5. после реализации заполнен `CHANGE_CHECKLIST.md`.

Если требования нет — статус не `TODO`, а **НЕ ДОБАВЛЯТЬ**.
