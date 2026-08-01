package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le prove della riscrittura del manifest della diretta.
 *
 * Il campione `mpd-live-i1.xml` è un manifest vero di Italia 1, scaricato dal CDN così com'è.
 * Il corpo non contiene credenziali — il `hdnts` sta solo nell'indirizzo — e l'indirizzo qui
 * sotto porta un `hmac` sostituito con degli zeri: la forma della query resta quella vera,
 * che è l'unica cosa che la riscrittura guarda.
 */
class MediasetMpdTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val manifestUrl =
        "https://live03t-col.msf.cdn.mediaset.net/live/ch-i1/i1-clr.isml/manifest_sd.mpd" +
            "?hdnts=st=1785620989~exp=1785635419~acl=/live/ch-i1/i1-clr.isml*" +
            "~hmac=0000000000000000000000000000000000000000000000000000000000000000"

    private val query =
        "hdnts=st=1785620989~exp=1785635419~acl=/live/ch-i1/i1-clr.isml*" +
            "~hmac=0000000000000000000000000000000000000000000000000000000000000000"

    private val cdnDir = "https://live03t-col.msf.cdn.mediaset.net/live/ch-i1/i1-clr.isml/"

    private fun rewritten() = MediasetMpd.rewrite(fixture("mpd-live-i1.xml"), manifestUrl)

    // ============= IL CAMPIONE VERO =============

    /**
     * La prova che conta: dal manifest riscritto si ricava l'indirizzo del segmento come lo
     * costruisce il lettore — `BaseURL` più il template con `$RepresentationID$` sostituito — e
     * quell'indirizzo deve essere quello del CDN, con il permesso attaccato. È esattamente
     * l'indirizzo che, provato con `curl`, risponde `200` invece di `403`.
     */
    @Test
    fun `l'indirizzo del segmento ricomposto punta al CDN col permesso`() {
        val out = rewritten()
        val base = Regex("<BaseURL>([^<]*)</BaseURL>").find(out)!!.groupValues[1]
        val template = Regex("""initialization="([^"]*)"""").find(out)!!.groupValues[1]
        val segment = base + template.replace("\$RepresentationID\$", "video=2200000")
        assertEquals(
            cdnDir + "dash/i1-clr-video=2200000.dash?" + query,
            segment
        )
    }

    /** Senza questo il lettore chiederebbe `dash/...` al proxy, che non ha segmenti da dare. */
    @Test
    fun `il BaseURL relativo diventa la cartella assoluta sul CDN`() {
        assertTrue(fixture("mpd-live-i1.xml").contains("<BaseURL>dash/</BaseURL>"))
        assertTrue(rewritten().contains("<BaseURL>${cdnDir}dash/</BaseURL>"))
    }

    /** Nessun indirizzo può restare relativo: sarebbe una richiesta al proxy. */
    @Test
    fun `nel manifest riscritto non resta nessun indirizzo relativo`() {
        val out = rewritten()
        assertFalse(out.contains("<BaseURL>dash/</BaseURL>"))
        Regex("""(initialization|media)="([^"]*)"""").findAll(out).forEach { m ->
            val value = m.groupValues[2]
            // Con il `BaseURL` assoluto i template restano relativi a lui: quello che non
            // devono perdere è il permesso.
            assertTrue("template senza permesso: $value", value.contains("?$query"))
        }
    }

    /** Il permesso su **tutti** i template, non solo sul primo: audio e video sono due. */
    @Test
    fun `ogni template audio e video si porta dietro la query`() {
        val out = rewritten()
        assertEquals(2, Regex("""initialization="[^"]*"""").findAll(out).count())
        assertEquals(2, Regex("""\bmedia="[^"]*"""").findAll(out).count())
        assertEquals(4, Regex(Regex.escape("?$query")).findAll(out).count())
    }

    /**
     * Il tranello vero della riscrittura: `Regex.replace` con una stringa di sostituzione
     * tratterebbe `$RepresentationID$` come riferimento a un gruppo e lo mangerebbe. I
     * segnaposto li sostituisce il lettore, e senza di loro non esiste nessun segmento.
     */
    @Test
    fun `i segnaposto sopravvivono alla riscrittura`() {
        val out = rewritten()
        assertTrue(out.contains("i1-clr-\$RepresentationID\$.dash?"))
        assertTrue(out.contains("i1-clr-\$RepresentationID\$-\$Time\$.dash?"))
        assertEquals(4, Regex(Regex.escape("\$RepresentationID\$")).findAll(out).count())
        assertEquals(2, Regex(Regex.escape("\$Time\$")).findAll(out).count())
    }

    /** Quello che non è un indirizzo non si tocca: la finestra dei segmenti e i tempi. */
    @Test
    fun `il resto del manifest resta com'era`() {
        val out = rewritten()
        assertTrue(out.contains("""<S t="85709802622080" d="184320" r="31" />"""))
        assertTrue(out.contains("""minimumUpdatePeriod="PT4S""""))
        assertTrue(out.contains("""value="https://time.akamai.com/?iso""""))
        assertEquals(2, Regex("""<Representation""").findAll(out).count())
    }

    // ============= LE ALTRE FORME =============

    /**
     * TGCOM24 (`KF`) sta su un altro host e il suo manifest arriva senza query: i segmenti
     * sono aperti (provato: `200` senza token). Un `?` vuoto attaccato per simmetria sarebbe
     * un indirizzo diverso, cacheato a parte e inutile.
     */
    @Test
    fun `senza query nell'indirizzo non si attacca niente`() {
        val out = MediasetMpd.rewrite(
            """<MPD><BaseURL>dash/</BaseURL><SegmentTemplate initialization="kf-clr-${'$'}RepresentationID${'$'}.dash" /></MPD>""",
            "https://live03-col.msf.cdn.mediaset.net/live/ch-kf/kf-clr.isml/manifest_sd.mpd"
        )
        assertTrue(
            out.contains(
                "<BaseURL>https://live03-col.msf.cdn.mediaset.net/live/ch-kf/kf-clr.isml/dash/</BaseURL>"
            )
        )
        assertTrue(out.contains("""initialization="kf-clr-${'$'}RepresentationID${'$'}.dash""""))
        assertFalse(out.contains("?"))
    }

    /**
     * Senza `<BaseURL>` la base sarebbe l'indirizzo del manifest, che servito dal proxy è il
     * proxy: allora sono i template a doversi allungare, altrimenti il lettore chiede i
     * segmenti a `127.0.0.1`.
     */
    @Test
    fun `senza BaseURL sono i template a diventare assoluti`() {
        val out = MediasetMpd.rewrite(
            """<MPD><SegmentTemplate initialization="i1-clr-${'$'}RepresentationID${'$'}.dash" media="i1-clr-${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.dash" /></MPD>""",
            manifestUrl
        )
        assertTrue(out.contains("""initialization="${cdnDir}i1-clr-${'$'}RepresentationID${'$'}.dash?$query""""))
        assertTrue(
            out.contains("""media="${cdnDir}i1-clr-${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.dash?$query"""")
        )
    }

    /** Un `<BaseURL>` già assoluto punta dove deve: riscriverlo lo romperebbe soltanto. */
    @Test
    fun `un BaseURL gia assoluto resta com'e`() {
        val out = MediasetMpd.rewrite(
            """<MPD><BaseURL>https://altro.cdn.example/x/</BaseURL><SegmentTemplate media="s-${'$'}Time${'$'}.dash" /></MPD>""",
            manifestUrl
        )
        assertTrue(out.contains("<BaseURL>https://altro.cdn.example/x/</BaseURL>"))
        assertTrue(out.contains("""media="s-${'$'}Time${'$'}.dash?$query""""))
    }

    /** Un `<BaseURL>` con la barra iniziale è relativo all'host, non alla cartella. */
    @Test
    fun `un BaseURL con la barra iniziale si risolve sull'host`() {
        val out = MediasetMpd.rewrite(
            """<MPD><BaseURL>/altro/dash/</BaseURL><SegmentTemplate media="s.dash" /></MPD>""",
            manifestUrl
        )
        assertTrue(
            out.contains("<BaseURL>https://live03t-col.msf.cdn.mediaset.net/altro/dash/</BaseURL>")
        )
    }

    /** Un template che ha già una sua query non la perde: il permesso si aggiunge con `&`. */
    @Test
    fun `un template con query propria tiene la sua e prende il permesso`() {
        val out = MediasetMpd.rewrite(
            """<MPD><BaseURL>dash/</BaseURL><SegmentTemplate media="s-${'$'}Time${'$'}.dash?v=2" /></MPD>""",
            manifestUrl
        )
        assertTrue(out.contains("""media="s-${'$'}Time${'$'}.dash?v=2&$query""""))
    }

    /**
     * Fuori da un `SegmentTemplate` un attributo che si chiama `media` non è un indirizzo:
     * attaccargli il permesso vorrebbe dire storpiare un valore che il lettore legge come
     * numero o come tipo.
     */
    @Test
    fun `gli attributi fuori dal SegmentTemplate non si toccano`() {
        val out = MediasetMpd.rewrite(
            """<MPD mediaPresentationDuration="PT0S"><Representation media="non-un-indirizzo" /><SegmentTemplate media="s.dash" /></MPD>""",
            manifestUrl
        )
        assertTrue(out.contains("""<Representation media="non-un-indirizzo" />"""))
        assertTrue(out.contains("""mediaPresentationDuration="PT0S""""))
        assertTrue(out.contains("""<SegmentTemplate media="${cdnDir}s.dash?$query" />"""))
    }
}
