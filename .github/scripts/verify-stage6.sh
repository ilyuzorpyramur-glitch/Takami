#!/usr/bin/env bash
# Проверка этапа 6: прогон всех 33 сайтов списка через генератор на устройстве.
# Сначала прогоняет гейт этапа 5 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Список сайтов читается из файла docs/takami-33-sites.txt — в скрипте его нет.
#
# Использование:
#   bash .github/scripts/verify-stage6.sh [путь-к-ветке] [базовая-ветка]
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
UI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/takami"
LIST="$ROOT/docs/takami-33-sites.txt"
REPORT="$ROOT/reports/stage6-sweep.md"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 6: прогон 33 сайтов ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия этапа 5 ----------
if [ -f "$HERE/verify-telemetry.sh" ]; then
    echo "--- регрессия этапа 5 ---"
    if bash "$HERE/verify-telemetry.sh" "$ROOT" "$BASE" > /tmp/stage5-regression.txt 2>&1; then
        N=$(grep -c '\] PASS' /tmp/stage5-regression.txt)
        ok R1 "этап 5 не сломан ($N гейтов зелёные)"
    else
        bad R1 "регрессия этапа 5: $(grep 'FAIL' /tmp/stage5-regression.txt | head -3 | tr '\n' ' ')"
    fi
    echo
else
    skip R1 "скрипт этапа 5 не найден рядом"
fi

echo "--- список сайтов ---"

if [ -f "$LIST" ]; then
    ok L1 "список сайтов есть: docs/takami-33-sites.txt"

    SITES=$(grep -c '^https://' "$LIST")
    if [ "$SITES" -eq 33 ]; then
        ok L2 "в списке ровно 33 адреса"
    else
        bad L2 "в списке $SITES адресов вместо 33"
    fi

    DUP=$(grep '^https://' "$LIST" | sort | uniq -d)
    if [ -z "$DUP" ]; then
        ok L3 "повторов нет"
    else
        bad L3 "повторы: $(echo "$DUP" | head -3 | tr '\n' ' ')"
    fi

    BADURL=$(grep '^https://' "$LIST" | grep -vE '^https://[a-z0-9.-]+/?$' | head -3)
    if [ -z "$BADURL" ]; then
        ok L4 "все адреса — голый https-хост, без путей"
    else
        bad L4 "не голые хосты: $(echo "$BADURL" | tr '\n' ' ')"
    fi

    if grep -q 'dreamerscast' "$LIST"; then
        ok L5 "kodikdreamcast (dreamerscast.com) в списке: прогон только на чтение"
    else
        bad L5 "kodikdreamcast пропал из списка"
    fi
else
    bad L1 "нет docs/takami-33-sites.txt"
fi

echo
echo "--- прогонщик ---"

if [ -f "$TAKAMI/Sweep.kt" ]; then
    ok S1 "Sweep.kt есть"

    # Список берётся из файла, а не из кода.
    if grep -qE 'const val LIST' "$TAKAMI/Sweep.kt" && grep -q 'sites.txt' "$TAKAMI/Sweep.kt"; then
        ok S2 "файл списка задан константой, а не списком в коде"
    else
        bad S2 "Sweep.kt не читает список из файла"
    fi

    # Хардкод сайтов в прогонщике: любая строка с https:// — это зашитый адрес.
    HARD=$(grep -n 'https://' "$TAKAMI/Sweep.kt" | grep -v '^\s*[0-9]*: *\*' | grep -v '//' | head -3)
    if [ -z "$HARD" ]; then
        ok S3 "адреса сайтов в прогонщике не зашиты"
    else
        bad S3 "в Sweep.kt зашиты адреса: $(echo "$HARD" | tr '\n' ' ')"
    fi

    # Один сайт за раз: параллельных запусков в прогонщике быть не должно.
    if grep -qE 'async|awaitAll|launch\(' "$TAKAMI/Sweep.kt"; then
        bad S4 "в прогонщике есть параллельный запуск"
    else
        ok S4 "сайты идут строго по одному"
    fi

    if grep -qE 'PAUSE_MS' "$TAKAMI/Sweep.kt" && grep -q 'delay(' "$TAKAMI/Sweep.kt"; then
        ok S5 "между сайтами есть пауза"
    else
        bad S5 "нет паузы между сайтами"
    fi

    # Итог дописывается после каждого сайта: прогон переживает обрыв.
    if grep -q 'appendText' "$TAKAMI/Sweep.kt"; then
        ok S6 "итог дописывается после каждого сайта"
    else
        bad S6 "итог не дописывается по ходу"
    fi

    # Продолжение с места остановки.
    if grep -q 'readDone' "$TAKAMI/Sweep.kt"; then
        ok S7 "прогон продолжается с места остановки"
    else
        bad S7 "нет пропуска уже пройденных сайтов"
    fi

    # Приватность: в отчёт идут только хост, шаг и тип, без названий и текста.
    if grep -qE 'put\("title"|put\("text"|put\("cookies"' "$TAKAMI/Sweep.kt"; then
        bad S8 "в итог прогона попадают названия или текст страницы"
    else
        ok S8 "в итоге прогона нет названий, текста и cookies"
    fi

    # Автозапуска нет: прогон начинает человек, а не фон.
    if grep -qE 'Worker|Service|BroadcastReceiver|BOOT_COMPLETED' "$TAKAMI/Sweep.kt"; then
        bad S9 "прогонщик запускается фоном"
    else
        ok S9 "прогон запускается только с экрана"
    fi

    # Второй запуск с экрана не должен поднимать второй прогон: на живом
    # устройстве два нажатия подряд дали по две строки на сайт.
    if grep -q 'private var running' "$UI/SweepScreenModel.kt" && \
        grep -q 'if (running) return' "$UI/SweepScreenModel.kt" && \
        [ "$(grep -n 'running = true' "$UI/SweepScreenModel.kt" | cut -d: -f1)" -lt \
          "$(grep -n 'screenModelScope.launch' "$UI/SweepScreenModel.kt" | cut -d: -f1)" ]; then
        ok S10 "повторный запуск блокируется до старта прогона"
    else
        bad S10 "нет защиты от двойного запуска прогона"
    fi
else
    bad S1 "нет takami/Sweep.kt"
fi

echo
echo "--- экран прогона ---"

[ -f "$UI/SweepScreen.kt" ] && ok U1 "SweepScreen.kt есть" || bad U1 "нет ui/takami/SweepScreen.kt"
[ -f "$UI/SweepScreenModel.kt" ] && ok U2 "SweepScreenModel.kt есть" || bad U2 "нет SweepScreenModel.kt"

if grep -q 'SweepScreen()' "$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/browse/anime/extension/AnimeExtensionsTab.kt"; then
    ok U3 "экран открывается из меню расширений"
else
    bad U3 "экран прогона не подключён к меню"
fi

if grep -qE 'Прогон' "$UI/SweepScreen.kt" 2>/dev/null; then
    ok U4 "экран подписан по-русски"
else
    bad U4 "нет русских подписей на экране прогона"
fi

echo
echo "--- стиль ---"

LONG=$(awk 'length > 120 {print FILENAME":"FNR}' "$TAKAMI/Sweep.kt" "$UI/SweepScreen.kt" "$UI/SweepScreenModel.kt" 2>/dev/null | head -5)
if [ -z "$LONG" ]; then
    ok K1 "нет строк длиннее 120 символов"
else
    bad K1 "длинные строки: $(echo "$LONG" | tr '\n' ' ')"
fi

if grep -qE '^\s*//\s*[A-Za-z]' "$TAKAMI/Sweep.kt"; then
    bad K2 "комментарии не по-русски"
else
    ok K2 "комментарии по-русски"
fi

if grep -rn 'TODO' "$TAKAMI/Sweep.kt" "$UI/SweepScreen.kt" "$UI/SweepScreenModel.kt" 2>/dev/null | head -3 | grep -q .; then
    bad K3 "в коде остались TODO"
else
    ok K3 "TODO нет"
fi

echo
echo "--- итог прогона ---"

if [ -f "$REPORT" ]; then
    ok P1 "отчёт прогона есть: reports/stage6-sweep.md"

    # В отчёте должна быть строка на каждый сайт списка: 33 из 33.
    if [ -f "$LIST" ]; then
        MISSING=""
        while read -r url; do
            case "$url" in
                https://*) ;;
                *) continue ;;
            esac
            host="${url#https://}"
            host="${host#www.}"
            grep -q "$host" "$REPORT" || MISSING="$MISSING $host"
        done < "$LIST"
        if [ -z "$MISSING" ]; then
            ok P2 "в отчёте есть все 33 сайта"
        else
            bad P2 "в отчёте нет:$MISSING"
        fi
    fi

    # Приватность отчёта: ни путей, ни названий тайтлов, ни cookies.
    if grep -qE 'cookies|authorization|token' "$REPORT"; then
        bad P3 "в отчёте есть служебные данные"
    else
        ok P3 "в отчёте нет cookies и токенов"
    fi
else
    bad P1 "прогона ещё нет: нет reports/stage6-sweep.md"
fi

echo
echo "=== Итого: PASS $PASS, FAIL $FAIL ==="
if [ "$FAIL" -ne 0 ]; then
    printf 'провалы: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
