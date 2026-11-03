package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Три уровня защиты: браузер проходит проверку сам, капчу проходит человек,
 * отказ не повторяется.
 */
class ChallengeTest {

    private lateinit var server: MockWebServer
    private lateinit var clock: FakeFetchClock

    private class FakeBrowser(private val cookie: String?) : ChallengeBrowser {
        var calls = 0
            private set
        var lastUrl: String? = null
            private set

        override suspend fun pass(url: String, host: String): String? {
            calls++
            lastUrl = url
            return cookie
        }
    }

    @BeforeEach
    fun setUp() {
        clock = FakeFetchClock()
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(browser: ChallengeBrowser?): Pair<Fetcher, ProtectionRegistry> {
        val registry = ProtectionRegistry()
        val fetcher = Fetcher(
            client = Fetcher.buildClient(),
            guard = HostGuard(InMemoryRequestBudget(), clock, jitter = { 0L }),
            challenges = browser,
            protection = registry,
        )
        return fetcher to registry
    }

    private fun challengeResponse(): MockResponse = MockResponse()
        .setResponseCode(403)
        .setHeader("Server", "cloudflare")
        .setHeader("cf-mitigated", "challenge")
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<html><head><title>Just a moment...</title></head><body></body></html>")

    @Test
    fun `проверка браузера проходит и запрос повторяется один раз`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/catalog" && attempts.incrementAndGet() == 1) {
                    challengeResponse()
                } else {
                    MockResponse()
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><body>каталог</body></html>")
                }
        }
        val browser = FakeBrowser("cf_clearance=abc")
        val (fetcher, registry) = fetcher(browser)

        val html = fetcher.get(server.url("/catalog").toString())

        html.contains("каталог") shouldBe true
        server.requestCount shouldBe 2
        browser.calls shouldBe 1
        registry.status(server.hostName) shouldBe null
    }

    @Test
    fun `не прошли проверку браузера — это уровень B`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = challengeResponse()
        }
        val browser = FakeBrowser(null)
        val (fetcher, registry) = fetcher(browser)

        val error = assertFailsWith<VerificationRequiredError> {
            fetcher.get(server.url("/catalog").toString())
        }

        error.level shouldBe ProtectionLevel.CAPTCHA
        server.requestCount shouldBe 1
        browser.calls shouldBe 1
        registry.status(server.hostName)?.level shouldBe ProtectionLevel.CAPTCHA
    }

    @Test
    fun `капча не решается сама и не повторяется`(): Unit = runBlocking {
        val page = "<html><head><title>Проверка</title></head>" +
            "<body><div class=\"g-recaptcha\"></div></body></html>"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(page)
        }
        val browser = FakeBrowser("cf_clearance=abc")
        val (fetcher, registry) = fetcher(browser)

        val error = assertFailsWith<VerificationRequiredError> {
            fetcher.get(server.url("/catalog").toString())
        }

        error.level shouldBe ProtectionLevel.CAPTCHA
        server.requestCount shouldBe 1
        // Скрытое окно для капчи не открываем: её проходит человек.
        browser.calls shouldBe 0
        registry.status(server.hostName)?.level shouldBe ProtectionLevel.CAPTCHA
    }

    @Test
    fun `отказ 403 не повторяется`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(403).setBody("нет доступа")
        }
        val (fetcher, registry) = fetcher(null)

        val error = assertFailsWith<VerificationRequiredError> {
            fetcher.get(server.url("/catalog").toString())
        }

        error.level shouldBe ProtectionLevel.BLOCKED
        server.requestCount shouldBe 1
        registry.status(server.hostName)?.level shouldBe ProtectionLevel.BLOCKED
    }

    @Test
    fun `пометка снимается после проверки человеком`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(451).setBody("нет доступа")
        }
        val (fetcher, registry) = fetcher(null)
        assertFailsWith<VerificationRequiredError> {
            fetcher.get(server.url("/catalog").toString())
        }
        registry.status(server.hostName)?.level shouldBe ProtectionLevel.BLOCKED

        fetcher.clearVerification(server.hostName)

        fetcher.verification(server.hostName) shouldBe null
    }

    private val attempts = java.util.concurrent.atomic.AtomicInteger(0)
}
