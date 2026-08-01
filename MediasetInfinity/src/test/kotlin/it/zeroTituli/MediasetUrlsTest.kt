package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetUrlsTest {

    /**
     * I soli campi che il parametro `fields=` chiede, nell'ordine in cui li chiede.
     *
     * Serve perché `assertTrue(url.contains("mediasetprogram%24brandTitle"))` non provava
     * niente: quella stessa sottostringa sta anche in
     * `sort=mediasetprogram%24brandTitle%7Casc`, nello stesso indirizzo, quindi togliendo
     * `brandTitle` dalla proiezione il test passava comunque — e il nome sarebbe sparito da
     * ogni riquadro del catalogo. Leggendo il valore del parametro e confrontandolo per
     * intero, l'unico modo di far passare l'asserzione è che il campo sia proprio lì.
     */
    private fun fieldsOf(url: String): List<String> =
        url.substringAfter("&fields=", "")
            .substringBefore("&")
            .split("%2C")
            .filter { it.isNotBlank() }

    @Test
    fun `la prima pagina parte da uno`() {
        // theplatform conta le voci da 1 e vuole gli estremi inclusi.
        assertEquals("1-40", MediasetUrls.range(page = 1, perPage = 40))
        assertEquals("41-80", MediasetUrls.range(page = 2, perPage = 40))
    }

    @Test
    fun `una pagina sotto uno vale come la prima`() {
        assertEquals("1-40", MediasetUrls.range(page = 0, perPage = 40))
    }

    @Test
    fun `la query per marchio chiede episodi film ed extra`() {
        val url = MediasetUrls.byBrand("100012714", page = 1)
        assertTrue(url.startsWith(MediasetUrls.FEED))
        assertTrue(url.contains("form=cjson"))
        assertTrue(url.contains("byCustomValue=%7BbrandId%7D%7B100012714%7D"))
        assertTrue(url.contains("range=1-100"))

        // Il filtro è la cosa che questo test esiste per guardare. Con `byProgramType=episode`
        // da solo, la scheda di un film tornava vuota — verificato sul feed vero: il marchio
        // 100002609 ("Il quarto Re") dava 0 voci con `episode` e 1 con questo filtro — e gli
        // extra non arrivavano mai, quindi la stagione dedicata non si riempiva mai.
        assertEquals("episode|movie|extra", MediasetUrls.BRAND_PROGRAM_TYPES)
        assertTrue(
            "il filtro deve lasciar passare anche film ed extra: $url",
            url.contains("byProgramType=episode%7Cmovie%7Cextra")
        )
        // Il totale serve al ciclo che scarica le puntate per sapere quando fermarsi.
        assertTrue(url.contains("count=true"))
    }

    @Test
    fun `la seconda pagina del marchio parte dove finisce la prima`() {
        // Il ciclo che scarica le puntate chiede pagine con lo stesso `perPage` della
        // costante: se i due numeri divergessero, le serie lunghe si fermerebbero alla
        // prima pagina in silenzio.
        assertEquals(100, MediasetUrls.EPISODES_PER_PAGE)
        assertTrue(MediasetUrls.byBrand("1", page = 2).contains("range=101-200"))
    }

    @Test
    fun `le righe di catalogo chiedono abbastanza voci per mostrare piu programmi`() {
        // Il feed elenca episodi, non programmi, e la riga tiene un riquadro per marchio:
        // con 40 voci la Fiction dalla A alla Z mostrava 5 programmi (misurato sul feed
        // vero), con 200 ne mostra 19.
        assertEquals(200, MediasetUrls.CARDS_PER_PAGE)
        assertTrue(MediasetUrls.alphabetical("Fiction", page = 1).contains("range=1-200"))
        assertTrue(MediasetUrls.byGenre("Commedia", page = 1).contains("range=1-200"))
        assertTrue(MediasetUrls.byCategory("Fiction", page = 1).contains("range=1-200"))
        // Seconda pagina: gli estremi devono seguire il passo nuovo, non quello vecchio.
        assertTrue(MediasetUrls.alphabetical("Fiction", page = 2).contains("range=201-400"))
    }

    @Test
    fun `le righe di catalogo chiedono solo i campi che il riquadro legge`() {
        // Alzare le voci per pagina senza tagliare i campi peggiorerebbe la memoria, che è
        // già il problema della home. Se un riquadro inizia a leggere un campo nuovo, va
        // aggiunto alla lista: qui si controlla che ci siano tutti quelli che legge oggi, e
        // nessuno in più. Il confronto è sul valore di `fields=`, non sull'indirizzo intero:
        // `url.contains("mediasetprogram%24brandTitle")` passava anche senza il campo,
        // perché quella sottostringa sta pure nel `sort=` di questa stessa query.
        val url = MediasetUrls.alphabetical("Fiction", page = 1)
        assertTrue("manca del tutto la proiezione in $url", url.contains("&fields="))
        assertEquals(
            listOf(
                "guid",
                "title",
                "programType",
                "thumbnails",
                "mediasetprogram%24brandId",
                "mediasetprogram%24brandTitle",
            ),
            fieldsOf(url)
        )
        // La stessa proiezione, identica, sulle altre due righe di catalogo.
        assertEquals(fieldsOf(url), fieldsOf(MediasetUrls.byGenre("Commedia", page = 1)))
        assertEquals(fieldsOf(url), fieldsOf(MediasetUrls.byCategory("Fiction", page = 1)))
    }

    @Test
    fun `la query per marchio chiede solo i campi che la scheda legge`() {
        // Era l'unica query di catalogo senza proiezione, e la più pesante: "La promessa"
        // sono 2699 voci col filtro `episode|movie|extra`, cioè 27 pagine da 100 aperte in
        // fila all'apertura della scheda. Una pagina non proiettata misurava 1 170 623 byte
        // sul feed vero, con questa proiezione 959 336.
        //
        // Ogni campo qui dentro ha qualcuno che lo legge su quella strada, e i campi che
        // nessuno legge — `media`, `seriesId`, `tvSeasonId`, i sottomarchi, `editorialType`,
        // `pageUrl` — devono restare fuori: il confronto è per intero proprio per questo.
        val url = MediasetUrls.byBrand("100012714", page = 1)
        assertTrue("manca del tutto la proiezione in $url", url.contains("&fields="))
        assertEquals(
            listOf(
                // `MediasetSeasons.arrange` (chiave e scarto dei doppioni) e la chiave VOD
                "guid",
                // nome della puntata, e ordine a pari numero d'episodio
                "title",
                // `FeedEntry.plot`
                "description",
                "longDescription",
                // extra/film/puntata: stagione degli extra e decisione film-o-serie
                "programType",
                // intestazione della scheda
                "year",
                // durata di riserva quando `mediasetprogram$duration` manca
                "runtime",
                // la numerazione
                "tvSeasonNumber",
                "tvSeasonEpisodeNumber",
                // cast (`addActors`): col sottocampo, perche` `fields=credits` liscio
                // fa rispondere al feed `"credits": []` e il cast sparirebbe in silenzio
                "credits.personName",
                // semaforo dell'età (`ageRating`)
                "ratings",
                // la categoria dei consigliati e i generi di riserva
                "tags",
                // `MediasetImages.still`, `poster`, `background`
                "thumbnails",
                // il marchio da escludere dai consigliati
                "mediasetprogram%24brandId",
                // il nome della scheda
                "mediasetprogram%24brandTitle",
                // durata mostrata, e la regola che distingue un film dal suo trailer
                "mediasetprogram%24duration",
                // i tag
                "mediasetprogram%24genres",
                // il diritto AVOD: tag "Abbonamento" e avviso nella trama
                "mediasetprogram%24channelsRights",
            ),
            fieldsOf(url)
        )
        // Il filtro e il totale non devono essersi persi mettendo la proiezione.
        assertTrue(url.contains("byProgramType=episode%7Cmovie%7Cextra"))
        assertTrue(url.contains("count=true"))
    }

    @Test
    fun `la proiezione della scheda copre tutti i campi che la scheda legge`() {
        // Contro-prova dell'altro verso: qui l'elenco non è copiato dalla produzione ma
        // scritto guardando chi legge cosa in `MediasetSeasons`, `MediasetImages`,
        // `MediasetLabels` e nei blocchi `newEpisode`/`newTvSeriesLoadResponse`. Se un
        // giorno un campo viene tolto dalla proiezione "perché non serve", questo test dice
        // di chi era.
        val fields = fieldsOf(MediasetUrls.byBrand("1", page = 1))
        mapOf(
            "guid" to "MediasetSeasons.arrange scarta le voci senza guid",
            "title" to "il nome della puntata",
            "longDescription" to "FeedEntry.plot",
            "programType" to "MediasetSeasons.playable e features",
            "tvSeasonNumber" to "la stagione",
            "tvSeasonEpisodeNumber" to "il numero d'episodio",
            "thumbnails" to "MediasetImages.still/poster/background",
            "credits.personName" to "addActors",
            "ratings" to "FeedEntry.ageRating",
            "tags" to "FeedEntry.categories, per i consigliati",
            "mediasetprogram%24brandTitle" to "il nome della scheda",
            "mediasetprogram%24brandId" to "il marchio escluso dai consigliati",
            "mediasetprogram%24duration" to "MediasetSeasons.features e durationMinutes",
            "mediasetprogram%24channelsRights" to "FeedEntry.isFree, cioè MediasetLabels",
        ).forEach { (field, reader) ->
            assertTrue("manca $field, che serve a: $reader", fields.contains(field))
        }
    }

    @Test
    fun `la query per serie usa l indirizzo completo del programma`() {
        val url = MediasetUrls.bySeries("SE000000002040", page = 1)
        assertTrue(url.contains("bySeriesId="))
        assertTrue(url.contains("SE000000002040"))
        // L'indirizzo va codificato: i due punti e le barre non possono restare nudi.
        assertTrue(url.contains("http%3A%2F%2Fdata.entertainment.tv.theplatform.eu"))
    }

    @Test
    fun `la query per categoria usa i tag`() {
        val url = MediasetUrls.byCategory("Programmi Tv", page = 1)
        assertTrue(url.contains("byTags=category%7CProgrammi+Tv"))
    }

    @Test
    fun `la query alfabetica ordina per titolo del marchio`() {
        val url = MediasetUrls.alphabetical("Fiction", page = 2)
        assertTrue(url.contains("sort=mediasetprogram%24brandTitle%7Casc"))
        assertTrue(url.contains("range=201-400"))
    }

    @Test
    fun `la ricerca passa il testo cosi come e`() {
        val url = MediasetUrls.search("zelig party", page = 1)
        assertTrue(url.contains("q=zelig+party"))
    }

    @Test
    fun `il SMIL porta il token e gli assetTypes`() {
        val url = MediasetUrls.smil(
            mediaUrl = "https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC",
            assetTypes = "HR,widevine,geoIT|geoNo",
            token = "abc.def"
        )
        assertTrue(url.startsWith("https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC?"))
        assertTrue(url.contains("format=SMIL"))
        assertTrue(url.contains("formats=mpeg-dash"))
        assertTrue(url.contains("assetTypes=HR%2Cwidevine%2CgeoIT%7CgeoNo"))
        assertTrue(url.contains("auth=abc.def"))
    }

    @Test
    fun `un indirizzo che arriva con una query non prende un secondo punto di domanda`() {
        // `mediaUrl` arriva da `playbackCheck` e da `nowNext`, cioè da fuori: incollare un
        // `?` fisso darebbe `...?x=1?format=SMIL` e theplatform lo rifiuterebbe.
        val url = MediasetUrls.smil(
            mediaUrl = "https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC?x=1",
            assetTypes = "HR,widevine,geoIT|geoNo",
            token = "abc.def"
        )
        assertEquals(1, url.count { it == '?' })
        assertTrue(url.contains("?x=1&format=SMIL"))
    }

    @Test
    fun `il SMIL di una diretta non porta il token`() {
        // Il flusso in chiaro è già autorizzato dal token che `nowNext` ha messo
        // nell'indirizzo: un `auth` in più non serve, e `assetTypes` nemmeno.
        val url = MediasetUrls.liveSmil("https://link.api.eu.theplatform.com/s/PR1GhC/c5-clr")
        assertEquals(
            "https://link.api.eu.theplatform.com/s/PR1GhC/c5-clr" +
                "?format=SMIL&formats=mpeg-dash&tracking=false",
            url
        )
    }

    @Test
    fun `il SMIL di una diretta rispetta una query gia presente`() {
        val url = MediasetUrls.liveSmil("https://cdn/c5-clr?hdnts=st%3D1")
        assertEquals(1, url.count { it == '?' })
        assertTrue(url.contains("hdnts=st%3D1&format=SMIL"))
    }

    @Test
    fun `l indirizzo della licenza porta token account e pid`() {
        val url = MediasetUrls.license(pid = "UXvEsmsZ1AvC", token = "abc.def")
        assertTrue(url.startsWith(MediasetUrls.LICENSE))
        assertTrue(url.contains("token=abc.def"))
        assertTrue(url.contains("releasePid=UXvEsmsZ1AvC"))
        assertTrue(url.contains("account=http%3A%2F%2Faccess.auth.theplatform.eu%2Fdata%2FAccount%2F2702976343"))
        assertTrue(url.contains("form=json"))
        assertTrue(url.contains("schema=1.0"))
    }

    @Test
    fun `nowNext vuole il nome del canale`() {
        assertEquals(
            "https://api-ott-prod-fe.mediaset.net/PROD/play/alive/nownext/v1.0?channelId=C5",
            MediasetUrls.nowNext("C5")
        )
    }

    @Test
    fun `la pagina sezione sta sul sito`() {
        assertEquals("https://mediasetinfinity.mediaset.it/fiction", MediasetUrls.section("fiction"))
    }
}
