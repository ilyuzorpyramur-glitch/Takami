#!/usr/bin/env bash
# Гейт этапа 12: новости аниме-сайтов на главной.
# Регрессии этапов 11 и 10 только читает; сам ничего не собирает и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage12.sh [путь-к-ветке] [базовая-ветка]
#
# Код возврата: 0 — можно вливать, 1 — есть FAIL.

set -uo pipefail

ROOT="${1:-$(pwd)}"
BASE="${2:-origin/main}"
HERE="$(cd "$(dirname "$0")" && pwd)"

PASS=0
FAIL=0
declare -a FAILED=()

ok()   { PASS=$((PASS + 1)); printf '[%s] PASS  %s\n' "$1" "$2"; }
bad()  { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf '[%s] FAIL  %s\n' "$1" "$2"; }
skip() { printf '[%s] SKIP  %s\n' "$1" "$2"; }

TAKAMI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami"
NEWS="$TAKAMI/news"
UI_NEWS="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/news"
HOME_NEWS="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeNewsSection.kt"
HUB="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeHubScreenSections.kt"
I18N_BASE="$ROOT/i18n/src/commonMain/moko-resources/base/strings.xml"
I18N_RU="$ROOT/i18n/src/commonMain/moko-resources/ru/strings.xml"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/news"
REPORT="$ROOT/reports/stage12-news.md"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 12: новости на главной ==="
echo "каталог: $ROOT"
echo

NEWFILES="$NEWS/NewsModels.kt $NEWS/NewsParser.kt $NEWS/NewsDetector.kt $NEWS/NewsPolicy.kt $NEWS/NewsCache.kt $NEWS/NewsFeed.kt $UI_NEWS/NewsStateModel.kt $UI_NEWS/NewsItemCard.kt $UI_NEWS/NewsScreen.kt $HOME_NEWS"

echo "--- спека ---"

if grep -q 'val news: NewsSpec? = null' "$TAKAMI/SiteSpec.kt" && grep -q 'val newsCheckedAt: Long = 0L' "$TAKAMI/SiteSpec.kt"; then
    ok N1a "NewsSpec и newsCheckedAt добавлены с дефолтами"
else
    bad N1a "в SiteSpec нет поля news или newsCheckedAt с дефолтом"
fi

if grep -q 'const val SPEC_VERSION = 3' "$TAKAMI/spec/SpecVersion.kt"; then
    ok N1b "SPEC_VERSION остался 3"
else
    bad N1b "SPEC_VERSION изменён"
fi

TOUCHED=$(git diff --name-only "$BASE" -- "$TAKAMI/spec/SpecVersion.kt" "$TAKAMI/spec/SpecMigration.kt" "$TAKAMI/spec/SpecHistory.kt" "$TAKAMI/spec/SpecRollback.kt" 2>/dev/null)
if [ -z "$TOUCHED" ]; then
    ok N2 "файлы версии и истории спеки не тронуты"
else
    bad N2 "тронуты чужие файлы спеки: $(echo "$TOUCHED" | tr '\n' ' ')"
fi

echo "--- детект и сеть ---"

[ -f "$NEWS/NewsDetector.kt" ] && ok N3a "NewsDetector.kt есть" || bad N3a "нет takami/news/NewsDetector.kt"
[ -f "$NEWS/NewsPolicy.kt" ] && ok N3b "NewsPolicy.kt есть" || bad N3b "нет takami/news/NewsPolicy.kt"

NET=$(grep -nE 'OkHttpClient|URL\(|HttpURLConnection|Jsoup\.connect' $NEWFILES 2>/dev/null | head -3)
if [ -z "$NET" ]; then
    ok N3c "в новых файлах сеть только через Fetcher"
else
    bad N3c "сеть мимо Fetcher: $NET"
fi

if grep -q 'MAX_DETECT_PER_OPENING = 2' "$NEWS/NewsPolicy.kt" && grep -q 'newsCheckedAt' "$UI_NEWS/NewsStateModel.kt" && grep -q 'RETRY_AFTER_MS' "$NEWS/NewsPolicy.kt"; then
    ok N4 "детект ограничен двумя источниками и отметкой newsCheckedAt"
else
    bad N4 "нет ограничения детекта"
fi

if grep -q 'RSS in offline mode' "$NEWS/NewsParser.kt" && grep -q 'isStubPage' "$NEWS/NewsDetector.kt" && grep -q 'isStubPage' "$NEWS/NewsFeed.kt"; then
    ok N5 "заглушка и пустой ответ дают null, а не запись"
else
    bad N5 "заглушки не распознаются"
fi

echo "--- главная ---"

if [ -f "$HOME_NEWS" ] && [ -f "$HUB" ]; then
    ok N6a "HomeNewsSection.kt и вставка на месте"

    HIST=$(grep -n 'item(key = "history"' "$HUB" | head -1 | cut -d: -f1)
    NEWS_LINE=$(grep -n 'item(key = "home_news"' "$HUB" | head -1 | cut -d: -f1)
    FORYOU=$(grep -n 'item(key = "for_you"' "$HUB" | head -1 | cut -d: -f1)
    if [ -n "$HIST" ] && [ -n "$NEWS_LINE" ] && [ -n "$FORYOU" ] && \
        [ "$HIST" -lt "$NEWS_LINE" ] && [ "$NEWS_LINE" -lt "$FORYOU" ]; then
        ok N6b "блок стоит после историй и до «Для вас»"
    else
        bad N6b "блок не на своём месте (history=$HIST, news=$NEWS_LINE, for_you=$FORYOU)"
    fi

    SWIPE_ADDED=$(git diff "$BASE" -- "$HUB" 2>/dev/null | grep '^+' | grep -v '^+++' | grep 'home_swipe_entry' | head -2)
    if [ -z "$SWIPE_ADDED" ]; then
        ok N6c "чужой ключ home_swipe_entry не добавлялся"
    else
        bad N6c "добавлен чужой ключ свайпа"
    fi
else
    bad N6a "нет ui/home/HomeNewsSection.kt или вставки"
fi

if grep -q 'if (items.isEmpty()) return' "$HOME_NEWS"; then
    ok N7 "пустой список не рисует блок"
else
    bad N7 "пустой блок всё же рисуется"
fi

echo "--- правила ---"

if grep -q 'dreamerscast.com' "$NEWS/NewsPolicy.kt" && grep -q 'kodikdreamcast' "$NEWS/NewsPolicy.kt" && \
    grep -q 'dreamerscast.com' "$TESTS/NewsPolicyTest.kt" && grep -q 'kodikdreamcast' "$TESTS/NewsPolicyTest.kt"; then
    ok N8 "источник только для чтения закрыт кодом и тестом"
else
    bad N8 "нет запрета dreamerscast/kodikdreamcast в коде или тесте"
fi

BODY=$(grep -nE 'val body|val fullText|val articleText' "$NEWS/NewsModels.kt" 2>/dev/null | head -3)
SNIPPET_LINES=$(grep -c 'maxLines = 3' "$UI_NEWS/NewsItemCard.kt")
if [ -z "$BODY" ] && [ "$SNIPPET_LINES" -ge 1 ]; then
    ok N9 "полного текста в моделях нет, анонс до трёх строк"
else
    bad N9 "в модели есть тело статьи или анонс не ограничен"
fi

echo "--- строки ---"

MISSINGI18N=""
for key in news_title news_more news_today news_yesterday news_screen_title news_empty news_retry news_refresh; do
    grep -q "name=\"$key\"" "$I18N_BASE" || MISSINGI18N="$MISSINGI18N base:$key"
    grep -q "name=\"$key\"" "$I18N_RU" || MISSINGI18N="$MISSINGI18N ru:$key"
done
if [ -z "$MISSINGI18N" ]; then
    ok N10a "строки на месте в base и ru"
else
    bad N10a "нет строк:$MISSINGI18N"
fi

UIFILES="$UI_NEWS/NewsStateModel.kt $UI_NEWS/NewsItemCard.kt $UI_NEWS/NewsScreen.kt $HOME_NEWS"
RUSSIAN=$(grep -lE '"[^"]*[А-Яа-яЁё][^"]*"' $UIFILES 2>/dev/null | head -3)
if [ -z "$RUSSIAN" ]; then
    ok N10b "в новых Compose-файлах нет русских литералов"
else
    bad N10b "русские литералы в коде: $(echo "$RUSSIAN" | tr '\n' ' ')"
fi

echo "--- стиль ---"

cat > /tmp/linecheck12.py <<'PY'
import sys
for path in sys.argv[1:]:
    try:
        for n, line in enumerate(open(path, encoding='utf-8'), 1):
            if len(line.rstrip('\n')) > 120:
                print(path + ':' + str(n))
    except OSError:
        pass
PY
LONG=$(python3 /tmp/linecheck12.py $NEWFILES 2>/dev/null | head -5)
TABS=$(grep -lP '\t' $NEWFILES 2>/dev/null | head -3)
if [ -z "$LONG" ] && [ -z "$TABS" ]; then
    ok N11 "строки до 120 символов, отступы без табуляции"
else
    bad N11 "длинные строки или табуляция: $(echo "$LONG" "$TABS" | tr '\n' ' ')"
fi

SECRETS=$(grep -lniE 'cookies|authorization|token|api_key' $NEWFILES "$REPORT" 2>/dev/null | head -3)
if [ -z "$SECRETS" ]; then
    ok N12 "секретов в новых файлах и отчёте нет"
else
    bad N12 "служебные данные: $(echo "$SECRETS" | tr '\n' ' ')"
fi

echo "--- отчёт ---"

if [ -f "$REPORT" ]; then
    ok N13a "отчёт есть: reports/stage12-news.md"
    SIZE=$(wc -c < "$REPORT")
    if [ "$SIZE" -le 4096 ]; then
        ok N13b "отчёт укладывается в 4 КБ ($SIZE байт)"
    else
        bad N13b "отчёт больше 4 КБ ($SIZE байт)"
    fi
    LINKS=$(grep -cE 'https?://' "$REPORT")
    if [ "$LINKS" -eq 0 ]; then
        ok N13c "в отчёте нет адресов записей"
    else
        bad N13c "в отчёте есть адреса"
    fi
else
    bad N13a "нет reports/stage12-news.md"
fi

echo "--- изоляция ---"

FOREIGN=$(git diff --name-only "$BASE" 2>/dev/null | grep -E 'ui/discovery/Swipe|HomeSwipeEntryCard|DiscoverySuggestion|DiscoveryRepository|HomeDiscoveryController|HomeHubDiscoverySection|swipe-v1\.md|verify-swipe\.sh|\.sqm' | head -5)
if [ -z "$FOREIGN" ]; then
    ok N14 "в диффе нет файлов второй фичи и миграций"
else
    bad N14 "чужие файлы в диффе: $(echo "$FOREIGN" | tr '\n' ' ')"
fi

SWIPE_KEYS=$(git diff "$BASE" -- "$ROOT/i18n/src/commonMain/moko-resources" 2>/dev/null | grep '^+' | grep -v '^+++' | grep -c 'swipe_')
if [ "$SWIPE_KEYS" -eq 0 ]; then
    ok N14b "ключи swipe_* не добавлялись"
else
    bad N14b "добавлены чужие ключи свайпа"
fi

echo "--- регрессии ---"

if [ -f "$HERE/verify-stage11.sh" ]; then
    if bash "$HERE/verify-stage11.sh" "$ROOT" "$BASE" > /tmp/stage11-regression.txt 2>&1; then
        ok R1 "этап 11 не сломан"
    else
        # Скидок нет: любой провал этапа 11 — регрессия этапа 12.
        # Раньше провалы этапа 9 вычитались дважды, и одна настоящая
        # поломка проходила как «ok». Считаем честно и печатаем причину.
        bad R1 "регрессия этапа 11: $(grep -E '^FAIL' /tmp/stage11-regression.txt | head -3 | tr '\n' ' ')"
    fi
else
    skip R1 "скрипт этапа 11 не найден рядом"
fi

if [ -f "$HERE/verify-stage10.sh" ]; then
    if bash "$HERE/verify-stage10.sh" "$ROOT" "$BASE" > /tmp/stage10-regression.txt 2>&1; then
        ok R2 "этап 10 не сломан"
    else
        # Та же честная арифметика: провал есть — регрессия есть.
        bad R2 "регрессия этапа 10: $(grep -E 'FAIL' /tmp/stage10-regression.txt | head -3 | tr '\n' ' ')"
    fi
else
    skip R2 "скрипт этапа 10 не найден рядом"
fi

echo
echo "=== Итого: PASS $PASS, FAIL $FAIL ==="
if [ "$FAIL" -ne 0 ]; then
    printf 'провалы: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
