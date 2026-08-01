package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediasetImagesTest {

    private fun thumb(name: String, w: Int, h: Int) =
        name to Thumbnail(url = "https://img/$name.jpg", width = w, height = h)

    private fun entry(vararg t: Pair<String, Thumbnail>) =
        FeedEntry(thumbnails = t.toMap())

    @Test
    fun `il poster prende il verticale piu grande`() {
        val e = entry(
            thumb("image_vertical-140x210", 140, 210),
            thumb("image_vertical-264x396", 264, 396),
            thumb("image_vertical-192x288", 192, 288),
        )
        assertEquals("https://img/image_vertical-264x396.jpg", MediasetImages.poster(e))
    }

    @Test
    fun `senza verticale il poster ripiega sull orizzontale`() {
        val e = entry(thumb("image_header_poster-768x480", 768, 480))
        assertEquals("https://img/image_header_poster-768x480.jpg", MediasetImages.poster(e))
    }

    @Test
    fun `senza nessuna immagine il poster e nullo`() {
        assertNull(MediasetImages.poster(entry()))
    }

    @Test
    fun `lo sfondo preferisce il formato ampio`() {
        val e = entry(
            thumb("image_vertical-264x396", 264, 396),
            thumb("image_header_poster-1440x630", 1440, 630),
        )
        assertEquals("https://img/image_header_poster-1440x630.jpg", MediasetImages.background(e))
    }

    @Test
    fun `il fotogramma dell episodio preferisce il keyframe`() {
        val e = entry(
            thumb("image_keyframe_poster-1280x720", 1280, 720),
            thumb("image_keyframe_poster-240x135", 240, 135),
            thumb("image_horizontal_cover-704x396", 704, 396),
        )
        assertEquals(
            "https://img/image_keyframe_poster-1280x720.jpg",
            MediasetImages.still(e)
        )
    }

    @Test
    fun `il logo del canale e orizzontale`() {
        val e = entry(
            thumb("logo_horizontal-320x128", 320, 128),
            thumb("brand_logo-210x210", 210, 210),
        )
        assertEquals("https://img/logo_horizontal-320x128.jpg", MediasetImages.brandLogo(e))
    }

    @Test
    fun `una variante senza indirizzo viene saltata`() {
        val e = entry(
            "image_vertical-264x396" to Thumbnail(url = null, width = 264, height = 396),
            thumb("image_vertical-192x288", 192, 288),
        )
        assertEquals("https://img/image_vertical-192x288.jpg", MediasetImages.poster(e))
    }

    @Test
    fun `una variante senza larghezza vale meno di una con larghezza`() {
        val e = entry(
            "image_vertical-sconosciuta" to Thumbnail(url = "https://img/x.jpg"),
            thumb("image_vertical-140x210", 140, 210),
        )
        assertEquals("https://img/image_vertical-140x210.jpg", MediasetImages.poster(e))
    }
}
