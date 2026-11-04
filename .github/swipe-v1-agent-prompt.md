# Задача: свайп-подбор тайтлов Takami (v1)

Репозиторий: `ilyuzorpyramur-glitch/Takami` (форк, Apache-2.0). Правки → коммит → push.
Сборка и тесты — только в GitHub Actions. `main` на момент написания задания — `aa29d769e`
(этап 11 влит); перед началом сделай `git fetch` и работай от актуального `main`.

Работай **в отдельной ветке** `feature/swipe-discovery`. В `main` не мержить:
слияние после приёмки.

Замысел — раздел 1 файла `docs/takami-future-features.md`, но **в репозитории его нет**
(каталог `docs/` в `.gitignore`, файл живёт только в рабочем каталоге владельца).
Если файла нет — источником требований считай это задание. Не ищи его в сети и не
создавай копию в репозитории.

## Твои границы

Ты работаешь **только с кодом**: экран, стек, данные, тесты, гейт, отчёт.
Живого устройства и живой сети у тебя нет.

Поэтому:

- не запускай `./gradlew` локально, ничего не устанавливай, не «проверяй на эмуляторе»;
- не пиши в отчёт числа, которых у тебя нет (сколько карточек, какая оценка у такого-то
  тайтла): прогона не было — чисел нет;
- не подгоняй код под конкретный тайтл или сайт — только под то, что доказывает код и тест;
- если для решения не хватает данных — так и скажи, вместо того чтобы выдумать поле.

## Главное про архитектуру

Свайп — это **новое представление уже существующей подборки**, а не рекомендательный движок.
Карточки берутся из той же таблицы `discovery_suggestions`, которую рисует карусель на
главной; оценки — из тех же ответов Shikimori/Jikan/AniList, что уже качает
`CompositeTrendingSource`. **Новых сетевых путей и новых источников данных не появляется.**
Если для карточки чего-то не хватает — поле протягивается по существующему конвейеру,
а не добывается отдельным запросом.

## 0.1 Изоляция: ты работаешь не один

Параллельно, в это же время, другой агент делает вторую фичу — блок новостей на главной.
У него своя ветка `feature/news-feed`, свой ключ `home_news` и своё задание. Обе ветки
растут от одной точки — `main` = `aa29d769e` — и сливаются по очереди. Всё, что ниже,
написано не из вежливости: это условия, при которых две работы не превращаются в один
конфликт на тысячу строк.

**Git**

- Работать только в `feature/swipe-discovery`. В `main` не пушить, чужие ветки не трогать,
  `feature/news-feed` не мержить в свою и не ребейзить на неё.
- Ребейз — только на `origin/main`, и только перед открытием PR.
- PR — в `main`, самому не вливать. Порядок слияния: сначала свайп, потом новости.

**Файлы, которые твои целиком** (создаёшь и правишь только их):

- `app/src/main/java/eu/kanade/tachiyomi/ui/discovery/` — новые файлы с префиксом `Swipe`
  (`SwipeDiscoveryScreen.kt`, `SwipeDiscoveryScreenModel.kt`, `SwipeDiscoveryLogic.kt`,
  `SwipeCard.kt` и прочее, что понадобится);
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeSwipeEntryCard.kt` — карточка-вход;
- `domain/src/main/java/tachiyomi/domain/discovery/` — только добавление полей в модель
  и миграция `63.sqm` (см. ниже);
- `app/src/test/` — тесты свайпа (`Swipe*Test.kt`);
- `.github/scripts/verify-swipe.sh`, `reports/swipe-v1.md`.

**Общий файл — ровно одна вставка.** `HomeHubScreenSections.kt` правят обе фичи, поэтому:

- вставляешь только свой `item(key = "home_swipe_entry", contentType = "home_hub_swipe_entry")`;
- место: сразу после закрытия блока hero (сейчас это строки ≈918–921 — два `}`, закрывающие
  `item(key = "hero")` и внешний `if`) и **до** `item(key = "home_search_bar")` (строка 922).
  Ниже «Историй» не спускаться;
- ничего не переформатировать: ни импорты целиком, ни соседние `item`, ни отступы чужого
  кода, ни `ktlint`-«причёсывание» всего файла. Твоя правка — добавленные строки;
- ключ `home_news` не добавлять, не переименовывать и не «улучшать»: его ставит второй
  агент, и его место — между `history` и `for_you`.

**Миграция БД — одна и твоя.** Последняя существующая — `62.sqm`, следующая свободная
`63.sqm` (`rating`, `episodes`, `status`). Второй фиче миграция не нужна вовсе: её поле
`news` живёт в JSON спеки. Значит `63.sqm` не отдавать, не переиспользовать, не
перенумеровывать и не «на всякий случай» дописывать туда чужие колонки.

**Строки.** Свои ключи — с префиксом `swipe_` — дописывать в конец
`i18n/src/commonMain/moko-resources/base/strings.xml` и `.../ru/strings.xml` отдельным
блоком с комментарием `<!-- Свайп-подбор -->`, прямо перед `</resources>`. Существующие
строки не переписывать и не сортировать. Если при слиянии два блока окажутся рядом —
остаются оба, порядок: свайп, затем новости.

**Чужая территория — не трогать вообще:** `takami/news/**`, `NewsSpec`, `newsCheckedAt`,
`SiteSpec.kt`, `Forge.kt`, `SpecEngine.kt`, `SelfCheck.kt`, ключи `news_*` в i18n,
`reports/stage12-news.md`, `.github/scripts/verify-stage12.sh`, `item(key = "home_news")`.
Если без правки одного из этих файлов фича не собирается — остановиться и написать об этом
в отчёте, а не править.

**Гейты не конфликтуют:** `verify-swipe.sh` — твой, `verify-stage12.sh` — чужой. Регрессии
`verify-stage11.sh` и `verify-stage10.sh` только читают, их менять нельзя.

**CI общий, но независимый.** Оба PR гоняют `takami-debug.yml` каждый на своей ветке.
Красный CI у соседней ветки — не твоя проблема и не повод ослаблять свой гейт.

## 0. Что уже есть — использовать, не писать заново

- `domain/src/main/java/tachiyomi/domain/discovery/model/DiscoverySuggestion.kt` — модель
  строки подборки: `id`, `mediaType`, `rowType`, `title`, `cleanTitle`, `coverUrl`, `reason`,
  `seedTitle`, `provider`, `score`, `position`, `createdAt`, `sourceId`, `sourceUrl`, `genres`.
  Там же `normalizeDiscoveryTitle()` — единственная разрешённая нормализация названий.
  **В модели нет ни оценки, ни числа серий, ни статуса, ни описания** — их придётся добавить
  (см. §2), это и есть основная работа по данным.
- `domain/src/main/java/tachiyomi/domain/discovery/repository/DiscoveryRepository.kt` —
  `subscribe()`, `hide`, `unhide`, `undoHide`, blacklist тегов, `getRecentExclusions` (48 ч),
  `recordExposure`, `recordSignal`, `getShownTitles`. Интерфейс без необходимости не менять.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeDiscoveryController.kt` (≈80–140) —
  карусель главной: `subscribeHidden` + `subscribeConsumed` + `subscribeBlacklist` →
  `policy.filterRowCandidates(items, { it.genres }, scoreSelector = { it.score })` (строка ≈132)
  → `dedupeCrossRow(filtered)` + `dismissedSessionTitles` (строка ≈134). **Это единственный
  правильный вход для стека свайпов.**
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/DiscoveryMix.kt` — `dedupeCrossRow`
  (≈285), приоритет рядов задаёт `DiscoveryRowType` (LIKE > TASTE > TREND > SOURCE).
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/DiscoveryRowBuilder.kt` (строка 6) —
  `DiscoveryRowItem` (`title`, `cleanTitle`, `coverUrl`, `reason`, `seedTitle`, `provider`,
  `score`, `sourceId`, `sourceUrl`, `genres`, `altCleanTitles`); `DiscoveryRunner.kt` (≈650)
  превращает его в `DiscoverySuggestion` перед `repository.replaceRows`.
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/ShikimoriTrendingSource.kt` — DTO
  `ShikimoriMediaItem` (строка ≈22) уже содержит `score: Double` (0..10), `status`, `kind`,
  `description`, но **не содержит `episodes`**; при маппинге в `DiscoveryTrendingItem`
  (≈88–102) всё это теряется, остаётся только `releaseStatus`.
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/AniListTrendingSource.kt` — точка
  внимания: `DiscoveryTrendingItem` (строка 30) и `DiscoveryMeta` (строка 43). В GraphQL-запросах
  списка выбираются только `id/title/coverImage/genres`, в запросе меты — `description/genres/
  coverImage/title`: `averageScore`, `episodes`, `status` **не запрошены**. Добавлять их нужно
  в те же запросы — это не новый путь, а расширение выборки.
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/JikanTrendingSource.kt` — ответ MAL
  содержит `score`, `episodes`, `status`, но маппинг (≈40–66) читает только `status`
  (`releaseStatus`). Оценка и серии — в том же ответе, отдельный запрос не нужен.
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/CompositeTrendingSource.kt` —
  `fetchMerged` (≈55–80): мержит **топ-2** провайдера, дедуп по `cleanTitle` «первый победил»,
  кап 50. Отсюда следствие для оценок: второй провайдер со своей оценкой уже отсёкся
  (см. §2.4).
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeHubDiscoverySection.kt` —
  `DiscoveryPosterCard` (≈652), `HomeHubDiscoveryItem`, `toSuggestionItem()`,
  `discoveryReasonText()`. Обложки и заголовки к ним — отдельный `DiscoveryCoverSource`
  (`ui/discovery/DiscoveryCoverSource.kt`), своего клиента для постеров не заводить.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeHubScreenSections.kt` — вход в фид
  подборки: `onForYouMoreClick` в строках 259 (anime), 402 (manga), 548 (novel),
  все три пушат `DiscoveryFeedScreen(...)`. Рядом и добавляется кнопка свайп-подбора.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeDiscoveryExposureTracker.kt` — показ
  ≥50 % и ≥1 с → `recordExposure`, ключ — `cleanTitle`.
- `app/src/main/java/eu/kanade/tachiyomi/ui/discovery/` — `DiscoveryFeedScreen`,
  `DiscoveryFeedScreenModel` (`hide()` ≈554, `undoHide()` ≈561, `addToLibrary()` ≈620,
  `prefetchDiscoveryMeta()` ≈207), `DiscoveryPreviewSheet`, `DiscoveryHideUndoQueue`
  (`enqueue { }`), `DiscoveryDirectOpenResolver`.
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/DiscoveryLibraryAdder.kt` —
  `addFromProvider(mediaType, title, provider): Boolean` (строка 45). Важно: `provider`
  он сравнивает с **именем источника-расширения** (`GetEnabledAnimeSources().first { it.name == provider }`),
  а не с провайдером подборки. Для строк трендов (`shikimori_trend`, `jikan_trend`,
  `anilist_trend`) совпадения обычно нет → `false`. В фиде на этот случай уже есть честный
  путь: `searchFallbackItem` + ручной поиск (см. `DiscoveryFeedScreenModel.addToLibrary`).
  Возвращаемые id уже есть внутри (`NetworkToLocalAnime.await(...)` → `List<Anime>` с `id`).
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/TasteSignalRecorder.kt` —
  `record(repository, item, signalType)` и `recordConsumed(...)`; сам решает жанры и
  альтернативные ключи. Свой «обучатель вкуса» не писать.
- Категории «Посмотреть позже»: `domain/src/main/java/tachiyomi/domain/category/{anime,manga,novel}/interactor/`
  — `CreateAnimeCategoryWithName` / `CreateMangaCategoryWithName` / `CreateNovelCategoryWithName`
  и `SetAnimeCategories` / `SetMangaCategories` / `SetNovelCategories` (`await(id, categoryIds)`).
- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/TitleCarouselScreen.kt` — существующий
  пример горизонтальных жестов; анимацию свайпа делай на `AnchoredDraggable`/`pointerInput`
  из Compose Foundation, без сторонних библиотек.
- Миграции: `data/src/main/sqldelight/migrations/`, последняя сейчас — `62.sqm`,
  таблица подборки — `data/src/main/sqldelight/data/discovery_suggestions.sq`,
  реализация — `data/src/main/java/tachiyomi/data/discovery/DiscoveryRepositoryImpl.kt`.

Если что-то из перечисленного уже переехало или API отличается — сверься с кодом и поступи
по коду, а в отчёте укажи расхождение. Не подгоняй код под этот список.

## 1. Экран и стек

Новый экран `SwipeDiscoveryScreen` + `SwipeDiscoveryScreenModel` в
`app/src/main/java/eu/kanade/tachiyomi/ui/discovery/`.

**Вход — сверху, там где сердце.** Отдельный `item(key = "home_swipe_entry",
contentType = "home_hub_swipe_entry")` в `HomeHubScreenSections.kt` сразу под hero
(или под приветствием, пока оно есть) и **выше строки поиска**: карточка-строка с иконкой
сердца, заголовком «Свайп-подбор» и короткой подписью. Это первое, что человек видит,
прокрутив главную на один экран, — а не кнопка в глубине «Для вас».
Старые входы тоже остаются: кнопка рядом с «Для вас» (`onForYouMoreClick`) на всех трёх
вкладках и пункт в шторке подборки. Медиатип — из `DiscoveryMediaType`, как в фиде.

Карточку-вход ставить так, чтобы соседние блоки не переставлять: свой `item` — своё место,
чужие ключи (`history`, `for_you`, `home_news`) не двигать. Блок новостей встанет ниже
«Историй», между ним и «Для вас», — вход свайпа и новости не должны спорить за место.

Стек — та же подборка, только по одной карточке. Порядок карточек = порядок после
`filterRowCandidates` + `dedupeCrossRow`; свою сортировку не выдумывать.

Поведение:

- Карточка: постер, название, краткое описание (3–4 строки, обрезка по словам, не по байтам),
  оценка, число серий, статус, жанры, причина рекомендации (`discoveryReasonText`).
  Нет значения — прочерк «—», поле не исчезает (иначе карточки будут разной высоты и
  пользователь не поймёт, что данных нет).
- Свайп вправо — «Посмотреть позже»: `LIKE`-сигнал тем же `TasteSignalRecorder`, добавление
  через `DiscoveryLibraryAdder`, затем назначение категории «Посмотреть позже».
  Детали, на которых легко ошибиться:
  - в `DiscoveryLibraryAdder` добавь вариант, возвращающий `id` добавленного тайтла: внутри
    `addAnime/addManga/addNovel` уже есть `NetworkToLocal{Anime,Manga,Novel}.await(listOf(...), autoFavorite = true)`,
    который возвращает `List<Anime>` с `id` — возьми `firstOrNull()?.id`. Существующий
    `Boolean`-метод `addFromProvider` не ломай (сделай его обёрткой над новым);
  - `addFromProvider` возвращает `false`, если `provider` не совпал с именем установленного
    источника (внешние `anilist_trend`/`jikan_trend`/`mangaupdates` и т.п.). Это не поломка,
    а норма: для таких карточек свайп вправо сразу ведёт на «Найти вручную», не выдумывай
    глобальный поиск с авто-добавлением;
  - `CreateAnimeCategoryWithName.await(name)` возвращает `Result.Success/InternalError` **без id** —
    id новой категории бери из `GetAnimeCategories.await()` по имени, уже созданную не дублируй;
  - `SetAnimeCategories.await(animeId, ids)` **заменяет** набор категорий целиком (внутри
    `deleteAnimeCategoryByAnimeId` + вставка), поэтому передавай объединение существующих
    категорий тайтла (`GetAnimeCategories.subscribe(animeId)`) с новой, иначе свайп сотрёт
    пользовательские категории. Аналогично `manga`/`novel`.
  Если добавить не удалось — тайтл **не теряется**: показываем честное сообщение и кнопку
  «Найти вручную» (тот же путь `searchFallbackItem`, что в фиде), а не молчим и не рисуем успех.
- Свайп влево — скрыть: `repository.hide()` + `recordSignal(HIDE)` + `backfillRemovedItem`,
  всё через `DiscoveryHideUndoQueue.enqueue`, как `DiscoveryFeedScreenModel.hide()`.
  Отмена — `repository.undoHide()` тем же путём: он удаляет и строку скрытия, и HIDE-сигнал
  (`deleteHideByTitle`), поэтому «отменил» не оставляет негативного следа в профиле вкуса.
  Окно отмены — 4 секунды, как в фиде (`delay(4000)` в `DiscoveryFeedScreen`), не 5.
- Тап — открыть тайтл (`DiscoveryDirectOpenResolver`), долгое нажатие — `DiscoveryPreviewSheet`.
- Кнопки «Скрыть» / «Позже» под карточкой обязательны: свайп не единственный способ.
  Обе кнопки достижимы одной рукой и имеют текстовые подписи для TalkBack.
- Память: не больше 30 карточек в стеке, предзагрузка 3 обложек вперёд, обложки — только
  через `DiscoveryCoverSource` (заголовки и реферер уже там).
- Анимация: карточка следует за пальцем, поворот ≤12°, при отпускании — возврат или уход.
  Без конфетти, звуков и вибрации-награды.
- Четыре честных пустых состояния: «Обновляем подборку» + кнопка; «На сегодня всё» +
  «Показать скрытые»; «Нет сети»; «Не удалось загрузить» + «Повторить». Пустого экрана
  без объяснения быть не должно.

## 2. Оценка и остальные поля карточки

Оценка не запрашивается отдельно: она протягивается по тому же конвейеру, которым карусель
и так наполняется, — от ответа провайдера до строки в `discovery_suggestions`.

**2.1. Источники (всё — из уже существующих запросов).**

Ни одного нового HTTP-вызова: только расширение уже выполняемых запросов и парсинга ответа,
который приложение и так получает для карусели. Сверяйся с кодом, ниже — состояние на момент
написания задания.

| Провайдер | Оценка | Серии | Статус | Описание |
|---|---|---|---|---|
| Shikimori | `score` 0..10 уже в `ShikimoriMediaItem` | поля нет в DTO — добавить (`episodes` в ответе API есть) | `status` уже в DTO (сырой: `ongoing`/`released`/`anons`/`on_hiatus`) | `description` уже в DTO |
| Jikan (MAL) | `score` 0..10 приходит в JSON, парсинг его не читает — добавить | `episodes` в JSON есть, не читается | сырой `status` уже читается в `releaseStatus` (по нему работает пост-фильтр) | `synopsis` уже читается в `fetchMeta` → `DiscoveryMeta.description` |
| AniList | `averageScore` 0..100 — добавить в выборку `buildSeasonQuery`/`buildPopularQuery`, делить на 10 | `episodes` — в выборку | `status` — в выборку | в meta-запросе `description(asHtml: false)` уже есть |

Важно: `DiscoveryTrendingItem` уже несёт `releaseStatus` — сырую строку провайдера
(`ongoing`, `Currently Airing`, `RELEASING`). Приводи её к общему виду через существующий
`DiscoveryReleaseStatus` (`ONGOING/FINISHED/ANONS/PAUSED`, в нём уже есть `shikimoriValue`
и `anilistValue`). Не заводить второй enum статусов и не показывать на карточке сырую
английскую строку.

**2.2. Оценка берётся из того же ответа, что и сама карточка.** Не из `DiscoveryMeta`:
мета кэшируется по названию и может быть получена у другого провайдера, тогда подпись
источника разъедется с цифрой. Оценка, серии и статус кладутся в `DiscoveryTrendingItem`
там, где парсится список:

`DiscoveryTrendingItem` (+`rating: Double? = null`, `episodes: Int? = null`; `releaseStatus`
уже есть) → `DiscoveryRowItem` (+те же поля) → `DiscoverySuggestion` (+`rating`, `episodes`,
`status`; `provider` уже есть) → колонки `rating REAL`, `episodes INTEGER`, `status TEXT`
в `discovery_suggestions` (миграция `63.sqm`, `ALTER TABLE ... ADD COLUMN` с `DEFAULT`;
обновить `insert` в `data/src/main/sqldelight/data/discovery_suggestions.sq` и маппер в
`DiscoveryRepositoryImpl.kt`, иначе новые колонки молча останутся пустыми).

Отдельная колонка источника оценки не нужна: оценка всегда приходит из ответа того же
провайдера, что и карточка, то есть `provider` уже отвечает на вопрос «откуда цифра».
Подпись рисуется одной функцией `ratingSourceLabel(provider)` в одном месте
(`shikimori_trend` → «Shikimori», `jikan_trend` → «MAL», `anilist_trend` → «AniList»),
незнакомый провайдер → подписи нет, остаётся только цифра. Названия сервисов — имена
собственные, переводить их не нужно; никаких других англицизмов в подписи нет.

Ноль — не оценка. У Shikimori `score` по умолчанию `0.0`, у AniList `averageScore` может
прийти `null`: и то и другое значит «оценки нет» и показывается как «—». Выдумывать
среднее, подставлять `0.0` или тянуть оценку из другого источника нельзя.

Описание в БД не дублируй: оно уже приходит из `DiscoveryMeta` и кэшируется (`globalMetaCache`,
`prefetchDiscoveryMeta`) — бери оттуда.

**2.3. `score` не переиспользовать под оценку.** `score` — множитель ранжирования, его читает
`DiscoveryCandidatePolicy` (`scoreSelector = { it.score }`). Смешаешь — поедут подборки на
главной. Это отдельное поле, отдельная колонка.

**2.4. Шкала и подпись.** Всегда 0..10. На карточке одна цифра и подпись источника
(«Shikimori 8.4»). Нет данных — «—», ни одного выдуманного числа: `0.0`, `null` и отсутствие
поля одинаково означают «нет оценки».

Разброс и «по N источникам» в v1 **не показывать**. Причина честная и техническая:
`CompositeTrendingSource.fetchMerged` дедупит по `cleanTitle` по принципу «первый победил»,
поэтому второй оценки у тайтла в модели обычно просто нет — подпись «по 2 источникам» была бы
ложью. Разброс — v1.1: мерж оценок внутри `fetchMerged` **до** дедупа (без новых запросов),
с показом «источники расходятся», если разница нормализованных оценок больше порога.

**2.5.** Никаких батч-запросов GraphQL, обвязок `ShikimoriApiRateLimiter` и отдельных кэшей
оценок на 24 часа в v1 нет: данные уже лежат рядом с карточкой.

## 3. Данные и привязка к карусели

- Экран читает те же `Flow` из `DiscoveryRepository`, что и `HomeDiscoveryController` —
  второго источника правды не создаём.
- Тот же фильтр, что у карусели, иначе стек покажет то, чего нет на главной, и наоборот.
  `filterRowCandidates` (`DiscoveryCandidatePolicy.kt:215`) уже вызывается в трёх местах:
  `HomeDiscoveryController.kt:132`, `DiscoveryFeedScreenModel.kt:342` и диагностика
  `DiscoveryFillAudit.kt:71` (там вызов без `scoreSelector` — поведение аудита не меняй).
  Стек — четвёртое. Чтобы шаг не разъехался, вынеси связку «фильтр + `dedupeCrossRow`»
  в общий селектор (например `DiscoveryStackSelector`) и вызывай его из карусели, фида и стека;
  поведение карусели и фида при этом не меняется, аудит не трогаем.
- Показ карточки учитывается той же логикой экспозиций (≥50 % и ≥1 с); окно 48 ч не обходить.
- `cleanTitle` — единственный ключ дедупликации. Свою логику сравнения названий не писать.
- Свайп вправо = `recordSignal(LIKE)` + добавление; влево = `recordSignal(HIDE)` + `hide`.
  Профиль вкуса учится сам, отдельный «обучатель» не нужен.
- Карточка визуально переиспользует `DiscoveryPosterCard`/`HomeHubDiscoveryItem`
  (`toSuggestionItem()`), чтобы арт, бейджи и подпись причины совпадали с каруселью.

## 4. Ограничения проекта (жёсткие)

- Сборка только через CI: `.github/workflows/takami-debug.yml` (`:app:testDebugUnitTest` +
  debug APK). Локально `gradlew` не запускать. Если правка не попала под path-фильтр
  workflow — запускай вручную: `gh workflow run takami-debug.yml --ref feature/swipe-discovery`,
  и репозиторий указывай явно: `-R ilyuzorpyramur-glitch/Takami` (в этом окружении `gh`
  по умолчанию смотрит на `upstream`).
- ktlint: `indent_size = 4`, `max_line_length = 120`, без wildcard-импортов. Комментарии
  по-русски, `TODO` не оставлять.
- Строки — в i18n: `i18n/src/commonMain/moko-resources/base/strings.xml` (английский,
  он же исходный) и `.../ru/strings.xml`; отдельного `en` в проекте нет, не заводить.
  Числа с падежами — в `plurals.xml` тех же каталогов. Русского текста в Compose-коде нет.
- Не трогать: `kodikdreamcast`, `dreamerscast.com`, подпись, `applicationId`, фильтр языка,
  каталог `heal/**`, `SPEC_VERSION` (остаётся `3`), спеки сайтов.
- Ключи, токены, cookies, эндпоинт коллектора — никогда в коде, логах, отчётах, коммитах.
- Лимиты к сайтам не менять: 2 запроса на приложение, 1 на домен, интервал 1500 мс,
  300 запросов в сутки на домен. Новых обращений к сайтам фича не добавляет.
- Новых тяжёлых зависимостей нет: сторонние swipe-библиотеки не подключать.
- Ветка `feature/swipe-discovery` не меняет `main`.

## 5. Тесты (обязательны, без сети и без устройства)

- Нормализация шкал: Shikimori 0..10, Jikan 0..10, AniList 0..100 → 10; `null`/`0` → «нет
  оценки» (а не «0.0» на карточке).
- Протяжка `rating`/`episodes`/`status` от провайдера до `DiscoverySuggestion` (маппер + фикстуры
  руками, без HTTP).
- Курсор стека: не отдаёт скрытые, не отдаёт показанные в окне 48 ч, не дублирует по `cleanTitle`,
  порядок совпадает с каруселью на одних и тех же входных данных.
- Undo: скрытие + отмена возвращает состояние и не плодит лишние сигналы (HIDE-сигнал удалён).
- Правая кнопка/свайп: неудача добавления не приводит к «успеху» в UI и не теряет тайтл.
- Экспозиция не срабатывает при свайпе мимо (<50 % или <1 с).
- Все существующие тесты остаются зелёными.

## 6. Гейт

Добавь `.github/scripts/verify-swipe.sh` по конвенции прошлых этапов (см.
`verify-stage10.sh`): shebang, русская шапка, `set -uo pipefail`,
`ROOT="${1:-$(pwd)}"`, `BASE="${2:-origin/main}"`, `HERE`, счётчики `PASS`/`FAIL` +
`declare -a FAILED=()`, помощники `ok()/bad()/skip()`, в конце
`ИТОГ: N PASS, M FAIL` и код возврата 0/1. Без сети, без gradle.

Регрессии запускать так же, как в `verify-stage11.sh`: в лог в `/tmp`, считать провалы
через `grep -E '^\[[A-Za-z0-9]+\] FAIL'` и вычитать `[H14b]`. Своё правило «этот провал
можно игнорировать» не выдумывать: список допустимых провалов — ровно `[H14b]`.

Проверки (минимум):

| # | Что проверяется |
|---|---|
| W1 | экран и модель на месте; вход `home_swipe_entry` стоит выше строки поиска, старые входы (три вкладки, шторка) не потеряны |
| W2 | стек строится из `DiscoveryRepository`, а не из своего HTTP/кэша: в новых файлах нет `OkHttp`, `fetch(`, прямых вызовов провайдеров |
| W3 | селектор стека общий с каруселью и фидом (оба вызывают его же), `DiscoveryFillAudit` не тронут |
| W4 | миграция `63.sqm` добавляет `rating`, `episodes`, `status`; миграции 1..62 не изменены |
| W4a | `insert` в `discovery_suggestions.sq` и маппер в `DiscoveryRepositoryImpl.kt` пишут все три колонки |
| W5 | `score` не переиспользован под оценку (колонки и поля разные) |
| W6 | нет строки «по N источникам»/`sources`-счётчика, пока разброс не реализован |
| W7 | строки есть в `base` и `ru`; в новых Compose-файлах нет русских литералов |
| W8 | длина строк ≤120 символов, отступы 4 пробела в новых файлах |
| W9 | нет секретов (`cookies`, `authorization`, `token`, `api_key`) в новых файлах и отчёте |
| W10 | отчёт `reports/swipe-v1.md` есть и ≤4096 байт |
| W11 | изоляция: в диффе от `origin/main` нет файлов второй фичи (`takami/news/**`, `NewsSpec`, `HomeNewsSection.kt`, `ui/news/**`, ключи `news_*`, `reports/stage12-news.md`, `verify-stage12.sh`); миграция только `63.sqm`, `64.sqm` и выше не создавались |
| R1 | `verify-stage11.sh` — регрессия; допустим единственный провал `[H14b]` («тронуты чужие файлы»), потому что фича законно правит главную; любой другой провал — нет |
| R2 | `verify-stage10.sh` — то же правило: только `[H14b]`, остальное — провал |

## 7. Порядок работы

1. Ветка `feature/swipe-discovery` от актуального `main`.
2. Прочитать перечисленные файлы, сверить API. Расхождения — в отчёт.
3. Каркас экрана и стек на существующих данных, без новых полей → push → CI.
4. Жесты, undo, кнопки, четыре пустых состояния, вход с трёх вкладок → push → CI.
5. Поля карточки: DTO/выборки провайдеров → модели → миграция `63.sqm` → маппер → карточка
   → push → CI.
6. «Посмотреть позже»: id из `DiscoveryLibraryAdder` + категория + честный отказ → push → CI.
7. Гейт `verify-swipe.sh` + `reports/swipe-v1.md`. Отчёт: `docs/` в `.gitignore`, поэтому
   `reports/` — нормальное место; если кладёшь документ в `docs/`, добавь его через `git add -f`.
8. Не мержить в `main` без зелёного CI и подтверждения владельца.

Коммиты по шагам, каждый собирается и проходит тесты:
`takami: экран свайп-подбора на существующей подборке (свайп 1)`,
`takami: жесты, отмена и пустые состояния (свайп 2)`,
`takami: поля карточки — оценка, серии, статус (свайп 3)`,
`takami: «посмотреть позже» с честным отказом (свайп 4)`,
`takami: гейт и отчёт свайп-подбора (свайп 5)`.

## 8. Критерии приёмки

1. Экран открывается с главной (карточка-вход `home_swipe_entry` выше строки поиска)
   и из шторки подборки; при наличии данных стек не пустой.
2. Свайп вправо добавляет тайтл в «Посмотреть позже», не стирая его прежние категории;
   неудача добавления видна пользователю и предлагает ручной поиск. Отмена — только у скрытия
   (как в фиде); добавление отменяется обычным удалением из библиотеки.
3. Свайп влево скрывает, отмена в течение 4 с работает, повторный показ не раньше 48 ч.
4. Оценка на карточке нормализована к 10 и подписана источником; нет данных — «—».
5. Карточка в стеке — тот же тайтл, что в карусели: состав и порядок совпадают, скрытое
   и прочитанное не всплывает.
6. Все четыре пустых состояния есть, пустого экрана без объяснения нет.
7. Кнопки-дубли свайпов есть, TalkBack читает карточку целиком.
8. `bash .github/scripts/verify-swipe.sh` → 0 FAIL; CI на коммите — success; старые тесты зелёные.
9. Новых обращений к сайтам сверх лимитов нет, ключей в коде нет.
10. Отчёт написан: что сделано, файлы, тесты, id прогонов CI, что осталось.

## 9. Вне рамок v1

- Разброс оценок и «по N источникам» (нужен мерж оценок в `fetchMerged` до дедупа).
- Отдельные сетевые запросы или кэши ради оценок.
- Похожие тайтлы на карточке, «умные» объяснения рекомендаций.
- Анимации-награды, XP, фамильяр.
- Правки парсеров сайтов и новые сетевые пути на источники.
- Перевод описаний, комментарии, новости.

## 10. Как сдавать

Короткий отчёт: что сделано, чем доказано (id CI, результат гейта), что осталось.
Без пересказа задания, без пересказа кода и без чисел, которых ты не получал.
