package eu.kanade.tachiyomi.takami.novel

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Поиск селекторов по самой разметке, без знания сайта.
 *
 * Селектор не угадывается по имени класса, а находится по
 * повторяющейся структуре: одинаковая карточка со ссылкой и
 * картинкой, ссылки с номером главы, блок с абзацами.
 */
object NovelAutoDetect {

    const val MIN_REPEAT = 3

    data class Catalog(
        val itemSelector: String,
        val linkSelector: String,
        val titleSelector: String?,
        val posterSelector: String?,
        val count: Int,
    )

    data class Chapters(val itemSelector: String, val linkSelector: String?, val count: Int)

    data class Text(val containerSelector: String, val paragraphs: Int)

    /**
    * Карточки каталога: ссылка с картинкой в
    * повторяющемся блоке.
     */
    fun catalog(doc: Document): Catalog? {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (link in doc.select("a[href]")) {
            if (!hasImage(link)) continue
            val holder = link.parent() ?: continue
            val signature = signatureOf(holder) ?: continue
            groups.getOrPut(signature) { mutableListOf() }.add(holder)
        }
        val best = groups.entries
            .filter { it.value.size >= MIN_REPEAT }
            .maxByOrNull { it.value.size }
            ?: return null
        val first = best.value.first()
        val link = first.selectFirst("a[href]") ?: return null
        val ownTitle = link.attr("title").isNotBlank()
        val hasHeading = first.selectFirst("h1, h2, h3") != null
        return Catalog(
            itemSelector = best.key,
            linkSelector = "a[href]",
            titleSelector = if (!ownTitle && hasHeading) "h1, h2, h3" else null,
            posterSelector = if (first.selectFirst("img") != null) "img" else null,
            count = best.value.size,
        )
    }

    /**
    * Список глав: повторяющиеся ссылки, в подписи
    * которых есть номер.
     */
    fun chapters(doc: Document): Chapters? {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (link in doc.select("a[href]")) {
            if (!NUMBER.containsMatchIn(link.text())) continue
            val holder = link.parent() ?: continue
            val signature = signatureOf(holder) ?: continue
            groups.getOrPut(signature) { mutableListOf() }.add(holder)
        }
        val best = groups.entries
            .filter { it.value.size >= 2 }
            .maxByOrNull { it.value.size }
            ?: return null
        return Chapters(itemSelector = best.key, linkSelector = null, count = best.value.size)
    }

    /**
     * Текст главы: блок, внутри которого больше всего абзацев.
     *
     * Меню и подвалы отсеиваются сами: в них абзацев нет, а если и
     * есть, блок с настоящим текстом всё равно их длиннее.
     */
    fun text(doc: Document): Text? {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (paragraph in doc.select("p")) {
            val holder = paragraph.parent() ?: continue
            val signature = signatureOf(holder) ?: continue
            groups.getOrPut(signature) { mutableListOf() }.add(holder)
        }
        val best = groups.entries
            .filter { it.value.size >= 2 }
            .maxByOrNull { it.value.size }
            ?: return null
        return Text(containerSelector = best.key, paragraphs = best.value.size)
    }

    private fun signatureOf(element: Element): String? {
        val tag = element.tagName()
        if (tag == "html" || tag == "body") return null
        val cls = element.className().trim().split(Regex("\\s+"))
            .firstOrNull { it.isNotBlank() }
        return if (cls != null) tag + "." + cls else tag
    }

    private fun hasImage(link: Element): Boolean {
        if (link.selectFirst("img") != null) return true
        val parent = link.parent() ?: return false
        return parent.selectFirst("img") != null
    }

    /** Номер главы в подписи: «Глава 12», «Chapter 5», «Том 1 Глава 3». */
    private val NUMBER = Regex("([0-9])")
}
