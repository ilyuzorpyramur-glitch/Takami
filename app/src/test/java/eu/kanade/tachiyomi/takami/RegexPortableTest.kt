package eu.kanade.tachiyomi.takami

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Регулярки такова должны работать и на JVM, и в движке Android (ICU).
 *
 * ICU строже: шаблон с одинокой закрывающей скобкой JVM молча
 * принимает, а на телефоне класс падает при инициализации — так
 * уронило экран прогона сайтов. Сам ICU здесь недоступен, поэтому
 * проверяем статически: шаблон обязан компилироваться на JVM и не
 * содержать конструкций, которые ICU не переваривает.
 */
class RegexPortableTest {

    @Test
    fun `шаблоны такова переносимы между JVM и Android`() {
        val root = sourceRoot()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(files.size > 5, "исходников слишком мало: ${files.size} в $root")

        val problems = mutableListOf<String>()
        var checked = 0
        files.forEach { file ->
            val patterns = literals(file.readText())
            patterns.forEach { pattern ->
                // Шаблонные строки собираются из кусков — их проверяет компилятор Kotlin.
                if (pattern.isBlank() || pattern.contains('$')) return@forEach
                checked++
                runCatching { Regex(pattern) }.onFailure {
                    problems += "${file.name}: не компилируется ($pattern)"
                }
                icuProblem(pattern)?.let { problems += "${file.name}: $it ($pattern)" }
            }
        }

        assertTrue(checked > 5, "не нашёл шаблонов для проверки: $checked")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** Корень исходников модуля: тесты запускаются из каталога `app/`. */
    private fun sourceRoot(): File {
        val relative = "src/main/java/eu/kanade/tachiyomi/takami"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("не нашёл исходники такова: " + candidates.joinToString { it.absolutePath })
    }

    /** Первый аргумент всех вызовов `Regex("<литерал>"`. */
    private fun literals(text: String): List<String> {
        val result = mutableListOf<String>()
        val marker = "Regex("
        var from = 0
        while (true) {
            val call = text.indexOf(marker, from)
            if (call < 0) return result
            var quoteAt = call + marker.length
            while (quoteAt < text.length && text[quoteAt].isWhitespace()) quoteAt++
            if (quoteAt >= text.length || text[quoteAt] != '"') {
                from = call + marker.length
                continue
            }
            val triple = text.startsWith("\"\"\"", quoteAt)
            val quote = if (triple) "\"\"\"" else "\""
            val sb = StringBuilder()
            var i = quoteAt + quote.length
            while (i < text.length && !text.startsWith(quote, i)) {
                val c = text[i]
                if (!triple && c == '\\' && i + 1 < text.length) {
                    sb.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            result += sb.toString()
            from = i
        }
    }

    /** Что именно ICU не примет: одинокие `}` и `]` вне класса символов. */
    private fun icuProblem(pattern: String): String? {
        var i = 0
        var quantifiers = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i += 2
                '[' -> i = skipClass(pattern, i)
                '{' -> {
                    quantifiers++
                    i++
                }
                '}' -> {
                    if (quantifiers == 0) return "одинокая } ломает ICU"
                    quantifiers--
                    i++
                }
                ']' -> return "одинокая ] ломает ICU"
                else -> i++
            }
            if (i == -1) return "незакрытый класс символов"
        }
        return null
    }

    /** Индекс за закрывающей `]` класса символов. */
    private fun skipClass(pattern: String, start: Int): Int {
        var i = start + 1
        if (i < pattern.length && pattern[i] == '^') i++
        if (i < pattern.length && pattern[i] == ']') i++
        while (i < pattern.length && pattern[i] != ']') {
            if (pattern[i] == '\\') i++
            i++
        }
        return if (i < pattern.length) i + 1 else -1
    }
}
