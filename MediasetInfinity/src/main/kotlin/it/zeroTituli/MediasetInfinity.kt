package it.zeroTituli

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
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
    private val liveApi = MediasetLiveApi(api)
    private val catalog = MediasetCatalog(api, liveApi)

    /**
     * Le righe della home. Il `data` dice a `getMainPage` cosa caricare, il nome della
     * riga arriva dal contenuto: le sezioni portano i titoli scelti dalla redazione.
     */
    override val mainPage = mainPageOf(
        "live" to "Dirette",
        "section:fiction" to "Sezione",
        "section:cinema" to "Sezione",
        "section:programmitv" to "Sezione",
        "section:kids" to "Sezione",
        "section:documentari" to "Sezione",
        "section:news-e-sport" to "Sezione",
        "genre:Commedia" to "Genere",
        "genre:Thriller" to "Genere",
        "genre:Documentari" to "Genere",
        "genre:Serie Tv" to "Genere",
        "az:Fiction" to "Alfabetico",
        "az:Cinema" to "Alfabetico",
        "az:Programmi Tv" to "Alfabetico",
        "az:Kids" to "Alfabetico",
        "az:Documentari" to "Alfabetico",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val data = request.data
        val argument = data.substringAfter(':', "")

        return when {
            // Le dirette non hanno pagine: sono dodici canali.
            data == "live" -> if (page > 1) null else with(catalog) {
                liveRow()?.let { newHomePageResponse(it, hasNext = false) }
            }

            data.startsWith("section:") -> {
                if (page > 1) return null
                val label = MediasetSections.SLUGS.firstOrNull { it.first == argument }?.second
                    ?: argument
                val rows = with(catalog) { sectionRows(argument, label) }
                if (rows.isEmpty()) null else newHomePageResponse(rows, hasNext = false)
            }

            data.startsWith("genre:") -> with(catalog) {
                genreRow(argument, page)?.let { newHomePageResponse(it, hasNext = true) }
            }

            data.startsWith("az:") -> with(catalog) {
                alphabeticalRow(argument, page)?.let { newHomePageResponse(it, hasNext = true) }
            }

            else -> null
        }
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

    override suspend fun load(url: String): LoadResponse? {
        val key = key(url)
        return when {
            key.startsWith("live:") -> loadLive(key.substringAfter(':'))
            key.startsWith("series:") -> loadSeries(key.substringAfter(':'))
            key.startsWith("brand:") -> loadBrand(key.substringAfter(':'))
            key.startsWith("guid:") -> loadSingle(key.substringAfter(':'))
            else -> null
        }
    }

    /**
     * Le schede non hanno un indirizzo web: l'identificativo è una chiave tipo
     * `brand:100012714`. Se un preferito salvato porta davanti l'indirizzo del sito —
     * capita quando una `new*SearchResponse` viene costruita senza `fix = false` — la
     * chiave si recupera invece di aprire una scheda vuota.
     */
    private fun key(url: String): String =
        url.removePrefix("$mainUrl/").removePrefix(mainUrl)

    private suspend fun loadLive(callSign: String): LoadResponse? {
        val label = MediasetLive.labelFor(callSign)
        val info = liveApi.info(callSign, label) ?: return null
        return newLiveStreamLoadResponse(
            name = info.title,
            url = "live:$callSign",
            dataUrl = "live:$callSign"
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
        val head = entries.firstOrNull() ?: return null
        val name = head.brandTitle?.takeIf { it.isNotBlank() } ?: head.title ?: return null

        // Un solo episodio senza numerazione è un film, non una serie da una puntata.
        val slots = MediasetSeasons.arrange(entries)
        if (slots.size == 1 && head.programType == "movie") return loadSingle(head.guid ?: return null)

        val episodes = slots.map { slot ->
            newEpisode("vod:${slot.entry.guid}") {
                this.name = slot.entry.title
                this.season = slot.season
                this.episode = slot.episode
                this.description = slot.entry.plot
                this.posterUrl = MediasetImages.still(slot.entry)
                this.runTime = slot.entry.durationMinutes
            }
        }

        return newTvSeriesLoadResponse(name, "brand:$brandId", TvType.TvSeries, episodes) {
            this.posterUrl = MediasetImages.poster(head)
            this.backgroundPosterUrl = MediasetImages.background(head)
            this.plot = MediasetLabels.description(head)
            this.tags = MediasetLabels.tags(head)
            this.year = head.year
            this.contentRating = head.ageRating
            addActors(head.actors)
        }
    }

    private suspend fun loadSingle(guid: String): LoadResponse? {
        val entry = api.entry(guid) ?: return null
        val name = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.brandTitle
            ?: return null
        return newMovieLoadResponse(name, "guid:$guid", TvType.Movie, dataUrl = "vod:$guid") {
            this.posterUrl = MediasetImages.poster(entry)
            this.backgroundPosterUrl = MediasetImages.background(entry)
            this.plot = MediasetLabels.description(entry)
            this.tags = MediasetLabels.tags(entry)
            this.year = entry.year
            this.duration = entry.durationMinutes
            this.contentRating = entry.ageRating
            addActors(entry.actors)
        }
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
            val batch = api.entries(MediasetUrls.byBrand(brandId, page))
            all += batch
            if (batch.size < EPISODES_PER_PAGE) break
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
        if (data.startsWith("live:")) return liveLink(data.substringAfter(':'), callback)
        if (data.startsWith("vod:")) return vodLink(data.substringAfter(':'), isCasting, callback)
        return false
    }

    /**
     * Le dirette sono in chiaro e il permesso è già dentro l'indirizzo: il Chromecast
     * le apre da solo, senza proxy e senza header da rimettere.
     */
    private suspend fun liveLink(callSign: String, callback: (ExtractorLink) -> Unit): Boolean {
        val label = MediasetLive.labelFor(callSign)
        val mediaUrl = liveApi.info(callSign, label)?.mediaUrl ?: return false
        val manifest = liveApi.manifest(mediaUrl) ?: return false
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
        val stream = when (val result = api.vod(guid)) {
            is VodResult.Ok -> result.stream
            VodResult.GeoBlocked -> return false
            VodResult.NotAvailable -> return false
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
        const val EPISODES_PER_PAGE = 100
        /** Cento pagine da cento: diecimila episodi, oltre i quali non serve andare. */
        const val MAX_EPISODE_PAGES = 100
    }
}
