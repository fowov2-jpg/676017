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
- [ ] Профильные тесты изменённой функции — PASS / FAIL / NOT RUN
- [ ] Соответствие `docs/VREMYAHODOM_SPEC.md` — PASS / FAIL / NOT RUN

## Профильная проверка

Указать применимые пункты и доказательства:

- маршрутизация / golden routes;
- UI / screenshot regression;
- accessibility;
- security / permissions / secrets / cleartext;
- performance / startup / memory / ANR;
- runtime/data integrity;
- геокодинг;
- active-trip / foreground service;
- другое.

## Результат

- Известные ограничения:
- Незакрытые ошибки:
- Что необходимо проверить вручную:
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
- [ ] Указан тип подписи APK
- [ ] Указан SHA-256
- [ ] Приложен APK
- [ ] Отдельно указано, что ещё не выполнено по ТЗ
