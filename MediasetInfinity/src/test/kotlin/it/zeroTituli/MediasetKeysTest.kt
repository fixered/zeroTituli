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
}
