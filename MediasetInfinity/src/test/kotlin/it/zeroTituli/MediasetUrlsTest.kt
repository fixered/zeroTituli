package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetUrlsTest {

    @Test
    fun `la prima pagina parte da uno`() {
        // theplatform conta le voci da 1 e vuole gli estremi inclusi.
        assertEquals("1-40", MediasetUrls.range(page = 1, perPage = 40))
        assertEquals("41-80", MediasetUrls.range(page = 2, perPage = 40))
    }

    @Test
    fun `una pagina sotto uno vale come la prima`() {
        assertEquals("1-40", MediasetUrls.range(page = 0, perPage = 40))
    }

    @Test
    fun `la query per marchio filtra e ordina`() {
        val url = MediasetUrls.byBrand("100012714", page = 1)
        assertTrue(url.startsWith(MediasetUrls.FEED))
        assertTrue(url.contains("form=cjson"))
        assertTrue(url.contains("byCustomValue=%7BbrandId%7D%7B100012714%7D"))
        assertTrue(url.contains("range=1-100"))
    }

    @Test
    fun `la query per serie usa l indirizzo completo del programma`() {
        val url = MediasetUrls.bySeries("SE000000002040", page = 1)
        assertTrue(url.contains("bySeriesId="))
        assertTrue(url.contains("SE000000002040"))
        // L'indirizzo va codificato: i due punti e le barre non possono restare nudi.
        assertTrue(url.contains("http%3A%2F%2Fdata.entertainment.tv.theplatform.eu"))
    }

    @Test
    fun `la query per categoria usa i tag`() {
        val url = MediasetUrls.byCategory("Programmi Tv", page = 1)
        assertTrue(url.contains("byTags=category%7CProgrammi+Tv"))
    }

    @Test
    fun `la query alfabetica ordina per titolo del marchio`() {
        val url = MediasetUrls.alphabetical("Fiction", page = 2)
        assertTrue(url.contains("sort=mediasetprogram%24brandTitle%7Casc"))
        assertTrue(url.contains("range=41-80"))
    }

    @Test
    fun `la ricerca passa il testo cosi come e`() {
        val url = MediasetUrls.search("zelig party", page = 1)
        assertTrue(url.contains("q=zelig+party"))
    }

    @Test
    fun `il SMIL porta il token e gli assetTypes`() {
        val url = MediasetUrls.smil(
            mediaUrl = "https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC",
            assetTypes = "HR,widevine,geoIT|geoNo",
            token = "abc.def"
        )
        assertTrue(url.startsWith("https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC?"))
        assertTrue(url.contains("format=SMIL"))
        assertTrue(url.contains("formats=mpeg-dash"))
        assertTrue(url.contains("assetTypes=HR%2Cwidevine%2CgeoIT%7CgeoNo"))
        assertTrue(url.contains("auth=abc.def"))
    }

    @Test
    fun `l indirizzo della licenza porta token account e pid`() {
        val url = MediasetUrls.license(pid = "UXvEsmsZ1AvC", token = "abc.def")
        assertTrue(url.startsWith(MediasetUrls.LICENSE))
        assertTrue(url.contains("token=abc.def"))
        assertTrue(url.contains("releasePid=UXvEsmsZ1AvC"))
        assertTrue(url.contains("account=http%3A%2F%2Faccess.auth.theplatform.eu%2Fdata%2FAccount%2F2702976343"))
        assertTrue(url.contains("form=json"))
        assertTrue(url.contains("schema=1.0"))
    }

    @Test
    fun `nowNext vuole il nome del canale`() {
        assertEquals(
            "https://api-ott-prod-fe.mediaset.net/PROD/play/alive/nownext/v1.0?channelId=C5",
            MediasetUrls.nowNext("C5")
        )
    }

    @Test
    fun `la pagina sezione sta sul sito`() {
        assertEquals("https://mediasetinfinity.mediaset.it/fiction", MediasetUrls.section("fiction"))
    }
}
