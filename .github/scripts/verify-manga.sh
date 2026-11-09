#!/usr/bin/env bash
# Гейт манга-генератора: спека, движок, кузница, проверка, тесты, изоляция.
set -uo pipefail

ROOT="${1:-$(pwd)}"
BASE="${2:-origin/main}"
cd "$ROOT" || exit 1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PASS=0
FAIL=0
declare -a FAILED=()
ok() { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); FAILED+=("$1"); printf 'FAIL  %s\n' "$1"; }

M=app/src/main/java/eu/kanade/tachiyomi/takami/manga
T=app/src/test/java/eu/kanade/tachiyomi/takami/manga

has() {
    if [ ! -f "$1" ]; then bad "$3 (нет $1)"; return; fi
    if grep -Eq -- "$2" "$1"; then ok "$3"; else bad "$3 (нет образца в $1)"; fi
}
hasnt() {
    if [ ! -f "$1" ]; then bad "$3 (нет $1)"; return; fi
    if grep -Eq -- "$2" "$1"; then bad "$3 (лишнее в $1)"; else ok "$3"; fi
}

echo "=== манга-генератор ==="

for f in MangaSpec MangaEngine MangaForge MangaCheck; do
    [ -f "$M/$f.kt" ] && ok "M1 файл $f.kt" || bad "M1 нет $f.kt"
done
for f in MangaEngineTest MangaCheckTest; do
    [ -f "$T/$f.kt" ] && ok "M2 проверка $f" || bad "M2 нет $f"
done

has "$M/MangaSpec.kt" 'data class MangaSpec\(' "M3 спека манги"
has "$M/MangaSpec.kt" 'val chapters: ChaptersSpec' "M4 главы описаны"
has "$M/MangaSpec.kt" 'val pages: PagesSpec' "M5 страницы описаны"
has "$M/MangaSpec.kt" 'val version: Int = SPEC_VERSION' "M6 версия схемы общая"

has "$M/MangaEngine.kt" 'fun parseCatalog\(' "M7 каталог разбирается без сети"
has "$M/MangaEngine.kt" 'fun parseDetails\(' "M8 карточка разбирается без сети"
has "$M/MangaEngine.kt" 'fun parseChapters\(' "M9 главы разбираются без сети"
has "$M/MangaEngine.kt" 'fun parsePages\(' "M10 страницы разбираются без сети"
has "$M/MangaEngine.kt" 'fetcher.page\(' "M11 сеть только через Fetcher"
hasnt "$M/MangaEngine.kt" 'okhttp3|HttpURLConnection|jsoup.connect' "M12 своего клиента нет"
has "$M/MangaEngine.kt" 'dropPatterns' "M13 служебные картинки отсеиваются"
has "$M/MangaEngine.kt" 'maxPages' "M14 предел страниц"

has "$M/MangaForge.kt" 'suspend fun build\(' "M15 сборка по ссылке"
has "$M/MangaForge.kt" 'pickCatalog' "M16 перебор форм карточки"
has "$M/MangaForge.kt" 'data class Failure' "M17 отказ с причиной"
has "$M/MangaCheck.kt" 'NO_CATALOG' "M18 отказ различает шаг"
has "$M/MangaCheck.kt" 'BLOCKED' "M19 закрытый сайт отдельно"

hasnt "$M/MangaSpec.kt" 'okhttp3|Fetcher' "M20 спека без сети"
TODO=$(grep -Rn 'TODO\|FIXME' "$M" 2>/dev/null | wc -l)
[ "$TODO" -eq 0 ] && ok "M21 незавершённых пометок нет" || bad "M21 пометок: $TODO"

LONG=$(python3 - "$M" "$T" <<'LEN'
import sys, pathlib
out = []
for root in sys.argv[1:]:
    for path in pathlib.Path(root).glob("*.kt"):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if len(line) > 120:
                out.append(path.name + ":" + str(n))
print(" ".join(out))
LEN
)
[ -z "$LONG" ] && ok "M22 строки не длиннее 120 знаков" || bad "M22 длинные строки: $LONG"

TABS=$(grep -lP '\t' "$M"/*.kt "$T"/*.kt 2>/dev/null | wc -l)
[ "$TABS" -eq 0 ] && ok "M23 отступы пробелами" || bad "M23 табы в файлах"

has "$M/MangaSpec.kt" 'SPEC_VERSION' "M24 версия схемы на месте"
grep -Rq 'const val SPEC_VERSION = 3' app/src/main/java/eu/kanade/tachiyomi/takami/spec \
    && ok "M25 версия схемы не менялась" || bad "M25 версия схемы изменилась"

SEL=$(grep -RhoE 'Selector = "[^"]*[\u0400-\u04FF][^"]*"' "$M" 2>/dev/null | wc -l)
[ "$SEL" -eq 0 ] && ok "M26 селекторы без русских имён" || bad "M26 русских селекторов: $SEL"

CHANGED=$(git diff --name-only "$BASE"...HEAD 2>/dev/null)
OUT=$(echo "$CHANGED" | grep -vE '^(app/src/main/java/eu/kanade/tachiyomi/takami/manga/|app/src/test/java/eu/kanade/tachiyomi/takami/manga/|\.github/scripts/verify-manga\.sh|reports/manga-forge\.md)' | grep -v '^$')
[ -z "$OUT" ] && ok "M27 чужие файлы не тронуты" || bad "M27 задеты: $(echo "$OUT" | head -3 | tr '\n' ' ')"

if [ -f reports/manga-forge.md ]; then
    SIZE=$(wc -c <reports/manga-forge.md)
    [ "$SIZE" -le 4096 ] && ok "M28 отчёт на месте ($SIZE байт)" || bad "M28 отчёт больше 4 КБ"
else
    bad "M28 нет отчёта reports/manga-forge.md"
fi

bash "$HERE/verify-stage1.sh" "$ROOT" "$BASE" >/tmp/manga-stage1.log 2>&1
G1=$(grep -cE '^\[[A-Za-z0-9.]+\] FAIL|^FAIL ' /tmp/manga-stage1.log)
[ "$G1" -eq 0 ] && ok "R гейт первого этапа проходит" || bad "R гейт первого этапа: провалов $G1"

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
[ "$FAIL" -eq 0 ] || { printf 'провалы: %s\n' "${FAILED[*]}"; exit 1; }
