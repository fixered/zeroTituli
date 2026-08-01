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
        // In secondi, come `mediasetprogram$duration` nel feed.
        seconds: Int? = null,
    ) = FeedEntry(
        guid = guid,
        title = "Ep $guid",
        programType = type,
        tvSeasonNumber = season,
        tvSeasonEpisodeNumber = number,
        subBrandId = subBrand,
        durationSeconds = seconds,
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

    // ============= FILM O SERIE: IL TRAILER TIPIZZATO `movie` =============

    @Test
    fun `un film col trailer tipizzato movie resta un film`() {
        // Il caso vero, misurato sul marchio 100000828 ("Seven"): due voci, entrambe
        // `programType=movie`, 7308 secondi il film e 49 il "Promo trailer - Seven (di d.
        // fincher)". Guardando solo il tipo ce n'erano due guardabili, quindi `onlyMovie`
        // veniva null e il film si apriva come `TvSeriesLoadResponse` da due puntate, con
        // la stagione chiamata "Extra e speciali" e il trailer accanto al film — mentre la
        // scheda di ricerca aveva promesso `TvType.Movie`. Succede su 1015 dei 6315 marchi
        // che hanno voci `movie`.
        val entries = listOf(
            ep("film", type = "movie", seconds = 7308),
            ep("promo", type = "movie", seconds = 49),
        )
        assertEquals(listOf("film", "promo"), MediasetSeasons.playable(entries).map { it.guid })
        assertEquals(listOf("film"), MediasetSeasons.features(entries).map { it.guid })
        assertEquals("film", MediasetSeasons.onlyMovie(entries)?.guid)
    }

    @Test
    fun `col trailer davanti al film la scheda prende comunque i dati dal film`() {
        // Sui sei marchi verificati il trailer arriva dopo il film, ma l'ordine del feed non
        // è garantito da niente: con [trailer, film] `head` prendeva il primo `movie`, cioè
        // il trailer, e la scheda si presentava col suo titolo e la sua copertina.
        val entries = listOf(
            ep("promo", type = "movie", seconds = 80),
            ep("film", type = "movie", seconds = 5960),
        )
        assertEquals("film", MediasetSeasons.head(entries)?.guid)
        assertEquals("film", MediasetSeasons.onlyMovie(entries)?.guid)
    }

    @Test
    fun `due film interi nello stesso marchio restano due`() {
        // Marchio 100000448 ("Un sacco bello"): due voci `movie` da 5638 e 5640 secondi.
        // Nessuna delle due è corta, quindi la regola non ne butta via nessuna e la scheda
        // resta una serie con due voci — che è quello che il marchio contiene.
        val entries = listOf(
            ep("film-a", type = "movie", seconds = 5638),
            ep("film-b", type = "movie", seconds = 5640),
        )
        assertEquals(2, MediasetSeasons.features(entries).size)
        assertEquals(null, MediasetSeasons.onlyMovie(entries))
    }

    @Test
    fun `un evento in due parti non si riduce a una`() {
        // Marchio 100010090: la diretta di un lancio spaziale in due parti, 3415 e 2615
        // secondi, entrambe `movie`. Una regola sul solo rapporto fra le durate avrebbe
        // potuto scartare la seconda parte; con la soglia dei dieci minuti non la sfiora.
        val entries = listOf(
            ep("parte1", type = "movie", seconds = 3415),
            ep("parte2", type = "movie", seconds = 2615),
        )
        assertEquals(2, MediasetSeasons.features(entries).size)
    }

    @Test
    fun `un film da solo nel suo marchio e un film`() {
        val entries = listOf(ep("film", type = "movie", seconds = 5619))
        assertEquals("film", MediasetSeasons.onlyMovie(entries)?.guid)
        assertEquals("film", MediasetSeasons.head(entries)?.guid)
    }

    @Test
    fun `un cortometraggio da solo nel suo marchio non viene scambiato per un promo`() {
        // La ragione per cui la durata assoluta non basta e serve anche il rapporto: 290
        // secondi sono sotto la soglia dei dieci minuti, ma questa voce è l'unica cosa
        // guardabile del marchio, e la voce più lunga non è mai un promo. Senza il rapporto,
        // `features` avrebbe restituito una lista vuota e la scheda non si sarebbe aperta.
        val entries = listOf(ep("corto", type = "movie", seconds = 290))
        assertEquals(listOf("corto"), MediasetSeasons.features(entries).map { it.guid })
        assertEquals("corto", MediasetSeasons.onlyMovie(entries)?.guid)
    }

    @Test
    fun `senza durate dichiarate non si butta via niente`() {
        // Il feed la durata la scrive sempre, ma se un giorno non la scrivesse la regola
        // non deve inventare: due voci senza durata restano due.
        val entries = listOf(ep("a", type = "movie"), ep("b", type = "movie"))
        assertEquals(2, MediasetSeasons.features(entries).size)
        assertEquals(null, MediasetSeasons.onlyMovie(entries))
    }

    @Test
    fun `una serie vera non diventa un film per colpa di una clip corta`() {
        // Le puntate sono lunghe e sono due: il marchio è una serie, e resta una serie
        // anche se la clip di trenta secondi in mezzo viene riconosciuta come promo.
        val entries = listOf(
            ep("s1e1", season = 1, number = 1, seconds = 2400),
            ep("s1e2", season = 1, number = 2, seconds = 2400),
            ep("clip", type = "movie", seconds = 30),
        )
        assertEquals(listOf("s1e1", "s1e2"), MediasetSeasons.features(entries).map { it.guid })
        assertEquals(null, MediasetSeasons.onlyMovie(entries))
        // E la clip resta comunque nell'elenco delle puntate: `arrange` legge le voci
        // grezze, quindi il filtro dei promo non fa sparire niente dalla scheda.
        assertEquals(3, MediasetSeasons.arrange(entries).size)
    }

    // ============= LE VOCI VERE SENZA NUMERO DI STAGIONE =============

    @Test
    fun `un film senza numero di stagione non finisce fra gli extra`() {
        // Nel feed i film arrivano sempre con `tvSeasonNumber` nullo (verificato sui marchi
        // 100000448 e 100010090). Mandandoli in `EXTRAS_SEASON` la scheda annunciava un film
        // intero sotto "Extra e speciali", cioè come materiale di contorno.
        val out = MediasetSeasons.arrange(
            listOf(
                ep("film-a", type = "movie", seconds = 5638),
                ep("film-b", type = "movie", seconds = 5640),
            )
        )
        assertEquals(
            listOf(MediasetSeasons.UNNUMBERED_SEASON, MediasetSeasons.UNNUMBERED_SEASON),
            out.map { it.season }
        )
        assertTrue(out.none { it.season == MediasetSeasons.EXTRAS_SEASON })
    }

    @Test
    fun `le voci senza stagione stanno dopo le stagioni vere e prima degli extra`() {
        // L'ordine dei tre numeri è il motivo per cui 998 sta fra le stagioni e 999.
        val out = MediasetSeasons.arrange(
            listOf(
                ep("extra", type = "extra"),
                ep("film", type = "movie", seconds = 5000),
                ep("s1e1", season = 1, number = 1, seconds = 2400),
            )
        )
        assertEquals(listOf("s1e1", "film", "extra"), out.map { it.entry.guid })
        assertEquals(
            listOf(1, MediasetSeasons.UNNUMBERED_SEASON, MediasetSeasons.EXTRAS_SEASON),
            out.map { it.season }
        )
    }

    @Test
    fun `un extra senza stagione resta fra gli extra e non fra le voci senza numero`() {
        val out = MediasetSeasons.arrange(listOf(ep("extra", type = "extra")))
        assertEquals(MediasetSeasons.EXTRAS_SEASON, out.single().season)
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
