package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetDTOsTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val feed: FeedResponse =
        MediasetJson.parse<FeedResponse>(fixture("feed-entry.json"))!!

    @Test
    fun `legge le voci del feed`() {
        assertTrue(feed.entries.isNotEmpty())
        val e = feed.entries.first()
        assertNotNull(e.guid)
        assertNotNull(e.title)
        assertNotNull(e.brandId)
    }

    @Test
    fun `i campi con il dollaro finiscono nelle proprieta giuste`() {
        val e = feed.entries.first()
        assertEquals("100001417", e.brandId)
        assertNotNull(e.brandTitle)
    }

    @Test
    fun `i generi arrivano dai tag e dal campo generi`() {
        val e = feed.entries.first()
        assertTrue(e.genres.isNotEmpty())
    }

    @Test
    fun `i generi uniscono le due fonti senza duplicati`() {
        // Nel campione la prima voce ha "Storia Miti e Religioni" solo in
        // mediasetprogram$genres e "Documentari" solo nel tag scheme=genre: se genres
        // leggesse una sola fonte, uno dei due mancherebbe.
        val e = feed.entries.first()
        assertTrue(e.genres.contains("Storia Miti e Religioni"))
        assertTrue(e.genres.contains("Documentari"))
        assertEquals(e.genres.size, e.genres.distinct().size)
    }

    @Test
    fun `la categoria arriva dai tag`() {
        val e = feed.entries.first()
        assertTrue(e.categories.contains("Documentari"))
    }

    @Test
    fun `la durata passa da secondi a minuti`() {
        // Il feed dà 3550 secondi: CloudStream vuole i minuti.
        val e = feed.entries.first()
        assertEquals(59, e.durationMinutes)
    }

    @Test
    fun `la classificazione italiana diventa una eta`() {
        val e = feed.entries.first()
        assertEquals("T", e.ageRating)
    }

    @Test
    fun `i contenuti con diritto AVOD sono gratuiti`() {
        assertTrue(feed.entries.first().isFree)
    }

    @Test
    fun `senza il diritto AVOD non sono gratuiti`() {
        val e = FeedEntry(guid = "x", rights = listOf("MediasetPlay_ANY", "SVOD"))
        assertFalse(e.isFree)
    }

    @Test
    fun `senza alcun diritto non sono gratuiti`() {
        val e = FeedEntry(guid = "x", rights = emptyList())
        assertFalse(e.isFree)
    }

    @Test
    fun `l identificativo della serie e l ultimo pezzo del seriesId`() {
        val e = feed.entries.first()
        assertEquals("SE000000000780", e.seriesGuid)
    }

    @Test
    fun `il plot usa la longDescription quando c'e`() {
        val e = FeedEntry(guid = "x", description = "breve", longDescription = "lunga")
        assertEquals("lunga", e.plot)
    }

    @Test
    fun `il plot usa la description quando la longDescription manca`() {
        // Nel campione la seconda voce ha longDescription assente: è il caso vero che
        // esercita il ramo di riserva, non solo un fixture inventato a mano.
        val e = feed.entries[1]
        assertNotNull(e.description)
        assertEquals(null, e.longDescription)
        assertEquals(e.description, e.plot)
    }

    @Test
    fun `il plot ignora una longDescription vuota e usa la description`() {
        val e = FeedEntry(guid = "x", description = "breve", longDescription = "   ")
        assertEquals("breve", e.plot)
    }

    @Test
    fun `una risposta non valida non lancia`() {
        assertEquals(null, MediasetJson.parse<FeedResponse>("non json"))
    }

    @Test
    fun `un campo nuovo nel feed non rompe la lettura`() {
        val json = """{"entries":[{"guid":"X","title":"T","campoNuovo":123}]}"""
        val r = MediasetJson.parse<FeedResponse>(json)
        assertEquals("X", r!!.entries.first().guid)
    }
}
