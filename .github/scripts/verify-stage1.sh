#!/usr/bin/env bash
# Проверка пригодности кода этапа 1 (сетевой слой Takami).
# Не собирает проект и не ходит в сеть — только статический разбор ветки.
#
# Использование:
#   tools/verify_stage1.sh [путь-к-ветке] [базовая-ветка]
# По умолчанию: текущий каталог, сравнение с origin/main.
#
# Формат вывода:
#   [G1.2] PASS  конструктор Fetcher internal
#   [G8.2] FAIL  найден секрет: app/src/.../X.kt:12
# Итог: 15 PASS, 1 FAIL. Код возврата 1 при любом FAIL.

set -uo pipefail

ROOT="${1:-$(pwd)}"
BASE="${2:-origin/main}"

PASS=0
FAIL=0
declare -a FAILED=()

ok()   { PASS=$((PASS + 1)); printf '[%s] PASS  %s\n' "$1" "$2"; }
bad()  { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf '[%s] FAIL  %s\n' "$1" "$2"; }
skip() { printf '[%s] SKIP  %s\n' "$1" "$2"; }

FETCH_DIR="$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami/fetch"
TEST_DIR="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/fetch"

echo "=== Этап 1: проверка ветки ==="
echo "каталог: $ROOT"
cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

# ---------- G0. Предусловия ----------
if git rev-parse --git-dir >/dev/null 2>&1; then
    ok G0.1 "git-репозиторий: $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo detached) @ $(git rev-parse --short HEAD)"
else
    bad G0.1 "это не git-репозиторий"
fi

REQUIRED=(Fetcher.kt HostGuard.kt RequestBudget.kt FetchLimits.kt HttpErrors.kt)
missing=()
for f in "${REQUIRED[@]}"; do
    [ -f "$FETCH_DIR/$f" ] || missing+=("$f")
done
if [ ${#missing[@]} -eq 0 ]; then
    ok G0.2 "все файлы сетевого слоя на месте (${#REQUIRED[@]})"
else
    bad G0.2 "отсутствуют: ${missing[*]}"
fi

# Изменения только в разрешённых путях
if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    CHANGED=$(git diff --name-only "$BASE"...HEAD 2>/dev/null)
    # С этапа 3 по протоколу экран диагностики живёт в ui/takami — путь разрешён.
    OUT=$(echo "$CHANGED" | grep -vE '^(app/src/main/java/eu/kanade/tachiyomi/takami/|app/src/main/java/eu/kanade/tachiyomi/ui/takami/|telemetry-collector/|app/src/test/java/eu/kanade/tachiyomi/takami/|\.github/(workflows|scripts)/|app/build\.gradle\.kts|gradle/libs\.versions\.toml|docs/|reports/)' | grep -v '^$')
    if [ -z "$OUT" ]; then
        ok G0.3 "изменения в разрешённых путях ($(echo "$CHANGED" | grep -c . ) файлов)"
    else
        bad G0.3 "посторонние изменения: $(echo "$OUT" | head -5 | tr '\n' ' ')"
    fi
else
    skip G0.3 "базовая ветка $BASE недоступна"
fi

# ---------- G1. Единственный путь в сеть ----------
if [ -d "$FETCH_DIR" ]; then
    HITS=$(grep -rn "OkHttpClient()" --include=*.kt "$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami" 2>/dev/null | grep -v "/takami/fetch/" | grep -v "OkHttpClient.Builder" || true)
    if [ -z "$HITS" ]; then
        ok G1.1 "нет OkHttpClient вне пакета fetch"
    else
        bad G1.1 "свой клиент вне fetch: $(echo "$HITS" | head -3 | cut -d: -f1-2 | tr '\n' ' ')"
    fi

    if grep -q "internal constructor" "$FETCH_DIR/Fetcher.kt" 2>/dev/null; then
        ok G1.2 "конструктор Fetcher закрыт"
    else
        bad G1.2 "конструктор Fetcher не internal — клиент можно создать снаружи"
    fi

    ALT=$(grep -rnE "HttpURLConnection|java\.net\.URL\(|newCall\(" --include=*.kt "$ROOT/app/src/main/java/eu/kanade/tachiyomi/takami" 2>/dev/null | grep -v "/fetch/" || true)
    if [ -z "$ALT" ]; then
        ok G1.3 "нет обходных сетевых вызовов в takami/"
    else
        bad G1.3 "обход лимитов: $(echo "$ALT" | head -3 | cut -d: -f1-2 | tr '\n' ' ')"
    fi
else
    bad G1 "пакет fetch отсутствует"
fi

# ---------- G1. Дополнительно ----------
if [ -f "$FETCH_DIR/Fetcher.kt" ]; then
    grep -q "runBlocking" "$FETCH_DIR/Fetcher.kt" && bad G1.4 "runBlocking в сетевом слое блокирует поток" || ok G1.4 "нет runBlocking в Fetcher"
    if grep -qE "by lazy|@Volatile|synchronized" "$FETCH_DIR/Fetcher.kt"; then
        ok G1.5 "синглтон Fetcher инициализируется потокобезопасно"
    else
        bad G1.5 "нет потокобезопасной инициализации Fetcher.get()"
    fi
    grep -q "ensureActive()" "$FETCH_DIR/Fetcher.kt" && ok G1.6 "отмена запроса поддержана" || bad G1.6 "нет ensureActive() — запрос не отменяется"
else
    bad G1.4 "Fetcher.kt отсутствует"
fi

# ---------- G3. Дополнительно ----------
if [ -f "$FETCH_DIR/HostGuard.kt" ]; then
    if grep -qE "hosts\.remove|prune|MAX_HOSTS|cleanup" "$FETCH_DIR/HostGuard.kt"; then
        ok G3.4 "состояние доменов ограничено по памяти"
    else
        bad G3.4 "карта доменов растёт без очистки — утечка при долгой работе"
    fi
fi

# ---------- G5. Дополнительно ----------
if [ -f "$FETCH_DIR/RequestBudget.kt" ]; then
    grep -q "filesDir" "$FETCH_DIR/RequestBudget.kt" && ok G5.3 "бюджет хранится в filesDir" || bad G5.3 "бюджет вне filesDir"
    grep -qE "MAX_BYTES|size >|length\(\) >" "$FETCH_DIR/RequestBudget.kt" && ok G5.4 "размер файла бюджета ограничен" || skip G5.4 "ограничение размера не задано"
fi

# ---------- G2. Инварианты лимитов ----------
if [ -f "$FETCH_DIR/FetchLimits.kt" ]; then
    L="$FETCH_DIR/FetchLimits.kt"
    # Подчёркивания в числах (1_500L) не должны ломать сравнение.
    # Убираем подчёркивания только внутри чисел: 1_500 -> 1500, имена не трогаем.
    NORM=$(sed -E 's/([0-9])_([0-9])/\1\2/g' "$L")
    expect() { # id, имя константы, значение
        if echo "$NORM" | grep -qE "(val|const val)[[:space:]]+$2[[:space:]]*=[[:space:]]*$3([^0-9]|$)"; then
            ok "$1" "$2 = $3"
        else
            bad "$1" "$2: ожидалось $3"
        fi
    }
    expect G2.1 MAX_CONCURRENT_TOTAL 2
    expect G2.2 MAX_CONCURRENT_PER_HOST 1
    expect G2.3 MIN_INTERVAL_MS 1500
    expect G2.4 AVERAGE_INTERVAL_MS 5000
    expect G2.5 BURST 2
    expect G2.6 DAILY_PER_HOST 300
else
    bad G2 "FetchLimits.kt отсутствует"
fi

if grep -q "HostGuard" "$FETCH_DIR/Fetcher.kt" 2>/dev/null; then
    ok G2.7 "Fetcher использует HostGuard"
else
    bad G2.7 "Fetcher не проходит через HostGuard"
fi

# ---------- G3. Параллельность ----------
if [ -f "$FETCH_DIR/HostGuard.kt" ]; then
    grep -q "Mutex()" "$FETCH_DIR/HostGuard.kt" && ok G3.1 "мьютекс на домен присутствует" || bad G3.1 "нет мьютекса на домен"
    grep -q "withLock" "$FETCH_DIR/HostGuard.kt" && ok G3.2 "проверка и захват под одним локом" || bad G3.2 "нет withLock — гонка между проверкой и отправкой"
    # Интервал должен отсчитываться после завершения запроса:
    # либо markSent стоит в finally, либо вызывается после block().
    RUN_BODY=$(awk '/suspend fun <T> run\(/,/^    }/' "$FETCH_DIR/HostGuard.kt" 2>/dev/null)
    POS_BLOCK=$(printf '%s' "$RUN_BODY" | grep -n "block()" | head -1 | cut -d: -f1)
    POS_MARK=$(printf '%s' "$RUN_BODY" | grep -n "markSent" | head -1 | cut -d: -f1)
    if printf '%s' "$RUN_BODY" | grep -q "finally" && printf '%s' "$RUN_BODY" | grep -q "markSent"; then
        ok G3.3 "интервал отсчитывается после завершения запроса (finally)"
    elif [ -n "$POS_BLOCK" ] && [ -n "$POS_MARK" ] && [ "$POS_MARK" -gt "$POS_BLOCK" ]; then
        ok G3.3 "интервал отсчитывается после завершения запроса"
    else
        bad G3.3 "markSent вызывается до block() — интервал считается от отправки, а не от ответа"
    fi
else
    bad G3 "HostGuard.kt отсутствует"
fi

# ---------- G4. Кэш ----------
if [ -f "$FETCH_DIR/HttpCache.kt" ] || [ -f "$FETCH_DIR/Conditional.kt" ]; then
    if grep -rqE "If-None-Match|If-Modified-Since" "$FETCH_DIR" 2>/dev/null; then
        ok G4.1 "условные заголовки формируются"
    else
        bad G4.1 "нет If-None-Match/If-Modified-Since"
    fi
    if grep -rq "304" "$FETCH_DIR" 2>/dev/null; then
        ok G4.2 "ответ 304 обрабатывается"
    else
        bad G4.2 "нет обработки 304"
    fi
else
    skip G4 "кэш относится к неделе 2"
fi

# ---------- G5. Бюджет ----------
if [ -f "$FETCH_DIR/RequestBudget.kt" ]; then
    if grep -qE "renameTo|move|Files\.move|atomic" "$FETCH_DIR/RequestBudget.kt"; then
        ok G5.1 "запись бюджета атомарная"
    else
        bad G5.1 "запись не атомарная — при сбое счётчик потеряется"
    fi
    grep -qE "SimpleDateFormat|LocalDate" "$FETCH_DIR/RequestBudget.kt" && ok G5.2 "ключ содержит дату (сброс в полночь)" || bad G5.2 "нет привязки к дате"
fi

# ---------- G6. Ошибки и повторы ----------
if [ -f "$FETCH_DIR/Retry.kt" ]; then
    if grep -q "429" "$FETCH_DIR/Retry.kt" && grep -qi "5..\|serverError" "$FETCH_DIR/Retry.kt"; then
        ok G6.1 "повторы только 429/5xx"
    else
        bad G6.1 "условия повтора не ограничены 429/5xx"
    fi
    grep -qi "Retry-After\|retryAfter" "$FETCH_DIR/Retry.kt" && ok G6.2 "Retry-After учитывается" || bad G6.2 "Retry-After не читается"
else
    skip G6 "повторы относятся к неделе 3"
fi

# ---------- G7. Совместимость API ----------
if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    # Сравниваем только сигнатуры: тело функции может меняться, объявление — нет.
    OLD=$(git show "$BASE:app/src/main/java/eu/kanade/tachiyomi/takami/SpecEngine.kt" 2>/dev/null \
        | grep -E "^\s{4}(suspend )?fun " | sed -E 's/[[:space:]]*(=|\{).*$//')
    NEW=$(git show "HEAD:app/src/main/java/eu/kanade/tachiyomi/takami/SpecEngine.kt" 2>/dev/null \
        | grep -E "^\s{4}(suspend )?fun " | sed -E 's/[[:space:]]*(=|\{).*$//')
    if [ "$OLD" = "$NEW" ]; then
        ok G7.1 "публичные методы SpecEngine не изменились"
    else
        bad G7.1 "сигнатуры SpecEngine изменились"
    fi

    if git diff --quiet "$BASE"...HEAD -- gradle/libs.versions.toml; then
        ok G7.2 "новых зависимостей нет"
    else
        bad G7.2 "изменён каталог зависимостей: $(git diff --unified=0 "$BASE"...HEAD -- gradle/libs.versions.toml | grep -E '^\+' | grep -v '^+++' | head -3 | tr '\n' ' ')"
    fi
else
    skip G7 "базовая ветка недоступна"
fi

# ---------- G7. Дополнительно ----------
if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    if git diff --quiet "$BASE"...HEAD -- app/src/main/AndroidManifest.xml; then
        ok G7.3 "новых разрешений в манифесте нет"
    else
        bad G7.3 "изменён AndroidManifest — проверь разрешения"
    fi
    DIFF_LINES=$(git diff --numstat "$BASE"...HEAD | awk '{s+=$1+$2} END {print s+0}')
    if [ "$DIFF_LINES" -le 3000 ]; then
        ok G7.4 "размер изменений в норме ($DIFF_LINES строк)"
    else
        bad G7.4 "слишком большой дифф: $DIFF_LINES строк"
    fi
    if git log --merges --oneline "$BASE"..HEAD | grep -q .; then
        bad G7.5 "в ветке есть merge-коммиты — история не линейная"
    else
        ok G7.5 "история линейная"
    fi
fi

# ---------- G8. Гигиена ----------
SECRETS=$(grep -rnE "AIza[0-9A-Za-z_-]{30,}|[0-9]{9,10}:[A-Za-z0-9_-]{35}|BEGIN (OPENSSH|RSA|PRIVATE) KEY|sk-[A-Za-z0-9]{32,}" \
    --include=*.kt --include=*.kts --include=*.json --include=*.yml "$ROOT/app/src" "$ROOT/.github" 2>/dev/null || true)
if [ -z "$SECRETS" ]; then
    ok G8.1 "секретов в исходниках нет"
else
    bad G8.1 "найден секрет: $(echo "$SECRETS" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
fi

LONG=$(grep -rnE '^.{121,}$' --include=*.kt "$FETCH_DIR" "$TEST_DIR" 2>/dev/null || true)
if [ -z "$LONG" ]; then
    ok G8.2 "строки не длиннее 120 символов"
else
    bad G8.2 "длинные строки: $(echo "$LONG" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
fi

TABS=$(grep -rnP "^\t" --include=*.kt "$FETCH_DIR" "$TEST_DIR" 2>/dev/null || true)
if [ -z "$TABS" ]; then
    ok G8.3 "табов в отступах нет"
else
    bad G8.3 "табы в отступах: $(echo "$TABS" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
fi

# ---------- G8. Дополнительно ----------
if [ -d "$FETCH_DIR" ]; then
    TODO=$(grep -rn "TODO\|FIXME" --include=*.kt "$FETCH_DIR" 2>/dev/null || true)
    if [ -z "$TODO" ]; then
        ok G8.4 "нет TODO/FIXME в сетевом слое"
    else
        bad G8.4 "незакрытые TODO/FIXME: $(echo "$TODO" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
    fi
    LEAK=$(grep -rnE "logcat.*url|logcat.*\\(url\\)|println\(" --include=*.kt "$FETCH_DIR" 2>/dev/null || true)
    if [ -z "$LEAK" ]; then
        ok G8.5 "URL не пишутся в лог целиком"
    else
        bad G8.5 "в лог попадает URL: $(echo "$LEAK" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
    fi
fi

# ---------- G9. Тесты ----------
if [ -d "$TEST_DIR" ]; then
    if grep -rq "FakeClock\|FetchClock" "$TEST_DIR" 2>/dev/null; then
        ok G9.1 "тесты используют подменяемые часы"
    else
        bad G9.1 "тесты зависят от реального времени"
    fi
    REAL=$(grep -rnE "Thread\.sleep|delay\(" "$TEST_DIR" 2>/dev/null | grep -v "FakeClock" || true)
    if [ -z "$REAL" ]; then
        ok G9.2 "нет реальных ожиданий в тестах"
    else
        bad G9.2 "реальные ожидания: $(echo "$REAL" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
    fi
else
    bad G9 "тестов нет"
fi

# ---------- G9. Дополнительно ----------
if [ -d "$TEST_DIR" ]; then
    for pair in Fetcher HostGuard RequestBudget; do
        if ls "$TEST_DIR"/*"$pair"*Test*.kt >/dev/null 2>&1; then
            ok "G9.3" "есть тест на $pair"
        else
            bad "G9.3" "нет теста на $pair"
        fi
    done

    REALNET=$(grep -rnE "https?://(?!127\.0\.0\.1|localhost)" -P --include=*.kt "$TEST_DIR" 2>/dev/null || true)
    if [ -z "$REALNET" ]; then
        ok G9.4 "тесты не ходят в настоящую сеть"
    else
        bad G9.4 "реальные адреса в тестах: $(echo "$REALNET" | head -2 | cut -d: -f1-2 | tr '\n' ' ')"
    fi

    DIS=$(grep -rn "@Disabled\|@Ignore" --include=*.kt "$TEST_DIR" 2>/dev/null || true)
    [ -z "$DIS" ] && ok G9.5 "нет отключённых тестов" || bad G9.5 "есть отключённые тесты"
fi

# ---------- G10. Документация ----------
if [ -f "$ROOT/reports/stage1-week1.md" ]; then
    if grep -qiE "лимит|запрос|1500|300" "$ROOT/reports/stage1-week1.md"; then
        ok G10.1 "отчёт недели описывает лимиты"
    else
        bad G10.1 "отчёт недели не упоминает лимиты"
    fi
else
    skip G10.1 "отчёта недели нет"
fi

# ---------- Итог ----------
echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf 'завалено: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
