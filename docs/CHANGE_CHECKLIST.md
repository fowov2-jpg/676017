# ВремяХодом — обязательный чек-лист после изменения

Используется после каждого изменения кода, данных, конфигурации, CI, UX или ТЗ.

Допустимые статусы: **PASS**, **FAIL**, **NOT RUN**.

## Изменение

- Версия/ветка:
- Commit:
- CI run:
- Что изменено:
- Зачем:
- Затронутые файлы/компоненты:

## Проверки

- [ ] Существующая функциональность не сломана — PASS / FAIL / NOT RUN
- [ ] Unit tests — PASS / FAIL / NOT RUN
- [ ] Android Lint — PASS / FAIL / NOT RUN
- [ ] APK build — PASS / FAIL / NOT RUN
- [ ] APK ZIP integrity — PASS / FAIL / NOT RUN
- [ ] APK signature (`apksigner verify`) — PASS / FAIL / NOT RUN
- [ ] SHA-256 сформирован и проверен — PASS / FAIL / NOT RUN
- [ ] Emulator/instrumentation API 26 — PASS / FAIL / NOT RUN
- [ ] Emulator/instrumentation API 35 — PASS / FAIL / NOT RUN
- [ ] Runtime integration smoke — PASS / FAIL / NOT RUN
- [ ] Published runtime-current smoke — PASS / FAIL / NOT RUN
- [ ] Address runtime pack присутствует и проходит checksum/schema smoke — PASS / FAIL / NOT RUN
- [ ] Адресные regression tests: дом/корпус/строение/владение/нумерованная улица — PASS / FAIL / NOT RUN
- [ ] FastSearchController подключён активным lifecycle-owner — PASS / FAIL / NOT RUN
- [ ] FastRoutePlanner подключён активным lifecycle-owner — PASS / FAIL / NOT RUN
- [ ] Route button и IME action используют fast path — PASS / FAIL / NOT RUN
- [ ] First-route bounded budget соответствует `<= 2000 ms` contract — PASS / FAIL / NOT RUN
- [ ] Фактический first-result latency на применимом Moscow route corpus — PASS / FAIL / NOT RUN
- [ ] Профильные тесты изменённой функции — PASS / FAIL / NOT RUN
- [ ] Соответствие `docs/VREMYAHODOM_SPEC.md` — PASS / FAIL / NOT RUN
- [ ] Если затронут UI: определены применимые эталоны из `docs/ui-reference/` — PASS / FAIL / NOT RUN
- [ ] Если затронут UI: получены фактические скриншоты изменённых экранов — PASS / FAIL / NOT RUN
- [ ] Если затронут UI: выполнено визуальное сравнение с эталонами раздела 9 ТЗ — PASS / FAIL / NOT RUN
- [ ] Если затронут UI: все заметные отличия перечислены и классифицированы — PASS / FAIL / NOT RUN

## Профильная проверка

Указать применимые пункты и доказательства:

- маршрутизация / golden routes;
- address regression corpus;
- first-route latency / fast preview;
- UI / screenshot regression;
- визуальная сверка с `docs/ui-reference/218231.jpg`;
- визуальная сверка с `docs/ui-reference/218232.jpg`;
- визуальная сверка с `docs/ui-reference/218233.jpg`;
- визуальная сверка с `docs/ui-reference/218234.jpg`;
- визуальная сверка с `docs/ui-reference/218235.jpg`;
- accessibility;
- security / permissions / secrets / cleartext;
- performance / startup / memory / ANR;
- runtime/data integrity;
- геокодинг;
- active-trip / foreground service;
- другое.

### Правило адресного PASS

PASS по поиску адресов нельзя ставить только по наличию поля поиска или одному успешному адресу. Должны быть проверены разные формы адреса, включая корпус/строение/владение и нумерованную улицу. Номер корпуса или строения нельзя ошибочно принимать за базовый номер дома. Если адрес отсутствует во всех настроенных доверенных источниках, результат должен быть честным `не найден`, без выдуманных координат.

### Правило performance PASS

PASS по скорости маршрута нельзя ставить только потому, что маршрут когда-либо появился. Нужно отдельно проверить fast path и first-result contract. Полный background refinement может занимать дольше, но не должен блокировать первый пригодный маршрут. Увеличение timeout без устранения причины регрессии не считается исправлением.

### Правило визуального PASS

Для изменения UI статус PASS разрешён только если есть фактическая проверка результата. Зелёный instrumentation/emulator smoke сам по себе **не является** доказательством визуального соответствия эталону.

Если фактический скриншот получить невозможно, визуальный пункт получает NOT RUN с объяснением причины и риска. Если фактический экран существенно отличается от эталона без согласованной причины — FAIL.

Эталонные изображения и их SHA-256 закреплены в разделе 9 `docs/VREMYAHODOM_SPEC.md` и в `docs/ui-reference/README.md`.

## Результат

- Известные ограничения:
- Незакрытые ошибки:
- Что необходимо проверить вручную:
- Какие адресные сценарии проверены:
- First-result latency / условия замера:
- Какие эталонные UI-экраны затронуты:
- Какие визуальные отличия найдены:
- Можно ли выдавать APK: ДА / НЕТ

**Правило:** APK можно выдавать только если обязательные проверки ТЗ имеют PASS. FAIL и NOT RUN нельзя скрывать, заменять предположением или обходить удалением проверки.

## Чек-лист выпуска версии

- [ ] Указан номер версии
- [ ] Указан исходный commit
- [ ] Указан CI run
- [ ] Есть раздел «Что изменилось» относительно предыдущей опубликованной версии
- [ ] Есть раздел «Исправлено»
- [ ] Есть раздел «Тестирование» с фактическими PASS/FAIL/NOT RUN
- [ ] Есть раздел «Известные ограничения»
- [ ] Для изменений геокодинга указан адресный regression result
- [ ] Для изменений маршрутизации указан first-result latency result
- [ ] Для UI-изменений указан результат сверки с эталонными скриншотами
- [ ] Для UI-изменений перечислены существенные визуальные отличия
- [ ] Указан тип подписи APK
- [ ] Указан SHA-256
- [ ] Приложен APK
- [ ] Отдельно указано, что ещё не выполнено по ТЗ
