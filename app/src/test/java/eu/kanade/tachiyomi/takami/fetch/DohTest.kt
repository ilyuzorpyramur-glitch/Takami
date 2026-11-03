package eu.kanade.tachiyomi.takami.fetch

import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_LIBREDNS
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import io.kotest.matchers.shouldBe
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Test

/** Клиент сетевого слоя уважает настройку DNS over HTTPS так же, как общий. */
class DohTest {

    @Test
    fun `выбранный провайдер применяется к клиенту`() {
        val client = Fetcher.buildClient(PREF_DOH_CLOUDFLARE)

        (client.dns is DnsOverHttps) shouldBe true
    }

    @Test
    fun `без провайдера остаётся системный DNS`() {
        val client = Fetcher.buildClient(Fetcher.NO_DOH)

        (client.dns is DnsOverHttps) shouldBe false
    }

    @Test
    fun `каждый провайдер из настроек даёт защищённый DNS`() {
        val providers = listOf(
            PREF_DOH_CLOUDFLARE,
            PREF_DOH_GOOGLE,
            PREF_DOH_ADGUARD,
            PREF_DOH_QUAD9,
            PREF_DOH_ALIDNS,
            PREF_DOH_DNSPOD,
            PREF_DOH_360,
            PREF_DOH_QUAD101,
            PREF_DOH_MULLVAD,
            PREF_DOH_CONTROLD,
            PREF_DOH_NJALLA,
            PREF_DOH_SHECAN,
            PREF_DOH_LIBREDNS,
        )

        providers.forEach { provider ->
            (Fetcher.buildClient(provider).dns is DnsOverHttps) shouldBe true
        }
    }

    @Test
    fun `неизвестный провайдер не ломает клиент`() {
        val client = Fetcher.buildClient(99)

        (client.dns is DnsOverHttps) shouldBe false
    }
}
