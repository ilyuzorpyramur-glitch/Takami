#!/usr/bin/env bash
# Проверка этапа 8: фильтры источника из спеки.
# Сначала прогоняет гейт этапа 6 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage8.sh [путь-к-ветке] [базовая-ветка]
#
# Код возврата: 0 — можно вливать, 1 — есть FAIL.

set -uo pipefail

ROOT="${1:-$(pwd)}"
BASE="${2:-origin/main}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# Длина строк считается в символах, а не в байтах: без локали кириллица
# удваивается, и на русских комментариях сыпятся ложные FAIL.
if locale -a 2>/dev/null | grep -qiE '^ru_RU\.(utf8|utf-8)$'; then
    export LC_ALL=ru_RU.UTF-8
fi

PASS=0
FAIL=0
declare -a FAILED=()

ok()   { PASS=$((PASS + 1)); printf '[%s] PASS  %s\n' "$1" "$2"; }
bad()  { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf '[%s] FAIL  %s\n' "$1" "$2"; }
skip() { printf '[%s] SKIP  %s\n' "$1" "$2"; }

TAKAMI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami"
FILTERS="$TAKAMI/filters"
UI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/takami"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/filters"
REPORT="$ROOT/reports/stage8-filters.md"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 8: фильтры источника ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия ----------
if [ -f "$HERE/verify-stage6.sh" ]; then
    echo "--- регрессия этапа 6 ---"
    if bash "$HERE/verify-stage6.sh" "$ROOT" "$BASE" > /tmp/stage6-regression.txt 2>&1; then
        N=$(grep -c '\] PASS' /tmp/stage6-regression.txt)
        ok R1 "этап 6 не сломан ($N гейтов зелёные)"
    else
        bad R1 "регрессия этапа 6: $(grep 'FAIL' /tmp/stage6-regression.txt | head -3 | tr '\n' ' ')"
    fi
    echo
else
    skip R1 "скрипт этапа 6 не найден рядом"
fi

echo "--- модель фильтра ---"

if [ -f "$TAKAMI/FilterSpec.kt" ]; then
    ok F1a "FilterSpec.kt есть"

    # Обязательные поля без дефолта: name, type, key. Остальные — с дефолтами.
    MISSING=""
    for field in 'valueTemplate: String = ' 'options: List<FilterOption> = ' \
        'maxSelected: Int = ' 'default: List<String> = ' 'inUrl: String = ' 'selector: String? = null'; do
        grep -q "$field" "$TAKAMI/FilterSpec.kt" || MISSING="$MISSING $field"
    done
    if [ -z "$MISSING" ]; then
        ok F1b "у необязательных полей FilterSpec есть дефолты"
    else
        bad F1b "нет дефолтов:$MISSING"
    fi

    # В SiteSpec ровно одно новое поле: добавленные строки — только комментарий и filters.
    # После слияния этапа в main база совпадает с веткой и дельта пустая:
    # тогда проверяем, что поле filters в файле есть, и на этом успокаиваемся.
    ADDED=$(git diff "$BASE" -- "$TAKAMI/SiteSpec.kt" 2>/dev/null | grep '^+' | grep -v '^+++' | sed 's/^+//')
    if [ -z "$ADDED" ]; then
        if grep -qE '^\s*val filters: List<FilterSpec> = emptyList\(\),' "$TAKAMI/SiteSpec.kt"; then
            ok F1c "в SiteSpec ровно одно новое поле filters (дельта пустая: этап уже в базе)"
        else
            bad F1c "в SiteSpec.kt нет поля filters с дефолтом"
        fi
    else
        # Этап 9 добавляет в VideoSpec поля обхода с умолчаниями: это аддитивно,
        # чужих полей этап 8 не трогает.
        STRAY=$(printf '%s\n' "$ADDED" \
            | grep -vE '^\s*(/\*\*.*|\*.*|val filters: List<FilterSpec> = emptyList\(\),|//.*)?$' \
            | grep -vE '^\s*(val (maxPlayers|maxRequests|embedAttrs|idRegex)|"(src|data-src|data-iframe|data-player|data-href|data)",|\)?,?)\s*' \
            | grep -v '^\s*$' | head -3)
        if [ -z "$STRAY" ]; then
            ok F1c "в SiteSpec добавлено только поле filters"
        else
            bad F1c "в SiteSpec.kt чужие изменения: $(printf '%s' "$STRAY" | tr '\n' ' ')"
        fi
    fi
else
    bad F1a "нет takami/FilterSpec.kt"
fi

echo "--- версия спеки ---"

# Этап 8 не меняет схему спеки, но номер берём из базы: предыдущие этапы
# его уже поднимали, и жёсткая двойка ломала гейт после слияния.
VER_REL="${TAKAMI#"$ROOT"/}/spec/SpecVersion.kt"
BASE_VER=$(git show "$BASE:$VER_REL" 2>/dev/null | grep -oE 'const val SPEC_VERSION = [0-9]+' |
    head -1 | grep -oE '[0-9]+$')
CUR_VER=$(grep -oE 'const val SPEC_VERSION = [0-9]+' "$TAKAMI/spec/SpecVersion.kt" |
    head -1 | grep -oE '[0-9]+$')
if [ -z "$BASE_VER" ]; then
    skip F2a "в базе $BASE нет файла версии спеки — сравнивать не с чем"
elif [ "$BASE_VER" = "$CUR_VER" ]; then
    ok F2a "SPEC_VERSION не поднят ($CUR_VER)"
else
    bad F2a "SPEC_VERSION изменился: $BASE_VER → $CUR_VER"
fi

# С этапа 10 журнал откатов участвует в лечении: SpecJournalEntry дополняется
# полями с дефолтами. Версию схемы несут SpecVersion и SpecMigration — их и проверяем.
TOUCHED=$(git diff --name-only "$BASE" -- "$TAKAMI/spec/SpecVersion.kt" "$TAKAMI/spec/SpecMigration.kt" 2>/dev/null)
if [ -z "$TOUCHED" ]; then
    ok F2b "SpecVersion/SpecMigration не тронуты"
else
    bad F2b "тронуты файлы версии спеки: $(echo "$TOUCHED" | tr '\n' ' ')"
fi

echo "--- адрес и состояние ---"

if [ -f "$FILTERS/FilterUrl.kt" ]; then
    ok F3a "FilterUrl.kt есть"

    # Тест на каждый тип адреса: query, path, sort, text.
    MISSING=""
    for kind in query path sort text; do
        grep -q "$kind" "$TESTS/FilterUrlTest.kt" || MISSING="$MISSING $kind"
    done
    if [ -z "$MISSING" ]; then
        ok F3b "тесты адреса покрывают все типы$([ -z "$MISSING" ] && echo '')"
    else
        bad F3b "нет тестов адреса:$MISSING"
    fi

    grep -q 'пустое состояние не меняет адрес' "$TESTS/FilterUrlTest.kt" \
        && ok F4 "есть тест пустого состояния" \
        || bad F4 "нет теста пустого состояния"

    grep -q 'умолчание из спеки' "$TESTS/FilterUrlTest.kt" \
        && ok F6 "есть тест умолчаний из спеки" \
        || bad F6 "нет теста умолчаний"
else
    bad F3a "нет takami/filters/FilterUrl.kt"
fi

if [ -f "$FILTERS/FilterState.kt" ]; then
    grep -q 'неизвестный ключ' "$TESTS/FilterStateTest.kt" && grep -q 'неизвестное значение' "$TESTS/FilterStateTest.kt" \
        && ok F5 "есть тесты мусора в состоянии" \
        || bad F5 "нет тестов мусора в состоянии"
else
    bad F5 "нет takami/filters/FilterState.kt"
fi

if [ -f "$FILTERS/FilterStore.kt" ]; then
    grep -q 'битый файл' "$TESTS/FilterStoreTest.kt" \
        && ok F7 "есть тест битого файла" \
        || bad F7 "нет теста битого файла"
else
    bad F7 "нет takami/filters/FilterStore.kt"
fi

echo "--- источник ---"

if grep -q 'FilterList.build' "$TAKAMI/TakamiForge.kt"; then
    ok F8a "getFilterList строит список из спеки"
else
    bad F8a "getFilterList не использует FilterList"
fi

if grep -q 'FilterUrl.apply' "$TAKAMI/TakamiForge.kt"; then
    ok F8b "getSearchAnime собирает адрес через FilterUrl"
else
    bad F8b "getSearchAnime не использует FilterUrl"
fi

if grep -qE 'FilterStore.save.*после|// Выбор запоминается после успешного запроса' "$TAKAMI/TakamiForge.kt"; then
    ok F8c "выбор сохраняется после запроса"
else
    bad F8c "нет пометки про сохранение после запроса"
fi

if [ -f "$TESTS/FilterScanTest.kt" ] && grep -q 'select' "$TESTS/FilterScanTest.kt" && grep -q 'флажки' "$TESTS/FilterScanTest.kt"; then
    ok F9 "сканер проверяется на фикстуре (select и флажки)"
else
    bad F9 "нет теста сканера на фикстуре"
fi

echo "--- экран ---"

[ -f "$UI/FilterScreen.kt" ] && ok U1a "FilterScreen.kt есть" || bad U1a "нет ui/takami/FilterScreen.kt"
[ -f "$UI/FilterScreenModel.kt" ] && ok U1b "FilterScreenModel.kt есть" || bad U1b "нет FilterScreenModel.kt"

grep -q 'У источника нет фильтров' "$UI/FilterScreen.kt" \
    && ok U2 "пустое состояние подписано по-русски" \
    || bad U2 "нет подписи пустого состояния"

grep -q 'Применить (' "$UI/FilterScreen.kt" \
    && ok U3 "есть счётчик выбранного на кнопке" \
    || bad U3 "нет счётчика на кнопке"

if grep -q 'FilterScreen' "$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/browse/anime/extension/AnimeExtensionsTab.kt"; then
    ok U4 "экран подключён к меню расширений"
else
    bad U4 "экран фильтров не подключён к меню"
fi

echo "--- стиль ---"

NEWFILES="$TAKAMI/FilterSpec.kt $FILTERS/FilterState.kt $FILTERS/FilterUrl.kt $FILTERS/FilterStore.kt $FILTERS/FilterList.kt $UI/FilterScreen.kt $UI/FilterScreenModel.kt"
cat > /tmp/linecheck.py <<'PY'
import sys
for path in sys.argv[1:]:
    try:
        for n, line in enumerate(open(path, encoding='utf-8'), 1):
            if len(line.rstrip('\n')) > 120:
                print(path + ':' + str(n))
    except OSError:
        pass
PY
LONG=$(python3 /tmp/linecheck.py $NEWFILES 2>/dev/null | head -5)
if [ -z "$LONG" ]; then
    ok K1 "нет строк длиннее 120 символов"
else
    bad K1 "длинные строки: $(echo "$LONG" | tr '\n' ' ')"
fi

LATIN=$(grep -lE '^\s*//\s*[A-Za-z]' $NEWFILES 2>/dev/null | head -3)
if [ -z "$LATIN" ]; then
    ok K2 "комментарии по-русски"
else
    bad K2 "комментарии не по-русски: $(echo "$LATIN" | tr '\n' ' ')"
fi

if grep -l 'TODO' $NEWFILES 2>/dev/null | head -3 | grep -q .; then
    bad K3 "в коде остались TODO"
else
    ok K3 "TODO нет"
fi

echo "--- отчёт ---"

if [ -f "$REPORT" ]; then
    ok P1 "отчёт есть: reports/stage8-filters.md"

    SIZE=$(wc -c < "$REPORT")
    if [ "$SIZE" -le 4096 ]; then
        ok P1b "отчёт укладывается в 4 КБ ($SIZE байт)"
    else
        bad P1b "отчёт больше 4 КБ ($SIZE байт)"
    fi

    if grep -qiE 'cookies|authorization|token|password' "$REPORT"; then
        bad P2 "в отчёте есть служебные данные"
    else
        ok P2 "в отчёте нет cookies и токенов"
    fi
else
    bad P1 "нет reports/stage8-filters.md"
fi

echo
echo "=== Итого: PASS $PASS, FAIL $FAIL ==="
if [ "$FAIL" -ne 0 ]; then
    printf 'провалы: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
