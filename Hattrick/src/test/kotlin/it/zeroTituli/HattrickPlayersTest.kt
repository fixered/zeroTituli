package it.zeroTituli

import it.zeroTituli.shared.Mpd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I frammenti sono copiati dalle pagine vere di htsport.org e dei player che incorpora:
 * è l'unico modo di accorgersi che una di quelle pagine ha cambiato forma.
 */
class HattrickPlayersTest {

    // ============= ESTENSIONE PER CHROME (canali TIM) =============

    private val extensionPage = """
        ATTENZIONE! * FUNZIONA SOLO CON CHROME SCARICANDO UN ESTENSIONE CHE LEGGE IL PLAYER
        <iframe class="shadow-lg rounded" width="800" name="iframe" id="iframe" title="Live Video"
         allow="autoplay; encrypted-media" allowfullscreen=""
         src="chrome-extension://opmeopcambhfimffbomjgemehjkbbmji/pages/player.html#https://timlivetu0.cb.ticdn.it/Content/DASH/Live/channel(eurosport1)/manifest.mpd?ck=NjEwYmNkYTExMWM3NGM5N2IwNzkyYjA1OTYzMGExMGI6Yjk4MTc4NTM1Mzg0NTliMzcxZjNmYjU2YTI2N2Q1NWM="></iframe>
    """.trimIndent()

    @Test
    fun `legge indirizzo e chiavi del player dell'estensione`() {
        val found = HattrickPlayers.stream(extensionPage)
        assertEquals(
            "https://timlivetu0.cb.ticdn.it/Content/DASH/Live/channel(eurosport1)/manifest.mpd" +
                "?ck=NjEwYmNkYTExMWM3NGM5N2IwNzkyYjA1OTYzMGExMGI6Yjk4MTc4NTM1Mzg0NTliMzcxZjNmYjU2YTI2N2Q1NWM=",
            found?.url
        )
        assertEquals("610bcda111c74c97b0792b059630a10b", found?.clearKey?.kid)
        assertEquals("b9817853538459b371f3fb56a267d55c", found?.clearKey?.key)
    }

    @Test
    fun `l'iframe dell'estensione non viene seguito come iframe normale`() {
        assertNull(HattrickPlayers.playerIframe(extensionPage))
    }

    @Test
    fun `ck che contiene un indirizzo invece delle chiavi viene ignorato`() {
        // aHR0cHM6Ly9rLmV4YW1wbGUvaw== = "https://k.example/k"
        assertNull(HattrickPlayers.clearKeyOf("https://x/y.mpd?ck=aHR0cHM6Ly9rLmV4YW1wbGUvaw=="))
    }

    // ============= FAMIGLIA DADDYLIVE (atob) =============

    @Test
    fun `legge l'indirizzo dentro atob`() {
        val page = """
            var player = new Clappr.Player({
              source:window.atob('aHR0cHM6Ly94YW1lbGVvbi5waGFudGVtbGlzLnRvcC9vbmUvc2VjdXJlL2FjOGJlNDQyNjMzNzMxYjMxODlmMGNmMjY1ZWJmYTMzLzE3ODYwMzEyNjgvcHJlbWl1bTE0L2luZGV4Lm0zdTg='),
              mimeType: "application/x-mpegURL",
            });
        """.trimIndent()
        assertEquals(
            "https://xameleon.phantemlis.top/one/secure/ac8be442633731b3189f0cf265ebfa33/1786031268/premium14/index.m3u8",
            HattrickPlayers.stream(page)?.url
        )
    }

    @Test
    fun `un atob che non contiene una playlist non viene preso`() {
        // "ciao ciao ciao ciao ciao" in base64: nessun indirizzo dentro
        assertNull(HattrickPlayers.atobStream("atob('Y2lhbyBjaWFvIGNpYW8gY2lhbyBjaWFv')"))
    }

    // ============= INDIRIZZO SPEZZATO IN CARATTERI =============

    @Test
    fun `ricompone l'indirizzo dall'array di caratteri e dalle sue code`() {
        val page = """
            var coda = ["&","x","=","1"];
            <span style='display:none' id=tail>7</span>
            function get() {
              return(["h","t","t","p","s",":","\/","\/","c","d","n",".","x",".","c","o","m","\/","a",".","m","3","u","8","?","m","d","5","="].join("")
                + coda.join("") + document.getElementById("tail").innerHTML);
            }
        """.trimIndent()
        assertEquals("https://cdn.x.com/a.m3u8?md5=&x=17", HattrickPlayers.stream(page)?.url)
    }

    @Test
    fun `le code vuote non aggiungono niente`() {
        val page = """
            var coda = [""];
            <span style='display:none' id=tail></span>
            return(["h","t","t","p","s",":","\/","\/","a","\/","b",".","m","3","u","8"].join("")
              + coda.join("") + document.getElementById("tail").innerHTML);
        """.trimIndent()
        assertEquals("https://a/b.m3u8", HattrickPlayers.stream(page)?.url)
    }

    // ============= CAMPI DEL PLAYER =============

    @Test
    fun `legge streamUrl con le barre sfuggite`() {
        val page = """
            const config = {
              streamUrl: "https:\/\/ds164.bluetier.top\/SkySportCalcioIT\/index.m3u8?token=abc.def",
              channelSlug: "SkySportCalcioIT",
              tokenExpiresIn: 300,
            };
        """.trimIndent()
        assertEquals(
            "https://ds164.bluetier.top/SkySportCalcioIT/index.m3u8?token=abc.def",
            HattrickPlayers.stream(page)?.url
        )
    }

    @Test
    fun `legge la playlist passata a hls loadSource`() {
        val page = """hls.loadSource("https://cdn.example/live/index.m3u8?t=9");"""
        assertEquals("https://cdn.example/live/index.m3u8?t=9", HattrickPlayers.stream(page)?.url)
    }

    // ============= IFRAME =============

    @Test
    fun `preferisce l'iframe del player e scarta i riquadri pubblicitari`() {
        val page = """
            <iframe src="https://www.freeshot.live/ads/300v250.php" width="300" height="250"></iframe>
            <iframe src="//popcdn.day/go.php?stream=SkySportCalcioIT" allowfullscreen="true" id="thatframe"></iframe>
        """.trimIndent()
        assertEquals("//popcdn.day/go.php?stream=SkySportCalcioIT", HattrickPlayers.playerIframe(page))
    }

    @Test
    fun `senza allowfullscreen prende il primo iframe utile`() {
        val page = """
            <iframe src="about:blank"></iframe>
            <iframe src="https://damitv.st/embed/channel/?id=eurosport-1-italy" width="100%"></iframe>
        """.trimIndent()
        assertEquals(
            "https://damitv.st/embed/channel/?id=eurosport-1-italy",
            HattrickPlayers.playerIframe(page)
        )
    }

    // ============= RIPIEGO DADDYLIVE =============

    @Test
    fun `trova il numero del canale nell'indirizzo del player morto`() {
        val hops = listOf(
            "https://htsport.org/sportarenahd.htm",
            "https://dami-tv.pro/player/hls/?v=300&preroll=1&resolve=dlhd-462&name=Sky%20Sport%20Arena",
        )
        assertEquals("462", HattrickPlayers.daddyId(hops))
    }

    @Test
    fun `un id qualsiasi non viene confuso con un canale daddylive`() {
        assertNull(HattrickPlayers.daddyId(listOf("https://s1.nexa.st/ch.php?id=47")))
    }

    // ============= MANIFEST DASH =============

    private val manifest = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <MPD profiles="urn:mpeg:dash:profile:isoff-live:2011" type="dynamic" minimumUpdatePeriod="PT2.0S"
         xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013">
        	<Period start="PT0S" id="1">
        		<AdaptationSet mimeType="video/mp4" startWithSAP="1">
        			<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
        			<ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"></ContentProtection>
        			<SegmentTemplate timescale="10000000" media="${'$'}RepresentationID${'$'}_Segment-${'$'}Time${'$'}.m4v" initialization="${'$'}RepresentationID${'$'}_init.m4i">
        			</SegmentTemplate>
        			<Representation width="384" height="216" id="1780606880879item-01item" bandwidth="350000" />
        		</AdaptationSet>
        	</Period>
        </MPD>
    """.trimIndent()

    @Test
    fun `ricava il percorso del segmento di inizializzazione`() {
        assertEquals("1780606880879item-01item_init.m4i", HattrickPlayers.initPath(manifest))
    }

    @Test
    fun `un modello con segnaposto non risolti non viene usato`() {
        val senzaRappresentazione = manifest.replace(
            """<Representation width="384" height="216" id="1780606880879item-01item" bandwidth="350000" />""",
            """<Representation width="384" height="216" bandwidth="350000" />"""
        )
        assertNull(HattrickPlayers.initPath(senzaRappresentazione))
    }

    /**
     * Il manifest può dichiarare più chiavi (video e audio cifrati con chiavi diverse): vanno
     * raccolte tutte, altrimenti la chiave buona pubblicata sul sito sembra sbagliata.
     */
    @Test
    fun `raccoglie tutti gli identificativi di chiave del manifest`() {
        val due = manifest.replace(
            """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />""",
            """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" """ +
                """cenc:default_KID="163303a8-8382-4977-b05d-7357da82f487" />""" +
                """<ContentProtection cenc:default_KID="EDB40DA832C44957B49A3035DEADBEEF" />"""
        )
        assertEquals(
            setOf("163303a883824977b05d7357da82f487", "edb40da832c44957b49a3035deadbeef"),
            HattrickPlayers.manifestKids(due)
        )
    }

    @Test
    fun `senza chiavi dichiarate l'insieme e vuoto`() {
        assertTrue(HattrickPlayers.manifestKids(manifest).isEmpty())
    }

    @Test
    fun `legge l'identificativo di chiave dalla scatola tenc`() {
        val kid = "163303a883824977b05d7357da82f487"
        val bytes = ByteArray(4) { 0 } +
            "tenc".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 8) +
            ByteArray(16) { i -> kid.substring(i * 2, i * 2 + 2).toInt(16).toByte() } +
            ByteArray(8) { 0 }
        assertEquals(kid, HattrickPlayers.tencKid(bytes))
    }

    @Test
    fun `il proxy aggiunge l'indirizzo di partenza e la chiave dichiarata`() {
        val patched = Mpd.patch(
            manifest,
            "https://timlivetu0.cb.ticdn.it/Content/DASH/Live/channel(eurosport1)/manifest.mpd?ck=x",
            "610bcda111c74c97b0792b059630a10b"
        )
        assertTrue(
            patched.contains(
                "<BaseURL>https://timlivetu0.cb.ticdn.it/Content/DASH/Live/channel(eurosport1)/</BaseURL>"
            )
        )
        assertTrue(
            patched.contains(
                """schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" """ +
                    """cenc:default_KID="610bcda1-11c7-4c97-b079-2b059630a10b"/>"""
            )
        )
    }

    @Test
    fun `un manifest che ha già la sua base non viene toccato due volte`() {
        val conBase = manifest.replace("<Period", "<BaseURL>https://cdn/x/</BaseURL><Period")
        val patched = Mpd.patch(conBase, "https://altro/y/manifest.mpd", null)
        assertEquals(1, Regex("<BaseURL>").findAll(patched).count())
    }

    @Test
    fun `identificativo di chiave in forma con trattini`() {
        assertEquals(
            "610bcda1-11c7-4c97-b079-2b059630a10b",
            Mpd.dashedUuid("610bcda111c74c97b0792b059630a10b")
        )
        assertNull(Mpd.dashedUuid("troppo-corto"))
    }

    // ============= CONVERSIONI =============

    @Test
    fun `chiavi esadecimali in base64 senza riempimento`() {
        assertEquals("YQvNoRHHTJeweSsFljChCw", HattrickPlayers.hexToBase64Url("610bcda111c74c97b0792b059630a10b"))
        assertEquals("uYF4U1OEWbNx8_tWomfVXA", HattrickPlayers.hexToBase64Url("b9817853538459b371f3fb56a267d55c"))
    }

    @Test
    fun `base64 con e senza riempimento`() {
        assertEquals("SkySport24IT|no_check_ip|1786020828", HattrickPlayers.decodeBase64("U2t5U3BvcnQyNElUfG5vX2NoZWNrX2lwfDE3ODYwMjA4Mjg="))
        assertEquals("ciao", HattrickPlayers.decodeBase64("Y2lhbw"))
        assertNull(HattrickPlayers.decodeBase64("non valido!"))
    }
}
