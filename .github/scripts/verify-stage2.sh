#!/usr/bin/env bash
# Проверка пригодности кода этапа 2 (кэш и условные запросы).
# Сначала прогоняет гейты этапа 1 как регрессию: они обязаны остаться зелёными.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage2.sh [путь-к-ветке] [базовая-ветка]
# По умолчанию: текущий каталог, origin/main.
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

FETCH="$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami/fetch"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/fetch"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 2: кэш и условные запросы ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия этапа 1 ----------
if [ -f "$HERE/verify-stage1.sh" ]; then
    echo "--- регрессия этапа 1 ---"
    if bash "$HERE/verify-stage1.sh" "$ROOT" "$BASE" > /tmp/stage1-regression.txt 2>&1; then
        N=$(grep -c '\] PASS' /tmp/stage1-regression.txt)
        ok R1 "этап 1 не сломан ($N гейтов зелёные)"
    else
        bad R1 "регрессия этапа 1: $(grep 'FAIL' /tmp/stage1-regression.txt | head -3 | tr '\n' ' ')"
    fi
    echo
else
    skip R1 "скрипт этапа 1 не найден рядом"
fi

echo "--- кэш ---"

# ---------- G4.1 файлы ----------
CACHE_FILES=()
for f in HttpCache.kt CacheEntry.kt Conditional.kt; do
    [ -f "$FETCH/$f" ] && CACHE_FILES+=("$f")
done
if [ ${#CACHE_FILES[@]} -ge 2 ]; then
    ok G4.1 "файлы кэша на месте: ${CACHE_FILES[*]}"
else
    bad G4.1 "нет файлов кэша (нужны HttpCache, CacheEntry, Conditional)"
fi

# ---------- G4.2 хранение во внутренней памяти ----------
if grep -rq "cacheDir" "$FETCH" 2>/dev/null; then
    ok G4.2 "кэш лежит во внутреннем cacheDir"
elif grep -rqE "externalCacheDir|getExternalFilesDir" "$FETCH" 2>/dev/null; then
    bad G4.2 "кэш на внешнем хранилище — может исчезнуть и требует прав"
else
    bad G4.2 "кэш не привязан к cacheDir"
fi

# ---------- G4.3 потолок размера ----------
if [ -f "$FETCH/FetchLimits.kt" ]; then
    NORM=$(sed -E 's/([0-9])_([0-9])/\1\2/g' "$FETCH/FetchLimits.kt")
    if echo "$NORM" | grep -qE "CACHE_MAX_BYTES[[:space:]]*=[[:space:]]*32\*1024\*1024|CACHE_MAX_BYTES[[:space:]]*=[[:space:]]*33554432"; then
        ok G4.3 "потолок кэша 32 МБ"
    else
        bad G4.3 "CACHE_MAX_BYTES не равен 32 МБ"
    fi
    if echo "$NORM" | grep -qE "CACHE_TTL_MS[[:space:]]*=[[:space:]]*24\*60\*60\*1000|CACHE_TTL_MS[[:space:]]*=[[:space:]]*86400000"; then
        ok G4.4 "TTL кэша 24 часа"
    else
        bad G4.4 "CACHE_TTL_MS не равен 24 часам"
    fi
    if echo "$NORM" | grep -qE "CACHE_TTL_JITTER_MS"; then
        ok G4.5 "разброс TTL задан (антисинхронизация)"
    else
        bad G4.5 "нет CACHE_TTL_JITTER_MS — все устройства обновятся одновременно"
    fi
else
    bad G4.3 "FetchLimits.kt отсутствует"
fi

# ---------- G4.6 вытеснение ----------
if grep -rqE "evict|trim|leastRecently|LRU|maxBytes" "$FETCH/HttpCache.kt" 2>/dev/null; then
    ok G4.6 "вытеснение старых записей реализовано"
else
    bad G4.6 "нет вытеснения — кэш вырастет выше потолка"
fi

# ---------- G4.7 атомарная запись ----------
if grep -rqE "renameTo|Files\.move|atomic" "$FETCH/HttpCache.kt" "$FETCH/CacheEntry.kt" 2>/dev/null; then
    ok G4.7 "запись в кэш атомарная"
else
    bad G4.7 "запись не атомарная — при сбое останется обрезанный файл"
fi

# ---------- G4.8 условные заголовки ----------
if grep -rq "If-None-Match" "$FETCH" 2>/dev/null && grep -rq "If-Modified-Since" "$FETCH" 2>/dev/null; then
    ok G4.8 "If-None-Match и If-Modified-Since отправляются"
else
    bad G4.8 "нет условных заголовков — сайт каждый раз рендерит страницу"
fi

# ---------- G4.9 обработка 304 ----------
if grep -rq "304" "$FETCH" 2>/dev/null; then
    ok G4.9 "ответ 304 распознаётся"
else
    bad G4.9 "нет обработки 304"
fi

# ---------- G4.10 304 не парсится заново ----------
# Jsoup не должен появляться в путях кэша: он живёт в SpecEngine/Forge.
if grep -rq "jsoup" "$FETCH" 2>/dev/null; then
    bad G4.10 "в пакете fetch появился jsoup — разбор просочился в слой сети"
else
    ok G4.10 "разбор HTML не заходит в сетевой слой"
fi

# ---------- G4.11 запасной признак изменения ----------
if grep -rqE "hash|sha|digest|contentLength" "$FETCH/HttpCache.kt" "$FETCH/Conditional.kt" 2>/dev/null; then
    ok G4.11 "есть запасной способ понять, что страница изменилась"
else
    bad G4.11 "нет сравнения содержимого на случай сайта без ETag"
fi

# ---------- G4.12 кэш не обходит лимиты ----------
if grep -q "guard" "$FETCH/HttpCache.kt" 2>/dev/null || ! grep -q "newCall" "$FETCH/HttpCache.kt" 2>/dev/null; then
    ok G4.12 "кэш не ходит в сеть напрямую"
else
    bad G4.12 "кэш делает собственные сетевые вызовы"
fi

# ---------- G4.13 размер одного ответа ограничен ----------
if [ -f "$FETCH/FetchLimits.kt" ] && grep -q "MAX_HTML_BYTES" "$FETCH/FetchLimits.kt"; then
    ok G4.13 "размер одной страницы ограничен"
else
    bad G4.13 "нет потолка на размер страницы"
fi

# ---------- G4.14 кэш учитывает referer ----------
if grep -rqE "referer" "$FETCH/HttpCache.kt" "$FETCH/CacheEntry.kt" 2>/dev/null; then
    ok G4.14 "ключ кэша учитывает referer"
else
    skip G4.14 "referer в ключе не используется — допустимо, если страница от него не зависит"
fi

echo
echo "--- тесты ---"

# ---------- T.1 тесты кэша ----------
if ls "$TESTS"/*Cache*Test*.kt >/dev/null 2>&1; then
    ok T1 "есть тест кэша"
else
    bad T1 "нет теста кэша"
fi

# ---------- T.2 повторный запрос не идёт в сеть ----------
if grep -rqiE "requestCount.*shouldBe.*1|second.*no.*network|повтор" "$TESTS"/*Cache*Test*.kt 2>/dev/null; then
    ok T2 "тест проверяет, что повторный запрос не идёт в сеть"
else
    bad T2 "нет проверки «второй запрос без сети»"
fi

# ---------- T.3 304 ----------
if grep -rq "304" "$TESTS" 2>/dev/null; then
    ok T3 "есть тест на 304"
else
    bad T3 "нет теста на 304"
fi

# ---------- T.4 TTL ----------
if grep -rqiE "ttl|jitter" "$TESTS"/*Cache*Test*.kt 2>/dev/null; then
    ok T4 "тест проверяет TTL и разброс"
else
    bad T4 "нет проверки TTL"
fi

# ---------- T.5 детерминированность ----------
if grep -rqE "Thread\.sleep|delay\(" "$TESTS" 2>/dev/null | grep -v FakeClock; then
    bad T5 "в тестах остались реальные ожидания"
else
    ok T5 "тесты не ждут реальное время"
fi

# ---------- T.6 нет отключённых тестов ----------
if grep -rqE "@Disabled|@Ignore" "$TESTS" 2>/dev/null; then
    bad T6 "есть отключённые тесты"
else
    ok T6 "отключённых тестов нет"
fi

echo
echo "--- гигиена ---"

# ---------- H.1 нет новых зависимостей ----------
if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    if git diff --quiet "$BASE"...HEAD -- gradle/libs.versions.toml app/build.gradle.kts; then
        ok H1 "новых зависимостей нет"
    else
        bad H1 "изменены зависимости: $(git diff --unified=0 "$BASE"...HEAD -- gradle/libs.versions.toml app/build.gradle.kts | grep -E '^\+' | grep -v '^+++' | head -2 | tr '\n' ' ')"
    fi
else
    skip H1 "базовая ветка недоступна"
fi

# ---------- H.2 секреты ----------
if grep -rqE "AIza[0-9A-Za-z_-]{30,}|[0-9]{9,10}:[A-Za-z0-9_-]{35}|BEGIN (OPENSSH|RSA|PRIVATE) KEY" "$ROOT/app/src" 2>/dev/null; then
    bad H2 "в исходниках найден секрет"
else
    ok H2 "секретов нет"
fi

# ---------- H.3 длина строк ----------
if grep -rqE '^.{121,}$' --include=*.kt "$FETCH" "$TESTS" 2>/dev/null; then
    bad H3 "есть строки длиннее 120 символов"
else
    ok H3 "строки в пределах 120"
fi

# ---------- H.4 отчёт ----------
if [ -f "$ROOT/reports/stage1-week2.md" ]; then
    ok H4 "отчёт недели 2 есть"
else
    bad H4 "нет reports/stage1-week2.md"
fi

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf 'завалено: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
