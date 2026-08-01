package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `le sezioni previste hanno slug nome e categoria di ripiego`() {
        MediasetSections.SLUGS.forEach { section ->
            assertTrue(section.slug.isNotBlank())
            assertTrue(section.label.isNotBlank())
            assertTrue("la sezione ${section.slug} non ha un ripiego", section.feedCategory.isNotBlank())
        }
        // Gli slug del sito e le categorie del feed non si chiamano allo stesso modo, ed è
        // il motivo per cui la tabella esiste: la pagina è `/programmitv`, la categoria è
        // `Programmi Tv`, e `/news-e-sport` pesca da `Calcio e Sport`.
        assertEquals("Programmi Tv", MediasetSections.sectionOf("programmitv")?.feedCategory)
        assertEquals("Calcio e Sport", MediasetSections.sectionOf("news-e-sport")?.feedCategory)
        assertEquals("News e Sport", MediasetSections.sectionOf("news-e-sport")?.label)
    }

    @Test
    fun `uno slug che non esiste non finge di essere Fiction`() {
        // Prima la traduzione slug-categoria finiva con `else -> "Fiction"`: una sezione
        // sconosciuta serviva Fiction sotto l'intestazione di un'altra sezione. Ora chi
        // chiama non ha una categoria e salta la riga.
        assertNull(MediasetSections.sectionOf("serie-tv"))
        assertNull(MediasetSections.sectionOf(""))
        assertNull(MediasetSections.sectionOf("action-zone"))
    }

    @Test
    fun `le sezioni della tabella sono le sei che il sito serve`() {
        assertEquals(
            listOf("fiction", "cinema", "programmitv", "kids", "documentari", "news-e-sport"),
            MediasetSections.SLUGS.map { it.slug }
        )
    }

    // ============= SFOLTIMENTO DEL MARKUP =============

    @Test
    fun `sfoltire gli script non perde nessuna riga ne nessuna voce`() {
        // Il campione pesa 6 020 473 byte e più della metà sta dentro `<script>`, che è
        // stato di Next.js e non markup: `Jsoup` lo costruisce nell'albero DOM per niente.
        // Questo test è la prova che buttarlo non porta via contenuto: si legge lo stesso
        // campione due volte, intero e sfoltito, e le due letture devono coincidere in
        // tutto — righe, titoli, voci, indirizzi, identificativi e copertine.
        val whole = MediasetSections.parse(html)
        val stripped = MediasetSections.parse(MediasetSections.stripScripts(html))

        assertEquals(whole.size, stripped.size)
        assertEquals(whole.map { it.title }, stripped.map { it.title })
        assertEquals(whole.map { it.items.size }, stripped.map { it.items.size })
        assertEquals(whole.flatMap { it.items }, stripped.flatMap { it.items })
        // Il campione deve avere davvero delle righe, o il confronto sopra sarebbe vero
        // per due liste vuote.
        assertTrue(whole.size >= 3)
        assertTrue(whole.flatMap { it.items }.size >= 20)
    }

    @Test
    fun `sfoltire gli script butta piu della meta del campione`() {
        val stripped = MediasetSections.stripScripts(html)
        assertTrue("nessuno script trovato: il campione è cambiato?", stripped.length < html.length)
        // Nel campione gli script sono il 60% dei byte: se un giorno scendessero sotto il
        // 40% vorrebbe dire che Next.js ha cambiato modo e questa ottimizzazione non serve
        // più a niente — meglio saperlo da un test che non saperlo.
        val kept = stripped.length.toDouble() / html.length
        assertTrue("gli script erano solo il ${((1 - kept) * 100).toInt()}%", kept < 0.60)
        assertTrue(!stripped.contains("<script", ignoreCase = true))
    }

    @Test
    fun `read sfoltisce da sola`() {
        assertEquals(
            MediasetSections.parse(MediasetSections.stripScripts(html)),
            MediasetSections.read(html)
        )
    }

    @Test
    fun `uno script non chiuso non porta via la pagina`() {
        // La chiusura pigra serve a questo: un `<script>` che non chiude resta dov'è
        // invece di mangiarsi tutto il markup che gli sta dietro.
        val markup = "<html><body><script>var a = 1;<p>testo</p></body></html>"
        assertTrue(MediasetSections.stripScripts(markup).contains("<p>testo</p>"))
    }

    @Test
    fun `piu script si buttano tutti e uno per uno`() {
        val markup = "<a/><script>uno</script><b/><script>due</script><c/>"
        assertEquals("<a/><b/><c/>", MediasetSections.stripScripts(markup))
    }
}
