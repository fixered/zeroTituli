package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSmilTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `legge il flusso DASH`() {
        val r = MediasetSmil.read(fixture("smil-dash.xml"))
        assertTrue(r is SmilResult.Stream)
        r as SmilResult.Stream
        assertEquals(
            "https://vod06.msf.cdn.mediaset.net/farmunica/2019/04/373226_16a43b7422a89c/dashrcenc/hr_wv_mpl.mpd",
            r.url
        )
        assertEquals(StreamKind.DASH, r.kind)
    }

    @Test
    fun `il video di cortesia non e un flusso`() {
        // Il CDN risponde con esito positivo e un cartello: senza riconoscerlo si
        // guarderebbe il cartello al posto del film.
        assertEquals(SmilResult.GeoBlocked, MediasetSmil.read(fixture("smil-geoblock.xml")))
    }

    @Test
    fun `assetTypes sbagliato si distingue dal blocco geografico`() {
        assertEquals(SmilResult.NoMatch, MediasetSmil.read(fixture("smil-nomatch.xml")))
    }

    @Test
    fun `riconosce l HLS`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/index.m3u8"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.HLS, r.kind)
    }

    @Test
    fun `riconosce il file progressivo`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/film.mp4"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.PROGRESSIVE, r.kind)
    }

    @Test
    fun `il manifest con parametri resta riconoscibile`() {
        val smil =
            """<smil><body><seq><video src="https://cdn/live/c5-clr.isml/manifest_sd.mpd?hdnts=st=1~exp=2"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.DASH, r.kind)
    }

    @Test
    fun `una risposta vuota non lancia`() {
        assertTrue(MediasetSmil.read("") is SmilResult.Failed)
    }

    @Test
    fun `una risposta che non e un SMIL non lancia`() {
        assertTrue(MediasetSmil.read("<html><body>errore</body></html>") is SmilResult.Failed)
    }
}
