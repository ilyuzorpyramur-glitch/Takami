#!/usr/bin/env bash
# Проверка пригодности кода этапа 4 (версии спеки, история, откат).
# Сначала прогоняет гейты этапа 3 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage4.sh [путь-к-ветке] [базовая-ветка]
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
SPEC="$TAKAMI/spec"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 4: версии спеки, история, откат ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия этапа 3 ----------
if [ -f "$HERE/verify-stage3.sh" ]; then
    echo "--- регрессия этапа 3 ---"
    if bash "$HERE/verify-stage3.sh" "$ROOT" "$BASE" > /tmp/stage3-regression.txt 2>&1; then
        N=$(grep -c '\] PASS' /tmp/stage3-regression.txt)
        ok R1 "этап 3 не сломан ($N гейтов зелёные)"
    else
        bad R1 "регрессия этапа 3: $(grep 'FAIL' /tmp/stage3-regression.txt | head -3 | tr '\n' ' ')"
    fi
    echo
else
    skip R1 "скрипт этапа 3 не найден рядом"
fi

echo "--- файлы ---"

[ -d "$SPEC" ] && ok V1 "пакет spec есть" || bad V1 "нет пакета takami/spec"
[ -f "$SPEC/SpecVersion.kt" ] && ok V2 "SpecVersion.kt есть" || bad V2 "нет SpecVersion.kt"
[ -f "$SPEC/SpecHistory.kt" ] && ok V3 "SpecHistory.kt есть" || bad V3 "нет SpecHistory.kt"
[ -f "$SPEC/SpecMigration.kt" ] && ok V4 "SpecMigration.kt есть" || bad V4 "нет SpecMigration.kt"

echo
echo "--- версия спеки ---"

if [ -f "$TAKAMI/SiteSpec.kt" ]; then
    if grep -qE "val version" "$TAKAMI/SiteSpec.kt"; then
        ok V5 "у спеки есть поле версии"
    else
        bad V5 "в SiteSpec нет версии"
    fi
    if grep -qE "SPEC_VERSION|CURRENT_VERSION|version[[:space:]]*:[[:space:]]*Int[[:space:]]*=[[:space:]]*2" "$TAKAMI/SiteSpec.kt" "$SPEC/SpecVersion.kt" 2>/dev/null; then
        ok V6 "текущая версия схемы задана константой"
    else
        bad V6 "версия не закреплена константой"
    fi
fi

if [ -f "$TAKAMI/TakamiForge.kt" ]; then
    grep -qE "version" "$TAKAMI/TakamiForge.kt" && ok V7 "хранилище пишет версию" \
        || bad V7 "версия не сохраняется вместе со спеками"
    if grep -qE "renameTo|Files\.move|atomic|writeAtomically" "$TAKAMI/TakamiForge.kt"; then
        ok V8 "запись файла спек атомарная"
    else
        bad V8 "запись sources.json не атомарная — при сбое потеряются все источники"
    fi
fi

echo
echo "--- история и откат ---"

if [ -f "$SPEC/SpecHistory.kt" ]; then
    grep -qE "MAX_[A-Z_]*3|take\(3\)|HISTORY_LIMIT" "$SPEC/SpecHistory.kt" && ok V9 "история ограничена тремя версиями" \
        || bad V9 "история не ограничена — файл будет расти"
    grep -qE "rollback|restore" "$SPEC/SpecHistory.kt" && ok V10 "есть откат" \
        || bad V10 "нет метода отката"
    if grep -qE "filesDir|File\\(" "$SPEC/SpecHistory.kt"; then
        ok V11 "история переживает перезапуск"
    else
        bad V11 "история только в памяти"
    fi
fi

ROLLBACK_USE=$(grep -rlE "rollback|restore" --include=*.kt "$TAKAMI" 2>/dev/null | grep -v "SpecHistory.kt" || true)
if [ -n "$ROLLBACK_USE" ]; then
    ok V12 "откат используется в рабочем коде"
else
    bad V12 "откат нигде не вызывается — мёртвый метод"
fi

echo
echo "--- авто-откат при деградации ---"

AUTO=$(grep -rlE "degrad|деград|checksDropped|падени" --include=*.kt "$TAKAMI" 2>/dev/null || true)
if [ -n "$AUTO" ]; then
    ok V13 "есть признак деградации"
else
    bad V13 "нет обнаружения деградации после правки спеки"
fi

if [ -n "$AUTO" ] && grep -rqiE "reason|причин|log" $AUTO 2>/dev/null; then
    ok V14 "причина отката записывается"
else
    bad V14 "откат без записи причины"
fi

echo
echo "--- миграция ---"

if [ -f "$SPEC/SpecMigration.kt" ]; then
    if grep -qE "1|first|legacy|unversioned" "$SPEC/SpecMigration.kt"; then
        ok V15 "миграция учитывает старые файлы без версии"
    else
        bad V15 "нет пути для файлов без версии"
    fi
    if grep -qiE "UnknownKey|ignoreUnknownKeys|coerce" "$SPEC/SpecMigration.kt" "$TAKAMI/TakamiForge.kt" 2>/dev/null; then
        ok V16 "неизвестные поля не ломают чтение"
    else
        bad V16 "старый или новый файл с лишними полями упадёт при чтении"
    fi
fi

echo
echo "--- сохранность данных ---"

# Поля, которые нельзя потерять при откате.
LOST=$(grep -rqE "iconPath" "$SPEC/SpecHistory.kt" 2>/dev/null && echo "ok" || echo "")
if [ -n "$LOST" ]; then
    ok V17 "откат сохраняет путь к иконке"
else
    skip V17 "история хранит спеку целиком — проверить тестом"
fi

if grep -rqiE "dubbings|озвуч" "$TESTS"/*History*Test*.kt "$TESTS"/*Rollback*Test*.kt 2>/dev/null; then
    ok V18 "тест проверяет сохранность озвучек при откате"
else
    bad V18 "нет теста на сохранность полей при откате"
fi

echo
echo "--- тесты ---"

ls "$TESTS"/*Migration*Test*.kt >/dev/null 2>&1 && ok V19 "есть тест миграции" || bad V19 "нет теста миграции"
if grep -rqiE "version[[:space:]]*=[[:space:]]*1|без версии|legacy" "$TESTS"/*Migration*Test*.kt 2>/dev/null; then
    ok V20 "тест миграции читает старый файл"
else
    bad V20 "тест миграции не проверяет старый формат"
fi

if ls "$TESTS"/*History*Test*.kt >/dev/null 2>&1; then
    ok V21 "есть тест истории"
    if grep -rqiE "3|три" "$TESTS"/*History*Test*.kt 2>/dev/null; then
        ok V22 "тест проверяет предел истории"
    else
        bad V22 "нет проверки предела"
    fi
else
    bad V21 "нет теста истории"
fi

if grep -rqiE "rollback|откат" "$TESTS"/*.kt 2>/dev/null; then
    ok V23 "есть тест отката"
else
    bad V23 "нет теста отката"
fi

if grep -rqiE "деград|checks.*(меньше|below)|degrad" "$TESTS"/*.kt 2>/dev/null; then
    ok V24 "есть тест авто-отката"
else
    bad V24 "нет теста авто-отката"
fi

if grep -rqE "Thread\.sleep|delay\(" "$TESTS" 2>/dev/null | grep -v FakeClock >/dev/null; then
    bad V25 "в тестах есть реальные ожидания"
else
    ok V25 "тесты без реальных ожиданий"
fi

echo
echo "--- DNS и защиты ---"

F="$TAKAMI/fetch/Fetcher.kt"
if [ -f "$F" ]; then
    if grep -qE "dohProvider|DnsOverHttps|dohCloudflare|NetworkHelper" "$F"; then
        ok V29 "сетевой слой виртуальных источников уважает настройку DNS over HTTPS"
    else
        bad V29 "Fetcher строит свой клиент без DoH: при блокировке DNS виртуальные источники падают, а обычные расширения работают"
    fi
    if grep -qE "CloudflareInterceptor" "$F"; then
        bad V30 "в сетевой слой виртуальных источников подключён автопроход Cloudflare"
    else
        ok V30 "автопрохода защит в нашем слое нет"
    fi
    if grep -qE "CookieJar|cookieJar" "$F"; then
        ok V31 "cookie ведёт сам слой (совместимо с хранением сессий)"
    else
        skip V31 "cookie-jar не найден — проверить при этапе cookie"
    fi
else
    skip V29 "Fetcher.kt не найден"
fi

echo
echo "--- гигиена ---"

if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    if git diff --quiet "$BASE"...HEAD -- gradle/libs.versions.toml app/build.gradle.kts; then
        ok V26 "новых зависимостей нет"
    else
        bad V26 "изменены зависимости"
    fi
else
    skip V26 "базовая ветка недоступна"
fi

LONG=$(git diff --name-only "$BASE"...HEAD -- "*.kt" 2>/dev/null | while read -r f; do
    [ -f "$f" ] && grep -qE "^.{121,}$" "$f" 2>/dev/null && echo "$f"
done)
if [ -z "$LONG" ]; then
    ok V27 "длинных строк в изменённых файлах нет"
else
    bad V27 "длинные строки: $(echo "$LONG" | head -3 | tr '\n' ' ')"
fi

[ -f "$ROOT/reports/stage1-week4.md" ] && ok V28 "отчёт недели 4 есть" || bad V28 "нет reports/stage1-week4.md"

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf 'завалено: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
