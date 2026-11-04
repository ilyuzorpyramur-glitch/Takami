package eu.kanade.tachiyomi.takami.video.kodik

/**
 * Слепок живого плеера для проверок без сети.
 *
 * Взяты только нужные узлы страницы: подпись запроса, данные видео и три
 * списка. Разметка и значения — как у плеера, ничего не придумано.
 * Длинные строки разбиты на куски: предел длины строки общий для проекта.
 */
object KodikFixture {

    /** Подпись запроса со страницы плеера. */
    private val params = listOf(
        "{\"d\":\"jutsu.tv\",\"d_sign\":\"6f7bbe877a640880c8b33ad8430701445195fc3c238f39d627f0dc570c5cef6b:26110",
        "61127\",\"pd\":\"kodikplayer.com\",\"pd_sign\":\"7901048749abf0aabce74cfa0a6bdb0feb93d11c87a5660ae949766",
        "6fe5a9728:2611061127\",\"ref\":\"https%3A%2F%2Fjutsu.tv%2F\",\"ref_sign\":\"f528d3ff96cc6b3f67278059256b",
        "56c7f363115431eb2adf41cafdda69135544:2611061127\",\"advert_debug\":true,\"min_age\":18,\"first_url\":fa",
        "lse}",
    ).joinToString("")

    /** Данные видео: тип, хеш и номер открытой серии. */
    private val video = listOf(
        "vInfo.type = 'seria';",
        "vInfo.hash = '9d438155136ff6eab595950b25e97d42';",
        "vInfo.id = '1672819';",
    ).joinToString("\n")

    /** Три списка страницы: сезоны, серии и переводы. */
    private val lists = """
<div class="serial-seasons-box">
<select>
            <option
              value="2"
              data-serial-id="78325"
              data-serial-hash="e7d07584d2fd7049c9c9e78f9731158b"
              data-title="2 сезон"
                selected="selected"
              data-other-translation="false"
                data-translation-title="AnimeVost"
            >2 сезон</option>
        </select>
</div>
<div class="serial-series-box">
<select>
            <option
              value="1"
              data-id="1672819"
              data-hash="9d438155136ff6eab595950b25e97d42"
              data-title="1 серия"
                selected="selected"
              data-other-translation="false"
            >1 серия</option>
            <option
              value="2"
              data-id="1675494"
              data-hash="0aabe7f249488a525dbdddcaaa208ee8"
              data-title="2 серия"
              data-other-translation="false"
            >2 серия</option>
            <option
              value="3"
              data-id="1678500"
              data-hash="8ea827ee85bb1c6a6e4563472a99281c"
              data-title="3 серия"
              data-other-translation="false"
            >3 серия</option>
            <option
              value="4"
              data-id="1681154"
              data-hash="0bcfe35a3e1e54cc1d1d930386dd7469"
              data-title="4 серия"
              data-other-translation="false"
            >4 серия</option>
            <option
              value="5"
              data-id="1684499"
              data-hash="809653e9a17c3614d5f8338811653c70"
              data-title="5 серия"
              data-other-translation="false"
            >5 серия</option>
            <option
              value="6"
              data-id="1687694"
              data-hash="d8b8c64a5a1a887cc538596b85289607"
              data-title="6 серия"
              data-other-translation="false"
            >6 серия</option>
        </select>
</div>
<div class="serial-translations-box">
<select>
              <option
                value="910"
                data-id="910"
                data-translation-type="voice"
                data-media-id="78329"
                data-media-hash="a2e351f3355e193cb4b571af7f2ff887"
                data-media-type="serial"
                data-title="AniStar"
                data-episode-count="6"
              >AniStar (6 эп.)</option>
              <option
                value="923"
                data-id="923"
                data-translation-type="voice"
                data-media-id="78325"
                data-media-hash="e7d07584d2fd7049c9c9e78f9731158b"
                data-media-type="serial"
                data-title="AnimeVost"
                data-episode-count="6"
                  selected="selected"
              >AnimeVost (6 эп.)</option>
              <option
                value="3957"
                data-id="3957"
                data-translation-type="voice"
                data-media-id="78326"
                data-media-hash="7956c07e15055d061fa82e76dc12fd20"
                data-media-type="serial"
                data-title="Flowers Media"
                data-episode-count="6"
              >Flowers Media (6 эп.)</option>
              <option
                value="609"
                data-id="609"
                data-translation-type="voice"
                data-media-id="78355"
                data-media-hash="9b02c12f2e7c9e4b512d382b32f83d5a"
                data-media-type="serial"
                data-title="AniDUB"
                data-episode-count="5"
              >AniDUB (5 эп.)</option>
              <option
                value="3861"
                data-id="3861"
                data-translation-type="voice"
                data-media-id="78381"
                data-media-hash="4f3a8dd1d2f05a9313c4841d5ca51a54"
                data-media-type="serial"
                data-title="AniLiberty"
                data-episode-count="5"
              >AniLiberty (5 эп.)</option>
              <option
                value="1978"
                data-id="1978"
                data-translation-type="voice"
                data-media-id="78359"
                data-media-hash="7f5141a191e2a2886cd8dbf2a5c35047"
                data-media-type="serial"
                data-title="Dream Cast"
                data-episode-count="5"
              >Dream Cast (5 эп.)</option>
              <option
                value="4136"
                data-id="4136"
                data-translation-type="voice"
                data-media-id="78848"
                data-media-hash="174bd3dae14daea61d15a11333b70c4d"
                data-media-type="serial"
                data-title="Закадровый Отдел"
                data-episode-count="5"
              >Закадровый Отдел (5 эп.)</option>
              <option
                value="557"
                data-id="557"
                data-translation-type="voice"
                data-media-id="78675"
                data-media-hash="4ace155bf0d5c47b8671514d28a29164"
                data-media-type="serial"
                data-title="JAM"
                data-episode-count="3"
              >JAM (3 эп.)</option>
          </select>
</div>
    """.trimIndent()

    /** Страница плеера целиком. */
    val pageHtml: String = "<html><head><script>\nvar urlParams = '{" + params + "}';\n" +
        video + "\n</script></head><body>\n" + lists + "\n</body></html>"

    /** Настоящий закодированный адрес из ответа плеера. */
    val encoded: String = listOf(
        "iPZ0kPU6Tg9eUBQck29aj2ZrHO4cG29bT3UdjA9pANQeG0pVVsf5WExqZhsfEsZRlEUgWFVqUtRyEsk0lDsgWFZUU1H6EtpS",
        "UEVPmPHHD1R6BPxvUM5MHOfWDOk0BNlVUNtCDFZWZ1sgBvseVM1CAu1UDND6EtZRUM9Mi3pVDMM0EukdHrC5HuU0U2Q4HLY5",
        "HrGgWBk0UrVsHrC0Uro2VLk5WEZqHrGgGhYfVuY2HEGeUrC3V2GgVOUhWEGgUuZrHOYgVBwgULQ2UBMeVBI0ThkgUK5bkLY6",
        "iOfhWu1pjutuHFV0Tu0hlBo",
    ).joinToString("")

    /** Ответ плеера: три качества, один и тот же адрес. */
    val ftorJson: String = "{\"links\":{" +
        "\"360\":[{\"src\":\"" + encoded + "\",\"type\":\"application/x-mpegURL\"}]," +
        "\"480\":[{\"src\":\"" + encoded + "\",\"type\":\"application/x-mpegURL\"}]," +
        "\"720\":[{\"src\":\"" + encoded + "\",\"type\":\"application/x-mpegURL\"}]}," +
        "\"default\":360}"
}
