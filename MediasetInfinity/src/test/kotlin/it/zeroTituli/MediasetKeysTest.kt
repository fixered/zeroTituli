package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le chiavi sono il punto in cui il plugin decide cosa aprire, e finiscono nei preferiti
 * salvati sul dispositivo: i valori attesi qui sono scritti a mano di proposito, perché
 * un test che confrontasse `brand(x)` con `"brand:" + x` non si accorgerebbe di un
 * prefisso cambiato — e un prefisso cambiato orfana i preferiti di chi ha già il plugin.
 */
class MediasetKeysTest {

    // ============= FORMATO SULLA RETE =============

    @Test
    fun `le chiavi delle schede hanno il formato di sempre`() {
        assertEquals("brand:100012714", MediasetKeys.brand("100012714"))
        assertEquals("series:SE000000002040", MediasetKeys.series("SE000000002040"))
        assertEquals("guid:F007651601000101", MediasetKeys.single("F007651601000101"))
        assertEquals("live:C5", MediasetKeys.live("C5"))
    }

    @Test
    fun `la chiave per titolo di programma ha il suo prefisso`() {
        assertEquals("program:Temptation Island", MediasetKeys.program("Temptation Island"))
    }

    @Test
    fun `le chiavi dei flussi hanno il formato di sempre`() {
        assertEquals("vod:F007651601000101", MediasetKeys.vod("F007651601000101"))
        // Per una diretta la scheda e il flusso portano la stessa chiave: era così prima
        // che ci fosse questo file e deve restare così, o le dirette salvate si rompono.
        assertEquals(MediasetKeys.live("C5"), "live:C5")
    }

    @Test
    fun `le chiavi delle righe hanno il formato di sempre`() {
        assertEquals("live", MediasetKeys.LIVE_ROW)
        assertEquals("section:news-e-sport", MediasetKeys.section("news-e-sport"))
        assertEquals("genre:Serie Tv", MediasetKeys.genre("Serie Tv"))
        assertEquals("az:Calcio e Sport", MediasetKeys.az("Calcio e Sport"))
    }

    // ============= ANDATA E RITORNO =============

    @Test
    fun `il marchio torna indietro uguale`() {
        assertEquals(
            MediasetKeys.Card.Brand("100012714"),
            MediasetKeys.card(MediasetKeys.brand("100012714"))
        )
    }

    @Test
    fun `il titolo di programma torna indietro uguale`() {
        assertEquals(
            MediasetKeys.Card.Program("Temptation Island"),
            MediasetKeys.card(MediasetKeys.program("Temptation Island"))
        )
    }

    @Test
    fun `un titolo con i due punti resta intero, perche il parsing e sul prefisso`() {
        // `card` riconosce la chiave guardando solo il prefisso `program:` e prendendo
        // tutto il resto della stringa: un titolo con `:` dentro (es. un sottotitolo) non
        // deve fermare il parsing al primo `:` che incontra.
        val title = "C'è posta per te: Puntata speciale"
        assertEquals(
            MediasetKeys.Card.Program(title),
            MediasetKeys.card(MediasetKeys.program(title))
        )
    }

    @Test
    fun `la stagione torna indietro uguale`() {
        assertEquals(
            MediasetKeys.Card.Series("SE000000002040"),
            MediasetKeys.card(MediasetKeys.series("SE000000002040"))
        )
    }

    @Test
    fun `il contenuto singolo torna indietro uguale`() {
        assertEquals(
            MediasetKeys.Card.Single("F007651601000101"),
            MediasetKeys.card(MediasetKeys.single("F007651601000101"))
        )
    }

    @Test
    fun `il canale torna indietro uguale`() {
        assertEquals(MediasetKeys.Card.Live("C5"), MediasetKeys.card(MediasetKeys.live("C5")))
    }

    @Test
    fun `il flusso VOD torna indietro uguale`() {
        assertEquals(
            MediasetKeys.Data.Vod("F007651601000101"),
            MediasetKeys.data(MediasetKeys.vod("F007651601000101"))
        )
    }

    @Test
    fun `il flusso della diretta torna indietro uguale`() {
        assertEquals(MediasetKeys.Data.Live("C5"), MediasetKeys.data(MediasetKeys.live("C5")))
    }

    @Test
    fun `le righe tornano indietro uguali`() {
        assertEquals(MediasetKeys.Row.Live, MediasetKeys.row(MediasetKeys.LIVE_ROW))
        assertEquals(
            MediasetKeys.Row.Section("fiction"),
            MediasetKeys.row(MediasetKeys.section("fiction"))
        )
        assertEquals(
            MediasetKeys.Row.Genre("Serie Tv"),
            MediasetKeys.row(MediasetKeys.genre("Serie Tv"))
        )
        assertEquals(
            MediasetKeys.Row.Az("Programmi Tv"),
            MediasetKeys.row(MediasetKeys.az("Programmi Tv"))
        )
    }

    @Test
    fun `un nome con spazi resta intero e non si ferma al primo pezzo`() {
        // `substringAfter(':')` andava bene, ma un domani con `split` uno si perderebbe
        // "Calcio e Sport" a metà: il valore torna intero, spazi compresi.
        assertEquals(
            MediasetKeys.Row.Az("Calcio e Sport"),
            MediasetKeys.row("az:Calcio e Sport")
        )
    }

    // ============= CHIAVI STORPIATE =============

    @Test
    fun `un prefisso senza valore non e una chiave`() {
        assertNull(MediasetKeys.card("brand:"))
        assertNull(MediasetKeys.card("program:"))
        assertNull(MediasetKeys.card("series:"))
        assertNull(MediasetKeys.card("guid:"))
        assertNull(MediasetKeys.card("live:"))
        assertNull(MediasetKeys.data("vod:"))
        assertNull(MediasetKeys.data("live:"))
        assertNull(MediasetKeys.row("section:"))
        assertNull(MediasetKeys.row("genre:"))
        assertNull(MediasetKeys.row("az:"))
    }

    @Test
    fun `un valore di soli spazi non e una chiave`() {
        assertNull(MediasetKeys.card("brand:   "))
        assertNull(MediasetKeys.row("genre: "))
    }

    @Test
    fun `un prefisso che non esiste non e una chiave`() {
        assertNull(MediasetKeys.card("marchio:100012714"))
        assertNull(MediasetKeys.card("100012714"))
        assertNull(MediasetKeys.card(""))
        assertNull(MediasetKeys.row("sezione:fiction"))
        assertNull(MediasetKeys.row(""))
    }

    @Test
    fun `le chiavi delle schede e quelle dei flussi non si confondono`() {
        // `vod:` non apre una scheda e `brand:` non apre un flusso: se un giorno le due
        // famiglie si mescolassero, `load` proverebbe a risolvere un flusso come scheda.
        assertNull(MediasetKeys.card("vod:F007651601000101"))
        assertNull(MediasetKeys.data("brand:100012714"))
        assertNull(MediasetKeys.data("guid:F007651601000101"))
    }

    @Test
    fun `una riga non e una scheda`() {
        assertNull(MediasetKeys.card("section:fiction"))
        assertNull(MediasetKeys.row("brand:100012714"))
    }

    // ============= PREFERITI VECCHI =============

    @Test
    fun `un preferito con l indirizzo del sito davanti torna una chiave`() {
        val site = "https://mediasetinfinity.mediaset.it"
        assertEquals("brand:100012714", MediasetKeys.strip("$site/brand:100012714", site))
        assertEquals("brand:100012714", MediasetKeys.strip("${site}brand:100012714", site))
        assertEquals(
            MediasetKeys.Card.Brand("100012714"),
            MediasetKeys.card(MediasetKeys.strip("$site/brand:100012714", site))
        )
    }

    @Test
    fun `una chiave nuda passa senza essere toccata`() {
        val site = "https://mediasetinfinity.mediaset.it"
        assertEquals("live:C5", MediasetKeys.strip("live:C5", site))
    }

    // ============= CHIAVE DELLA SCHEDA PER VOCE DI FEED =============

    /**
     * `cardKeyFor` è la regola che decide se una voce di feed finisce sotto una scheda per
     * titolo o per marchio: sbagliarla vuol dire tornare a cinque schede identiche per
     * "Temptation Island", oppure costruire una query storpiata con un titolo che porta
     * `{`, `}` o `|`. I valori attesi sono scritti a mano, non derivati dalla stessa
     * funzione che si vuole provare.
     */

    @Test
    fun `una voce di serie con titolo diventa una chiave per titolo`() {
        val entry = FeedEntry(
            guid = "FD1",
            programType = "episode",
            brandId = "100013024",
            brandTitle = "Temptation Island",
        )
        assertEquals("program:Temptation Island", MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `edizioni diverse dello stesso programma condividono la stessa chiave per titolo`() {
        // Sono i cinque marchi veri di "Temptation Island" (stagioni 10-14): brandId
        // diversi, stesso titolo, quindi la stessa chiave.
        val brandIds = listOf("100013024", "100014972", "100015201", "100016100", "100017150")
        val keys = brandIds.map { id ->
            MediasetKeys.cardKeyFor(
                FeedEntry(guid = id, programType = "episode", brandId = id, brandTitle = "Temptation Island")
            )
        }
        assertEquals(listOf("program:Temptation Island"), keys.distinct())
    }

    @Test
    fun `un film resta sulla chiave per marchio anche con un titolo valido`() {
        // Raggruppare i film per titolo non porta nulla — non hanno stagioni da unire — e
        // due film senza parentela possono chiamarsi uguale.
        val entry = FeedEntry(
            guid = "F1",
            programType = "movie",
            brandId = "100002609",
            brandTitle = "Il quarto Re",
        )
        assertEquals("brand:100002609", MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `un titolo con le graffe della sintassi dei filtri torna alla chiave per marchio`() {
        val entry = FeedEntry(
            guid = "FD2",
            programType = "episode",
            brandId = "100099999",
            brandTitle = "Un programma {strano}",
        )
        assertEquals("brand:100099999", MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `un titolo con la barra verticale della sintassi dei filtri torna alla chiave per marchio`() {
        val entry = FeedEntry(
            guid = "FD3",
            programType = "episode",
            brandId = "100099998",
            brandTitle = "Prima|Seconda",
        )
        assertEquals("brand:100099998", MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `un titolo vuoto torna alla chiave per marchio`() {
        val entry = FeedEntry(guid = "FD4", programType = "episode", brandId = "100099997", brandTitle = "")
        assertEquals("brand:100099997", MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `un titolo di soli spazi torna alla chiave per marchio`() {
        val entry = FeedEntry(guid = "FD5", programType = "episode", brandId = "100099996", brandTitle = "   ")
        assertEquals("brand:100099996", MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `senza titolo ne brandId non c e scheda`() {
        val entry = FeedEntry(guid = "FD6", programType = "episode", brandId = null, brandTitle = null)
        assertNull(MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `un titolo storpiato senza brandId non e comunque una scheda`() {
        val entry = FeedEntry(guid = "FD7", programType = "episode", brandId = null, brandTitle = "Con {graffe}")
        assertNull(MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `un film senza brandId non e una scheda`() {
        val entry = FeedEntry(guid = "F2", programType = "movie", brandId = null, brandTitle = "Un film")
        assertNull(MediasetKeys.cardKeyFor(entry))
    }

    @Test
    fun `il raggruppamento per chiave riunisce le edizioni e separa i programmi diversi`() {
        // Come farebbe `MediasetCatalog.brandCards`: cinque edizioni di "Temptation
        // Island" più due voci di un programma diverso devono dare due chiavi, non sette.
        val temptationEditions = listOf("100013024", "100014972", "100015201", "100016100", "100017150")
            .map { id -> FeedEntry(guid = id, programType = "episode", brandId = id, brandTitle = "Temptation Island") }
        val otherProgram = listOf(
            FeedEntry(guid = "U1", programType = "episode", brandId = "100020001", brandTitle = "Uomini e Donne"),
            FeedEntry(guid = "U2", programType = "episode", brandId = "100020002", brandTitle = "Uomini e Donne"),
        )
        val keys = (temptationEditions + otherProgram).mapNotNull { MediasetKeys.cardKeyFor(it) }.distinct()
        assertEquals(
            listOf("program:Temptation Island", "program:Uomini e Donne"),
            keys
        )
    }

    // ============= IL PREFISSO DI fixUrl =============

    @Test
    fun `un data col sito davanti torna a essere una chiave di flusso`() {
        // È il guasto vero visto sul telefono: `newEpisode` passa il `data` per `fixUrl`,
        // che a `vod:F310717301001004` mette davanti `mainUrl`. Il valore qui sotto è
        // quello letto dalla diagnostica sul dispositivo, non inventato.
        val site = "https://mediasetinfinity.mediaset.it"
        val prefissato = "$site/vod:F310717301001004"

        assertEquals(null, MediasetKeys.data(prefissato))
        assertEquals(
            MediasetKeys.Data.Vod("F310717301001004"),
            MediasetKeys.data(MediasetKeys.strip(prefissato, site))
        )
    }

    @Test
    fun `il sito davanti non disturba una chiave di flusso pulita`() {
        val site = "https://mediasetinfinity.mediaset.it"
        assertEquals(
            MediasetKeys.Data.Vod("F310717301001004"),
            MediasetKeys.data(MediasetKeys.strip("vod:F310717301001004", site))
        )
        assertEquals(
            MediasetKeys.Data.Live("C5"),
            MediasetKeys.data(MediasetKeys.strip("live:C5", site))
        )
    }
}
