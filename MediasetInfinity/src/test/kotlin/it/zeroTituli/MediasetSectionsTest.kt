package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSectionsTest {

    private val html: String =
        javaClass.classLoader!!.getResourceAsStream("section-fiction.html")!!
            .bufferedReader().readText()

    private val rows = MediasetSections.read(html)

    @Test
    fun `trova piu righe`() {
        assertTrue(rows.size >= 3)
    }

    @Test
    fun `ogni riga ha un titolo e almeno una voce`() {
        rows.forEach { row ->
            assertTrue("riga senza titolo", row.title.isNotBlank())
            assertTrue("riga vuota: ${row.title}", row.items.isNotEmpty())
        }
    }

    @Test
    fun `le voci hanno titolo indirizzo e copertina`() {
        val item = rows.first().items.first()
        assertTrue(item.title.isNotBlank())
        assertTrue(item.href.startsWith("/"))
        assertNotNull(item.poster)
    }

    @Test
    fun `l identificativo della serie viene estratto dall indirizzo`() {
        val item = rows.flatMap { it.items }.first { it.href.contains("_SE") }
        assertNotNull(item.seriesGuid)
        assertTrue(item.seriesGuid!!.startsWith("SE"))
    }

    @Test
    fun `l identificativo della serie scarta il secondo identificativo dopo la virgola`() {
        // Alcune schede del campione hanno un indirizzo tipo
        // `.../chicagomed/stagione7_SE000000000661,ST000000003784`: il pezzo dopo la
        // virgola non va nella query al feed, quindi seriesGuid deve fermarsi a `SE...`.
        val item = rows.flatMap { it.items }.first { it.href.contains("_SE000000000661,ST000000003784") }
        assertEquals("SE000000000661", item.seriesGuid)
    }

    @Test
    fun `le entita HTML nei titoli vengono sciolte`() {
        // Nel markup i titoli arrivano con &#x27; al posto dell'apostrofo.
        assertTrue(rows.flatMap { it.items }.none { it.title.contains("&#") })
    }

    @Test
    fun `un markup senza caroselli da nessuna riga`() {
        assertTrue(MediasetSections.read("<html><body><p>niente</p></body></html>").isEmpty())
    }

    @Test
    fun `una pagina vuota non lancia`() {
        assertTrue(MediasetSections.read("").isEmpty())
    }

    @Test
    fun `le sezioni previste hanno slug e nome`() {
        assertTrue(MediasetSections.SLUGS.any { it.first == "fiction" })
        MediasetSections.SLUGS.forEach { (slug, label) ->
            assertTrue(slug.isNotBlank())
            assertTrue(label.isNotBlank())
        }
    }
}
