#!/usr/bin/env bash
# Проверка ранобэ-генератора: структура, тесты, отчёт, отсутствие чужих правок.
# Не собирает проект. Использование: verify-novel.sh [корень] [база]
set -uo pipefail

ROOT="${1:-$(pwd)}"
BASE="${2:-origin/main}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0
FAILED=()

ok() { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf 'FAIL  %s\n' "$1"; }

has() { # файл, регулярка, подпись
    if [ -f "$1" ] && grep -qE "$2" "$1"; then ok "$3"; else bad "$3"; fi
}

cd "$ROOT" || exit 1
N="app/src/main/java/eu/kanade/tachiyomi/takami/novel"
T="app/src/test/java/eu/kanade/tachiyomi/takami/novel"
U="app/src/main/java/eu/kanade/tachiyomi/ui/takami"
A="app/src/main/java/eu/kanade/tachiyomi"

has "$N/NovelSpec.kt" 'data class NovelSpec' "N1 спека источника"
has "$N/NovelSpec.kt" 'NovelTextSpec' "N2 спека текста главы"
has "$N/NovelEngine.kt" 'fun parseCatalog' "N3 чистый разбор каталога"
has "$N/NovelEngine.kt" 'fun parseChapters' "N4 чистый разбор глав"
has "$N/NovelEngine.kt" 'fun parseText' "N5 чистый разбор текста"
has "$N/NovelEngine.kt" 'GregorianCalendar' "N6 дата без java.time"
has "$N/NovelAutoDetect.kt" 'fun catalog' "N7 карточки по структуре"
has "$N/NovelAutoDetect.kt" 'fun chapters' "N8 главы по номеру"
has "$N/NovelAutoDetect.kt" 'fun text' "N9 текст по абзацам"
has "$N/NovelMarkup.kt" 'JS_ONLY' "N10 каркас отличается от разметки"
has "$N/NovelCheck.kt" 'NO_TEXT' "N11 отказ объяснён причиной"
has "$N/NovelStore.kt" 'SUFFIX' "N12 хранилище на диске"
has "$N/NovelSourceId.kt" '7_200_000_000_000' "N13 свой диапазон номеров"
has "$N/NovelForge.kt" 'NovelAutoDetect.catalog' "N14 кузница ищет структуру"
has "$N/NovelForge.kt" 'NovelAutoDetect.text' "N15 текст уточняется по главе"
has "$N/VirtualNovelSource.kt" 'NovelCatalogueSource' "N16 источник для приложения"
has "$N/VirtualNovelSource.kt" 'getChapterText' "N17 текст главы отдаётся"
has "$N/NovelForgeSources.kt" 'fun install' "N18 установка по ссылке"
has "$N/NovelForgeSources.kt" 'NovelStore' "N19 реестр пишет на диск"
has "$A/source/novel/AndroidNovelSourceManager.kt" 'NovelForgeSources' "N20 менеджер видит источник"
has "$A/ui/browse/novel/extension/NovelExtensionsTab.kt" 'NovelForgeScreen' "N21 вход в мастер"
has "$U/NovelForgeScreen.kt" 'NovelForgeScreenModel' "N22 экран сборки"
has "$U/NovelForgeScreenModel.kt" 'NovelForgeSources.install' "N23 экран зовёт реестр"
[ -f "$T/NovelEngineTest.kt" ] && ok "N24 тест разбора" || bad "N24 нет NovelEngineTest"
[ -f "$T/NovelMarkupTest.kt" ] && ok "N25 тест разметки" || bad "N25 нет NovelMarkupTest"
[ -f "$T/NovelCheckTest.kt" ] && ok "N26 тест причин" || bad "N26 нет NovelCheckTest"
[ -f "$T/NovelStoreTest.kt" ] && ok "N27 тест хранилища" || bad "N27 нет NovelStoreTest"
[ -f "$T/NovelAutoDetectTest.kt" ] && ok "N28 тест поиска" || bad "N28 нет NovelAutoDetectTest"
[ -f "$T/VirtualNovelSourceTest.kt" ] && ok "N29 тест обёртки" || bad "N29 нет обёртки теста"

SEL=$(grep -rnE 'Selector *= *"[^"]*[А-Яа-я]' "$N" 2>/dev/null || true)
[ -z "$SEL" ] && ok "N30 селекторы без русских имён" || bad "N30 русский селектор"

TODO=$(grep -rnE 'TODO|FIXME' "$N" 2>/dev/null || true)
[ -z "$TODO" ] && ok "N31 нет TODO в коде" || bad "N31 есть TODO"

LONG=$(grep -rnE '^.{121,}$' --include=*.kt "$N" "$T" 2>/dev/null || true)
[ -z "$LONG" ] && ok "N32 строки не длиннее 120" || bad "N32 длинные строки"

if [ -f reports/novel-forge.md ]; then
    SIZE=$(wc -c <reports/novel-forge.md)
    [ "$SIZE" -le 4096 ] && ok "N33 отчёт на месте ($SIZE байт)" || bad "N33 отчёт больше 4 КБ"
else
    bad "N33 нет отчёта reports/novel-forge.md"
fi

if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
    CHANGED=$(git diff --name-only "$BASE"...HEAD 2>/dev/null)
    OUT=$(echo "$CHANGED" | grep -vE '^(app/src/main/java/eu/kanade/tachiyomi/takami/|app/src/test/java/eu/kanade/tachiyomi/takami/|app/src/main/java/eu/kanade/tachiyomi/ui/takami/|app/src/main/java/eu/kanade/tachiyomi/source/(novel|manga)/|app/src/main/java/eu/kanade/tachiyomi/ui/browse/(novel|manga)/|app/src/main/java/eu/kanade/tachiyomi/ui/discovery/|app/src/main/java/eu/kanade/tachiyomi/data/discovery/|data/src/main/|domain/src/main/|i18n/src/|\.github/(scripts|workflows)/|reports/|docs/|scripts/|app/build\.gradle\.kts|gradle/|telemetry-collector/)' | grep -v '^$')
    [ -z "$OUT" ] && ok "N34 чужие файлы не тронуты" || bad "N34 задеты: $(echo "$OUT" | head -3 | tr '\n' ' ')"
    DIFF=$(git diff --numstat "$BASE"...HEAD -- app/src/*/java/eu/kanade/tachiyomi/*/*/novel app/src/main/java/eu/kanade/tachiyomi/takami/novel app/src/test/java/eu/kanade/tachiyomi/takami/novel | awk '{s+=$1+$2} END {print s+0}')
    [ "$DIFF" -le 3000 ] && ok "N35 размер правки ($DIFF строк)" || bad "N35 слишком много: $DIFF"
else
    ok "N34 базовая ветка недоступна"
    ok "N35 базовая ветка недоступна"
fi

bash "$HERE/verify-stage1.sh" "$ROOT" "$BASE" >/tmp/novel-stage1.log 2>&1
G1=$(grep -cE '^\[[A-Za-z0-9.]+\] FAIL' /tmp/novel-stage1.log)
[ "$G1" -eq 0 ] && ok "R гейт первого этапа проходит" || bad "R гейт этапа 1: провалов $G1"

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
[ "$FAIL" -eq 0 ] || { printf 'провалы: %s\n' "${FAILED[*]}"; exit 1; }
