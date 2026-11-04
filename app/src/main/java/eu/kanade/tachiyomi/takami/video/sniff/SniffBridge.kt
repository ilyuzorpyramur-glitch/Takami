package eu.kanade.tachiyomi.takami.video.sniff

/**
 * Точка подключения снимка.
 *
 * Сам разбор не знает про окно: вызывающий код подставляет снимок, когда
 * человек открыл экран. Нет подстановки — снимок просто не делается, и это
 * видно по причине остановки.
 */
object SniffBridge {

    @Volatile
    private var capture: (suspend (SniffRequest) -> SniffResult)? = null

    fun attach(block: suspend (SniffRequest) -> SniffResult) {
        capture = block
    }

    fun detach() {
        capture = null
    }

    fun ready(): Boolean = capture != null

    /** Снимок или честный отказ, если окно не подключено. */
    suspend fun run(request: SniffRequest): SniffResult =
        capture?.invoke(request) ?: SniffResult(stopReason = SniffStop.UNAVAILABLE)
}
