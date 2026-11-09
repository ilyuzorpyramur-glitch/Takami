package eu.kanade.tachiyomi.takami.manga

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Поиск селекторов по самой разметке, без знания сайта.
 *
 * Генератор не может держать список сайтов: вёрстка меняется, сайтов
 * сотни. Поэтому селектор не угадывается по имени класса, а находится
 * по повторяющейся структуре: одинаковая карточка, внутри ссылка с
 * картинкой.
 */
object MangaAutoDetect {

    /** Сколько повторов нужно, чтобы считаться списком, а не меню. */
    const val MIN_REPEAT = 3

    data class Catalog(
        val itemSelector: String,
        val linkSelector: String,
        val titleSelector: String?,
        val posterSelector: String?,
        val count: Int,
    )

    data class Chapters(val itemSelector: String, val linkSelector: String?, val count: Int)

    data class Pages(val containerSelector: String?, val itemSelector: String, val count: Int)

    /**
     * Контейнер карточки каталога: ссылка с картинкой внутри одного блока.
     *
     * Меню и хлебные крошки отсеиваются сами: в них нет картинки рядом
     * со ссылкой, и повторов нужного вида не набирается.
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
        val title = link.attr("title").takeIf { it.isNotBlank() }
            ?: first.selectFirst("h1, h2, h3, .title, .name")?.text()?.takeIf { it.isNotBlank() }
        val poster = first.selectFirst("img")?.let { "img" }
        return Catalog(
            itemSelector = best.key,
            linkSelector = "a[href]",
            titleSelector = if (title != null && link.attr("title").isNotBlank()) null else "h1, h2, h3",
            posterSelector = poster,
            count = best.value.size,
        )
    }

    /** Список глав: повторяющиеся ссылки, в подписи которых есть номер. */
    fun chapters(doc: Document): Chapters? {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (link in doc.select("a[href]")) {
            val text = link.text()
            if (!NUMBER.containsMatchIn(text)) continue
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
     * Страницы главы: самая длинная группа картинок с общим родителем.
     *
     * Логотипы и аватары отсеиваются размером группы: их одна-две, а
     * страниц десятки.
     */
    fun pages(doc: Document): Pages? {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (img in doc.select("img")) {
            val holder = img.parent() ?: continue
            val signature = signatureOf(holder) ?: continue
            groups.getOrPut(signature) { mutableListOf() }.add(holder)
        }
        val best = groups.entries
            .filter { it.value.size >= 2 }
            .maxByOrNull { it.value.size }
            ?: return null
        val first = best.value.first()
        val img = first.selectFirst("img") ?: return null
        val shared = sharedClass(best.value.mapNotNull { it.selectFirst("img") })
        return Pages(
            containerSelector = best.key,
            itemSelector = if (shared != null) "img." + shared else "img",
            count = best.value.size,
        )
    }

    /** Подпись блока: тег плюс первый класс. Имя класса — метка, не догадка. */
    private fun signatureOf(element: Element): String? {
        val tag = element.tagName()
        if (tag == "html" || tag == "body") return null
        val cls = element.className().trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
        return if (cls != null) tag + "." + cls else tag
    }

    /** Класс, общий у всех картинок группы: он и станет уточнением. */
    private fun sharedClass(images: List<Element>): String? {
        val first = images.firstOrNull()?.className()?.split(Regex("\\s+"))
            ?.firstOrNull { it.isNotBlank() } ?: return null
        val same = images.all { img -> img.className().split(Regex("\\s+")).contains(first) }
        return if (same) first else null
    }

    private fun hasImage(link: Element): Boolean {
        if (link.selectFirst("img") != null) return true
        val parent = link.parent() ?: return false
        return parent.selectFirst("img") != null
    }

    /** Номер главы в подписи: «Глава 12», «Chapter 5», «Том 1 Глава 3». */
    private val NUMBER = Regex("([0-9])")
}
