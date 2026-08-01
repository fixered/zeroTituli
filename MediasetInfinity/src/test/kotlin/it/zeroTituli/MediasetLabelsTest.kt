package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetLabelsTest {

    private fun entry(
        plot: String? = "Trama",
        genres: List<String> = listOf("Commedia", "Fiction"),
        free: Boolean = true,
    ) = FeedEntry(
        guid = "g1",
        title = "Titolo",
        longDescription = plot,
        // `isFree` guarda `rights`: senza "AVOD" il contenuto è a pagamento.
        rights = if (free) listOf("AVOD") else listOf("SVOD"),
        genreList = genres,
    )

    @Test
    fun `voce gratuita, i tag sono esattamente i generi, nell ordine, senza Abbonamento`() {
        val out = entry(free = true, genres = listOf("Commedia", "Fiction"))
        assertEquals(listOf("Commedia", "Fiction"), MediasetLabels.tags(out))
        assertFalse(MediasetLabels.tags(out).contains("Abbonamento"))
    }

    @Test
    fun `voce gratuita, la descrizione e la trama invariata`() {
        val out = entry(free = true, plot = "Una trama qualsiasi")
        assertEquals("Una trama qualsiasi", MediasetLabels.description(out))
    }

    @Test
    fun `voce a pagamento, Abbonamento e il primo tag e i generi seguono nell ordine`() {
        val out = entry(free = false, genres = listOf("Commedia", "Fiction"))
        assertEquals(listOf("Abbonamento", "Commedia", "Fiction"), MediasetLabels.tags(out))
    }

    @Test
    fun `voce a pagamento, la descrizione inizia con l avviso e contiene ancora la trama`() {
        val out = entry(free = false, plot = "Una trama qualsiasi")
        val description = MediasetLabels.description(out)!!
        assertTrue(description.startsWith("Serve un abbonamento o un noleggio Mediaset Infinity"))
        assertTrue(description.contains("Una trama qualsiasi"))
    }

    @Test
    fun `voce a pagamento senza trama, la descrizione e solo l avviso e non contiene null`() {
        val out = entry(free = false, plot = null)
        val description = MediasetLabels.description(out)!!
        assertTrue(description.startsWith("Serve un abbonamento o un noleggio Mediaset Infinity"))
        assertFalse(description.contains("null"))
    }

    @Test
    fun `voce gratuita senza generi, i tag sono una lista vuota`() {
        val out = entry(free = true, genres = emptyList())
        assertTrue(MediasetLabels.tags(out).isEmpty())
    }

    @Test
    fun `la stagione degli extra ha un nome, non il suo numero`() {
        // 999 è scelto per tenere gli extra in fondo al selettore, non per essere letto:
        // senza un nome CloudStream annuncerebbe "Season 999".
        assertEquals("Extra e speciali", MediasetLabels.seasonName(MediasetSeasons.EXTRAS_SEASON))
    }

    @Test
    fun `le stagioni vere si chiamano col loro numero`() {
        assertEquals("Stagione 1", MediasetLabels.seasonName(1))
        assertEquals("Stagione 12", MediasetLabels.seasonName(12))
    }

    @Test
    fun `le voci senza numero di stagione non si chiamano Extra e speciali`() {
        // Nel feed i film arrivano sempre senza `tvSeasonNumber`: finivano nella stagione
        // degli extra, quindi la scheda annunciava un film intero come materiale di
        // contorno. Il nome dei due gruppi deve essere diverso, altrimenti il difetto
        // torna senza che nessuno se ne accorga.
        val unnumbered = MediasetLabels.seasonName(MediasetSeasons.UNNUMBERED_SEASON)
        assertEquals("Senza stagione", unnumbered)
        assertFalse(unnumbered.contains("Extra"))
        // E non deve nemmeno annunciare il numero scelto per tenerla in fondo.
        assertFalse(unnumbered.contains("998"))
    }

    @Test
    fun `i diritti veri del feed decidono l etichetta Abbonamento`() {
        // Le sigle sono quelle vere, copiate dal feed: `mediasetprogram$channelsRights` di
        // "La promessa" (gratis) e de "La grande bellezza" (abbonamento). È la sola cosa che
        // avverte l'utente prima di premere play, perché il messaggio lanciato da `loadLinks`
        // non arriva a nessuno: CloudStream lo cattura e lo scrive solo nel log. Nel
        // campione di 400 voci `movie` del feed, 265 sono senza AVOD.
        val free = FeedEntry(
            guid = "g",
            title = "t",
            longDescription = "Trama",
            rights = listOf("MediasetPlay_ANY", "AVOD", "MediasetPlay_AVOD"),
        )
        val paid = FeedEntry(
            guid = "g",
            title = "t",
            longDescription = "Trama",
            rights = listOf("Infinity_ANY", "SVOD", "Infinity_SVOD"),
        )
        assertTrue(free.isFree)
        assertFalse("SVOD non contiene AVOD: questa voce va segnalata", paid.isFree)
        assertFalse(MediasetLabels.tags(free).contains("Abbonamento"))
        assertEquals(listOf("Abbonamento"), MediasetLabels.tags(paid))
        assertEquals("Trama", MediasetLabels.description(free))
        assertTrue(MediasetLabels.description(paid)!!.contains("abbonamento o un noleggio"))
    }

    // ============= TIPO DELLA SCHEDA (i chip in cima alla Home) =============

    @Test
    fun `un film resta un film anche nella riga Kids`() {
        // Nei chip di CloudStream "Cartoni" raccoglie le serie animate: un film per
        // bambini lo si cerca sotto Film, non sotto Cartoni.
        assertEquals(
            MediasetLabels.CardKind.MOVIE,
            MediasetLabels.kind(rowCategory = "Kids", programType = "movie")
        )
    }

    @Test
    fun `la riga Kids da schede di tipo Cartoni`() {
        assertEquals(
            MediasetLabels.CardKind.KIDS,
            MediasetLabels.kind(rowCategory = "Kids", programType = "episode")
        )
    }

    @Test
    fun `la riga Documentari da schede di tipo Documentario`() {
        assertEquals(
            MediasetLabels.CardKind.DOCUMENTARY,
            MediasetLabels.kind(rowCategory = "Documentari", programType = "episode")
        )
    }

    @Test
    fun `il genere Documentari vale come la categoria`() {
        // Le righe per genere passano il nome del genere, e "Documentari" compare in
        // entrambi i vocabolari: il chip deve funzionare anche da lì.
        assertEquals(
            MediasetLabels.CardKind.DOCUMENTARY,
            MediasetLabels.kind(rowCategory = "Documentari", programType = null)
        )
    }

    @Test
    fun `le altre categorie danno una serie`() {
        assertEquals(
            MediasetLabels.CardKind.SERIES,
            MediasetLabels.kind(rowCategory = "Fiction", programType = "episode")
        )
        assertEquals(
            MediasetLabels.CardKind.SERIES,
            MediasetLabels.kind(rowCategory = "Calcio e Sport", programType = "extra")
        )
    }

    @Test
    fun `senza categoria una voce non film resta una serie`() {
        // È il caso della ricerca, che non ha categoria da cui dedurre.
        assertEquals(
            MediasetLabels.CardKind.SERIES,
            MediasetLabels.kind(rowCategory = null, programType = "episode")
        )
        assertEquals(
            MediasetLabels.CardKind.MOVIE,
            MediasetLabels.kind(rowCategory = null, programType = "movie")
        )
    }

    @Test
    fun `la categoria si riconosce a prescindere dalle maiuscole`() {
        assertEquals(
            MediasetLabels.CardKind.KIDS,
            MediasetLabels.kind(rowCategory = "kids", programType = "episode")
        )
    }
}
