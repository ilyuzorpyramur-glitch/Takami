#!/usr/bin/env python3
"""Отчёт этапа 7: две оси видео по строкам прогона.

Читает sweep.jsonl (по строке на сайт), список сайтов и пишет отчёт с двумя
таблицами: качество и озвучки. Без данных прогона оси честно «не определена».
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIST = ROOT / "docs" / "takami-33-sites.txt"
OUT = ROOT / "reports" / "stage7-quality-audio.md"
BASE_VIDEO = 0

UNDEF = "не определена"


def load(path):
    rows = {}
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        host = str(row.get("host", "")).strip()
        if host:
            rows[host] = row
    return rows


def axis(row, key):
    if row is None:
        return UNDEF
    value = str(row.get(key, "") or "").strip()
    return value if value else UNDEF


def main():
    sweep = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "sweep.jsonl"
    rows = load(sweep)
    hosts = []
    for line in LIST.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("https://"):
            hosts.append(line.split("://", 1)[1].strip("/").removeprefix("www."))

    both = 0
    one = 0
    quality_rows = []
    dubbing_rows = []
    for host in hosts:
        row = rows.get(host)
        quality = axis(row, "qualities")
        dub = axis(row, "dubbings")
        count = 0 if quality == UNDEF else len([q for q in quality.split(",") if q])
        dubs = 0 if dub == UNDEF else len([d for d in dub.split(",") if d])
        if count >= 2 and dubs >= 2:
            both += 1
        if count >= 1 or dubs >= 1:
            one += 1
        quality_rows.append("| " + host + " | " + quality + " | " + str(count) + " |")
        dubbing_rows.append("| " + host + " | " + dub + " | " + str(dubs) + " |")

    with_video = sum(1 for host in hosts if rows.get(host, {}).get("qualities") or
                     rows.get(host, {}).get("dubbings"))
    text = []
    text.append("# Этап 7: качество и озвучка")
    text.append("")
    text.append("Две оси считаются отдельно: качество и озвучка не смешиваются.")
    text.append("Ось, которую сайт не назвал, помечена как «" + UNDEF + "».")
    text.append("")
    text.append("## Итог")
    text.append("")
    text.append("| показатель | сайтов |")
    text.append("| --- | --- |")
    text.append("| в списке | " + str(len(hosts)) + " |")
    text.append("| строк прогона | " + str(len(rows)) + " |")
    text.append("| хотя бы одна ось | " + str(one) + " |")
    text.append("| качество и озвучка вместе (от двух значений) | " + str(both) + " |")
    text.append("| с любыми ссылками | " + str(with_video) + " |")
    text.append("| этап 6: прямые ссылки на видео | " + str(BASE_VIDEO) + " из 33 |")
    text.append("")
    text.append("## Качество")
    text.append("")
    text.append("| домен | варианты | сколько |")
    text.append("| --- | --- | --- |")
    text.extend(quality_rows)
    text.append("")
    text.append("## Озвучки")
    text.append("")
    text.append("| домен | озвучки | сколько |")
    text.append("| --- | --- | --- |")
    text.extend(dubbing_rows)
    text.append("")
    text.append("Всего доменов: " + str(len(hosts)) + ".")
    OUT.write_text("\n".join(text) + "\n", encoding="utf-8")
    print("отчёт записан:", OUT, len(OUT.read_bytes()), "байт")


if __name__ == "__main__":
    main()
