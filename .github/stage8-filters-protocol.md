# Протокол этапа 8 — фильтры источника

## Что сделано

Фильтры источника описаны в спеке (`SiteSpec.filters`), приложение показывает
их на экране «Фильтры источника», выбор уходит в адрес каталога и поиска,
хранится в `filesDir/filters.json` по хосту и переживает перезапуск.

## Файлы

- `app/src/main/java/eu/kanade/tachiyomi/takami/FilterSpec.kt` — модель: `FilterSpec`, `FilterOption`
- `app/src/main/java/eu/kanade/tachiyomi/takami/SiteSpec.kt` — одно новое поле `filters`
- `app/src/main/java/eu/kanade/tachiyomi/takami/filters/FilterState.kt` — выбранное, чистка чужих ключей и значений
- `app/src/main/java/eu/kanade/tachiyomi/takami/filters/FilterUrl.kt` — сборка адреса из выбора
- `app/src/main/java/eu/kanade/tachiyomi/takami/filters/FilterStore.kt` — хранение по хосту
- `app/src/main/java/eu/kanade/tachiyomi/takami/filters/FilterList.kt` — спека ↔ `AnimeFilterList`
- `app/src/main/java/eu/kanade/tachiyomi/takami/SpecEngine.kt` — `catalogue`/`latest`/`search` приняли готовый адрес
- `app/src/main/java/eu/kanade/tachiyomi/takami/TakamiForge.kt` — `getFilterList`, `getSearchAnime`, сохранение после успешного запроса
- `app/src/main/java/eu/kanade/tachiyomi/takami/Forge.kt` — `scanFilters` + вызов при создании спеки
- `app/src/main/java/eu/kanade/tachiyomi/ui/takami/FilterScreen.kt`, `FilterScreenModel.kt` — экран
- `.github/scripts/verify-stage8.sh` — гейт
- `app/src/test/java/eu/kanade/tachiyomi/takami/filters/` — тесты без сети

## Формат filters.json

```json
{ "хост": { "ключ": ["значение", "..."] } }
```

Значения — как в адресе; исключение у tri-фильтра хранится с минусом
(`"-значение"`). Пустой выбор убирает хост из файла. Битый файл читается
как пустое состояние, запись после него восстанавливает файл.

## Поля спеки

Единственное новое поле `SiteSpec`: `filters: List<FilterSpec> = emptyList()`.

`FilterSpec`: `name` (подпись по-русски), `type` (`check | select | tri | text | sort`),
`key` (имя параметра), `valueTemplate` (`"{v}"`, `"genre/{v}"`, `"year-{v}"`),
`options` (подпись → значение), `maxSelected` (0 — без ограничения, 1 — как radio),
`default` (работает, если пользователь ключ не трогал), `inUrl` (`query | path`),
`selector` (блок фильтров для сканера).

Семантика адреса: `query` — каждое значение отдельным параметром через `&`;
`path` — `valueTemplate` подставляется в путь; `sort` и `text` — один параметр;
пустое значение `text` не добавляется; пустое состояние адрес не меняет вообще.

## Команда приёмки

```bash
bash .github/scripts/verify-stage8.sh
```

CI на коммите ветки `takami/etap8-filters`: `:app:assembleDebug -Ptakami.abi=x86_64`
и юнит-тесты. Сборка и тесты — только в CI.

## Что считается успехом

1. Гейт: 0 FAIL (включая регрессию этапа 6).
2. CI зелёный, APK собирается, старые тесты не падают.
3. Спека версии 2 без поля `filters` читается и работает как раньше.
4. Ветка не содержит правок этапа 7 и не меняет `main`.
