package eu.kanade.tachiyomi.takami.fetch

import java.util.concurrent.ConcurrentHashMap

/** Уровень защиты сайта, с которой столкнулся запрос. */
enum class ProtectionLevel {
    /** Обычный ответ: защита не встретилась. */
    NONE,

    /** Проверка браузера: JS ставит cookie. Проходит настоящий WebView. */
    BROWSER_CHECK,

    /** Капча: без человека не пройти. Приложение её не решает. */
    CAPTCHA,

    /** Отказ: 403, 451 или блок по адресу. Повторять нельзя. */
    BLOCKED,
}

/** Что именно встретилось и почему так решили. */
data class ProtectionSignal(
    val level: ProtectionLevel,
    val reason: String = "",
) {
    val passed: Boolean get() = level == ProtectionLevel.NONE
}

/**
 * Разбор ответа на предмет защиты.
 *
 * Проверка браузера распознаётся по заголовкам: `Server`, `cf-mitigated`.
 * Поиск по строкам тела здесь не используется — тело разметки слишком легко
 * спутать с обычной страницей. Капча отличается от проверки браузера: её
 * проходят руками, поэтому и ответ другой.
 */
object Protection {

    /** Сайты, которые ставят JS-проверку перед выдачей страницы. */
    private val CHALLENGE_SERVERS = listOf(
        "cloudflare",
        "ddos-guard",
        "ddosguard",
        "qrator",
        "sucuri",
        "imperva",
    )

    private val CHALLENGE_CODES = setOf(403, 503)

    /** Признаки капчи в разметке: её проходит человек, а не браузер. */
    private val CAPTCHA_MARKERS = listOf(
        "g-recaptcha",
        "recaptcha/api",
        "hcaptcha",
        "h-captcha",
        "cf-turnstile",
        "не робот",
        "not a robot",
    )

    /** Страница капчи короткая: форма, а не выдача с виджетом в подвале. */
    private const val CAPTCHA_PAGE_BYTES = 32_768

    fun of(code: Int, server: String?, mitigated: String?, html: String? = null): ProtectionSignal {
        val serverName = server?.lowercase().orEmpty()
        val antibot = CHALLENGE_SERVERS.any { serverName.contains(it) }

        if (!mitigated.isNullOrBlank()) {
            return ProtectionSignal(
                ProtectionLevel.BROWSER_CHECK,
                "проверка браузера: " + mitigated.trim(),
            )
        }
        if (antibot && code in CHALLENGE_CODES) {
            return ProtectionSignal(ProtectionLevel.BROWSER_CHECK, "проверка браузера: " + serverName)
        }
        if (html != null && html.length <= CAPTCHA_PAGE_BYTES && isCaptcha(html)) {
            return ProtectionSignal(ProtectionLevel.CAPTCHA, "капча")
        }
        if (code in 200..399) return ProtectionSignal(ProtectionLevel.NONE)
        if (code == 403 || code == 451) {
            return ProtectionSignal(ProtectionLevel.BLOCKED, "отказ HTTP " + code)
        }
        return ProtectionSignal(ProtectionLevel.NONE)
    }

    private fun isCaptcha(html: String): Boolean {
        val head = html.lowercase()
        return CAPTCHA_MARKERS.any { head.contains(it) }
    }
}

/** Сайт ждёт человека: капчу или подтверждение. Повторов не будет. */
data class VerificationStatus(
    val host: String,
    val level: ProtectionLevel,
    val reason: String,
    val at: Long,
)

/**
 * Пометки «требует проверки».
 *
 * Живут в памяти на время работы приложения: пока человек не пройдёт проверку,
 * сайт не дёргаем. Запись с истечением — через полчаса имеет смысл попробовать
 * снова, человек мог пройти проверку в браузере.
 */
class ProtectionRegistry(private val ttlMs: Long = FetchLimits.VERIFICATION_TTL_MS) {

    private val hosts = ConcurrentHashMap<String, VerificationStatus>()

    fun mark(host: String, level: ProtectionLevel, reason: String, at: Long) {
        hosts[host] = VerificationStatus(host, level, reason, at)
    }

    fun status(host: String, now: Long = System.currentTimeMillis()): VerificationStatus? {
        val found = hosts[host] ?: return null
        if (now - found.at > ttlMs) {
            hosts.remove(host, found)
            return null
        }
        return found
    }

    fun clear(host: String) {
        hosts.remove(host)
    }

    fun all(now: Long = System.currentTimeMillis()): List<VerificationStatus> =
        hosts.keys.toList().mapNotNull { status(it, now) }.sortedBy { it.host }
}
