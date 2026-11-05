# Задача агенту 2: довести новости до готовности к слиянию

Репозиторий: `ilyuzorpyramur-glitch/Takami` (форк, Apache-2.0).
Ветка: `feature/news-feed` (`f6ddf8ec8`, 14 коммитов от `main` = `a1521e0ee`, CI зелёный).
Работай в своём worktree (например `/home/work/tmp/news`). В `main` не мержить, свайп не трогать.

## Что сделать

1. Прогнать на своей ветке `bash .github/scripts/verify-stage12.sh . origin/main` и
   `bash .github/scripts/verify-stage10.sh . origin/main`. Провалы устранять **только в своих файлах**;
   гейты не ослаблять.
2. Привести `reports/stage12-news.md` к фактам: сборка, номер прогона CI, число проверок, что именно проверено.
   Никаких чисел «на глаз».
3. Подготовить ветку к слиянию (не сливать, не rebase-ить на свайп): `git fetch origin`, убедиться, что с
   `main` конфликтов нет.
4. Если для проверки нужен живой прогон на устройстве — попроси оператора и жди; сам чисел не выдумывай.

## Границы (параллельно работает второй агент — свайп)

Разрешено править:

- `app/src/main/java/eu/kanade/tachiyomi/takami/news/**`
- `app/src/main/java/eu/kanade/tachiyomi/ui/news/**`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeNewsSection.kt`
- `app/src/test/java/eu/kanade/tachiyomi/takami/news/**`
- `.github/scripts/verify-stage12.sh`
- ключи `news_*` в `i18n/src/commonMain/moko-resources/{base,ru}/strings.xml`
- `reports/stage12-news.md`, этот файл

Запрещено (чужая зона, там работает второй агент):

- `app/src/main/java/eu/kanade/tachiyomi/ui/discovery/**`, `data/discovery/**`, `domain/.../discovery/**`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeSwipeEntryCard.kt`, `ui/home/HomeDiscoveryController.kt`
- `.github/scripts/verify-swipe.sh` и **`verify-stage10.sh`** (последний нельзя трогать ни при каких условиях)
- ветка `feature/swipe-discovery`, её файлы; ключи `swipe_*`

## Общие файлы — правило одно на двоих

1. `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeHubScreenSections.kt`
   Твой единственный блок — `item(key = "home_news", contentType = "home_hub_news")`: он стоит **после** блока
   «Истории» и **перед** «Для вас». Блок `home_swipe_entry` не трогать, импорты не переставлять, файл целиком
   не форматировать.
2. `i18n/.../strings.xml`
   Только ключи `news_*`, дописываются в конец файла. Ключи `swipe_*` не трогать, порядок не менять.

## Правила работы

- `app/src/main/java/eu/kanade/tachiyomi/takami/SiteSpec.kt`: допустимы только аддитивные правки поля `news`
  (ни одной удалённой строки). `SPEC_VERSION` остаётся `3`.
- `./gradlew` локально **не запускать** (Android SDK нет): сборка и тесты только в GitHub Actions.
- Строки ≤120 символов, без табуляций, комментарии по-русски, без `TODO/FIXME`.
- Коммиты по-русски, стиль `takami(news): …`.
- Порядок слияния объявлен: **свайп → новости**. Конфликт в общих файлах разрешает интегратор, не ты.

## Готово, когда

- `verify-stage12.sh` — 0 провалов, `verify-stage10.sh` — 0 провалов;
- CI на ветке зелёный;
- отчёт соответствует фактам;
- в ответе: хеш коммита, номер прогона CI, число проверок, что осталось.
