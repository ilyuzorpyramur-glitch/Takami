package eu.kanade.tachiyomi.takami.video

/** Одна озвучка: название и, если сайт показывает, число серий. */
data class DubbingTrack(val name: String, val episodes: Int = 0)

/**
 * Сбор озвучек со страницы.
 *
  * Названия берутся из табов плеера, заголовков «Озвучка:
  * …» и подписей серий.
  * Пустые и служебные подписи («смотреть», «ещё»)
  * отбрасываются: список должен
 * показывать студии, а не навигацию сайта.
 */
object Dubbing {

    /** Что пишем, когда сайт дорожки не различает. */
    const val NONE = "без озвучки"

    /** Сколько дорожек держим: больше на странице не бывает. */
    const val MAX = 20

    private val JUNK = setOf(
        "смотреть", "ещё", "еще", "все", "всё", "показать", "далее", "назад",
        "плеер", "видео", "озвучка", "озвучки", "серии", "серия",
        "поиск", "меню",
    )

    private val SPACES = Regex("""\s+""")

    /**
     * Нормализация имени: без лишних пробелов и с
     * обрезанными скобками. */
    fun normalize(raw: String?): String = (raw ?: "")
        .replace('\n', ' ')
        .replace(SPACES, " ")
        .trim()
        .trim(':', '-', '—', '·', '"', '\'')

    /** Годится ли подпись в название озвучки. */
    fun meaningful(raw: String?): Boolean {
        val name = normalize(raw)
        if (name.length < 2 || name.length > 40) return false
        if (name.lowercase() in JUNK) return false
        // Подпись из одной цифры или из номера серии — не студия.
        if (name.all { it.isDigit() || it in " .-()" }) return false
        return name.any { it.isLetter() }
    }

    /**
     * Имена без повторов, в порядке появления; пустой список
     * — «нет озвучек». */
    fun names(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (item in raw) {
            if (!meaningful(item)) continue
            val name = normalize(item)
            if (seen.add(name.lowercase())) result += name
            if (result.size >= MAX) break
        }
        return result
    }

    /**
     * То же для дорожек с числом серий: повторы складываются
     * по имени. */
    fun tracks(raw: List<DubbingTrack>): List<DubbingTrack> {
        val seen = HashSet<String>()
        val result = mutableListOf<DubbingTrack>()
        for (item in raw) {
            if (!meaningful(item.name)) continue
            val name = normalize(item.name)
            if (seen.add(name.lowercase())) {
                result += DubbingTrack(name, item.episodes)
            } else {
                val index = result.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                if (index >= 0 && item.episodes > result[index].episodes) {
                    result[index] = result[index].copy(episodes = item.episodes)
                }
            }
            if (result.size >= MAX) break
        }
        return result
    }
}
