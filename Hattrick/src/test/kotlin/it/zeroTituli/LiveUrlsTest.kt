package it.zeroTituli

import it.zeroTituli.shared.LiveUrls
import it.zeroTituli.shared.M3u8
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Il rinnovo degli indirizzi visto dal lato che conta: dopo che il gettone è scaduto e la catena
 * del player ne ha dato uno nuovo, le richieste già consegnate al player devono finire sul CDN
 * giusto, con la firma nuova.
 *
 * Gli indirizzi sono quelli veri delle tre famiglie che servono le dirette di Hattrick.
 */
class LiveUrlsTest {

    // bluetier: gettone da cinque minuti, ripetuto identico in ogni riga della playlist
    private val bluetierOld =
        "https://ds164.bluetier.top/SkySportCalcioIT/index.m3u8?token=VECCHIO.aaa"
    private val bluetierNew =
        "https://ds164.bluetier.top/SkySportCalcioIT/index.m3u8?token=NUOVO.bbb"

    @Test
    fun `il gettone della playlist figlia viene sostituito con quello fresco`() {
        val params = LiveUrls.childParams(
            "k", bluetierOld,
            "https://ds164.bluetier.top/SkySportCalcioIT/tracks-v1a1/mono.m3u8?token=VECCHIO.aaa"
        )
        assertEquals("tracks-v1a1/mono.m3u8", params["p"])
        assertEquals("token=VECCHIO.aaa", params["q"])
        assertEquals(
            "https://ds164.bluetier.top/SkySportCalcioIT/tracks-v1a1/mono.m3u8?token=NUOVO.bbb",
            LiveUrls.target(bluetierNew, params["p"], params["q"].orEmpty())
        )
    }

    @Test
    fun `dopo un rinnovo che cambia host il segmento segue il nuovo host`() {
        val params = LiveUrls.childParams(
            "k", bluetierOld,
            "https://ds164.bluetier.top/SkySportCalcioIT/2026/08/06/12/46/54-05760.ts?token=VECCHIO.aaa"
        )
        val ricollocato = "https://ds177.bluetier.top/SkySportCalcioIT/index.m3u8?token=NUOVO.bbb"
        assertEquals(
            "https://ds177.bluetier.top/SkySportCalcioIT/2026/08/06/12/46/54-05760.ts?token=NUOVO.bbb",
            LiveUrls.target(ricollocato, params["p"], params["q"].orEmpty())
        )
    }

    // xameleon: la firma sta nel percorso, non nella query, e cambia a ogni rinnovo
    @Test
    fun `la firma nel percorso viene ripresa dall'indirizzo principale nuovo`() {
        val vecchio =
            "https://xameleon.phantemlis.top/one/secure/AAAA/1786031268/premium14/index.m3u8"
        val nuovo =
            "https://xameleon.phantemlis.top/one/secure/BBBB/1786040000/premium14/index.m3u8"
        val params = LiveUrls.childParams(
            "k", vecchio,
            "https://xameleon.phantemlis.top/one/secure/AAAA/1786031268/premium14/tracks-v1a1/mono.m3u8"
        )
        assertEquals("tracks-v1a1/mono.m3u8", params["p"])
        assertEquals(
            "https://xameleon.phantemlis.top/one/secure/BBBB/1786040000/premium14/tracks-v1a1/mono.m3u8",
            LiveUrls.target(nuovo, params["p"], params["q"].orEmpty())
        )
    }

    @Test
    fun `i segmenti su storage firmato restano quelli che la playlist ha detto`() {
        val segmento = "https://007853151782.eu.r2.cloudflarestorage.com/IMG_4758134421.png" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=900&X-Amz-Signature=abc"
        val params = LiveUrls.childParams("k", bluetierOld, segmento)
        assertEquals(segmento, params["u"])
        assertEquals(null, params["p"])
    }

    @Test
    fun `una query con chiavi diverse da quelle principali non viene sostituita`() {
        val master = "https://cdn.example/live/index.m3u8?token=NUOVO"
        val params = LiveUrls.childParams(
            "k", "https://cdn.example/live/index.m3u8?token=VECCHIO",
            "https://cdn.example/live/seg1.ts?md5=xyz&expires=99"
        )
        assertEquals(
            "https://cdn.example/live/seg1.ts?md5=xyz&expires=99",
            LiveUrls.target(master, params["p"], params["q"].orEmpty())
        )
    }

    @Test
    fun `senza percorso relativo si serve l'indirizzo principale del momento`() {
        assertEquals(bluetierNew, LiveUrls.target(bluetierNew, null, ""))
        assertEquals(bluetierNew, LiveUrls.target(bluetierNew, "", "token=VECCHIO.aaa"))
    }

    @Test
    fun `le righe della playlist relative diventano assolute prima di essere riscritte`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",URI="tracks-a1/mono.m3u8?token=VECCHIO.aaa"
            #EXT-X-STREAM-INF:BANDWIDTH=2870000,AUDIO="aac"
            tracks-v1a1/mono.m3u8?token=VECCHIO.aaa
        """.trimIndent()
        val visti = mutableListOf<String>()
        M3u8.rewrite(playlist, bluetierOld) { url -> visti += url; "PROXY" }
        assertEquals(
            listOf(
                "https://ds164.bluetier.top/SkySportCalcioIT/tracks-a1/mono.m3u8?token=VECCHIO.aaa",
                "https://ds164.bluetier.top/SkySportCalcioIT/tracks-v1a1/mono.m3u8?token=VECCHIO.aaa",
            ),
            visti
        )
    }
}
