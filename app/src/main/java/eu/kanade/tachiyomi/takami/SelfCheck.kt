package eu.kanade.tachiyomi.takami

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Один шаг самопроверки: то же, что стенд делает на компьютере, только на телефоне. */
data class CheckResult(
    val key: String,
    val title: String,
    val ok: Boolean,
    val note: String = "",
)

/**
 * Самопроверка источника сразу после сборки спеки.
 *
 * Проверяем только то, что зависит от спеки: каталог, вторая страница, карточка,
 * серии, медиа. Приложение, установку и прочие внешние вещи не трогаем.
 */
data class SelfCheckReport(
    val checks: List<CheckResult>,
    val dubbings: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
)

class SelfCheck(private val engine: SpecEngine = SpecEngine()) {

    suspend fun run(spec: SiteSpec): List<CheckResult> = report(spec).checks

    suspend fun report(spec: SiteSpec): SelfCheckReport = withContext(Dispatchers.IO) {
        val results = mutableListOf<CheckResult>()
        var dubbings: List<String> = emptyList()
        var qualities: List<String> = emptyList()

        val (animes, hasNext) = runCatching { engine.catalogue(spec, 1) }
            .getOrElse {
                ParseFailures.noteFailure(spec.host, "catalog", "empty_body", spec.version)
                return@withContext SelfCheckReport(
                    listOf(CheckResult("catalog", "Каталог", false, it.message.orEmpty())),
                )
            }

        val catalogOk = animes.size >= MIN_CATALOG
        if (!catalogOk) ParseFailures.noteFailure(spec.host, "catalog", "no_cards", spec.version)
        results += CheckResult(
            key = "catalog",
            title = "Каталог",
            ok = catalogOk,
            note = animes.size.toString() + " тайтлов",
        )
        if (animes.isEmpty()) return@withContext SelfCheckReport(results)

        if (hasNext) {
            val second = runCatching { engine.catalogue(spec, 2) }.getOrNull()
            val differ = second != null && second.first.isNotEmpty() &&
                second.first.first().url != animes.first().url
            results += CheckResult(
                key = "pagination",
                title = "Вторая страница",
                ok = differ,
                note = when {
                    second == null -> "не открылась"
                    second.first.isEmpty() -> "пустая"
                    !differ -> "та же выдача"
                    else -> second.first.size.toString() + " новых"
                },
            )
        }

        // Проверяем на нескольких тайтлах: у первого может не быть серий.
        var sample = animes.first()
        var details = runCatching { engine.details(spec, sample) }.getOrNull()
        var episodes = runCatching { engine.episodes(spec, sample) }.getOrNull().orEmpty()

        for (candidate in animes.drop(1).take(SAMPLE_TITLES)) {
            if (episodes.isNotEmpty()) break
            val next = runCatching { engine.episodes(spec, candidate) }.getOrNull().orEmpty()
            if (next.size > episodes.size) {
                sample = candidate
                episodes = next
                details = runCatching { engine.details(spec, sample) }.getOrNull()
            }
        }

        val detailsOk = details != null && details.title.isNotBlank() &&
            !details.thumbnail_url.isNullOrBlank()
        if (!detailsOk) ParseFailures.noteFailure(spec.host, "details", "empty_body", spec.version)
        results += CheckResult(
            key = "details",
            title = "Карточка тайтла",
            ok = detailsOk,
            note = details?.title?.take(40).orEmpty(),
        )

        val episodesOk = episodes.isNotEmpty()
        if (!episodesOk) {
            ParseFailures.noteFailure(spec.host, "episodes", "no_episodes", spec.version)
        }
        results += CheckResult(
            key = "episodes",
            title = "Серии",
            ok = episodesOk,
            note = episodes.size.toString() + " шт.",
        )

        val episode = episodes.firstOrNull()
        if (episode != null) {
            val videos = runCatching { engine.videos(spec, episode) }.getOrNull().orEmpty()
            qualities = videos.map { it.quality }.filter { it.isNotBlank() }.distinct()
            val playable = videos.firstOrNull()
            val media = playable != null && looksLikeMedia(engine.probe(playable.videoUrl, episode.url))
            val videoOk = videos.isNotEmpty() && media
            if (!videoOk) {
                val error = if (videos.isEmpty()) "no_links" else "no_video"
                ParseFailures.noteFailure(spec.host, "video", error, spec.version)
            }
            results += CheckResult(
                key = "video",
                title = "Видео",
                ok = videoOk,
                note = when {
                    videos.isEmpty() -> "ссылок нет"
                    !media -> "ссылка не отдаёт поток"
                    else -> videos.size.toString() + " ссылок"
                },
            )
        }

        dubbings = episodes.mapNotNull { ep -> ep.name.substringBefore(" (").takeIf { "(" in ep.name } }
            .distinct()

        SelfCheckReport(results, dubbings, qualities)
    }

    private fun looksLikeMedia(bytes: String): Boolean =
        bytes.contains("#EXTM3U") || bytes.contains("ftyp") || bytes.contains("moov") ||
            bytes.contains("webm", true) || bytes.contains("<MPD")

    companion object {
        private const val MIN_CATALOG = 10
        private const val SAMPLE_TITLES = 4
    }
}
