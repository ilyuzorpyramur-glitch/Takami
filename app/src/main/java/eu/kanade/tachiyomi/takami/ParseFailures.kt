package eu.kanade.tachiyomi.takami

/**
 * Приёмник провалов разбора.
 *
  * Путь разбора знает только этот интерфейс: домен, шаг и
  * тип ошибки. Кто и
  * куда их складывает, решает сбор отчётов — он
  * подписывается при запуске и
 * снимает подписку вместе со всем сбором после калибровки.
 */
fun interface ParseFailureSink {
    fun onFailure(host: String, step: String, error: String, specVersion: Int)
}

/** Точка, через которую разбор сообщает о провале. */
object ParseFailures {

    @Volatile
    var sink: ParseFailureSink? = null

    /**
     * Провал разбора: домен, шаг и короткий тип ошибки.
     *
      * Ни текста страницы, ни названия тайтла, ни пути здесь
      * нет и быть не
      * может: калибровке нужно понять, что сломалось, а не
      * что там было.
     */
    fun noteFailure(host: String?, step: String, error: String, specVersion: Int = 0) {
        val target = sink ?: return
        val domain = host.orEmpty().trim()
        if (domain.isBlank()) return
        // Сбор не должен ломать разбор: сбой записи — не
        // повод падать.
        runCatching { target.onFailure(domain, step, error, specVersion) }
    }
}
