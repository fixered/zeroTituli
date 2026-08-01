package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSeasonsTest {

    private fun ep(
        guid: String,
        season: Int? = null,
        number: Int? = null,
        type: String = "Full Episode",
        subBrand: String? = null,
    ) = FeedEntry(
        guid = guid,
        title = "Ep $guid",
        tvSeasonNumber = season,
        tvSeasonEpisodeNumber = number,
        editorialType = type,
        subBrandId = subBrand,
    )

    @Test
    fun `ordina per stagione e poi per episodio`() {
        val out = MediasetSeasons.arrange(
            listOf(
                ep("c", season = 2, number = 1),
                ep("a", season = 1, number = 1),
                ep("b", season = 1, number = 2),
            )
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.entry.guid })
        assertEquals(listOf(1, 1, 2), out.map { it.season })
    }

    @Test
    fun `stagioni in sottomarchi diversi restano nella stessa serie`() {
        // Mediaset a volte spezza le stagioni in subBrand diversi: contano i numeri
        // di stagione, non da quale sottomarchio arrivano.
        val out = MediasetSeasons.arrange(
            listOf(
                ep("s2e1", season = 2, number = 1, subBrand = "200"),
                ep("s1e1", season = 1, number = 1, subBrand = "100"),
            )
        )
        assertEquals(listOf(1, 2), out.map { it.season })
        assertEquals(listOf("s1e1", "s2e1"), out.map { it.entry.guid })
    }

    @Test
    fun `gli extra senza stagione finiscono in fondo`() {
        val out = MediasetSeasons.arrange(
            listOf(
                ep("extra", type = "Extra"),
                ep("s1e1", season = 1, number = 1),
            )
        )
        assertEquals("s1e1", out.first().entry.guid)
        assertEquals("extra", out.last().entry.guid)
        assertEquals(MediasetSeasons.EXTRAS_SEASON, out.last().season)
    }

    @Test
    fun `un episodio senza numero mantiene la sua stagione e va dopo i numerati`() {
        val out = MediasetSeasons.arrange(
            listOf(
                ep("senza", season = 1),
                ep("s1e2", season = 1, number = 2),
            )
        )
        assertEquals(listOf("s1e2", "senza"), out.map { it.entry.guid })
        assertEquals(listOf(1, 1), out.map { it.season })
    }

    @Test
    fun `le voci doppie del feed compaiono una volta sola`() {
        val out = MediasetSeasons.arrange(
            listOf(ep("a", season = 1, number = 1), ep("a", season = 1, number = 1))
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `le voci senza guid vengono scartate`() {
        val out = MediasetSeasons.arrange(listOf(FeedEntry(title = "senza guid")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `una lista vuota da una lista vuota`() {
        assertTrue(MediasetSeasons.arrange(emptyList()).isEmpty())
    }

    @Test
    fun `la stagione ordina prima di avere episodio numerato`() {
        // Caso conflittuale per ordine di confronto: season=2/episode=1 vs season=1/episode=null
        // Se la stagione viene prima nel comparatore, season-1/null-episode vincerà.
        // Se hasEpisode viene prima, season-2/episode=1 vincerebbe.
        // Verifichiamo che la stagione vince: s1unnumbered prima di s2e1.
        val out = MediasetSeasons.arrange(
            listOf(
                ep("s2e1", season = 2, number = 1),
                ep("s1unnumbered", season = 1),
            )
        )
        assertEquals(listOf("s1unnumbered", "s2e1"), out.map { it.entry.guid })
        assertEquals(listOf(1, 2), out.map { it.season })
    }

    @Test
    fun `dedup per guid con titoli diversi preserva il primo`() {
        // Due entry con lo stesso guid ma titoli diversi verifica che
        // distinctBy(guid) mantiene la prima occorrenza.
        val entry1 = FeedEntry(guid = "same-guid", title = "Titolo Primo")
        val entry2 = FeedEntry(guid = "same-guid", title = "Titolo Secondo", tvSeasonNumber = 1, tvSeasonEpisodeNumber = 1)

        val out = MediasetSeasons.arrange(listOf(entry1, entry2))
        assertEquals(1, out.size)
        assertEquals("Titolo Primo", out.first().entry.title)
    }
}
