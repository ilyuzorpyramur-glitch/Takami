package eu.kanade.tachiyomi.takami.news

/**
 * Правила новостей: что разрешено детектить и показывать, какие пороги держать.
 *
 * Всё, что отличается от сайта к сайту и проверено живьём, живёт здесь,
 * а не в разборе. Список лент пуст, пока оператор не подтвердит адреса:
 * лента, отдающая заглушку, помечается мёртвой на неделю.
 */
object NewsPolicy {

    /** Источник только для чтения: домен и ключ спеки, детект не запускается. */
    const val READ_ONLY_DOMAIN = "dreamerscast.com"
    const val READ_ONLY_KEY = "kodikdreamcast"

    /** Не больше двух источников проверяется за одно открытие главной. */
    const val MAX_DETECT_PER_OPENING = 2

    /** Повтор неудачной проверки — не раньше чем через неделю. */
    const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    /** Кэш ленты живёт 45 минут, между обновлениями показываем снимок. */
    const val TTL_MS = 45L * 60 * 1000

    /** Мёртвая лента не опрашивается неделю. */
    const val DEAD_FEED_MS = 7L * 24 * 60 * 60 * 1000

    /** Лент в списке — не больше десяти. */
    const val MAX_FEEDS = 10

    /**
     * Проверенные ленты: домен → путь. Пусто, пока адреса не проверены
     * оператором: выдуманные адреса в код не попадают.
     */
    val feeds: Map<String, String> = emptyMap()

    /** Адрес ленты источника, если он есть в проверенном списке. */
    fun feedFor(host: String): String? = feeds[host.removePrefix("www.")]

    /** Детект запрещён для источника только для чтения. */
    fun canDetect(host: String): Boolean {
        val domain = host.trim().removePrefix("www.").lowercase()
        return domain != READ_ONLY_DOMAIN && READ_ONLY_KEY !in domain
    }

    /** Блок новостей не собирается для источника только для чтения. */
    fun canShow(host: String): Boolean = canDetect(host)

    /** Список лент не раздувается: место только для проверенных. */
    fun canAddFeed(host: String): Boolean =
        feeds.size < MAX_FEEDS && feedFor(host) == null

    /**
     * Пора ли проверять раздел снова.
     *
     * Ни разу не проверяли — пора. Проверяли и не нашли — ждём неделю:
     * иначе каждая главная заново дёргала бы сайт, где новостей нет.
     */
    fun dueForDetect(checkedAt: Long, now: Long): Boolean =
        checkedAt == 0L || now - checkedAt >= RETRY_AFTER_MS

    private val dead = HashMap<String, Long>()

    /** Лента мертва: неделю не опрашивается. */
    fun isDeadFeed(host: String, now: Long): Boolean {
        val since = dead[host] ?: return false
        return now - since < DEAD_FEED_MS
    }

    /** Отметка о мёртвой ленте. */
    fun markDeadFeed(host: String, now: Long) {
        dead[host] = now
    }
}
