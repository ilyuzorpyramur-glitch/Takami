package eu.kanade.tachiyomi.takami

import kotlinx.serialization.Serializable

/**
 * Один фильтр источника в спеке: без кода, только данные.
 *
 * Фильтры описываются так же, как остальные поля [SiteSpec]: генератор заполняет
 * их при создании спеки, пользователь правит на экране фильтров.
 *
 * Типы: check — список флажков, select — выпадающий список, tri — трёхпозиционный
 * (включить/исключить/игнорировать), text — свободный ввод, sort — сортировка.
 */
@Serializable
data class FilterSpec(
    /** Название для экрана: «Жанр», «Год», «Статус» — по-русски. */
    val name: String,
    /** check | select | tri | text | sort. */
    val type: String,
    /** Имя параметра в адресе каталога: genre, year, status… */
    val key: String,
    /** Шаблон значения: «{v}», «genre/{v}», «year-{v}». */
    val valueTemplate: String = "{v}",
    /** Подпись → значение; пусто у text. */
    val options: List<FilterOption> = emptyList(),
    /** Сколько значений можно выбрать: 0 — без ограничения, 1 — как radio. */
    val maxSelected: Int = 0,
    /** Значения по умолчанию; пусто — «любое». */
    val default: List<String> = emptyList(),
    /** query — параметр в адресе, path — подстановка в путь. */
    val inUrl: String = "query",
    /** Селектор блока фильтров на странице — чтобы сканер нашёл его снова. */
    val selector: String? = null,
) {

    companion object {
        const val TYPE_CHECK = "check"
        const val TYPE_SELECT = "select"
        const val TYPE_TRI = "tri"
        const val TYPE_TEXT = "text"
        const val TYPE_SORT = "sort"

        const val IN_QUERY = "query"
        const val IN_PATH = "path"
    }
}

/** Один пункт фильтра: подпись для экрана и значение для адреса. */
@Serializable
data class FilterOption(
    val label: String,
    val value: String,
)
