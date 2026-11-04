package eu.kanade.tachiyomi.takami.report

import kotlinx.serialization.Serializable

/**
 * Один отчёт о сбое источника.
 *
  * Полей ровно столько, сколько нужно для калибровки.
  * Названий тайтлов,
  * адресов страниц, cookie и признаков устройства здесь нет
  * и быть не может:
 * домен источника, шаг, тип ошибки, состояние, код, время.
 */
@Serializable
data class ReportEvent(
    /** Домен источника без www. */
    val host: String,
    /** Шаг, на котором сорвалось: каталог, карточка, серии, видео. */
    val step: String,
    /** Тип ошибки, а не текст: тексты содержат адреса. */
    val error: String,
    /** Сколько раз этот же сбой повторился внутри окна. */
    val count: Int = 1,
    /** Состояние источника после сбоя. */
    val health: String = "unknown",
    val code: Int? = null,
    val durationMs: Long = 0L,
    val appVersion: String = "",
    val specVersion: Int = 0,
    /**
     * Обезличенный номер установки: хеш, обратно не
     * восстанавливается. */
    val installId: String = "",
    val at: Long = 0L,
)

/** Что видно пользователю про сбор отчётов. */
data class ReportStatus(
    val queued: Int = 0,
    val totalSent: Int = 0,
    val lastSentAt: Long = 0L,
    val enabled: Boolean = true,
    /** Идёт ли сбор по сроку: после конца калибровки false. */
    val active: Boolean = true,
    val deadline: Long = 0L,
)
