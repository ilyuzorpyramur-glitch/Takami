#!/usr/bin/env bash
# Гейт свайп-подбора: экран, стек, данные карточки, «посмотреть позже», изоляция.
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

MAIN=app/src/main/java/eu/kanade/tachiyomi
UI="$MAIN/ui/discovery"
HOME="$MAIN/ui/home"

has() {
    if [ ! -f "$1" ]; then bad "$3 (нет $1)"; return; fi
    if grep -Eq -- "$2" "$1"; then ok "$3"; else bad "$3 (нет образца в $1)"; fi
}

hasnt() {
    if [ ! -f "$1" ]; then bad "$3 (нет $1)"; return; fi
    if grep -Eq -- "$2" "$1"; then bad "$3 (лишнее в $1)"; else ok "$3"; fi
}

echo "=== свайп-подбор ==="

[ -f "$UI/SwipeDiscoveryScreen.kt" ] && ok "W1a экран на месте" || bad "W1a нет экрана"
[ -f "$UI/SwipeDiscoveryScreenModel.kt" ] && ok "W1b модель на месте" || bad "W1b нет модели"
has "$HOME/HomeHubScreenSections.kt" 'key = "home_swipe_entry"' "W1c вход на главной"
SW=$(grep -n 'key = "home_swipe_entry"' "$HOME/HomeHubScreenSections.kt" | head -1 | cut -d: -f1)
SB=$(grep -n 'key = "home_search_bar"' "$HOME/HomeHubScreenSections.kt" | head -1 | cut -d: -f1)
if [ -n "$SW" ] && [ -n "$SB" ] && [ "$SW" -lt "$SB" ]; then
    ok "W1d вход выше строки поиска"
else
    bad "W1d вход не выше строки поиска"
fi
TABS=$(grep -c "onForYouMoreClick = { navigator.push(DiscoveryFeedScreen" "$HOME/HomeHubScreenSections.kt")
[ "$TABS" -ge 3 ] && ok "W1e старые входы трёх вкладок целы" || bad "W1e старых входов $TABS"

hasnt "$UI/SwipeDiscoveryScreenModel.kt" 'OkHttp|okhttp3|fetch\(' "W2a нет своего сетевого пути"
has "$UI/SwipeDiscoveryScreenModel.kt" 'repository.subscribe' "W2b подписка на репозиторий"

has "$MAIN/data/discovery/DiscoveryStackSelector.kt" 'object DiscoveryStackSelector' "W3a селектор есть"
has "$HOME/HomeDiscoveryController.kt" 'DiscoveryStackSelector.select' "W3b карусель через селектор"
has "$UI/DiscoveryFeedScreenModel.kt" 'DiscoveryStackSelector.select' "W3c лента через селектор"
hasnt "$MAIN/data/discovery/DiscoveryFillAudit.kt" 'DiscoveryStackSelector' "W3d аудит не тронут"

[ -f data/src/main/sqldelight/migrations/63.sqm ] && ok "W4a миграция 63 есть" || bad "W4a нет 63.sqm"
has data/src/main/sqldelight/migrations/63.sqm 'ADD COLUMN rating REAL' "W4b колонка rating"
has data/src/main/sqldelight/migrations/63.sqm 'ADD COLUMN episodes INTEGER' "W4c колонка episodes"
has data/src/main/sqldelight/migrations/63.sqm 'ADD COLUMN status TEXT' "W4d колонка status"
has data/src/main/sqldelight/data/discovery_suggestions.sq 'rating, episodes, status' "W4e вставка пишет три колонки"
has data/src/main/java/tachiyomi/data/discovery/DiscoveryRepositoryImpl.kt 'rating = row.rating' "W4f маппер читает оценку"

hasnt data/src/main/sqldelight/migrations/63.sqm 'score' "W5a score не тронут"
has domain/src/main/java/tachiyomi/domain/discovery/model/DiscoverySuggestion.kt 'val rating: Double' "W5b оценка отдельным полем"
has domain/src/main/java/tachiyomi/domain/discovery/model/DiscoverySuggestion.kt 'val score: Double' "W5c score на месте"

hasnt "$UI/SwipeCard.kt" 'по [0-9N] источник' "W6 разброса оценок не обещаем"

has i18n/src/commonMain/moko-resources/base/strings.xml 'name="swipe_title"' "W7a строки в base"
has i18n/src/commonMain/moko-resources/ru/strings.xml 'name="swipe_title"' "W7b строки в ru"
CYR=$(python3 - "$UI" "$HOME" <<'CYRCHECK'
import sys, pathlib, re
bad = []
roots = [pathlib.Path(root) for root in sys.argv[1:]]
files = []
for root in roots:
    files.extend(sorted(root.glob("Swipe*.kt")))
files.append(pathlib.Path(sys.argv[2]) / "HomeSwipeEntryCard.kt")
pattern = re.compile("[\u0400-\u04FF]")
for path in files:
    if not path.is_file():
        continue
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    for n, line in enumerate(text.splitlines(), 1):
        if pattern.search(line):
            bad.append(path.name + ":" + str(n))
print(" ".join(bad))
CYRCHECK
)
if [ -z "$CYR" ]; then
    ok "W7c русских литералов в Compose нет"
else
    bad "W7c русские литералы в Compose: $CYR"
fi

LONG=$(python3 - "$UI" "$HOME" <<'LEN'
import sys, pathlib
out = []
for root in sys.argv[1:]:
    for path in pathlib.Path(root).glob("Swipe*.kt"):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if len(line) > 120:
                out.append(path.name + ":" + str(n))
path = pathlib.Path(sys.argv[2]) / "HomeSwipeEntryCard.kt"
if path.is_file():
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if len(line) > 120:
            out.append(path.name + ":" + str(n))
print(" ".join(out))
LEN
)
[ -z "$LONG" ] && ok "W8a строки не длиннее 120 знаков" || bad "W8a длинные строки: $LONG"
TABS2=$(grep -lP '\t' "$UI"/Swipe*.kt "$HOME"/HomeSwipeEntryCard.kt 2>/dev/null | wc -l)
[ "$TABS2" -eq 0 ] && ok "W8b отступы пробелами" || bad "W8b табы в новых файлах"

if grep -RniE 'cookiemanager|authorization|api_key|ghp_' "$UI"/Swipe*.kt "$HOME"/HomeSwipeEntryCard.kt 2>/dev/null |
    head -2 | grep -q .; then
    bad "W9 похоже на служебные данные"
else
    ok "W9 ключей и служебных данных нет"
fi

if [ -f reports/swipe-v1.md ]; then
    SIZE=$(wc -c <reports/swipe-v1.md)
    [ "$SIZE" -le 4096 ] && ok "W10 отчёт на месте ($SIZE байт)" || bad "W10 отчёт больше 4 КБ"
else
    bad "W10 нет отчёта reports/swipe-v1.md"
fi

DIFF=$(git diff --name-only "$BASE" 2>/dev/null)
if echo "$DIFF" | grep -qE 'takami/news/|NewsSpec|HomeNewsSection|ui/news/|stage12-news|verify-stage12'; then
    bad "W11a чужая фича задета"
else
    ok "W11a файлов второй фичи нет"
fi
[ -f data/src/main/sqldelight/migrations/64.sqm ] && bad "W11b создана миграция 64" || ok "W11b миграция только 63"
if echo "$DIFF" | grep -q 'news_'; then bad "W11c ключи news_* в диффе"; else ok "W11c ключей news_* нет"; fi

for gate in verify-stage11.sh verify-stage10.sh; do
    if [ ! -f "$HERE/$gate" ]; then bad "R $gate отсутствует"; continue; fi
    bash "$HERE/$gate" "$ROOT" "$BASE" >"/tmp/swipe-$gate.log" 2>&1
    OTHER=$(grep -E '^\[[A-Za-z0-9]+\] FAIL' "/tmp/swipe-$gate.log" | grep -v '\[H14b\]' | wc -l)
    OTHER=$((OTHER + $(grep -cE '^FAIL ' "/tmp/swipe-$gate.log")))
    if [ "$OTHER" -eq 0 ]; then ok "R $gate: только [H14b]"; else bad "R $gate: провалов $OTHER"; fi
done

echo
echo "ИТОГ: $PASS PASS, $FAIL FAIL"
[ "$FAIL" -eq 0 ] || { printf 'провалы: %s\n' "${FAILED[*]}"; exit 1; }
