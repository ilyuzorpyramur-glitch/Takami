#!/usr/bin/env bash
# Проверка этапа 9: прямая ссылка на видео из цепочки плееров.
# Сначала прогоняет гейт этапа 8 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage9.sh [путь-к-ветке] [базовая-ветка]
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
VIDEO="$TAKAMI/video"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/video"
REPORT="$ROOT/reports/stage9-players.md"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 9: прямая ссылка на видео ==="
echo "каталог: $ROOT"
echo

if [ -f "$HERE/verify-stage8.sh" ]; then
    echo "--- регрессия этапа 8 ---"
    if bash "$HERE/verify-stage8.sh" "$ROOT" "$BASE" > /tmp/stage8-regression.txt 2>&1; then
        N=$(grep -c '\] PASS' /tmp/stage8-regression.txt)
        ok R1 "этап 8 не сломан ($N гейтов зелёные)"
    else
        bad R1 "регрессия этапа 8: $(grep 'FAIL' /tmp/stage8-regression.txt | head -3 | tr '\n' ' ')"
    fi
    echo
else
    skip R1 "скрипт этапа 8 не найден рядом"
fi

echo "--- декодер плеера ---"

if [ -f "$VIDEO/Playerjs.kt" ]; then
    grep -q 'PlayerMap.parse' "$VIDEO/Playerjs.kt" \
        && ok V1a "строковая и объектная карта отдаются общему разбору" \
        || bad V1a "декодер не использует разбор карты"
    grep -q '\[360p\]' "$TESTS/PlayerjsTest.kt" 2>/dev/null \
        && ok V1b "строковая карта проверена тестом" || bad V1b "нет теста строковой карты"
    grep -q 'PLAYLIST' "$VIDEO/Playerjs.kt" && grep -q 'title' "$VIDEO/Playerjs.kt" \
        && ok V2a "плейлист разбирается по title и file" || bad V2a "плейлиста нет"
    grep -q 'плейлист' "$TESTS/PlayerjsTest.kt" 2>/dev/null \
        && ok V2b "плейлист проверен тестом" || bad V2b "нет теста плейлиста"
    grep -q 'decodeBase64' "$VIDEO/Playerjs.kt" \
        && ok V3a "base64 раскодируется" || bad V3a "base64 не поддержан"
    grep -q 'битый base64' "$TESTS/PlayerjsTest.kt" 2>/dev/null \
        && ok V3b "битый base64 проверен тестом" || bad V3b "нет теста битого base64"
    grep -q 'мусор' "$TESTS/PlayerjsTest.kt" 2>/dev/null \
        && ok V10 "мусор и пустой вход дают пустой список" || bad V10 "нет теста мусора"
else
    bad V1 "нет video/Playerjs.kt"
    bad V2 "нет video/Playerjs.kt"
    bad V3 "нет video/Playerjs.kt"
    bad V10 "нет video/Playerjs.kt"
fi

echo
echo "--- плеер по спеке ---"

if [ -f "$VIDEO/Hoster.kt" ]; then
    grep -q 'fun match(' "$VIDEO/Hoster.kt" && grep -q 'fun idOf(' "$VIDEO/Hoster.kt" \
        && ok V4a "плеер узнаётся по шаблону, идентификатор достаётся" || bad V4a "нет поиска плеера"
    grep -q 'fun apiUrl(' "$VIDEO/Hoster.kt" && grep -q '{id}' "$VIDEO/Hoster.kt" \
        && ok V4b "адрес карты подставляет идентификатор" || bad V4b "нет подстановки идентификатора"
    grep -q '{id}' "$TESTS/HosterTest.kt" 2>/dev/null \
        && ok V4c "подстановка проверена тестом" || bad V4c "нет теста подстановки"
else
    bad V4 "нет video/Hoster.kt"
fi

echo
echo "--- цепочка ---"

if [ -f "$VIDEO/Hoster.kt" ] && grep -q 'object Chain' "$VIDEO/Hoster.kt"; then
    grep -q 'второй плеер' "$TESTS/ChainTest.kt" 2>/dev/null \
        && ok V5 "рабочий второй плеер проверен тестом" || bad V5 "нет теста второго плеера"
    grep -q 'iframeDepth' "$VIDEO/Hoster.kt" && grep -q 'iframeDepth' "$TESTS/ChainTest.kt" 2>/dev/null \
        && ok V6 "глубина вложенности ограничена и проверена" || bad V6 "нет предела вложенности"
    grep -q 'maxPlayers' "$VIDEO/Hoster.kt" && grep -q 'предел числа плееров' "$TESTS/ChainTest.kt" 2>/dev/null \
        && ok V7a "предел числа плееров соблюдается" || bad V7a "нет предела числа плееров"
    grep -q 'distinctBy { it.url }' "$VIDEO/Hoster.kt" && grep -q 'повтор адреса' "$TESTS/ChainTest.kt" 2>/dev/null \
        && ok V7b "повтор адреса склеивается" || bad V7b "нет дедупа по адресу"
    grep -q 'maxRequests' "$VIDEO/Hoster.kt" && grep -q 'class Budget' "$VIDEO/Hoster.kt" \
        && ok V7c "потолок запросов на серию задан" || bad V7c "нет потолка запросов"
else
    bad V5 "нет обхода цепочки"
    bad V6 "нет обхода цепочки"
    bad V7 "нет обхода цепочки"
fi

echo
echo "--- адреса ---"

if [ -f "$VIDEO/MediaUrl.kt" ]; then
    MISS=""
    for part in 'u0026' '&amp;' '%2F' 'javascript:' '//'; do
        grep -qF "$part" "$VIDEO/MediaUrl.kt" || MISS="$MISS $part"
    done
    [ -z "$MISS" ] && ok V8a "нормализация знает экранирование, мнемоники и кодирование" \
        || bad V8a "не обрабатывается:$MISS"
    grep -q 'u0026' "$TESTS/MediaUrlTest.kt" 2>/dev/null && grep -q '%2F' "$TESTS/MediaUrlTest.kt" 2>/dev/null \
        && ok V8b "случаи адресов проверены тестом" || bad V8b "нет теста адресов"
    grep -q 'normalizeMedia' "$TAKAMI/SpecEngine.kt" \
        && bad V8c "в движке осталась вторая копия нормализации" || ok V8c "нормализация одна"
else
    bad V8 "нет video/MediaUrl.kt"
fi

if grep -rqE 'Jsoup\.connect|OkHttpClient' "$TESTS" 2>/dev/null; then
    bad V9a "в тестах есть сетевые вызовы"
else
    ok V9a "в тестах нет сетевых вызовов"
fi

LIVE=$(grep -rhoE 'https?://[a-z0-9.-]+\.[a-z]{2,}' "$TESTS"/*.kt 2>/dev/null \
    | grep -vE '\.example$' | head -3)
if [ -z "$LIVE" ]; then
    ok V9b "в тестах только выдуманные домены"
else
    bad V9b "в тестах живые домены: $(echo "$LIVE" | tr '\n' ' ')"
fi

echo
echo "--- спека и честность ---"

if grep -qE 'const val SPEC_VERSION = 3' "$TAKAMI/spec/SpecVersion.kt" 2>/dev/null; then
    ok V11a "версия схемы осталась третьей"
else
    bad V11a "версия схемы изменилась"
fi

if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    # Версию схемы несут SpecVersion и SpecMigration; журнал откатов
    # (SpecRollback) правят другие этапы — он дополняется полями с умолчаниями.
    TOUCHED=$(git diff --name-only "$BASE"...HEAD -- \
        app/src/main/java/eu/kanade/tachiyomi/takami/spec/SpecVersion.kt \
        app/src/main/java/eu/kanade/tachiyomi/takami/spec/SpecMigration.kt 2>/dev/null)
    if [ -z "$TOUCHED" ]; then
        ok V11b "SpecVersion/SpecMigration не тронуты"
    else
        bad V11b "тронуты файлы версии спеки: $(echo "$TOUCHED" | tr '\n' ' ')"
    fi
else
    skip V11b "базовая ветка недоступна"
fi

grep -q 'maxPlayers' "$TESTS/SpecDefaultsTest.kt" 2>/dev/null \
    && ok V11c "спека без новых полей проверена тестом" || bad V11c "нет теста умолчаний"

grep -q '"video"' "$TAKAMI/SelfCheck.kt" 2>/dev/null && \
    grep -q 'player_blocked' "$TAKAMI/SelfCheck.kt" 2>/dev/null \
    && ok V12a "провал видео уходит коротким типом" || bad V12a "нет короткого типа провала"

if grep -qE 'put\("text"|put\("title"|put\("cookies"' "$TAKAMI/SpecEngine.kt" "$VIDEO"/*.kt 2>/dev/null; then
    bad V12b "в код попадают текст страницы или названия"
else
    ok V12b "текста страниц в коде нет"
fi

echo
echo "--- стиль ---"

LONG=$(for f in "$VIDEO"/*.kt "$TAKAMI/SpecEngine.kt" \
    "$TAKAMI/SelfCheck.kt" "$TAKAMI/SiteSpec.kt"; do
    [ -f "$f" ] && grep -nE '^.{121,}$' "$f" | sed "s|^|$f:|"
done 2>/dev/null | head -5)
if [ -z "$LONG" ]; then
    ok K1 "нет строк длиннее 120 символов"
else
    bad K1 "длинные строки: $(echo "$LONG" | tr '\n' ' ')"
fi

if grep -rnE '^\s*//\s*[A-Za-z]' "$VIDEO"/*.kt 2>/dev/null | head -2 | grep -q .; then
    bad K2 "комментарии не по-русски"
else
    ok K2 "комментарии по-русски"
fi

if grep -rn 'TODO\|FIXME' "$VIDEO"/*.kt "$TAKAMI/SpecEngine.kt" "$TAKAMI/SelfCheck.kt" 2>/dev/null \
    | head -2 | grep -q .; then
    bad K3 "в коде остались TODO"
else
    ok K3 "TODO нет"
fi

echo
echo "--- отчёт ---"

if [ -f "$REPORT" ]; then
    ok P1a "отчёт есть: reports/stage9-players.md"
    SIZE=$(wc -c < "$REPORT")
    if [ "$SIZE" -le 4096 ]; then
        ok P1b "отчёт укладывается в 4 КБ ($SIZE байт)"
    else
        bad P1b "отчёт больше 4 КБ ($SIZE байт)"
    fi
    if grep -qiE 'cookies|authorization|token' "$REPORT"; then
        bad P2 "в отчёте есть служебные данные"
    else
        ok P2 "в отчёте нет служебных данных"
    fi
else
    bad P1 "нет reports/stage9-players.md"
fi

echo
echo "=== Итого: PASS $PASS, FAIL $FAIL ==="
if [ "$FAIL" -ne 0 ]; then
    printf 'провалы: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
