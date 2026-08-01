package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le prove dell'ordine dei riquadri.
 *
 * I casi della ricerca non sono inventati: sono l'ordine che il feed ha davvero
 * restituito il 2026-08-01 per quelle tre richieste, marchio per marchio. Un caso
 * inventato proverebbe la funzione contro se stessa; questi provano che il problema
 * segnalato — il programma cercato ottavo — non c'è più.
 *
 * I diritti sono quelli veri del feed: `MediasetPlay_AVOD` per il gratuito,
 * `TVCrime_SVOD` per l'abbonamento, `Noleggio_TVOD` per il noleggio.
 */
class MediasetRankingTest {

    private val free = listOf("MediasetPlay_ANY", "AVOD", "MediasetPlay_AVOD")
    private val svod = listOf("TVCrime_ANY", "SVOD", "TVCrime_SVOD")
    private val tvod = listOf("Noleggio_ANY", "TVOD", "Noleggio_TVOD")

    /** Una voce di feed ridotta a quel che l'ordinamento guarda: nome e diritti. */
    private fun brand(title: String, rights: List<String> = free) =
        FeedEntry(brandTitle = title, rights = rights)

    private fun titles(entries: List<FeedEntry>) = entries.map { it.brandTitle }

    // ============= RICERCA: I TRE CASI VERI =============

    @Test
    fun `la promessa mette il programma omonimo davanti a tutti`() {
        // Ordine vero del feed per `q=la promessa`: il programma cercato arrivava
        // **ottavo**, dietro a un cartone animato e a una soap con una parola in comune.
        val fromFeed = listOf(
            brand("Arriva Cristina"),
            brand("Terra promessa"),
            brand("Striscia la notizia"),
            brand("Forum"),
            brand("Casa Vianello 2"),
            brand("Che campioni Holly e Benji!"),
            brand("Stargate SG-1 8"),
            brand("La promessa"),
        )

        val ordered = titles(MediasetRanking.ordered(fromFeed, "la promessa"))

        assertEquals(
            listOf(
                // il nome è la richiesta
                "La promessa",
                // una parola in comune, ed è quella che pesa: otto lettere
                "Terra promessa",
                // una parola in comune, ma è l'articolo: due lettere
                "Striscia la notizia",
                // niente in comune: restano, nell'ordine in cui il feed le ha date
                "Arriva Cristina",
                "Forum",
                "Casa Vianello 2",
                "Che campioni Holly e Benji!",
                "Stargate SG-1 8",
            ),
            ordered
        )
    }

    @Test
    fun `grande fratello tiene insieme le tre edizioni e le mette in cima`() {
        val fromFeed = listOf(
            brand("Grande Fratello - L'inizio"),
            brand("Studio Aperto"),
            brand("Striscia la notizia"),
            brand("Grande Fratello VIP"),
            brand("Grande Fratello"),
            brand("Domenica Live"),
            brand("TG5"),
            brand("SportMediaset"),
        )

        val ordered = titles(MediasetRanking.ordered(fromFeed, "grande fratello"))

        assertEquals(
            listOf(
                "Grande Fratello",
                // I due che cominciano con la richiesta, nell'ordine del feed: fra loro
                // non c'è motivo di preferirne uno, e inventarne uno butterebbe via
                // l'ordine per novità che il feed ha dato.
                "Grande Fratello - L'inizio",
                "Grande Fratello VIP",
                "Studio Aperto",
                "Striscia la notizia",
                "Domenica Live",
                "TG5",
                "SportMediaset",
            ),
            ordered
        )
        // La forma della richiesta dell'utente: i tre Grande Fratello prima dei telegiornali.
        val gf = ordered.indexOfLast { it!!.startsWith("Grande Fratello") }
        assertTrue("i tre Grande Fratello devono stare tutti sopra Studio Aperto e TG5", gf == 2)
    }

    @Test
    fun `temptation island non lascia davanti Uomini e Donne`() {
        val fromFeed = listOf(
            brand("Temptation Island"),
            brand("Temptation"),
            brand("Uomini e Donne"),
            brand("Verissimo"),
            brand("Mattino Cinque"),
            brand("TG5"),
        )

        assertEquals(
            listOf(
                "Temptation Island",
                // metà richiesta trovata: sopra a chi non ne ha nemmeno una parola
                "Temptation",
                "Uomini e Donne",
                "Verissimo",
                "Mattino Cinque",
                "TG5",
            ),
            titles(MediasetRanking.ordered(fromFeed, "temptation island"))
        )

        // Questa è la richiesta che il feed già serviva quasi bene, quindi l'asserzione
        // qui sopra da sola passerebbe anche senza ordinare niente. Le stesse voci con il
        // programma cercato a pagamento provano la cosa che conta: la rilevanza comanda, e
        // `Temptation Island` resta primo invece di finire in coda ai cinque gratuiti.
        val aPagamento = listOf(brand("Temptation Island", svod)) + fromFeed.drop(1)
        assertEquals(
            "Temptation Island",
            titles(MediasetRanking.ordered(aPagamento, "temptation island")).first()
        )
    }

    @Test
    fun `i risultati che non c entrano restano, in fondo`() {
        // Chi cerca il titolo di una puntata invece del nome del programma deve comunque
        // trovare la sua voce: si spostano, non si buttano.
        val fromFeed = listOf(brand("Forum"), brand("La promessa"), brand("TG5"))
        val ordered = MediasetRanking.ordered(fromFeed, "la promessa")
        assertEquals(3, ordered.size)
        assertEquals(listOf("La promessa", "Forum", "TG5"), titles(ordered))
    }

    // ============= COME SI CONFRONTANO I TESTI =============

    @Test
    fun `accenti maiuscole e spazi di troppo non contano`() {
        // Nessuno cerca con gli accenti giusti, e la tastiera del telefono raddoppia gli
        // spazi: se una di queste tre cose contasse, la ricerca del programma fallirebbe
        // e l'utente vedrebbe di nuovo l'elenco a caso.
        assertEquals(
            MediasetRanking.Match.EXACT,
            MediasetRanking.match("  CITTÀ   segrete ", "Città Segrète")
        )
        assertEquals(
            MediasetRanking.Match.EXACT,
            MediasetRanking.match("perche", "Perché")
        )
        assertEquals("citta segrete", MediasetRanking.normalize("  Città   Segrète  "))

        // E l'ordine, non solo il confronto: la richiesta con gli accenti sbagliati deve
        // comunque mettere il programma in cima.
        val fromFeed = listOf(brand("Forum"), brand("Città Segrète"))
        assertEquals(
            listOf("Città Segrète", "Forum"),
            titles(MediasetRanking.ordered(fromFeed, "CITTA  segrete"))
        )
    }

    @Test
    fun `la scala dei gradini è quella dichiarata`() {
        // Ogni gradino con un caso che sta solo su quello: se due gradini si
        // confondessero, l'ordine cambierebbe senza che nessun altro test se ne accorga.
        assertEquals(
            MediasetRanking.Match.EXACT,
            MediasetRanking.match("grande fratello", "Grande Fratello")
        )
        assertEquals(
            MediasetRanking.Match.PREFIX,
            MediasetRanking.match("grande fratello", "Grande Fratello VIP")
        )
        assertEquals(
            MediasetRanking.Match.CONTAINS,
            MediasetRanking.match("fratello", "Il Grande Fratello")
        )
        assertEquals(
            MediasetRanking.Match.ALL_WORDS,
            MediasetRanking.match("fratello grande", "Grande Fratello")
        )
        assertEquals(
            MediasetRanking.Match.SOME_WORDS,
            MediasetRanking.match("temptation island", "Temptation")
        )
        assertEquals(
            MediasetRanking.Match.NONE,
            MediasetRanking.match("temptation island", "Uomini e Donne")
        )
        // Una richiesta vuota non promuove niente: la ricerca senza testo non esiste, ma
        // un titolo vuoto sì, e non deve diventare un risultato esatto.
        assertEquals(MediasetRanking.Match.NONE, MediasetRanking.match("", "Forum"))
        assertEquals(MediasetRanking.Match.NONE, MediasetRanking.match("forum", null))
    }

    // ============= GRATIS PRIMA =============

    @Test
    fun `senza richiesta i gratuiti passano davanti e l ordine del feed resta`() {
        // Il caso della segnalazione: la riga Cinema, dove le prime voci sono tutte da
        // abbonamento e chi apre la riga trova schede che non partono.
        val row = listOf(
            brand("Delitto ai Caraibi", svod),
            brand("Oppenheimer", tvod),
            brand("Alaska, la terra degli orsi", free),
            brand("Una figlia in vendita", svod),
            brand("Il prescelto", free),
        )

        assertEquals(
            listOf(
                // I due gratuiti, nell'ordine in cui stavano nel feed
                "Alaska, la terra degli orsi",
                "Il prescelto",
                // e dietro i tre a pagamento, anche loro nell'ordine del feed
                "Delitto ai Caraibi",
                "Oppenheimer",
                "Una figlia in vendita",
            ),
            titles(MediasetRanking.ordered(row))
        )
    }

    @Test
    fun `l ordine dentro i due gruppi è esattamente quello del feed`() {
        // La riga per categoria è ordinata per novità e la riga A-Z per titolo: quell'ordine
        // è l'unica cosa che quelle query chiedono al feed, e un ordinamento non stabile lo
        // butterebbe via senza che si veda. Dieci voci alternate: se la stabilità saltasse,
        // i numeri dentro ciascun gruppo uscirebbero rimescolati.
        val row = (1..10).map { n ->
            brand("Programma $n", if (n % 2 == 0) free else svod)
        }
        assertEquals(
            listOf(
                "Programma 2", "Programma 4", "Programma 6", "Programma 8", "Programma 10",
                "Programma 1", "Programma 3", "Programma 5", "Programma 7", "Programma 9",
            ),
            titles(MediasetRanking.ordered(row))
        )
    }

    @Test
    fun `due gratuiti non si scambiano di posto`() {
        val row = listOf(brand("Primo", free), brand("Secondo", free))
        assertEquals(listOf("Primo", "Secondo"), titles(MediasetRanking.ordered(row)))
    }

    @Test
    fun `un a pagamento che stava primo finisce dietro tutti i gratuiti`() {
        val row = listOf(
            brand("A pagamento", svod),
            brand("Gratis uno", free),
            brand("Gratis due", free),
        )
        assertEquals(
            listOf("Gratis uno", "Gratis due", "A pagamento"),
            titles(MediasetRanking.ordered(row))
        )
    }

    @Test
    fun `senza il campo dei diritti nessuno si sposta`() {
        // È lo stato in cui era il catalogo prima: la proiezione `fields=` non portava
        // `channelsRights`, quindi `isFree` era falso per tutti e l'ordinamento non
        // ordinava niente. Il test lo scrive nero su bianco, così si capisce che a tenere
        // in piedi la regola non è questo file ma la proiezione in `MediasetUrls`.
        val row = listOf(brand("Uno", emptyList()), brand("Due", emptyList()))
        assertEquals(listOf("Uno", "Due"), titles(MediasetRanking.ordered(row)))
    }

    // ============= CHI COMANDA FRA LE DUE REGOLE =============

    @Test
    fun `nella ricerca la rilevanza viene prima del prezzo`() {
        // Un risultato esatto a pagamento è più utile da vedere di un programma gratuito
        // che non c'entra niente: il primo porta l'etichetta "Abbonamento" e si riconosce,
        // il secondo occuperebbe la prima riga senza motivo.
        val fromFeed = listOf(
            brand("Forum", free),
            brand("La promessa", svod),
            brand("Terra promessa", free),
        )
        assertEquals(
            listOf("La promessa", "Terra promessa", "Forum"),
            titles(MediasetRanking.ordered(fromFeed, "la promessa"))
        )
    }

    @Test
    fun `a pari rilevanza passa davanti il gratuito`() {
        // Stesso gradino della scala — due nomi che cominciano con la richiesta — e allora
        // decide il prezzo: quello che si apre davvero va prima.
        val fromFeed = listOf(
            brand("Grande Fratello VIP", svod),
            brand("Grande Fratello Party", free),
        )
        assertEquals(
            listOf("Grande Fratello Party", "Grande Fratello VIP"),
            titles(MediasetRanking.ordered(fromFeed, "grande fratello"))
        )
    }

    @Test
    fun `il nome usato per ordinare è quello che il riquadro mostra`() {
        // `MediasetCatalog.toSearchResponse` scrive `brandTitle`, e se manca il titolo
        // della voce: ordinare su una stringa e mostrarne un'altra darebbe una lista che
        // sembra ordinata a caso proprio come quella di prima.
        val senzaMarchio = FeedEntry(title = "La promessa", rights = free)
        assertEquals("La promessa", MediasetRanking.cardTitle(senzaMarchio))
        assertEquals(
            listOf("La promessa", "Forum"),
            MediasetRanking.ordered(listOf(brand("Forum"), senzaMarchio), "la promessa")
                .map { MediasetRanking.cardTitle(it) }
        )
    }

    @Test
    fun `una richiesta di soli spazi vale come nessuna richiesta`() {
        // `ordered(entries, "   ")` non deve mettere tutto sul gradino NONE e lasciare
        // l'ordine com'è: deve comportarsi come una riga di catalogo, cioè gratis prima.
        val row = listOf(brand("A pagamento", svod), brand("Gratis", free))
        assertEquals(listOf("Gratis", "A pagamento"), titles(MediasetRanking.ordered(row, "   ")))
        assertEquals(listOf("Gratis", "A pagamento"), titles(MediasetRanking.ordered(row, null)))
    }
}
