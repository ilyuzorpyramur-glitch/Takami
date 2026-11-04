#!/usr/bin/env bash
# Проверка этапа 7: видео разветвляется на качество и озвучку.
# Сначала прогоняет гейт этапа 6 как регрессию.
# Не собирает проект и не ходит в сеть.
#
# Использование:
#   bash .github/scripts/verify-stage7.sh [путь-к-ветке] [базовая-ветка]
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
UI="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/takami"
TESTS="$ROOT/app/src/test/java/eu/kanade/tachiyomi/takami/video"
LIST="$ROOT/docs/takami-33-sites.txt"
REPORT="$ROOT/reports/stage7-quality-audio.md"

cd "$ROOT" || { echo "нет каталога $ROOT"; exit 2; }

echo "=== Этап 7: качество и озвучка ==="
echo "каталог: $ROOT"
echo

# ---------- Регрессия этапа 6 ----------
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

echo "--- спека ---"

if [ -f "$TAKAMI/SiteSpec.kt" ]; then
    MISS=""
    for field in hosters qualityFrom hlsMaster preferred dubbingMode; do
        grep -q "val $field" "$TAKAMI/SiteSpec.kt" || MISS="$MISS $field"
    done
    if [ -z "$MISS" ]; then
        ok Q1a "VideoSpec расширен пятью полями"
    else
        bad Q1a "в VideoSpec нет полей:$MISS"
    fi

    # Новые поля обязаны иметь значения по умолчанию: старые файлы читаются.
    NODEF=$(grep -E 'val (hosters|qualityFrom|hlsMaster|preferred|dubbingMode):' "$TAKAMI/SiteSpec.kt" \
        | grep -vc '=' || true)
    if [ "$NODEF" -eq 0 ]; then
        ok Q1b "у новых полей есть значения по умолчанию"
    else
        bad Q1b "поле без значения по умолчанию: старые спеки не прочитаются"
    fi

    if grep -q 'listOf("1080p", "720p", "480p", "360p")' "$TAKAMI/SiteSpec.kt"; then
        ok Q1c "порядок качеств задан в спеке"
    else
        bad Q1c "нет порядка качеств по умолчанию"
    fi

    if grep -q 'availableDubbings' "$TAKAMI/SiteSpec.kt"; then
        ok Q1d "спека отдаёт список доступных озвучек"
    else
        bad Q1d "спека не умеет перечислить озвучки"
    fi
else
    bad Q1 "нет SiteSpec.kt"
fi

if grep -qE 'const val SPEC_VERSION = 3' "$TAKAMI/spec/SpecVersion.kt" 2>/dev/null; then
    ok Q1e "версия схемы поднята до 3"
else
    bad Q1e "SPEC_VERSION не равна 3"
fi

echo
echo "--- качество ---"

if [ -f "$VIDEO/HlsMaster.kt" ]; then
    if grep -q '#EXT-X-STREAM-INF' "$VIDEO/HlsMaster.kt"; then
        ok Q2a "мастер-плейлист разбирается по строкам с атрибутами"
    else
        bad Q2a "HlsMaster не ищет строки вариантов"
    fi
    # Сегменты не запрашиваются: в разборе нет ни запросов, ни .ts.
    if grep -qE 'segment|\.ts|fetch\(' "$VIDEO/HlsMaster.kt"; then
        bad Q2b "HlsMaster трогает сегменты или ходит в сеть"
    else
        ok Q2b "сегменты не запрашиваются"
    fi
    if grep -qE '' "$TESTS/HlsMasterTest.kt" 2>/dev/null; then
        ok Q2c "разбор мастера покрыт тестом"
    else
        bad Q2c "нет теста на мастер-плейлист"
    fi
else
    bad Q2 "нет video/HlsMaster.kt"
fi

if [ -f "$VIDEO/PlayerMap.kt" ]; then
    if grep -q 'PAIR' "$VIDEO/PlayerMap.kt" && grep -q 'OBJECT' "$VIDEO/PlayerMap.kt"; then
        ok Q3a "карта плеера читается в двух видах"
    else
        bad Q3a "PlayerMap понимает только один вид карты"
    fi
    if grep -qE '\[360p\]' "$TESTS/PlayerMapTest.kt" 2>/dev/null &&
        grep -qE '\{"360"|\{ "360"' "$TESTS/PlayerMapTest.kt" 2>/dev/null; then
        ok Q3b "оба вида проверены тестом"
    else
        bad Q3b "тест карты плеера не покрывает оба вида"
    fi
else
    bad Q3 "нет video/PlayerMap.kt"
fi

if [ -f "$VIDEO/Quality.kt" ]; then
    if grep -q 'fun dedupe' "$VIDEO/Quality.kt" && grep -q 'fun arrange' "$VIDEO/Quality.kt"; then
        ok Q4a "дедуп и порядок заданы в одном месте"
    else
        bad Q4a "нет дедупа или порядка качеств"
    fi
    if grep -q 'dubbing + "|" + it.quality' "$VIDEO/Quality.kt"; then
        ok Q4b "повтор считается по паре озвучка и качество"
    else
        bad Q4b "дедуп не учитывает озвучку"
    fi
    if grep -qE 'else -> ""' "$VIDEO/Quality.kt"; then
        ok Q5a "мусорная метка даёт пустое качество"
    else
        bad Q5a "мусорная метка превращается в качество"
    fi
    if grep -q 'мусорная метка' "$TESTS/QualityTest.kt" 2>/dev/null; then
        ok Q5b "пустая метка проверена тестом"
    else
        bad Q5b "нет теста на мусорную метку"
    fi
    if grep -q 'не определена' "$VIDEO/Quality.kt"; then
        ok Q5c "неопределённое качество названо словом"
    else
        bad Q5c "нет подписи для неопределённого качества"
    fi
else
    bad Q4 "нет video/Quality.kt"
    bad Q5 "нет video/Quality.kt"
fi

echo
echo "--- озвучки ---"

if [ -f "$VIDEO/Dubbing.kt" ]; then
    if grep -q 'fun names' "$VIDEO/Dubbing.kt" && grep -q 'fun tracks' "$VIDEO/Dubbing.kt"; then
        ok A1a "список озвучек собирается и склеивает повторы"
    else
        bad A1a "нет сбора или склейки озвучек"
    fi
    if grep -q 'JUNK' "$VIDEO/Dubbing.kt" && grep -q 'MAX = 20' "$VIDEO/Dubbing.kt"; then
        ok A1b "служебные подписи отброшены, предел двадцати задан"
    else
        bad A1b "мусорные подписи или предел не заданы"
    fi
    if grep -q 'служебные подписи' "$TESTS/DubbingTest.kt" 2>/dev/null; then
        ok A1c "отбор подписей проверен тестом"
    else
        bad A1c "нет теста на отбор озвучек"
    fi
else
    bad A1 "нет video/Dubbing.kt"
fi

if grep -qE 'fun videos\(spec: SiteSpec, episode: SEpisode, dubbing: String\? = null\)' "$TAKAMI/SpecEngine.kt" 2>/dev/null; then
    ok A2 "выдача ссылок принимает озвучку"
else
    bad A2 "videos не умеет фильтровать по озвучке"
fi

if grep -qE 'dubbing == Dubbing.NONE|filter \{ it.dubbing' "$TAKAMI/SpecEngine.kt" 2>/dev/null; then
    ok A2b "ссылки фильтруются по выбранной озвучке"
else
    bad A2b "фильтра по озвучке нет"
fi

if [ -f "$UI/TakamiForgeScreen.kt" ]; then
    MISSING=""
    for phrase in "Сайт не различает озвучки" "Качество не определено" "Ссылок нет"; do
        grep -q "$phrase" "$UI/TakamiForgeScreen.kt" || MISSING="$MISSING $phrase"
    done
    if [ -z "$MISSING" ]; then
        ok A3a "пустые состояния названы по-русски"
    else
        bad A3a "нет пустых состояний:$MISSING"
    fi
    if grep -q 'ready.qualities' "$UI/TakamiForgeScreen.kt" && grep -q 'ready.dubbings' "$UI/TakamiForgeScreen.kt"; then
        ok A3b "экран показывает обе оси"
    else
        bad A3b "на экране нет одной из осей"
    fi
    if grep -qE 'Ссылки|Озвучки|Качество' "$UI/TakamiForgeScreen.kt"; then
        ok A3c "подписи блоков русские"
    else
        bad A3c "блоки не подписаны"
    fi
else
    bad A3 "нет экрана мастера"
fi

echo
echo "--- прогонщик этапа 6 ---"

if [ -f "$TAKAMI/Sweep.kt" ]; then
    grep -q 'sites.txt' "$TAKAMI/Sweep.kt" && ok S2 "список читается из файла" || bad S2 "список не из файла"
    if grep -n 'https://' "$TAKAMI/Sweep.kt" | grep -v '^\s*[0-9]*: *\*' | grep -qv '//' ; then
        bad S3 "в прогонщике зашиты адреса"
    else
        ok S3 "адреса не зашиты"
    fi
    grep -qE 'async|awaitAll|launch\(' "$TAKAMI/Sweep.kt" && bad S4 "есть параллельный запуск" || ok S4 "сайты по одному"
    grep -q 'PAUSE_MS' "$TAKAMI/Sweep.kt" && ok S5 "пауза между сайтами есть" || bad S5 "паузы нет"
    grep -q 'appendText' "$TAKAMI/Sweep.kt" && ok S6 "итог дописывается по ходу" || bad S6 "итог пишется в конце"
    grep -q 'readDone' "$TAKAMI/Sweep.kt" && ok S7 "прогон возобновляемый" || bad S7 "нет возобновления"
    grep -qE 'put\("title"|put\("text"|put\("cookies"' "$TAKAMI/Sweep.kt" \
        && bad S8 "в итог попадают названия или текст" || ok S8 "в итоге нет названий и cookies"
    grep -qE 'Worker|Service|BroadcastReceiver|BOOT_COMPLETED' "$TAKAMI/Sweep.kt" \
        && bad S9 "прогон запускается фоном" || ok S9 "прогон только с экрана"
    if grep -q 'qualities' "$TAKAMI/Sweep.kt" && grep -q 'dubbings' "$TAKAMI/Sweep.kt" && \
        grep -q 'hoster' "$TAKAMI/Sweep.kt"; then
        ok S11 "в строке прогона обе оси и плеер"
    else
        bad S11 "в строке прогона нет осей"
    fi
else
    bad S1 "нет Sweep.kt"
fi

if grep -q 'private var running' "$UI/SweepScreenModel.kt" 2>/dev/null; then
    ok S10 "повторный запуск блокируется"
else
    bad S10 "нет защиты от двойного запуска"
fi

echo
echo "--- стиль ---"

LONG=$(for f in "$VIDEO"/*.kt "$TAKAMI/SpecEngine.kt" \
    "$TAKAMI/SiteSpec.kt" "$UI/TakamiForgeScreen.kt"; do
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

if grep -rn 'TODO\|FIXME' "$VIDEO"/*.kt "$TAKAMI/SpecEngine.kt" "$UI/TakamiForgeScreen.kt" 2>/dev/null \
    | head -2 | grep -q .; then
    bad K3 "в коде остались TODO"
else
    ok K3 "TODO нет"
fi

echo
echo "--- отчёт ---"

if [ -f "$REPORT" ]; then
    ok P1 "отчёт есть: reports/stage7-quality-audio.md"

    MISSING=""
    while read -r url; do
        case "$url" in
            https://*) ;;
            *) continue ;;
        esac
        host="${url#https://}"
        host="${host#www.}"
        host="${host%/}"
        grep -q "$host" "$REPORT" || MISSING="$MISSING $host"
    done < "$LIST"
    if [ -z "$MISSING" ]; then
        ok P2 "в отчёте есть все 33 домена"
    else
        bad P2 "в отчёте нет:$MISSING"
    fi

    SIZE=$(wc -c < "$REPORT")
    if [ "$SIZE" -le 8192 ]; then
        ok P3 "отчёт не больше 8 КБ ($SIZE байт)"
    else
        bad P3 "отчёт больше 8 КБ ($SIZE байт)"
    fi

    if grep -qiE 'cookies|authorization|token' "$REPORT"; then
        bad P4 "в отчёте есть служебные данные"
    else
        ok P4 "в отчёте нет cookies и токенов"
    fi
else
    bad P1 "нет reports/stage7-quality-audio.md"
fi

echo
echo "=== Итого: PASS $PASS, FAIL $FAIL ==="
if [ "$FAIL" -ne 0 ]; then
    printf 'провалы: %s\n' "${FAILED[*]}"
    exit 1
fi
exit 0
