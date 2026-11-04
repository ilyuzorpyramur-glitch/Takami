package eu.kanade.tachiyomi.takami.video.relay

import eu.kanade.tachiyomi.takami.video.MediaUrl
import org.jsoup.Jsoup

/** Место плеера на странице: номер тайтла и номер слота. */
data class RelaySlot(val animeId: String, val slot: Int, val title: String = "")

/**
 * Реле плеера: страница не содержит кадр плеера, а собирает его скриптом.
 *
 * Скрипт берёт номер тайтла из `data-player-anime-id`, номер слота из
 * `data-player-slot` и открывает служебный адрес, который отвечает
 * адресом самого плеера. Без этого шага в разметке нет ни одного кадра, и
 * обход честно сообщает «ссылок нет».
 */
object PlayerRelay {

    /** Служебный адрес по умолчанию: так его собирает скрипт сайта. */
    const val PATH = "/engine/ajax/controller.php"

    private const val ID = "{id}"

    private const val SLOT = "{slot}"

    private val CONTROLLER = Regex("""["']([^"']*controller\.php)""")

    private val SOURCE = Regex(""""src"\s*:\s*"([^"]+)"""")

    /**
     * Плечи реле со страницы: по одному на слот.
     *
     * Нет `data-player-anime-id` — реле на странице нет, список пуст:
     * придумывать номер тайтла нельзя.
     */
    fun slots(html: String, pageUrl: String): List<RelaySlot> {
        if (html.isBlank() || !html.contains("data-player-anime-id")) return emptyList()
        val doc = runCatching { Jsoup.parse(html, pageUrl) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<RelaySlot>()
        for (root in doc.select("[data-player-anime-id]")) {
            val animeId = root.attr("data-player-anime-id").trim()
            if (animeId.isEmpty()) continue
            val nested = root.select("[data-player-slot]")
            if (nested.isEmpty()) {
                result += RelaySlot(animeId = animeId, slot = 0)
                continue
            }
            nested.forEachIndexed { index, node ->
                val slot = node.attr("data-player-slot").trim().toIntOrNull() ?: index
                result += RelaySlot(
                    animeId = animeId,
                    slot = slot,
                    title = node.attr("data-player-title").trim(),
                )
            }
        }
        return result.distinctBy { it.animeId + "/" + it.slot }
    }

    /**
     * Адреса служебных запросов: подстановка номера тайтла и слота.
     *
     * Путь берём со страницы, если он там есть: у части сайтов служебный
     * адрес лежит в другой папке. Иначе — общий путь движка.
     */
    fun endpoints(html: String, pageUrl: String): List<String> {
        val slots = slots(html, pageUrl)
        if (slots.isEmpty()) return emptyList()
        val path = controllerPath(html).ifEmpty { PATH }
        val root = MediaUrl.normalize(path, pageUrl)
        if (root.isEmpty()) return emptyList()
        return slots.map { slot ->
            root + "?mod=player&id=" + slot.animeId + "&slot=" + slot.slot
        }.distinct()
    }

    /** Путь служебного адреса со страницы; пусто — на странице его нет. */
    fun controllerPath(html: String): String {
        val found = CONTROLLER.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (found.isEmpty()) return ""
        return found.substringBefore('?')
    }

    /**
     * Адрес плеера из ответа реле.
     *
     * Ответ — короткий JSON: имя плеера и адрес кадра. Берём только
     * абсолютный адрес: относительный без страницы не открыть.
     */
    fun playerOf(response: String): String? {
        if (response.isBlank() || response.length > LIMIT) return null
        val raw = SOURCE.find(response)?.groupValues?.get(1) ?: return null
        val url = MediaUrl.normalize(raw)
        return url.takeIf { it.isNotEmpty() }
    }

    /** Ответ реле короткий: длинный текст — это страница, а не ответ. */
    private const val LIMIT = 4096

    /** Подстановка в шаблон: нужна тем, кто собирает адрес сам. */
    fun fill(template: String, slot: RelaySlot): String = template
        .replace(ID, slot.animeId)
        .replace(SLOT, slot.slot.toString())
}
