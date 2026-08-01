package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSmilTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `legge il flusso DASH`() {
        val r = MediasetSmil.read(fixture("smil-dash.xml"))
        assertTrue(r is SmilResult.Stream)
        r as SmilResult.Stream
        assertEquals(
            "https://vod06.msf.cdn.mediaset.net/farmunica/2019/04/373226_16a43b7422a89c/dashrcenc/hr_wv_mpl.mpd",
            r.url
        )
        assertEquals(StreamKind.DASH, r.kind)
    }

    @Test
    fun `il campione con InvalidAuthToken e un token scaduto, non un blocco geografico`() {
        // Questo campione si chiama `smil-geoblock` e per dodici revisioni ha fatto da
        // blocco geografico, ma non lo è: porta `title="Invalid Token"`,
        // `abstract="This content requires a valid, unexpired auth token."`,
        // `exception="InvalidAuthToken"` e `responseCode=403`. Ha lo stesso `src` di
        // cortesia del blocco geografico, ed è per questo che leggere l'indirizzo per
        // primo li confondeva. Chiamandolo fuori area, il nuovo login non partiva mai e
        // l'utente aspettava le quattro ore di vita della sessione senza un messaggio.
        assertEquals(SmilResult.TokenExpired, MediasetSmil.read(fixture("smil-geoblock.xml")))
    }

    @Test
    fun `il video di cortesia senza eccezione e un blocco geografico`() {
        // Il caso che il progetto descrive e che nessun campione copriva: il CDN risponde
        // con esito positivo e un cartello, senza dichiarare niente. Qui l'indirizzo è la
        // sola cosa da cui accorgersene, e senza riconoscerlo si guarderebbe il cartello
        // al posto del film.
        assertEquals(SmilResult.GeoBlocked, MediasetSmil.read(fixture("smil-geolock.xml")))
    }

    @Test
    fun `il blocco geografico dichiarato resta un blocco geografico`() {
        val smil = """<smil><body><seq><ref src="https://cdn/cortesia/GEOLOCK-DEF_2.mp4">""" +
            """<param name="exception" value="GeoLocationBlocked"/></ref></seq></body></smil>"""
        assertEquals(SmilResult.GeoBlocked, MediasetSmil.read(smil))
    }

    @Test
    fun `assetTypes sbagliato si distingue dal blocco geografico`() {
        assertEquals(SmilResult.NoMatch, MediasetSmil.read(fixture("smil-nomatch.xml")))
    }

    @Test
    fun `riconosce l HLS`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/index.m3u8"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.HLS, r.kind)
    }

    @Test
    fun `riconosce il file progressivo`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/film.mp4"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.PROGRESSIVE, r.kind)
    }

    @Test
    fun `il manifest con parametri resta riconoscibile`() {
        val smil =
            """<smil><body><seq><video src="https://cdn/live/c5-clr.isml/manifest_sd.mpd?hdnts=st=1~exp=2"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.DASH, r.kind)
    }

    @Test
    fun `una risposta vuota non lancia`() {
        assertTrue(MediasetSmil.read("") is SmilResult.Failed)
    }

    @Test
    fun `una risposta che non e un SMIL non lancia`() {
        assertTrue(MediasetSmil.read("<html><body>errore</body></html>") is SmilResult.Failed)
    }

    @Test
    fun `l eccezione dichiarata batte l indirizzo di cortesia`() {
        // La priorità è invertita rispetto a prima, e l'inversione è la correzione:
        // theplatform manda lo stesso `src` di cortesia per casi diversi, quindi
        // l'indirizzo è l'indizio debole e il `param` `exception` quello forte. Con la
        // vecchia priorità questo campione tornava GeoBlocked, cioè "non disponibile in
        // questa zona", mentre il feed stava dicendo che l'`assetTypes` non combaciava e
        // che bastava provare il successivo della catena.
        val smil =
            """<smil><body><seq><ref src="https://vod06-mediaset-it.akamaized.net/cortesia/GEOLOCK-DEF_2.mp4"><param name="exception" value="NoAssetTypeFormatMatches"/></ref></seq></body></smil>"""
        assertEquals(SmilResult.NoMatch, MediasetSmil.read(smil))
    }

    @Test
    fun `un nome di eccezione sconosciuto non copre il video di cortesia`() {
        // Il ramo `else` del `when` faceva `return NoMatch`, cioè si fermava prima
        // dell'euristica dell'indirizzo: un blocco geografico dichiarato con un nome
        // qualunque — theplatform ne cambia le sigle senza avvisare — non arrivava più al
        // riconoscimento del cartello di cortesia che lo prendeva da sempre. Per chi guarda
        // da fuori l'Italia il conto era: tre `assetTypes` provati a vuoto, un login rifatto
        // per niente, e alla fine "Contenuto non disponibile" al posto di "Non disponibile in
        // questa zona".
        val smil = """<smil><body><seq><ref src="https://vod06-mediaset-it.akamaized.net/cortesia/GEOLOCK-DEF_2.mp4">""" +
            """<param name="exception" value="GeoBlockingNotAllowed"/></ref></seq></body></smil>"""
        assertEquals(SmilResult.GeoBlocked, MediasetSmil.read(smil))
    }

    @Test
    fun `un nome di eccezione sconosciuto senza cartello non diventa un flusso`() {
        // L'altro verso della stessa modifica: lasciar passare il ramo `else` serve ad
        // arrivare all'indirizzo, non a fidarsi di un `src` che theplatform ha accompagnato
        // con un errore. Senza questo controllo il lettore avrebbe provato ad aprirlo.
        val smil = """<smil><body><seq><ref src="https://cdn/qualcosa/hr_wv_mpl.mpd">""" +
            """<param name="exception" value="QualcosaDiNuovo"/></ref></seq></body></smil>"""
        assertEquals(SmilResult.NoMatch, MediasetSmil.read(smil))
    }

    @Test
    fun `errorFiles senza exception torna NoMatch`() {
        val smil =
            """<smil><body><seq><ref src="http://link.theplatform.eu/s/errorFiles/Unavailable.flv"/></ref></seq></body></smil>"""
        assertEquals(SmilResult.NoMatch, MediasetSmil.read(smil))
    }

    // ============= IL PID DELLA RELEASE (la licenza Widevine) =============

    @Test
    fun `prende il pid della release e non quello del media`() {
        // Campione vero, con i due pid che il SMIL porta insieme:
        //   mediaPid=Kk3NxdxEdjLA   (l'ultimo pezzo dell'indirizzo del mediaSelector)
        //   pid=96DDt7fMRkTc        (quello che la licenza vuole)
        // Chiedere la licenza col primo dà ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, che
        // è esattamente come il guasto si è manifestato sul dispositivo.
        val result = MediasetSmil.read(fixture("smil-widevine-tracking.xml"))
        assertTrue(result is SmilResult.Stream)
        result as SmilResult.Stream
        assertEquals("96DDt7fMRkTc", result.releasePid)
        assertEquals(StreamKind.DASH, result.kind)
    }

    @Test
    fun `mediaPid non viene confuso col pid`() {
        // `mediaPid` finisce anch'essa per `pid`: se il confronto fosse per suffisso
        // invece che per chiave intera, tornerebbe l'identificativo sbagliato — e sarebbe
        // di nuovo il bug, con i test verdi.
        val smil = """
            <smil><body><seq><ref src="https://cdn/x/hr_wv_mpl.mpd">
            <param name="trackingData" value="aid=2702976343|mediaPid=SBAGLIATO|pid=GIUSTO|rc=MO"/>
            </ref></seq></body></smil>
        """.trimIndent()
        assertEquals("GIUSTO", (MediasetSmil.read(smil) as SmilResult.Stream).releasePid)
    }

    @Test
    fun `senza trackingData il pid della release e nullo`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/hr_wv_mpl.mpd"/></seq></body></smil>"""
        assertEquals(null, (MediasetSmil.read(smil) as SmilResult.Stream).releasePid)
    }

    @Test
    fun `un pid vuoto vale come assente`() {
        val smil = """
            <smil><body><seq><ref src="https://cdn/x/hr_wv_mpl.mpd">
            <param name="trackingData" value="aid=1|pid=|rc=MO"/>
            </ref></seq></body></smil>
        """.trimIndent()
        assertEquals(null, (MediasetSmil.read(smil) as SmilResult.Stream).releasePid)
    }
}
