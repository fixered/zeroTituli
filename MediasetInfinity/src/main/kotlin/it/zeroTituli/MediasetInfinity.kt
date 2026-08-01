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

    override suspend fun search(query: String): List<SearchResponse> = with(catalog) {
        api.entries(MediasetUrls.search(query, page = 1))
            .filter { !it.brandId.isNullOrBlank() }
            .distinctBy { it.brandId }
            .mapNotNull { toSearchResponse(it) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList = with(catalog) {
        val items = api.entries(MediasetUrls.search(query, page))
            .filter { !it.brandId.isNullOrBlank() }
            .distinctBy { it.brandId }
            .mapNotNull { toSearchResponse(it) }
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

    private suspend fun loadBrand(brandId: String): LoadResponse? {
        val entries = allEpisodes(brandId)
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
            newEpisode(MediasetKeys.vod(slot.entry.guid.orEmpty())) {
                this.name = slot.entry.title
                this.season = slot.season
                this.episode = slot.episode
                this.description = slot.entry.plot
                this.posterUrl = MediasetImages.still(slot.entry)
                this.runTime = slot.entry.durationMinutes
            }
        }

        val recommended = recommendationsFor(head)

        return newTvSeriesLoadResponse(
            name,
            MediasetKeys.brand(brandId),
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
     */
    private suspend fun allEpisodes(brandId: String): List<FeedEntry> {
        val all = mutableListOf<FeedEntry>()
        var page = 1
        while (page <= MAX_EPISODE_PAGES) {
            val response = api.page(MediasetUrls.byBrand(brandId, page)) ?: break
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
        return when (val key = MediasetKeys.data(data)) {
            is MediasetKeys.Data.Live -> liveLink(key.callSign, callback)
            is MediasetKeys.Data.Vod -> vodLink(key.guid, isCasting, callback)
            null -> false
        }
    }

    /**
     * Le dirette sono in chiaro e il permesso è già dentro l'indirizzo: il Chromecast
     * le apre da solo, senza proxy e senza header da rimettere.
     */
    private suspend fun liveLink(callSign: String, callback: (ExtractorLink) -> Unit): Boolean {
        val label = MediasetLive.labelFor(callSign)
        // Questi due `throw` finiscono nel log e **non** sotto gli occhi di chi guarda: il
        // perché sta scritto per esteso su `vodLink`.
        val mediaUrl = liveApi.info(callSign, label)?.mediaUrl
            ?: throw ErrorLoadingException("Diretta non disponibile per $label")
        val manifest = liveApi.manifest(mediaUrl)
            ?: throw ErrorLoadingException("Il flusso di $label non si è risolto")
        callback(
            newExtractorLink(
                source = name,
                name = "$label (diretta)",
                url = manifest,
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
