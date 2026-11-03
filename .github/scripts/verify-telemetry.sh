#!/usr/bin/env bash
# Проверка пригодности кода этапа 5 (сбор отчётов о сбоях).
# Сначала прогоняет гейты этапа 3 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-telemetry.sh [путь-к-ветке] [базовая-ветка]
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
TEL="$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami/report"
UI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/takami"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/report"
WORKER="$ROOT/telemetry-collector"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 5: сбор отчётов о сбоях ==="
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

echo "--- адрес приёмника не в репозитории ---"

# Ищем литеральные IP и хосты в коде приложения.
# Проверяем только слой отчётов: старые файлы не относятся к этапу.
IP_HITS=$(grep -rnE "([0-9]{1,3}\.){3}[0-9]{1,3}" --include=*.kt "$TEL" 2>/dev/null \
    | grep -vE "127\.0\.0\.1|0\.0\.0\.0" || true)
if [ -z "$IP_HITS" ]; then
    ok T1 "в коде нет литеральных IP"
else
    bad T1 "в коде IP: $(echo "$IP_HITS" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
fi

URL_HITS=$(grep -rnE "https?://[a-zA-Z0-9.-]+\.[a-z]{2,}" --include=*.kt "$TEL" 2>/dev/null \
    | grep -viE "schema|example\.com|android\.com" || true)
if [ -z "$URL_HITS" ]; then
    ok T2 "в коде отчётов нет адреса приёмника"
else
    bad T2 "адрес приёмника вшит в код: $(echo "$URL_HITS" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
fi

if [ -f "$ROOT/app/build.gradle.kts" ]; then
    if grep -qE "TELEMETRY_URL|telemetryUrl" "$ROOT/app/build.gradle.kts"; then
        ok T3 "адрес передаётся через поле сборки"
    else
        bad T3 "нет поля сборки для адреса"
    fi
    if grep -qE "providers\.gradleProperty|System\.getenv|providers\.environmentVariable" "$ROOT/app/build.gradle.kts"; then
        ok T4 "адрес берётся из переменной, а не из файла"
    else
        bad T4 "адрес не читается из переменной окружения"
    fi
fi

if [ -f "$ROOT/.github/workflows/takami-debug.yml" ]; then
    if grep -qE "secrets\.TELEMETRY_URL" "$ROOT/.github/workflows/takami-debug.yml"; then
        ok T5 "CI подставляет адрес из секрета"
    else
        skip T5 "секрет в workflow не используется"
    fi
    if grep -qE "echo.*TELEMETRY|run: *echo.*\\\$\{\{ *secrets" "$ROOT/.github/workflows/takami-debug.yml"; then
        bad T6 "секрет печатается в лог CI"
    else
        ok T6 "секрет не попадает в лог CI"
    fi
fi

echo
echo "--- что собирается ---"

[ -d "$TEL" ] && ok T7 "пакет отчётов есть" || bad T7 "нет пакета takami/report"
[ -f "$TEL/Sanitizer.kt" ] && ok T8 "есть очистка данных" || bad T8 "нет Sanitizer.kt"

if [ -f "$TEL/Sanitizer.kt" ]; then
    if grep -qE "query|\\?|takeWhile|substringBefore" "$TEL/Sanitizer.kt"; then
        ok T9 "из адресов убирается query"
    else
        bad T9 "query не вырезается — токены из ссылок утекут"
    fi
    if grep -qiE "title|название" "$TEL/Sanitizer.kt" 2>/dev/null; then
        ok T10 "названия тайтлов отбрасываются"
    else
        bad T10 "нет явного отказа от названий тайтлов"
    fi
    if grep -qiE "hash|sha|digest" "$TEL/Sanitizer.kt"; then
        ok T11 "идентификатор установки хешируется"
    else
        bad T11 "идентификатор установки не обезличен"
    fi
fi

if [ -d "$TEL" ]; then
    if grep -rqiE "installId|install_id" "$TEL" 2>/dev/null; then
        if grep -rqiE "sha256|hash" "$TEL" 2>/dev/null; then
            ok T12 "установка различается обезличенно"
        else
            bad T12 "идентификатор установки в открытом виде"
        fi
    else
        skip T12 "идентификатор установки не используется"
    fi
fi

echo
echo "--- надёжность ---"

[ -f "$TEL/ReportQueue.kt" ] && ok T13 "очередь отчётов есть" || bad T13 "нет ReportQueue.kt"

if [ -f "$TEL/ReportQueue.kt" ]; then
    grep -qE "MAX_EVENTS|MAX_BYTES|limit" "$TEL/ReportQueue.kt" && ok T14 "размер очереди ограничен" \
        || bad T14 "очередь растёт без предела"
    grep -qE "filesDir|cacheDir|File\(" "$TEL/ReportQueue.kt" && ok T15 "очередь переживает перезапуск" \
        || bad T15 "очередь только в памяти — отчёт потеряется"
    grep -qE "renameTo|Files\.move|atomic" "$TEL/ReportQueue.kt" && ok T16 "запись очереди атомарная" \
        || bad T16 "запись не атомарная"
fi

[ -f "$TEL/Reporter.kt" ] && ok T17 "отправка реализована" || bad T17 "нет Reporter.kt"

if [ -f "$TEL/Reporter.kt" ]; then
    if grep -qE "isBlank|isEmpty" "$TEL/Reporter.kt"; then
        ok T18 "пустой адрес выключает отправку"
    else
        bad T18 "при пустом адресе отправка всё равно пойдёт"
    fi
    grep -qE "HostGuard|Fetcher|guard" "$TEL/Reporter.kt" && ok T19 "отправка уважает лимиты" \
        || bad T19 "отчёты ходят мимо лимитов"
    grep -qE "batch|chunk|take\(" "$TEL/Reporter.kt" && ok T20 "отчёты уходят пачкой" \
        || bad T20 "каждый отчёт — отдельный запрос"
fi

echo
echo "--- приватность ---"

if [ -d "$TEL" ]; then
    if grep -rqiE "ip(Addr|Address)?[[:space:]]*=|getIp|ipify" "$TEL" 2>/dev/null; then
        bad T21 "в отчёт попадает IP-адрес"
    else
        ok T21 "IP не собирается"
    fi
    if grep -rqiE "ANDROID_ID|androidId|IMEI|deviceId" "$TEL" 2>/dev/null; then
        bad T22 "собирается идентификатор устройства"
    else
        ok T22 "идентификатор устройства не собирается"
    fi
    if grep -rqiE "email|login|account" "$TEL" 2>/dev/null; then
        bad T23 "в отчёт попадают данные аккаунта"
    else
        ok T23 "данные аккаунта не собираются"
    fi
    if grep -rqiE "library|история|history" "$TEL" 2>/dev/null; then
        skip T24 "упоминание библиотеки — проверить вручную, что не уходит содержимое"
    else
        ok T24 "содержимое библиотеки не собирается"
    fi
fi

if [ -f "$UI/DiagnosticsScreen.kt" ]; then
    if grep -qiE "отчёт|отчет|report" "$UI/DiagnosticsScreen.kt"; then
        ok T25 "в диагностике есть раздел отчётов"
    else
        bad T25 "пользователь не видит, что отправляется"
    fi
    if grep -qiE "выключ|отключ|switch|toggle" "$UI/DiagnosticsScreen.kt"; then
        ok T26 "отправку можно выключить"
    else
        bad T26 "нет переключателя — отправка без согласия"
    fi
fi

echo
echo "--- подпись запросов ---"

FETCHER="$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami/fetch/Fetcher.kt"
if [ -f "$FETCHER" ]; then
    if grep -qE "fun postReport\(.*token" "$FETCHER"; then
        ok T43 "слой умеет передавать подпись запроса"
    else
        bad T43 "postReport не принимает подпись — приёмник ответит 401 на каждый отчёт"
    fi
    if grep -qE "Authorization" "$FETCHER"; then
        ok T44 "заголовок подписи действительно ставится"
    else
        bad T44 "заголовок Authorization не выставляется"
    fi
else
    skip T43 "Fetcher.kt не найден"
fi

if grep -q "TELEMETRY_TOKEN" "$ROOT/app/build.gradle.kts" 2>/dev/null; then
    ok T45 "подпись приходит в сборку из переменной"
else
    bad T45 "подписи нет в сборке — нечем подтвердить запрос"
fi

WF="$ROOT/.github/workflows/takami-debug.yml"
if [ -f "$WF" ]; then
    if grep -q "secrets.TELEMETRY_TOKEN" "$WF"; then
        ok T46 "сборка получает подпись из секрета"
    else
        bad T46 "workflow не передаёт подпись"
    fi
    if grep -qE 'echo .*\$\{?(TELEMETRY_URL|TELEMETRY_TOKEN)' "$WF"; then
        bad T47 "workflow печатает значение секрета"
    else
        ok T47 "значения секретов не печатаются"
    fi
fi

W="$ROOT/telemetry-collector/src/index.js"
if [ -f "$W" ]; then
    if grep -qE "TELEMETRY_TOKEN" "$W" && grep -qE "401" "$W"; then
        ok T48 "приёмник требует подпись"
    else
        bad T48 "приёмник принимает кого угодно"
    fi
else
    skip T48 "приёмник отсутствует"
fi

echo
echo "--- режим калибровки ---"

if [ -d "$TEL" ]; then
    if grep -rqE "CALIBRATION_UNTIL|CALIBRATION_DAYS|calibrationUntil" "$TEL"; then
        ok T38 "задан срок окончания калибровки"
    else
        bad T38 "нет срока окончания — сбор станет постоянным"
    fi

    if grep -rqE "CALIBRATION_UNTIL|calibrationUntil" "$TEL/Reporter.kt" 2>/dev/null; then
        ok T39 "срок проверяется перед отправкой"
    else
        bad T39 "срок не проверяется в коде отправки"
    fi

    if grep -rqE "MAX_TOTAL|totalSent|TOTAL_LIMIT" "$TEL"; then
        ok T40 "есть общий предел отправленных отчётов"
    else
        bad T40 "нет общего предела — поток не остановится"
    fi

    # Изоляция: снаружи пакет дергается в одном месте, иначе его не удалить целиком.
    OUT=$(grep -rl "takami.report" --include=*.kt "$ROOT/app/src/main/java" 2>/dev/null | grep -v "/takami/report/" || true)
    if [ "$(echo "$OUT" | grep -c . )" -le 2 ]; then
        ok T41 "пакет отчётов изолирован ($(echo "$OUT" | grep -c . ) точек входа)"
    else
        bad T41 "пакет отчётов размазан по коду: $(echo "$OUT" | head -3 | tr '\n' ' ')"
    fi
else
    skip T38 "пакет отчётов отсутствует"
fi

if [ -f "$UI/DiagnosticsScreen.kt" ]; then
    if grep -qiE "калибровк|до [0-9]|срок" "$UI/DiagnosticsScreen.kt"; then
        ok T42 "в интерфейсе указано, что сбор временный"
    else
        bad T42 "пользователь не видит, что сбор временный"
    fi
else
    skip T42 "DiagnosticsScreen отсутствует"
fi

echo
echo "--- приёмник ---"

if [ -d "$WORKER" ]; then
    ok T27 "код приёмника в отдельной папке"
    if grep -rqE "AIza|sk-|BEGIN (OPENSSH|RSA)" "$WORKER" 2>/dev/null; then
        bad T28 "в коде приёмника есть секреты"
    else
        ok T28 "секретов в коде приёмника нет"
    fi
    if grep -rqiE "wrangler\.toml|kv_namespaces|d1_databases" "$WORKER" 2>/dev/null; then
        ok T29 "приёмник описан как развёртываемый (без своего сервера)"
    else
        skip T29 "описание развёртывания не найдено"
    fi
else
    skip T27 "папка telemetry-collector отсутствует"
fi

echo
echo "--- тесты ---"

[ -d "$TESTS" ] && ok T30 "тесты отчётов есть" || bad T30 "нет тестов отчётов"

if [ -d "$TESTS" ]; then
    ls "$TESTS"/*Sanitizer*Test*.kt >/dev/null 2>&1 && ok T31 "тест очистки есть" || bad T31 "нет теста очистки"
    if grep -rqiE "query|\\?token|referer" "$TESTS"/*Sanitizer*Test*.kt 2>/dev/null; then
        ok T32 "тест проверяет вырезание query"
    else
        bad T32 "нет проверки вырезания query"
    fi
    if grep -rqiE "MAX_EVENTS|limit|переполн" "$TESTS"/*Queue*Test*.kt 2>/dev/null; then
        ok T33 "тест проверяет предел очереди"
    else
        bad T33 "нет проверки предела очереди"
    fi
    if grep -rqE "Thread\.sleep|delay\(" "$TESTS" 2>/dev/null | grep -v FakeClock >/dev/null; then
        bad T34 "в тестах есть реальные ожидания"
    else
        ok T34 "тесты без реальных ожиданий"
    fi
fi

echo
echo "--- гигиена ---"

if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    DEPS=$(git diff "$BASE"...HEAD -- gradle/libs.versions.toml app/build.gradle.kts 2>/dev/null \
        | grep -E '^\+' | grep -v '^\+\+\+' \
        | grep -iE 'implementation|libs\.|classpath|kapt|ksp|plugin' || true)
    if [ -z "$DEPS" ]; then
        ok T35 "новых зависимостей нет"
    else
        bad T35 "изменены зависимости: $(echo "$DEPS" | head -2 | tr '\n' ' ')"
    fi
else
    skip T35 "базовая ветка недоступна"
fi

if [ -d "$TEL" ] && grep -rqE "^.{121,}$" --include=*.kt "$TEL" 2>/dev/null; then
    bad T36 "есть строки длиннее 120"
else
    ok T36 "строки в пределах 120"
fi

[ -f "$ROOT/reports/stage1-week5.md" ] && ok T37 "отчёт недели 5 есть" || bad T37 "нет reports/stage1-week5.md"

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf 'завалено: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
