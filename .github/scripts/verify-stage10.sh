#!/usr/bin/env bash
# Проверка этапа 10: самолечение спеки.
# Сначала прогоняет гейт этапа 8 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage10.sh [путь-к-ветке] [базовая-ветка]
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
HEAL="$TAKAMI/heal"
UI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/takami"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/heal"
REPORT="$ROOT/reports/stage10-heal.md"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 10: самолечение спеки ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия ----------
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

echo "--- лечение не запускается само ---"

# В теле init и в классе приложения ссылок на лечение быть не должно.
INITBODY=$(sed -n '/fun init(context: Context)/,/^    }$/p' "$TAKAMI/TakamiForge.kt" 2>/dev/null)
if [ -n "$INITBODY" ]; then
    if echo "$INITBODY" | grep -q 'Healer'; then
        bad H1 "лечение упоминается в TakamiForge.init"
    else
        ok H1 "init не запускает лечение"
    fi
else
    bad H1 "не нашёл fun init в TakamiForge.kt"
fi

APPFILE="$ROOT/app/src/main/java/eu/kanade/tachiyomi/App.kt"
if [ -f "$APPFILE" ]; then
    if grep -q 'Heal' "$APPFILE"; then
        bad H1b "класс приложения ссылается на лечение"
    else
        ok H1b "приложение не запускает лечение"
    fi
else
    skip H1b "класс приложения не найден"
fi

# Таймеров и фоновых запусков в пакете лечения нет.
if grep -qE 'Worker|Service|BroadcastReceiver|BOOT_COMPLETED|fixedRate|setInterval' "$HEAL"/*.kt 2>/dev/null; then
    bad H1c "в heal/** есть фоновый запуск"
else
    ok H1c "фонового запуска нет"
fi

echo "--- правила лечения ---"

if [ -f "$HEAL/HealPolicy.kt" ]; then
    ok H2a "HealPolicy.kt есть"

    if grep -q 'dreamerscast.com' "$HEAL/HealPolicy.kt" && grep -q 'kodikdreamcast' "$HEAL/HealPolicy.kt"; then
        ok H2b "запрет только для чтения оформлен кодом"
    else
        bad H2b "в HealPolicy нет запрета dreamerscast/kodikdreamcast"
    fi

    if grep -q 'never_worked' "$HEAL/HealPolicy.kt" && grep -q 'checksPassed' "$HEAL/HealPolicy.kt" && grep -q 'historyVersions' "$HEAL/HealPolicy.kt"; then
        ok H3 "запрет источника без истории есть в коде и в тестах"
    else
        bad H3 "нет запрета источника без истории"
    fi

    MISSING=""
    for name in attempts_today cooldown cool_down_hard no_breakage; do
        grep -q "$name" "$HEAL/HealPolicy.kt" || MISSING="$MISSING $name"
    done
    grep -q 'MAX_ATTEMPTS_PER_DAY = 2' "$HEAL/HealPolicy.kt" || MISSING="$MISSING MAX_ATTEMPTS"
    grep -q 'COOLDOWN_MS = 6' "$HEAL/HealPolicy.kt" || MISSING="$MISSING COOLDOWN"
    grep -q 'HARD_COOLDOWN_MS = 24' "$HEAL/HealPolicy.kt" || MISSING="$MISSING HARD_COOLDOWN"
    if [ -z "$MISSING" ]; then
        ok H4 "пороги и запреты named-константами: $(grep -c 'TEST_ПОРОГ' "$TESTS/HealPolicyTest.kt" 2>/dev/null || true) тестов правила"
    else
        bad H4 "нет в HealPolicy:$MISSING"
    fi

    if grep -q 'третья попытка в сутки запрещена' "$TESTS/HealPolicyTest.kt" && \
        grep -q 'после провала ждут шесть часов' "$TESTS/HealPolicyTest.kt" && \
        grep -q 'после двух провалов подряд ждут сутки' "$TESTS/HealPolicyTest.kt"; then
        ok H4b "пороги покрыты тестами"
    else
        bad H4b "нет тестов порогов"
    fi
else
    bad H2a "нет heal/HealPolicy.kt"
fi

if [ -f "$HEAL/HealPlanner.kt" ]; then
    if grep -q 'видео не лечится' "$TESTS/HealPlannerTest.kt" && \
        grep -q 'каталог и пагинация вместе дают каталог' "$TESTS/HealPlannerTest.kt" && \
        grep -q 'каталог старше карточки' "$TESTS/HealPlannerTest.kt"; then
        ok H5 "старшинство частей и отказ от видео покрыты тестами"
    else
        bad H5 "нет тестов старшинства"
    fi
else
    bad H5 "нет heal/HealPlanner.kt"
fi

echo "--- вердикт ---"

if [ -f "$HEAL/HealVerdict.kt" ]; then
    if grep -q 'сломанный шаг починился, остальные равны — принять' "$TESTS/HealVerdictTest.kt"; then
        ok H6 "принятие покрыто тестом"
    else
        bad H6 "нет теста принятия"
    fi
    if grep -q 'но другой шаг сломался — отказ' "$TESTS/HealVerdictTest.kt"; then
        ok H7 "отказ при деградации соседа покрыт тестом"
    else
        bad H7 "нет теста отказа"
    fi
    if grep -q 'счёт равный, шаг всё ещё сломан — отказ' "$TESTS/HealVerdictTest.kt"; then
        ok H8 "отказ при равном счёте покрыт тестом"
    else
        bad H8 "нет теста равного счёта"
    fi
else
    bad H6 "нет heal/HealVerdict.kt"
fi

echo "--- слияние ---"

if [ -f "$HEAL/HealMerge.kt" ]; then
    if grep -q 'чужие поля не меняются никогда' "$TESTS/HealMergeTest.kt"; then
        ok H9 "неизменяемые поля покрыты тестом"
    else
        bad H9 "нет теста неизменяемых полей"
    fi
    if grep -q 'слияние идемпотентно' "$TESTS/HealMergeTest.kt" && grep -q 'оставляет спеку без изменений' "$TESTS/HealMergeTest.kt"; then
        ok H10 "идемпотентность и пустая секция покрыты тестами"
    else
        bad H10 "нет тестов идемпотентности"
    fi
else
    bad H9 "нет heal/HealMerge.kt"
fi

echo "--- границы лечения ---"

BADREFS=$(grep -nE 'spec\.video|streams|PlayerMap' "$HEAL"/*.kt 2>/dev/null | head -3)
if [ -z "$BADREFS" ]; then
    ok H11 "в heal/** нет лечения видео"
else
    bad H11 "в heal/** трогают видео: $BADREFS"
fi

NET=$(grep -nE 'Jsoup\.connect|OkHttpClient' "$HEAL"/*.kt 2>/dev/null | head -3)
if [ -z "$NET" ]; then
    ok H12 "сеть в heal/** только через Forge и SelfCheck"
else
    bad H12 "сеть мимо Fetcher: $NET"
fi

echo "--- журнал ---"

if [ -f "$HEAL/HealLog.kt" ]; then
    if grep -q 'MAX_LINES = 500' "$HEAL/HealLog.kt" && grep -q 'обреза' "$HEAL/HealLog.kt"; then
        ok H13a "журнал ограничен пятьюстами строками"
    else
        bad H13a "нет ограничения журнала"
    fi
    if grep -q 'битые строки пропускаются' "$TESTS/HealLogTest.kt" && grep -q 'обрезается до пятисот строк' "$TESTS/HealLogTest.kt" && grep -q 'формат строки в файле' "$TESTS/HealLogTest.kt"; then
        ok H13b "формат, обрезка и битые строки покрыты тестами"
    else
        bad H13b "нет тестов журнала"
    fi
else
    bad H13 "нет heal/HealLog.kt"
fi

echo "--- версия спеки ---"

if grep -q 'const val SPEC_VERSION = 3' "$TAKAMI/spec/SpecVersion.kt"; then
    ok H14a "SPEC_VERSION остался 3"
else
    bad H14a "SPEC_VERSION изменён"
fi

TOUCHED=$(git diff --name-only "$BASE" -- "$TAKAMI/SiteSpec.kt" "$TAKAMI/spec/SpecVersion.kt" "$TAKAMI/spec/SpecMigration.kt" "$TAKAMI/SpecEngine.kt" "$TAKAMI/SelfCheck.kt" 2>/dev/null)
if [ -z "$TOUCHED" ]; then
    ok H14b "SiteSpec, SpecVersion, SpecMigration, SpecEngine, SelfCheck не тронуты"
else
    bad H14b "тронуты чужие файлы: $(echo "$TOUCHED" | tr '\n' ' ')"
fi

echo "--- экран ---"

[ -f "$UI/HealScreen.kt" ] && ok U1a "HealScreen.kt есть" || bad U1a "нет ui/takami/HealScreen.kt"
[ -f "$UI/HealScreenModel.kt" ] && ok U1b "HealScreenModel.kt есть" || bad U1b "нет HealScreenModel.kt"

MISSINGUI=""
for text in 'Починить' 'Проверяю страницу' 'Починено' 'Не вышло, оставил прежнюю версию' 'Подождите' 'Починка недоступна'; do
    grep -q "$text" "$UI/HealScreen.kt" || MISSINGUI="$MISSINGUI $text"
done
if [ -z "$MISSINGUI" ]; then
    ok U2 "все подписи экрана на месте и по-русски"
else
    bad U2 "нет подписей:$MISSINGUI"
fi

if grep -q 'HealScreen' "$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/browse/anime/extension/AnimeExtensionsTab.kt"; then
    ok U3 "экран починки подключён к меню расширений"
else
    bad U3 "экран починки не подключён к меню"
fi

if grep -q 'Процент\|percent\|успеха' "$UI/HealScreen.kt"; then
    bad U4 "на экране есть проценты успеха"
else
    ok U4 "чисел, которых нет, на экране нет"
fi

echo "--- стиль ---"

NEWFILES="$HEAL/HealPlanner.kt $HEAL/HealPolicy.kt $HEAL/HealMerge.kt $HEAL/HealVerdict.kt $HEAL/HealLog.kt $HEAL/Healer.kt $UI/HealScreen.kt $UI/HealScreenModel.kt"
cat > /tmp/linecheck10.py <<'PY'
import sys
for path in sys.argv[1:]:
    try:
        for n, line in enumerate(open(path, encoding='utf-8'), 1):
            if len(line.rstrip('\n')) > 120:
                print(path + ':' + str(n))
    except OSError:
        pass
PY
LONG=$(python3 /tmp/linecheck10.py $NEWFILES 2>/dev/null | head -5)
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

if grep -l 'TODO' $NEWFILES "$TESTS"/*.kt 2>/dev/null | head -3 | grep -q .; then
    bad K3 "в коде остались TODO"
else
    ok K3 "TODO нет"
fi

echo "--- отчёт ---"

if [ -f "$REPORT" ]; then
    ok P1 "отчёт есть: reports/stage10-heal.md"

    SIZE=$(wc -c < "$REPORT")
    if [ "$SIZE" -le 4096 ]; then
        ok P1b "отчёт укладывается в 4 КБ ($SIZE байт)"
    else
        bad P1b "отчёт больше 4 КБ ($SIZE байт)"
    fi

    if grep -qiE 'cookies|authorization|token|password' "$REPORT" "$HEAL/HealLog.kt"; then
        bad P2 "в отчёте или журнале есть служебные данные"
    else
        ok P2 "в отчёте и журнале нет cookies и токенов"
    fi
else
    bad P1 "нет reports/stage10-heal.md"
fi

echo
echo "=== Итого: PASS $PASS, FAIL $FAIL ==="
if [ "$FAIL" -ne 0 ]; then
    printf 'провалы: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
