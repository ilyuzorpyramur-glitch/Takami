# Задача агенту 1: добить свайп-подбор

Репозиторий: `ilyuzorpyramur-glitch/Takami` (форк, Apache-2.0).
Ветка: `feature/swipe-discovery` (`29e9b2181`, 10 коммитов от `main` = `a1521e0ee`).
Работай в своём worktree (например `/home/work/tmp/swipe`). В `main` не мержить, чужие ветки не трогать.

## Что сломано

CI падает на компиляции. Точки отказа — `app/src/main/java/eu/kanade/tachiyomi/ui/discovery/SwipeDiscoveryScreen.kt`:

- строка 41: `Unresolved reference 'Screen'` — класс объявлен `: Screen()`, но интерфейс импортирован под псевдонимом
  `import cafe.adriel.voyager.core.screen.Screen as VoyagerScreenInterface`;
- строка 54: `rememberScreenModel` — `receiver type mismatch` (следствие первой ошибки);
- строка 55: `Unresolved reference 'start'` (тоже следствие).

Починить минимально: класс реализует интерфейс Voyager (через псевдоним или обычный импорт), экран не переписывать.
Затем `bash .github/scripts/verify-swipe.sh . origin/main` — добиться нуля провалов. Проверки не отключать,
не ослаблять и не «подгонять» под код: если гейт прав — правится код.

## Границы (параллельно работает второй агент — новости)

Разрешено править:

- `app/src/main/java/eu/kanade/tachiyomi/ui/discovery/**`
- `app/src/main/java/eu/kanade/tachiyomi/data/discovery/**`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeSwipeEntryCard.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeDiscoveryController.kt`
- `data/src/main/java/tachiyomi/data/discovery/**`, `data/src/main/sqldelight/data/discovery_suggestions.sq`,
  `data/src/main/sqldelight/migrations/63.sqm`
- `domain/src/main/java/tachiyomi/domain/discovery/**`
- `app/src/test/java/eu/kanade/tachiyomi/ui/discovery/**`
- `.github/scripts/verify-swipe.sh`, `reports/swipe-v1.md`, этот файл

Запрещено (чужая зона, там работает второй агент):

- весь пакет `app/src/main/java/eu/kanade/tachiyomi/takami/**`
- любые `.github/scripts/verify-stage*.sh` — **все гейты этапов**, включая `verify-stage10.sh`
- `app/src/main/java/eu/kanade/tachiyomi/ui/news/**`, `ui/home/HomeNewsSection.kt`, `reports/stage12-news.md`
- ветка `feature/news-feed`, `feature/video-chain` и их файлы

## Общие файлы — правило одно на двоих

1. `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeHubScreenSections.kt`
   Твой единственный блок — `item(key = "home_swipe_entry", contentType = "home_hub_swipe_entry")`, он ставится
   **перед** `item(key = "home_search_bar", …)`. Блок `home_news` не трогать, чужие блоки не переставлять,
   файл целиком не форматировать, импорты не сортировать — добавляется ровно один импорт (`SwipeDiscoveryScreen`).
2. `i18n/src/commonMain/moko-resources/base/strings.xml` и `.../ru/strings.xml`
   Только ключи `swipe_*`, дописываются **в конец** файла. Ключи `news_*` не трогать, ничего не сортировать
   и не переставлять.

## Правила работы

- `./gradlew` локально **не запускать** (Android SDK нет). Сборка и тесты — только GitHub Actions:
  push → дождаться зелёного `Takami debug APK`.
- `SPEC_VERSION` остаётся `3`; новых зависимостей не добавлять.
- Строки не длиннее 120 символов, табуляции не использовать, комментарии по-русски, `TODO/FIXME` не оставлять.
- Коммиты по-русски, стиль `takami(swipe): …`.
- Числа только свои: хеш, номер прогона CI, число проверок. Не придумывать ни отчётов, ни цифр.

## Готово, когда

- `bash .github/scripts/verify-swipe.sh . origin/main` — 0 провалов;
- CI на ветке зелёный;
- в `reports/swipe-v1.md` записано, что было сломано и чем доказано;
- в ответе: хеш коммита, номер прогона CI, число проверок, что осталось.
