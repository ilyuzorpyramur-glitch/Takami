#!/usr/bin/env bash
# Гейт этапа 11: слой шахты (данные страницы) и снимок сети (окно).
# Проверяет только то, что видно без запуска: код, тесты, документы.
set -uo pipefail

ROOT=${1:-.}
BASE=${2:-origin/main}
cd "$ROOT" || exit 1

PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL  %s\n' "$1"; }

MAIN=app/src/main/java/eu/kanade/tachiyomi/takami
TEST=app/src/test/java/eu/kanade/tachiyomi/takami

# Файл существует и содержит образец.
has() {
    if [ ! -f "$1" ]; then bad "$3 (нет файла $1)"; return; fi
    if grep -Eq -- "$2" "$1"; then ok "$3"; else bad "$3 (нет образца $2 в $1)"; fi
}

# Файл существует и НЕ содержит образец.
hasnt() {
    if [ ! -f "$1" ]; then bad "$3 (нет файла $1)"; return; fi
    if grep -Eq -- "$2" "$1"; then bad "$3 (лишнее $2 в $1)"; else ok "$3"; fi
}

echo "=== этап 11: шахта конфига и снимок сети ==="

# --- R: прошлые этапы не сломаны ---
if [ -x .github/scripts/verify-stage10.sh ] || [ -f .github/scripts/verify-stage10.sh ]; then
    bash .github/scripts/verify-stage10.sh "$ROOT" "$BASE" >/tmp/g11-stage10.log 2>&1
    # Единственный допустимый провал: этап 11 обязан править общие файлы движка.
    OTHER=$(grep -E '^\[[A-Za-z0-9]+\] FAIL' /tmp/g11-stage10.log | grep -v '\[H14b\]' | wc -l)
    if [ "$OTHER" -eq 0 ]; then
        ok "R1 гейт этапа 10: провалов нет, кроме правки общих файлов"
    else
        bad "R1 гейт этапа 10: провалов $OTHER (см. /tmp/g11-stage10.log)"
    fi
else
    bad "R1 нет гейта этапа 10"
fi

if [ -f .github/scripts/verify-stage9.sh ]; then
    if bash .github/scripts/verify-stage9.sh "$ROOT" "$BASE" >/tmp/g11-stage9.log 2>&1; then
        ok "R2 гейт этапа 9 проходит"
    else
        bad "R2 гейт этапа 9 не проходит (см. /tmp/g11-stage9.log)"
    fi
else
    bad "R2 нет гейта этапа 9"
fi

# --- H: заголовки доезжают до ссылки ---
has "$MAIN/video/Quality.kt" 'val headers: Map<String, String> = emptyMap\(\)' "H1 у ссылки есть заголовки"
has "$MAIN/video/Quality.kt" 'fun byUrl\(' "H2 повторы склеиваются по адресу"
has "$MAIN/video/Quality.kt" 'seen.headers.isNotEmpty\(\)' "H3 непустые заголовки побеждают"
has "$MAIN/video/VideoStream.kt" 'Dubbing.NONE' "H4 подпись озвучки на месте"
has "$MAIN/video/VideoStream.kt" 'headers = link.headers' "H5 поток переносит заголовки"
has "$MAIN/video/Hoster.kt" 'Quality.byUrl\(links\).distinctBy \{ it.url \}' "H6 цепочка склеивает по адресу"
has "$TEST/video/HeadersTest.kt" 'fun .*заголовк' "H7 есть проверка заголовков"

# --- C: шахта конфига ---
has "$MAIN/video/config/PlaylistPick.kt" 'fun pick\(urls: List<String>, pageUrl: String' "C1 отбор из плейлиста"
has "$MAIN/video/config/PlaylistPick.kt" 'if \(number <= 0\) return emptyList\(\)' "C2 без номера — пусто"
has "$MAIN/video/config/MediaMiner.kt" 'fun mine\(html: String, pageUrl: String, spec: VideoSpec\)' "C3 шахта данных страницы"
has "$MAIN/video/config/MediaMiner.kt" '#EXT-X-STREAM-INF' "C4 мастер HLS распознаётся"
hasnt "$MAIN/video/config/MediaMiner.kt" 'okhttp3|Fetcher|HttpURLConnection' "C5 шахта без сети"
hasnt "$MAIN/video/config/PlaylistPick.kt" 'okhttp3|Fetcher|HttpURLConnection' "C6 отбор без сети"
has "$TEST/video/config/MediaMinerTest.kt" '@Test' "C7 проверки шахты"
has "$TEST/video/config/PlaylistPickTest.kt" '@Test' "C8 проверки отбора"

# --- N: снимок сети ---
has "$MAIN/SiteSpec.kt" 'data class SniffSpec\(' "N1 снимок описан в спеке"
has "$MAIN/SiteSpec.kt" 'val sniff: SniffSpec = SniffSpec\(\)' "N2 снимок привязан к видео"
has "$MAIN/SiteSpec.kt" 'MODE_FALLBACK = "fallback"' "N3 режимы снимка"
has "$MAIN/video/sniff/SniffModels.kt" 'class SnifferState' "N4 решения по времени отделены"
has "$MAIN/video/sniff/SniffModels.kt" 'fun settled\(at: Long\)' "N5 «дозор» после находки"
has "$MAIN/video/sniff/MediaFilter.kt" 'fun keep\(hits: List<SniffHit>, spec: SniffSpec' "N6 отбор пойманного"
has "$MAIN/video/sniff/MediaFilter.kt" 'DROP_EXT = listOf\("\.ts", "\.m4s"' "N7 сегменты не берём"
has "$MAIN/video/sniff/MediaFilter.kt" '"blob:", "data:"' "N8 blob и data не берём"
has "$MAIN/video/sniff/SniffMemo.kt" 'fun allowed\(host: String, layer: String\)' "N9 память домена"
has "$MAIN/video/sniff/SniffMemo.kt" 'DAY_MS' "N10 сутки паузы после двух неудач"
has "$MAIN/video/sniff/SniffPolicy.kt" 'dreamerscast.com' "N11 домен только для чтения"
has "$MAIN/video/sniff/SniffPolicy.kt" 'MODE_NEVER\) return false' "N12 запрет уважается"
has "$MAIN/video/sniff/SniffVerdict.kt" 'const val UNAVAILABLE' "N13 отказ вместо нуля"
has "$MAIN/video/sniff/SniffVerdict.kt" 'SniffStop.DRM -> DRM' "N14 DRM отдельным итогом"
has "$MAIN/video/sniff/SniffBridge.kt" 'SniffStop.UNAVAILABLE' "N15 без окна — недоступно"
has "$MAIN/video/sniff/MediaSniffer.kt" 'shouldInterceptRequest' "N16 окно ловит запросы"
has "$MAIN/video/sniff/MediaSniffer.kt" 'view.destroy\(\)' "N17 окно снимается"
has "$MAIN/video/sniff/MediaSniffer.kt" 'withTimeoutOrNull' "N18 срок снимка"
has "$MAIN/video/sniff/MediaSniffer.kt" 'MAX_URL = 2_048' "N19 длинные адреса не берём"
hasnt "$MAIN/video/sniff/MediaSniffer.kt" 'setJavaScriptEnabled\(true\)' "N20 JS страницы не включаем"
has "$MAIN/video/sniff/LinkHeaders.kt" 'fun okhttp\(headers: Map<String, String>\): Headers\?' "N21 заголовки для сети"
has "$MAIN/SpecEngine.kt" 'SniffBridge' "N22 движок спрашивает снимок"
has "$MAIN/SpecEngine.kt" 'SniffPolicy.canSniff' "N23 решение по правилу"
has "$MAIN/SpecEngine.kt" 'fetcher.isPaused' "N24 пауза домена учитывается"
has "$MAIN/SelfCheck.kt" 'SniffVerdict.text' "N25 проверка показывает слой"
has "$MAIN/SelfCheck.kt" 'player_blocked' "N26 причина отказа в отчёте"
has "$MAIN/fetch/Fetcher.kt" 'fun isPaused\(host: String\)' "N27 сеть знает паузу домена"

# --- O: границы ---
hasnt "$MAIN/video/sniff/MediaSniffer.kt" 'okhttp3' "O1 окно без своего клиента"
hasnt "$MAIN/video/sniff/SniffPolicy.kt" 'fun .*setAllowed|fun .*bypass' "O2 обхода защиты нет"
hasnt "$MAIN/video/sniff/SniffMemo.kt" 'https\?://|url' "O3 в памяти нет адресов"
hasnt "$MAIN/video/sniff/MediaSniffer.kt" 'addJavascriptInterface\([^,]*, "takami"' "O4 мост один и именован"
has "$MAIN/video/sniff/MediaSniffer.kt" 'addJavascriptInterface' "O5 мост к окну есть"

# --- V: проверки ---
for f in MediaFilterTest SniffMemoTest SniffPolicyTest SniffVerdictTest SnifferStateTest; do
    has "$TEST/video/sniff/$f.kt" '@Test' "V $f"
done
has "$TEST/RegexPortableTest.kt" '@Test' "V6 проверка регулярок на месте"
has "$MAIN/../takami/spec/SpecVersion.kt" 'const val SPEC_VERSION = 3' "V7 версия схемы не менялась" 2>/dev/null || true
if grep -Rq 'const val SPEC_VERSION = 3' app/src/main/java/eu/kanade/tachiyomi/takami/spec 2>/dev/null; then
    ok "V7 версия схемы не менялась"
else
    bad "V7 версия схемы не 3"
fi

# --- K: стиль ---
TABS=$(grep -RlP '\t' "$MAIN/video/config" "$MAIN/video/sniff" 2>/dev/null | wc -l)
if [ "$TABS" -eq 0 ]; then ok "K1 отступы пробелами"; else bad "K1 табы в новых файлах: $TABS"; fi

LONG=$(python3 - "$MAIN" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
bad = []
for path in list((root / "video" / "config").glob("*.kt")) + list((root / "video" / "sniff").glob("*.kt")):
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if len(line) > 120:
            bad.append(f"{path.name}:{n}")
print(" ".join(bad))
PY
)
if [ -z "$LONG" ]; then ok "K2 строки не длиннее 120 знаков"; else bad "K2 длинные строки: $LONG"; fi

TODO=$(grep -Rn 'TODO\|FIXME' "$MAIN/video/config" "$MAIN/video/sniff" 2>/dev/null | wc -l)
if [ "$TODO" -eq 0 ]; then ok "K3 незавершённых пометок нет"; else bad "K3 пометок TODO: $TODO"; fi

SECRETS=$(grep -RniE 'api[_-]?key|secret|bearer [a-z0-9]{10,}|ghp_[a-z0-9]{10,}' \
    "$MAIN/video/config" "$MAIN/video/sniff" .github/stage11-sniff-protocol.md 2>/dev/null | wc -l)
if [ "$SECRETS" -eq 0 ]; then ok "K4 ключей и токенов нет"; else bad "K4 похоже на ключи: $SECRETS"; fi

# --- P: документы ---
if [ -f reports/stage11-sniff.md ]; then
    SIZE=$(wc -c <reports/stage11-sniff.md)
    if [ "$SIZE" -le 4096 ]; then ok "P1 отчёт на месте ($SIZE байт)"; else bad "P1 отчёт больше 4 КБ ($SIZE)"; fi
else
    bad "P1 нет отчёта reports/stage11-sniff.md"
fi
has ".github/stage11-sniff-protocol.md" 'Живой прогон' "P2 протокол живого прогона"

echo
echo "ИТОГ: PASS $PASS, FAIL $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
