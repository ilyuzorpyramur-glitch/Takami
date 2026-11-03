#!/usr/bin/env bash
# Проверка пригодности кода этапа 3 (повторы, предохранитель, диагностика).
# Сначала прогоняет гейты этапа 2 как регрессию: они обязаны остаться зелёными.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage3.sh [путь-к-ветке] [базовая-ветка]
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
UI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/takami"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/fetch"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 3: повторы, предохранитель, диагностика ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия этапа 2 ----------
if [ -f "$HERE/verify-stage2.sh" ]; then
    echo "--- регрессия этапа 2 ---"
    if bash "$HERE/verify-stage2.sh" "$ROOT" "$BASE" > /tmp/stage2-regression.txt 2>&1; then
        N=$(grep -c '\] PASS' /tmp/stage2-regression.txt)
        ok R1 "этап 2 не сломан ($N гейтов зелёные)"
    else
        bad R1 "регрессия этапа 2: $(grep 'FAIL' /tmp/stage2-regression.txt | head -3 | tr '\n' ' ')"
    fi
    echo
else
    skip R1 "скрипт этапа 2 не найден рядом"
fi

echo "--- файлы ---"

[ -f "$FETCH/Retry.kt" ] && ok S1 "Retry.kt есть" || bad S1 "нет Retry.kt"
[ -f "$FETCH/CircuitBreaker.kt" ] && ok S2 "CircuitBreaker.kt есть" || bad S2 "нет CircuitBreaker.kt"
[ -f "$UI/DiagnosticsScreen.kt" ] && ok S3 "DiagnosticsScreen.kt есть" || bad S3 "нет DiagnosticsScreen.kt"

echo
echo "--- повторы ---"

if [ -f "$FETCH/Retry.kt" ]; then
    grep -q "RETRY_ATTEMPTS" "$FETCH/Retry.kt" && ok S4 "число попыток берётся из лимитов" \
        || bad S4 "Retry.kt не использует RETRY_ATTEMPTS"

    if grep -qE "429" "$FETCH/Retry.kt" && grep -qE "500|502|503|504|5\.\." "$FETCH/Retry.kt"; then
        ok S5 "повторяются только 429 и 5xx"
    else
        bad S5 "условие повтора не ограничено 429/5xx"
    fi

    if grep -qE "400|404|403" "$FETCH/Retry.kt" 2>/dev/null; then
        bad S6 "в повторах упомянуты 4xx — они не должны повторяться"
    else
        ok S6 "4xx не повторяются"
    fi

    grep -q "retryDelaysMs" "$FETCH/Retry.kt" && ok S7 "паузы 5/30/120 берутся из лимитов" \
        || bad S7 "нет пауз из retryDelaysMs"

    grep -qi "retryAfter" "$FETCH/Retry.kt" && ok S8 "Retry-After учитывается при повторе" \
        || bad S8 "повтор игнорирует Retry-After"

    grep -q "newCall" "$FETCH/Retry.kt" 2>/dev/null && bad S9 "Retry.kt делает свои сетевые вызовы" \
        || ok S9 "повтор идёт через общий путь, а не напрямую"
else
    skip S4 "Retry.kt отсутствует"
fi

echo
echo "--- предохранитель ---"

if [ -f "$FETCH/CircuitBreaker.kt" ]; then
    grep -q "BREAKER_FAILURES" "$FETCH/CircuitBreaker.kt" && ok S10 "порог из BREAKER_FAILURES" \
        || bad S10 "порог не берётся из лимитов"
    grep -q "BREAKER_PAUSE_MS" "$FETCH/CircuitBreaker.kt" && ok S11 "пауза из BREAKER_PAUSE_MS" \
        || bad S11 "пауза не берётся из лимитов"

    if grep -qE "MutableMap|ConcurrentHashMap|by host|hosts" "$FETCH/CircuitBreaker.kt"; then
        ok S12 "состояние ведётся по каждому домену"
    else
        bad S12 "нет состояния по доменам — один сбой остановит все сайты"
    fi

    if grep -qiE "halfOpen|HALF_OPEN|probe|пробн" "$FETCH/CircuitBreaker.kt"; then
        ok S13 "есть пробный запрос после паузы"
    else
        bad S13 "нет пробного запроса — предохранитель не откроется снова"
    fi

    if grep -qiE "allow|canRequest|tryPass" "$FETCH/CircuitBreaker.kt"; then
        ok S14 "перед запросом спрашивают разрешение"
    else
        bad S14 "нет проверки состояния перед запросом"
    fi

    if grep -qiE "success|reset|onSuccess" "$FETCH/CircuitBreaker.kt"; then
        ok S15 "успех сбрасывает счётчик ошибок"
    else
        bad S15 "успех не сбрасывает состояние"
    fi

    if grep -qE "filesDir|cacheDir|File\(" "$FETCH/CircuitBreaker.kt"; then
        ok S16 "состояние переживает перезапуск"
    else
        bad S16 "состояние только в памяти — после перезапуска пауза теряется"
    fi

    if grep -q "CircuitBreaker" "$FETCH/Fetcher.kt" 2>/dev/null; then
        ok S17 "предохранитель подключён к сетевому слою"
    else
        bad S17 "CircuitBreaker не используется в Fetcher"
    fi
else
    skip S10 "CircuitBreaker.kt отсутствует"
fi

echo
echo "--- диагностика ---"

if [ -f "$UI/DiagnosticsScreen.kt" ]; then
    if grep -qiE "remaining|остал|budget" "$UI/DiagnosticsScreen.kt"; then
        ok S18 "показан остаток дневного лимита"
    else
        bad S18 "нет остатка лимита — пользователь не видит потолок"
    fi

    if grep -qiE "запрос|requests|used" "$UI/DiagnosticsScreen.kt"; then
        ok S19 "показано число запросов за сутки"
    else
        bad S19 "нет счётчика запросов"
    fi

    if grep -qiE "breaker|предохранит|пауза" "$UI/DiagnosticsScreen.kt"; then
        ok S20 "показано состояние предохранителя"
    else
        bad S20 "нет состояния предохранителя"
    fi

    if grep -qiE "ошибк|error|lastError" "$UI/DiagnosticsScreen.kt"; then
        ok S21 "показана последняя ошибка"
    else
        bad S21 "нет последней ошибки"
    fi

    if grep -q "BuildConfig.DEBUG" "$UI/DiagnosticsScreen.kt"; then
        ok S22 "кнопка сброса только в debug"
    else
        bad S22 "сброс счётчиков доступен в релизе"
    fi

    if grep -qE "Fetcher\.get\(\)|\.get\(" "$UI/DiagnosticsScreen.kt" 2>/dev/null; then
        bad S23 "экран диагностики сам ходит в сеть"
    else
        ok S23 "диагностика не делает сетевых запросов"
    fi

    if grep -qP "[\x{0400}-\x{04FF}]" "$UI/DiagnosticsScreen.kt"; then
        ok S24 "тексты на русском"
    else
        bad S24 "нет русских текстов в интерфейсе"
    fi
else
    skip S18 "DiagnosticsScreen.kt отсутствует"
fi

echo
echo "--- тесты ---"

# RetryAfterTest относится к этапу 1: нужен отдельный тест логики повторов.
if ls "$TESTS"/RetryTest.kt "$TESTS"/RetryPolicyTest.kt >/dev/null 2>&1; then
    ok S25 "есть тест логики повторов"
else
    bad S25 "нет отдельного теста повторов (RetryAfterTest не в счёт)"
fi
ls "$TESTS"/*CircuitBreaker*Test*.kt >/dev/null 2>&1 && ok S26 "есть тест предохранителя" \
    || bad S26 "нет теста предохранителя"

if grep -rqiE "restart|перезапуск|reopen|newInstance" "$TESTS"/*CircuitBreaker*Test*.kt 2>/dev/null; then
    ok S27 "тест проверяет, что пауза переживает перезапуск"
else
    bad S27 "нет проверки сохранения паузы"
fi

if grep -rqiE "ровно один|exactly one|single probe|один пробн" "$TESTS"/*CircuitBreaker*Test*.kt 2>/dev/null; then
    ok S28 "тест проверяет, что после паузы уходит ровно один запрос"
else
    bad S28 "нет проверки «ровно один пробный запрос»"
fi

if grep -rqE "Thread\.sleep|delay\(" "$TESTS" 2>/dev/null | grep -v FakeClock >/dev/null; then
    bad S29 "в тестах есть реальные ожидания"
else
    ok S29 "тесты без реальных ожиданий"
fi

echo
echo "--- гигиена ---"

if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    if git diff --quiet "$BASE"...HEAD -- gradle/libs.versions.toml app/build.gradle.kts; then
        ok S30 "новых зависимостей нет"
    else
        bad S30 "изменены зависимости"
    fi
else
    skip S30 "базовая ветка недоступна"
fi

# Проверяем только файлы, изменённые в ветке: старые нарушения не должны блокировать этап.
if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    LONG=$(git diff --name-only "$BASE"...HEAD -- "*.kt" 2>/dev/null | while read -r f; do
        [ -f "$f" ] && grep -qE "^.{121,}$" "$f" 2>/dev/null && echo "$f"
    done)
    if [ -z "$LONG" ]; then
        ok S31 "длинных строк в изменённых файлах нет"
    else
        bad S31 "длинные строки: $(echo "$LONG" | head -3 | tr '\n' ' ')"
    fi
else
    skip S31 "базовая ветка недоступна"
fi

if grep -rqE "TODO|FIXME" --include=*.kt "$FETCH" "$UI" 2>/dev/null; then
    bad S32 "есть TODO/FIXME"
else
    ok S32 "незакрытых TODO нет"
fi

[ -f "$ROOT/reports/stage1-week3.md" ] && ok S33 "отчёт недели 3 есть" || bad S33 "нет reports/stage1-week3.md"

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf 'завалено: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
