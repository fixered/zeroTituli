package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSeasonsTest {

    /**
     * `programType` e non `editorialType`: la classificazione degli extra guarda il primo,
     * e mettere il secondo nei campioni faceva credere il contrario a chi leggeva il test.
     */
    private fun ep(
        guid: String,
        season: Int? = null,
        number: Int? = null,
        type: String = "episode",
        subBrand: String? = null,
    ) = FeedEntry(
        guid = guid,
        title = "Ep $guid",
        programType = type,
        tvSeasonNumber = season,
        tvSeasonEpisodeNumber = number,
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
                ep("extra", type = "extra"),
                ep("s1e1", season = 1, number = 1),
            )
        )
        assertEquals("s1e1", out.first().entry.guid)
        assertEquals("extra", out.last().entry.guid)
        assertEquals(MediasetSeasons.EXTRAS_SEASON, out.last().season)
    }

    @Test
    fun `un extra con la stagione scritta finisce comunque fra gli extra`() {
        // Nel feed vero gli extra di "La promessa" portano `tvSeasonNumber = 1` come le
        // puntate, e nessun numero d'episodio. Fidandosi di quel numero finivano in mezzo
        // alla prima stagione, senza posizione, che è esattamente il buco che la stagione
        // dedicata deve evitare. Conta il `programType`, non la stagione dichiarata.
        val out = MediasetSeasons.arrange(
            listOf(
                ep("promo", season = 1, type = "extra"),
                ep("s1e1", season = 1, number = 1),
                ep("s1e2", season = 1, number = 2),
            )
        )
        assertEquals(listOf("s1e1", "s1e2", "promo"), out.map { it.entry.guid })
        assertEquals(listOf(1, 1, MediasetSeasons.EXTRAS_SEASON), out.map { it.season })
    }

    @Test
    fun `un film resta nella sua stagione senza diventare un extra`() {
        val out = MediasetSeasons.arrange(listOf(ep("film", season = 1, number = 1, type = "movie")))
        assertEquals(1, out.single().season)
    }

    // ============= LA VOCE CHE DA I DATI ALLA SCHEDA =============

    @Test
    fun `la scheda prende i dati da una puntata intera, non dal promo che apre il feed`() {
        // L'ordine è quello che il feed vero restituisce per "La promessa": le prime due
        // voci sono un promo e una clip sul cast. Prendendo la prima, la scheda della serie
        // si presentava col titolo e la grafica di un trailer.
        val head = MediasetSeasons.head(
            listOf(
                ep("promo", season = 1, type = "extra"),
                ep("clip-cast", season = 1, type = "extra"),
                ep("s1e1", season = 1, number = 1),
            )
        )
        assertEquals("s1e1", head?.guid)
    }

    @Test
    fun `senza puntate la scheda prende i dati dal film`() {
        val head = MediasetSeasons.head(
            listOf(
                ep("trailer", type = "extra"),
                ep("film", number = 1, type = "movie"),
            )
        )
        assertEquals("film", head?.guid)
    }

    @Test
    fun `un marchio di soli extra da comunque una scheda`() {
        // Meglio una scheda coi dati di un extra che nessuna scheda: `load` tornerebbe
        // null e CloudStream mostrerebbe un errore di caricamento.
        val head = MediasetSeasons.head(listOf(ep("solo-extra", type = "extra")))
        assertEquals("solo-extra", head?.guid)
    }

    @Test
    fun `senza voci non c e nessuna testa`() {
        assertEquals(null, MediasetSeasons.head(emptyList()))
    }

    @Test
    fun `i promo non contano per decidere se un marchio e un film`() {
        // È la regola che decide fra scheda film e serie da una puntata: un film con due
        // promo attaccati ha tre voci, ma una sola guardabile.
        val entries = listOf(
            ep("trailer", type = "extra"),
            ep("film", number = 1, type = "movie"),
            ep("backstage", type = "extra"),
        )
        val playable = MediasetSeasons.playable(entries)
        assertEquals(listOf("film"), playable.map { it.guid })
    }

    @Test
    fun `una serie da due puntate non e un film`() {
        val entries = listOf(
            ep("s1e1", season = 1, number = 1),
            ep("s1e2", season = 1, number = 2),
            ep("extra", type = "extra"),
        )
        assertEquals(2, MediasetSeasons.playable(entries).size)
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
