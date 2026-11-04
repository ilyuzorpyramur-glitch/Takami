package eu.kanade.tachiyomi.data.discovery

/**
 * Приведение оценки провайдера к одной шкале 0..10.
 *
 * Ноль и отсутствие значения одинаково значат «оценки нет»: подставлять
 * ноль или чужое среднее нельзя, карточка покажет прочерк.
 */
object RatingScale {

    fun fromTen(raw: Double?): Double? = raw?.takeIf { it > 0.0 && it <= 10.0 }

    fun fromHundred(raw: Double?): Double? = raw?.takeIf { it > 0.0 && it <= 100.0 }?.div(10.0)
}
