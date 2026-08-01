package it.zeroTituli

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.zeroTituli.shared.LocalProxy

/**
 * Mediaset Infinity.
 *
 * Il catalogo arriva dai feed theplatform di Mediaset, aperti; la riproduzione passa
 * da una sessione anonima. Le dirette sono in chiaro e si castano, il catalogo on
 * demand è protetto con Widevine e si vede solo sul dispositivo: il perché sta nel
 * progetto, in docs/superpowers/specs/2026-08-01-mediaset-infinity-design.md.
 */
class MediasetInfinity : MainAPI() {
    override var mainUrl = MediasetUrls.SITE
    override var name = "Mediaset Infinity"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Documentary,
        TvType.Cartoon,
        TvType.Live
    )

    private val api = MediasetApi()
    private val liveApi = MediasetLiveApi()
    private val catalog = MediasetCatalog(api, liveApi)

    /**
     * Le righe della home. Il `data` dice a `getMainPage` cosa caricare, il nome della
     * riga arriva dal contenuto: le sezioni portano i titoli scelti dalla redazione.
     *
     * Le sezioni escono dalla tabella di `MediasetSections`, così non si aggiunge una
     * riga qui e si dimentica la categoria di ripiego là. Manca la sezione "Serie TV" che
     * il progetto elencava: la sua pagina non esiste, e il perché sta in quella tabella.
     */
    override val mainPage = mainPageOf(*homeRows().toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? =
        when (val row = MediasetKeys.row(request.data)) {
            // Le dirette non hanno pagine: sono dodici canali.
            MediasetKeys.Row.Live -> if (page > 1) null else with(catalog) {
                liveRow()?.let { newHomePageResponse(it, hasNext = false) }
            }

            is MediasetKeys.Row.Section -> {
                // Uno slug fuori tabella non ha né nome né categoria di ripiego: la riga
                // si salta, invece di servire Fiction sotto un'altra intestazione.
                val section = MediasetSections.sectionOf(row.slug)
                if (page > 1 || section == null) {
                    null
                } else {
                    val rows = with(catalog) { sectionRows(section) }
                    if (rows.isEmpty()) null else newHomePageResponse(rows, hasNext = false)
                }
            }

            is MediasetKeys.Row.Genre -> with(catalog) {
                genreRow(row.name, page)?.let { newHomePageResponse(it, hasNext = true) }
            }

            is MediasetKeys.Row.Az -> with(catalog) {
                alphabeticalRow(row.category, page)?.let { newHomePageResponse(it, hasNext = true) }
            }

            null -> null
        }

    // La ricerca usava un `filter { brandId }.distinctBy { brandId }` per conto suo,
    // identico a quello che oggi vive in `MediasetCatalog.brandCards`: senza riunirli, la
    // ricerca avrebbe continuato a mostrare cinque risultati per "Temptation Island" anche
    // dopo aver sistemato le righe del catalogo.
    //
    // La richiesta va passata anche a valle, e non solo al feed: il `q=` di theplatform
    // cerca nei metadati delle puntate, quindi cercando "la promessa" il programma
    // omonimo arrivava **ottavo**, dietro `Arriva Cristina` e `Terra promessa`. È
    // `MediasetRanking` a rimetterlo in cima, confrontando la richiesta con il nome del
    // programma; senza questo parametro resterebbe la sola regola gratis-prima.
    override suspend fun search(query: String): List<SearchResponse> = with(catalog) {
        brandCards(api.entries(MediasetUrls.search(query, page = 1)), searchQuery = query)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList = with(catalog) {
        // Il riordino è per pagina, perché per pagina è quel che si ha in mano: il feed
        // pagina prima che il plugin possa vedere il resto.
        val items = brandCards(api.entries(MediasetUrls.search(query, page)), searchQuery = query)
        newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    /**
     * Le schede non hanno un indirizzo web: l'identificativo è una chiave tipo
     * `brand:100012714`, e a comporla e a leggerla ci pensa `MediasetKeys`.
     */
    override suspend fun load(url: String): LoadResponse? =
        when (val card = MediasetKeys.card(MediasetKeys.strip(url, mainUrl))) {
            is MediasetKeys.Card.Live -> loadLive(card.callSign)
            is MediasetKeys.Card.Series -> loadSeries(card.seriesGuid)
            is MediasetKeys.Card.Brand -> loadBrand(card.brandId)
            is MediasetKeys.Card.Program -> loadProgramByTitle(card.title)
            is MediasetKeys.Card.Single -> loadSingle(card.guid)
            null -> null
        }

    private suspend fun loadLive(callSign: String): LoadResponse? {
        val label = MediasetLive.labelFor(callSign)
        val info = liveApi.info(callSign, label) ?: return null
        return newLiveStreamLoadResponse(
            name = info.title,
            url = MediasetKeys.live(callSign),
            dataUrl = MediasetKeys.live(callSign)
        ) {
            this.posterUrl = info.logo
            this.plot = info.nowPlaying?.let { "Ora in onda: $it" }
        }
    }

    /** Le voci lette dal sito portano la stagione: da lì si risale al marchio. */
    private suspend fun loadSeries(seriesGuid: String): LoadResponse? {
        val brandId = api.entries(MediasetUrls.bySeries(seriesGuid, page = 1))
            .firstNotNullOfOrNull { it.brandId }
            ?: return null
        return loadBrand(brandId)
    }

    /** Un programma raggiunto per `brandId`: una query per marchio, come sempre. */
    private suspend fun loadBrand(brandId: String): LoadResponse? = loadProgram(
        cardKey = MediasetKeys.brand(brandId),
        pageUrl = { page -> MediasetUrls.byBrand(brandId, page) },
    )

    /**
     * Lo stesso programma di [loadBrand], ma raggiunto per titolo del marchio: la query
     * per titolo ritrova da sola tutte le edizioni che Mediaset ha spezzato su `brandId`
     * diversi (vedi `MediasetKeys.cardKeyFor` e `MediasetUrls.byProgramTitle`), quindi qui
     * non serve nessuna logica in più — è la stessa identica paginazione di `loadBrand`,
     * solo con un'altra query.
     */
    private suspend fun loadProgramByTitle(title: String): LoadResponse? = loadProgram(
        cardKey = MediasetKeys.program(title),
        pageUrl = { page -> MediasetUrls.byProgramTitle(title, page) },
    )

    /**
     * Il corpo condiviso da [loadBrand] e [loadProgramByTitle]: paginare la query fino
     * in fondo, scegliere la voce di testa, delegare il film solitario, e costruire la
     * scheda serie con le stagioni. L'unica differenza fra le due strade è quale
     * indirizzo interrogare e quale chiave dare alla scheda — passate qui come parametri
     * invece di essere due copie dello stesso corpo, che sarebbero divergenti alla prima
     * modifica fatta su una sola delle due.
     */
    private suspend fun loadProgram(cardKey: String, pageUrl: (Int) -> String): LoadResponse? {
        val entries = allEntries(pageUrl)
        // Non la prima voce del feed: con gli extra in mezzo, la prima può essere un
        // promo, e la scheda prenderebbe titolo e copertina da un trailer.
        val head = MediasetSeasons.head(entries) ?: return null
        val name = head.brandTitle?.takeIf { it.isNotBlank() } ?: head.title ?: return null

        // Un marchio con una sola voce guardabile, e quella è un film, è un film: i promo
        // che gli stanno attorno non lo trasformano in una serie da una puntata. La regola
        // sta in `MediasetSeasons` perché guardare solo `programType` non bastava — il
        // trailer di un film arriva tipizzato `movie` come il film — e perché così si
        // prova senza dispositivo.
        val onlyMovie = MediasetSeasons.onlyMovie(entries)
        if (onlyMovie != null) {
            // La voce è già in mano: passarla evita di richiederla al feed per `guid`.
            return loadSingle(onlyMovie.guid ?: return null, onlyMovie)
        }

        val slots = MediasetSeasons.arrange(entries)
        val episodes = slots.map { slot ->
            // `fix = false` è obbligatorio, ed è la stessa trappola delle
            // `new*SearchResponse`: `newEpisode` passa il `data` per `fixUrl`, che a una
            // stringa senza `http` mette davanti `mainUrl`. La chiave `vod:F310…` arrivava
            // a `loadLinks` come `https://mediasetinfinity.mediaset.it/vod:F310…`, non
            // veniva riconosciuta, e ogni episodio diceva "nessun link trovato" — mentre
            // film e dirette, che non passano da qui, funzionavano.
            //
            // Va scritto in forma esplicita: con la lambda in coda Kotlin scioglie la
            // chiamata sull'overload generico `newEpisode(data, initializer)`, dove `fix`
            // non si può passare e resta al suo default.
            newEpisode(
                url = MediasetKeys.vod(slot.entry.guid.orEmpty()),
                initializer = {
                    this.name = slot.entry.title
                    this.season = slot.season
                    this.episode = slot.episode
                    this.description = slot.entry.plot
                    this.posterUrl = MediasetImages.still(slot.entry)
                    this.runTime = slot.entry.durationMinutes
                },
                fix = false,
            )
        }

        val recommended = recommendationsFor(head)

        return newTvSeriesLoadResponse(
            name,
            cardKey,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = MediasetImages.poster(head)
            this.backgroundPosterUrl = MediasetImages.background(head)
            this.plot = MediasetLabels.description(head)
            this.tags = MediasetLabels.tags(head)
            this.year = head.year
            this.contentRating = head.ageRating
            // Senza nomi il selettore annuncerebbe "Season 999" per gli extra, che è il
            // numero scelto per tenerli in fondo, non qualcosa da mostrare.
            this.seasonNames = slots.map { it.season }.distinct().sorted().map { season ->
                SeasonData(season, MediasetLabels.seasonName(season))
            }
            this.recommendations = recommended
            addActors(head.actors)
        }
    }

    private suspend fun loadSingle(guid: String, known: FeedEntry? = null): LoadResponse? {
        val entry = known ?: api.entry(guid) ?: return null
        val name = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.brandTitle
            ?: return null
        val recommended = recommendationsFor(entry)
        return newMovieLoadResponse(
            name,
            MediasetKeys.single(guid),
            TvType.Movie,
            dataUrl = MediasetKeys.vod(guid)
        ) {
            this.posterUrl = MediasetImages.poster(entry)
            this.backgroundPosterUrl = MediasetImages.background(entry)
            this.plot = MediasetLabels.description(entry)
            this.tags = MediasetLabels.tags(entry)
            this.year = entry.year
            this.duration = entry.durationMinutes
            this.contentRating = entry.ageRating
            this.recommendations = recommended
            addActors(entry.actors)
        }
    }

    /**
     * I consigliati della scheda. Se la voce non porta una categoria non si inventa un
     * legame: la riga non compare, come le altre righe vuote.
     */
    private suspend fun recommendationsFor(entry: FeedEntry): List<SearchResponse> {
        val category = entry.categories.firstOrNull() ?: return emptyList()
        return runCatching { with(catalog) { recommendations(category, entry.brandId) } }
            .getOrDefault(emptyList())
    }

    /**
     * Le soap hanno migliaia di puntate: si scaricano a blocchi finché il feed ne dà,
     * con un tetto, perché una scheda con diecimila episodi non si scorre e intanto
     * l'app resta ad aspettare.
     *
     * `pageUrl` è l'unica differenza fra la query per `brandId` e quella per titolo del
     * marchio: il ciclo di paginazione è lo stesso, quindi non si copia.
     */
    private suspend fun allEntries(pageUrl: (Int) -> String): List<FeedEntry> {
        val all = mutableListOf<FeedEntry>()
        var page = 1
        while (page <= MAX_EPISODE_PAGES) {
            val response = api.page(pageUrl(page)) ?: break
            val batch = response.entries
            if (batch.isEmpty()) break
            all += batch

            // `count=true` fa dire al feed quante voci ha in tutto: è il limite giusto del
            // ciclo. Prima si confrontava la dimensione del blocco con una costante scritta
            // in questo file, mentre la richiesta usava il valore di default dell'altro:
            // due numeri uguali per combinazione, e cambiarne uno avrebbe fatto fermare le
            // serie lunghe alla prima pagina, in silenzio.
            val total = response.totalResults
            if (total != null && all.size >= total) break
            if (total == null && batch.size < MediasetUrls.EPISODES_PER_PAGE) break
            page++
        }
        return all
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // DIAGNOSTICA TEMPORANEA — da togliere.
        //
        // `APIRepository.loadLinks` cattura qualunque Throwable e torna `false`, quindi
        // "nessun link trovato" è il messaggio unico di sei cause diverse più ogni
        // errore a runtime: dal telefono non si distingue niente. Qui la causa vera
        // viene messa nel nome di una sorgente, che nell'elenco si legge senza `adb`.
        // `strip` come in `load`, e non per simmetria: CloudStream conserva il `data` degli
        // episodi (ripresa della visione, download), quindi gli episodi salvati da una
        // versione che passava per `fixUrl` portano ancora il prefisso del sito. Senza
        // spogliarlo qui, resterebbero rotti anche dopo la correzione a monte.
        return runCatching {
            when (val key = MediasetKeys.data(MediasetKeys.strip(data, mainUrl))) {
                is MediasetKeys.Data.Live -> liveLink(key.callSign, isCasting, callback)
                is MediasetKeys.Data.Vod -> vodLink(key.guid, isCasting, callback)
                null -> {
                    report(callback, "chiave non riconosciuta '$data'")
                    true
                }
            }
        }.getOrElse { error ->
            // Per ErrorLoadingException il messaggio è già la spiegazione: aggiungere il
            // nome della classe mangerebbe lo spazio visibile del nome della sorgente.
            val reason = error.message?.takeIf { it.isNotBlank() }
                ?: error::class.java.simpleName
            report(callback, reason)
            true
        }
    }

    /**
     * Il motivo per cui non c'è niente da guardare, scritto dove si legge.
     *
     * `APIRepository.loadLinks` cattura qualunque `Throwable` e torna `false`, quindi
     * fuori area, sessione scaduta, abbonamento e contenuto assente diventano tutti lo
     * stesso "nessun link trovato". Una voce nell'elenco delle sorgenti, col motivo come
     * nome, è il solo posto in cui la differenza arriva a chi guarda.
     *
     * Non è riproducibile — l'indirizzo non esiste — e premendola il player risponde
     * `ERROR_CODE_IO_BAD_HTTP_STATUS`. Il compromesso è voluto: un messaggio che si legge
     * vale più di un elenco vuoto. Il prefisso lo dice, così non si prova a premerla.
     */
    private suspend fun report(callback: (ExtractorLink) -> Unit, reason: String) {
        callback(
            newExtractorLink(
                source = "Mediaset",
                name = "Non riproducibile: $reason",
                url = "https://mediasetinfinity.mediaset.it/motivo.m3u8",
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /**
     * Le dirette sono in chiaro, ma il permesso sta nella query del manifest e **non** dentro
     * il manifest: i segmenti, che lì sono scritti relativi, partirebbero nudi e il CDN li
     * rifiuta con `403`. Un browser non se ne accorge perché il manifest gli lascia il cookie
     * `hdntl`; la sorgente dati di ExoPlayer non tiene cookie e la diretta restava muta.
     *
     * Quindi il manifest — pochi kB — passa dal proxy locale, che lo serve riscritto con gli
     * indirizzi assoluti e il permesso attaccato a ogni segmento (vedi [MediasetMpd]). I
     * segmenti continuano ad andare diretti al CDN: il flusso vero non attraversa il telefono.
     *
     * `forCast = isCasting` come in FCTV33: in locale basta `127.0.0.1`, e l'indirizzo di rete
     * serve solo quando è il televisore a dover raggiungere il telefono.
     */
    private suspend fun liveLink(
        callSign: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = MediasetLive.labelFor(callSign)
        // Questi due `throw` finiscono nel log e **non** sotto gli occhi di chi guarda: il
        // perché sta scritto per esteso su `vodLink`.
        val mediaUrl = liveApi.info(callSign, label)?.mediaUrl
            ?: throw ErrorLoadingException("Diretta non disponibile per $label")
        val manifest = liveApi.manifest(mediaUrl)
            ?: throw ErrorLoadingException("Il flusso di $label non si è risolto")
        // L'indirizzo del CDN viaggia nei parametri invece di restare catturato nella lambda:
        // il proxy tiene una sola sorgente per volta, e così ogni richiesta porta con sé tutto
        // quello che serve a rispondere, anche a distanza di ore dal `play`.
        val url = LocalProxy.playlist(
            source = { params -> liveApi.rewrittenManifest(params["u"].orEmpty()) },
            params = mapOf("u" to manifest),
            forCast = isCasting,
            mime = LocalProxy.MIME_DASH,
        )
        callback(
            newExtractorLink(
                source = name,
                name = "$label (diretta)",
                url = url,
                type = ExtractorLinkType.DASH
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /**
     * Il catalogo on demand è protetto con Widevine: ExoPlayer lo apre col CDM del
     * dispositivo, il Chromecast no, perché CloudStream gli manda solo l'indirizzo.
     * Il nome del link lo dice, così in casting si legge invece di trovare un errore
     * muto sul televisore.
     */
    private suspend fun vodLink(
        guid: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Attenzione, e non è un dettaglio: questi quattro messaggi **non arrivano a chi
        // guarda**. `APIRepository.loadLinks`, nella jar di CloudStream, avvolge la chiamata
        // al plugin in un `try/catch (Throwable) { logError(t); return false }` — letto nel
        // bytecode e non dedotto: la tabella delle eccezioni del metodo copre tutto il corpo
        // con `java/lang/Throwable`, e il gestore chiama `ArchComponentExtKt.logError` e
        // restituisce `false`. Quindi l'utente vede lo stesso errore generico di un `false`
        // secco, e l'unica cosa che cambia è una riga in più nel log.
        //
        // Restano comunque, perché quella riga di log è il solo posto dove fuori area,
        // sessione scaduta e abbonamento si distinguono, e perché tornare `false` da qui non
        // porterebbe niente in più. Quello che invece si vede davvero sta prima di premere
        // play: `load` passa per `safeApiCall`, che di `ErrorLoadingException` fa un
        // `Resource.Failure` con il suo messaggio (anche questo letto nel bytecode di
        // `ArchComponentExtKt.safeFail`), e la scheda di un contenuto a pagamento porta già
        // il tag "Abbonamento" in cima e l'avviso in testa alla trama — vedi
        // `MediasetLabels`, che legge `mediasetprogram$channelsRights` dalla stessa voce di
        // feed su cui si costruisce la scheda.
        val stream = when (val result = api.vod(guid)) {
            is VodResult.Ok -> result.stream
            VodResult.GeoBlocked ->
                throw ErrorLoadingException("Non disponibile in questa zona")
            VodResult.TokenExpired ->
                throw ErrorLoadingException("Sessione Mediaset scaduta: riprova fra un momento")
            VodResult.SubscriptionRequired ->
                throw ErrorLoadingException(
                    "Serve un abbonamento o un noleggio Mediaset Infinity"
                )
            VodResult.NotAvailable ->
                throw ErrorLoadingException("Contenuto non disponibile")
        }

        val label = if (isCasting) "Widevine — solo sul telefono" else "Widevine"
        callback(
            newDrmExtractorLink(
                source = name,
                name = label,
                url = stream.manifest,
                type = ExtractorLinkType.DASH,
                uuid = WIDEVINE_UUID
            ) {
                this.licenseUrl = stream.licenseUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private companion object {
        /** Cento pagine da cento: diecimila episodi, oltre i quali non serve andare. */
        const val MAX_EPISODE_PAGES = 100

        /**
         * Le sei categorie di `azListing`, quelle che l'indice alfabetico conosce.
         * "Calcio e Sport" era l'unica fuori dall'elenco, senza motivo.
         */
        val AZ_CATEGORIES = listOf(
            "Fiction",
            "Cinema",
            "Programmi Tv",
            "Kids",
            "Documentari",
            "Calcio e Sport",
        )

        /** I quattro gruppi di righe che il progetto vuole, in quest'ordine. */
        fun homeRows(): List<Pair<String, String>> = buildList {
            add(MediasetKeys.LIVE_ROW to "Dirette")
            MediasetSections.SLUGS.forEach { add(MediasetKeys.section(it.slug) to "Sezione") }
            listOf("Commedia", "Thriller", "Documentari", "Serie Tv")
                .forEach { add(MediasetKeys.genre(it) to "Genere") }
            AZ_CATEGORIES.forEach { add(MediasetKeys.az(it) to "Alfabetico") }
        }
    }
}
