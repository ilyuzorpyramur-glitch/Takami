# Генератор плагинов ранобэ (Takami)

Из JSON-конфига сайта собирает готовый LNReader-совместимый плагин для рантайма
новелл Takami (`NovelJsRuntime`): каталог, поиск, страница новеллы, глава.

## Конфиг

Обязательные поля: `id`, `name`, `site`, `lang`, `version`.

```json
{
  "id": "example.ranobe",
  "name": "Example Ranobe",
  "site": "https://example.org",
  "lang": "ru",
  "version": 1,
  "listings": {
    "popular": { "path": "/catalog?page={page}", "item": ".card", "name": ".title", "link": ".title@href", "cover": "img@src" },
    "latest":  { "path": "/updates?page={page}", "item": ".card", "name": ".title", "link": ".title@href" }
  },
  "search":   { "path": "/search?q={query}&page={page}", "item": ".card", "name": ".title", "link": ".title@href", "queryEncoding": "uriComponent" },
  "novel": {
    "name": ".title", "author": ".author", "artist": ".artist",
    "cover": ".poster img@src", "description": ".summary",
    "status": ".status", "tags": ".tags a",
    "chapters": { "item": ".chapter", "name": ".chapter-name", "path": "a@href", "releaseTime": ".chapter-time" }
  },
  "chapter": { "content": ".chapter-content", "mode": "text", "remove": [".ad"] }
}
```

- Селектор без `@` берёт текст, `селектор@атрибут` — атрибут (`a@href`, `img@src`).
- `latestNovels` и `searchNovels` попадают в плагин, только если описаны в конфиге;
  рантайм сам откатывается на `popularNovels`, когда `latestNovels` нет.
- `chapter.mode`: `text` (абзацы через перевод строки, по умолчанию) или `html`.
- `chapter.remove` — селекторы, вычищаемые из главы вместе со `script`/`style`.
- `encoding` — кодировка сайта для `textEncoding` рантайма (например,
  `windows-1251`): передаётся с каждым запросом плагина.
- `headers` — объект заголовков, уходящих с каждым запросом (`Referer`,
  `User-Agent`).
- Селектор с `@атрибут` без селектора (`@href`) берёт атрибут самого элемента;
  селектор вида `^a@href` поднимается к ближайшему предку `a` и берёт его
  атрибут — для карточек, где сама ссылка оборачивает разметку.
- Обложка из `style="background: url(...)"` распознаётся автоматически.

## Запуск

```bash
npm install --no-save   # только для проверок: cheerio и dayjs
node index.js --input examples/example-ranobe.json --out-dir out --index
```

Конфигом может быть один объект или массив объектов: во втором случае
плагины генерируются для каждой записи, а индекс пополняется всеми ими.

Конфигом может быть один объект или массив объектов: во втором случае
плагины генерируются для каждой записи, а индекс пополняется всеми ими.

`--index` пишет `plugins.json` — формат LNReader-репозитория, который приложение
ищет первым (`plugins.min.json`, `plugins.json`, `index.json`, `index.pb`,
`index.min.json`, см. `NovelPluginRepoUrls.kt`). Запись несёт `sha256` файла
плагина и совместима с `tools/lnreader-plugin-converter`: результат генератора
можно прогнать и через него.

## Как поставить плагин в приложение

Приложение читает репозиторий плагинов по адресу, который добавляет
пользователь в разделе расширений новелл: первым запрашивается
`plugins.min.json`, затем `plugins.json`, `index.json`, `index.pb`,
`index.min.json` (см. `NovelPluginRepoUrls.kt`). Путь работы:

1. `node index.js --input config.json --out-dir out --index` — плагин и
   `plugins.json`.
2. Каталог `out/` публикуется как статический репозиторий (GitHub Pages,
   любой хостинг): файлы плагинов и `plugins.json` рядом.
3. Адрес каталога добавляется в приложении как репозиторий плагинов новелл.

## Проверки

`npm test` — функциональный прогон без сети:

- генерирует плагины из примеров и загружает их в песочницу `node:vm` тем же
  способом, что и `NovelPluginScriptBuilder` в приложении;
- подменяет `@libs/fetch` локальными приспособлениями (HTML-страницы), `cheerio`
  и `dayjs` берутся из npm, `isUrlAbsolute` копирует логику рантайма;
- проверяет все поверхности: карточки, страницы, подстановку `{page}`/`{query}`,
  фильтр карточек без ссылки, даты глав в ISO, чистку главы, оба режима `mode`;
- проверяет CLI с `--index` и совпадение `sha256` в `plugins.json`;
- проверяет правила репозитория: строки до 120 символов, без табуляций,
  без `TODO/FIXME`, комментарии по-русски;
- проверяет отказ на плохом конфиге с понятным сообщением.
