package it.zeroTituli

import android.content.SharedPreferences
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.zeroTituli.shared.LocalProxy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * StreamingCommunity.
 *
 * Adattamento del plugin di doGior (github.com/doGior/doGiorsHadEnough): stessa lettura del sito
 * (Laravel + Inertia) e stesse informazioni sulle schede. Le due differenze sono il dominio, che
 * qui viene cercato da solo a ogni cambio ([SiteDomain]), e il casting, che passa dal proxy locale.
 */
class StreamingCommunity(
    override var lang: String = "it",
    private val prefs: SharedPreferences? = null,
    private val customBaseUrl: String? = null,
    private val showUpcoming: Boolean = true
) : MainAPI() {

    private var site = SiteDomain.manual(customBaseUrl)
        ?: SiteDomain.cached(prefs)
        ?: SiteDomain.Site(SiteDomain.DEFAULT_ROOT, SiteDomain.DEFAULT_CDN)
    private val siteRootUrl get() = site.root
    private val cdnUrl get() = site.cdn

    private var inertiaVersion = ""
    private var decodedXsrfToken = ""
    private val headers = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to "true",
        "X-Inertia-Version" to "",
        "X-Requested-With" to "XMLHttpRequest",
    )

    override var mainUrl = siteRootUrl + lang
    override var name = "StreamingCommunity"
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override val hasMainPage = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        SliderFetchRequestSlider(name = "top10", genre = null).toJson() to "Slider",
        SliderFetchRequestSlider(name = "trending", genre = null).toJson() to "Slider",
        SliderFetchRequestSlider(name = "latest", genre = null).toJson() to "Slider",
        SliderFetchRequestSlider(name = "upcoming", genre = null).toJson() to "Slider",
        GenreRequest(nameEN = "Animation", nameIT = "Animazione", id = 19).toJson() to "Genre",
        GenreRequest(nameEN = "Adventure", nameIT = "Avventura", id = 11).toJson() to "Genre",
        GenreRequest(nameEN = "Action", nameIT = "Azione", id = 4).toJson() to "Genre",
        GenreRequest(nameEN = "Comedy", nameIT = "Commedia", id = 12).toJson() to "Genre",
        GenreRequest(nameEN = "Crime", nameIT = "Crime", id = 2).toJson() to "Genre",
        GenreRequest(nameEN = "Documentary", nameIT = "Documentario", id = 24).toJson() to "Genre",
        GenreRequest(nameEN = "Drama", nameIT = "Dramma", id = 1).toJson() to "Genre",
        GenreRequest(nameEN = "Family", nameIT = "Famiglia", id = 16).toJson() to "Genre",
        GenreRequest(nameEN = "Science Fiction", nameIT = "Fantascienza", id = 10).toJson() to "Genre",
        GenreRequest(nameEN = "Fantasy", nameIT = "Fantasy", id = 8).toJson() to "Genre",
        GenreRequest(nameEN = "Horror", nameIT = "Horror", id = 7).toJson() to "Genre",
        GenreRequest(nameEN = "Reality", nameIT = "Reality", id = 18).toJson() to "Genre",
        GenreRequest(nameEN = "Romance", nameIT = "Romance", id = 15).toJson() to "Genre",
        GenreRequest(nameEN = "Thriller", nameIT = "Thriller", id = 5).toJson() to "Genre",
    )

    // ============= DOMINIO =============

    /**
     * Chiamata all'inizio di ogni operazione: se il sito ha traslocato gli indirizzi cambiano tutti
     * e la sessione (cookie, versione di Inertia) va rifatta.
     */
    private suspend fun ensureDomain() {
        if (customBaseUrl != null) return
        val found = SiteDomain.current(prefs)
        if (found == site) return
        site = found
        mainUrl = found.root + lang
        headers["Cookie"] = ""
        headers["X-Inertia-Version"] = ""
        inertiaVersion = ""
        decodedXsrfToken = ""
    }

    private suspend fun ready() {
        ensureDomain()
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
    }

    private suspend fun setupHeaders() {
        val response = app.get("$mainUrl/archive")
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }

        val csrfResponse = app.get(
            "${siteRootUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest"
            )
        )
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        headers["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        decodedXsrfToken = cookieJar["XSRF-TOKEN"]
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: ""

        inertiaVersion = response.document.select("#app").attr("data-page")
            .substringAfter("\"version\":\"")
            .substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Cookie" to headers["Cookie"].orEmpty(),
        "X-Requested-With" to "XMLHttpRequest",
        "X-XSRF-TOKEN" to decodedXsrfToken,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "Origin" to siteRootUrl.removeSuffix("/")
    )

    // ============= LETTURA DELLE RISPOSTE =============

    private fun isHtml(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    /** Se arriva la pagina intera invece del JSON, il payload di Inertia è dentro `#app`. */
    private fun inertiaFromHtml(html: String): String? =
        Jsoup.parse(html).selectFirst("#app")?.attr("data-page")
            ?.takeIf { it.isNotBlank() }
            ?.let { Parser.unescapeEntities(it, true) }

    private fun parseTitles(payload: String): List<Title> {
        val json = if (isHtml(payload)) inertiaFromHtml(payload) ?: return emptyList() else payload
        if (json.isBlank()) return emptyList()
        return runCatching { parseJson<InertiaResponse>(json) }.getOrNull()?.props?.titles.orEmpty()
    }

    private fun parseSlider(payload: String): HomePageList? {
        if (payload.isBlank() || isHtml(payload)) return null
        val trimmed = payload.trimStart()
        // In caso di errore l'API risponde con un oggetto `{"message": ...}` invece dell'elenco.
        if (trimmed.startsWith("{")) return null
        val slider = runCatching { parseJson<List<Slider>>(payload) }.getOrNull()?.firstOrNull()
            ?: return null
        val items = toSearchResponses(slider.titles)
        if (items.isEmpty()) return null
        return HomePageList(
            name = slider.label.ifBlank { slider.name },
            list = items,
            isHorizontalImages = false
        )
    }

    private fun toSearchResponses(titles: List<Title>): List<SearchResponse> =
        titles.filter { it.type == "movie" || it.type == "tv" }.map { title ->
            val url = "$mainUrl/titles/${title.id}-${title.slug}"
            if (title.type == "tv") {
                newTvSeriesSearchResponse(title.name, url) {
                    posterUrl = "$cdnUrl/images/" + title.getPoster()
                }
            } else {
                newMovieSearchResponse(title.name, url) {
                    posterUrl = "$cdnUrl/images/" + title.getPoster()
                }
            }
        }

    // ============= HOME =============

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val upcoming = SliderFetchRequestSlider(name = "upcoming", genre = null).toJson()
        if (!showUpcoming && request.data == upcoming) return null

        ready()

        return when (request.name) {
            "Slider" -> {
                val slider = parseJson<SliderFetchRequestSlider>(request.data)
                val response = app.post(
                    "${siteRootUrl}api/sliders/fetch?lang=$lang",
                    headers = apiHeaders(),
                    requestBody = "{\"sliders\":[${slider.toJson()}]}".toRequestBody()
                )
                val list = parseSlider(response.body.string()) ?: return null
                newHomePageResponse(list, hasNext = false)
            }

            "Genre" -> {
                val genre = parseJson<GenreRequest>(request.data)
                val response = app.get(
                    "${siteRootUrl}$lang/archive",
                    params = mapOf(
                        "page" to page.toString(),
                        "lang" to lang,
                        "genre[]" to genre.id.toString()
                    ),
                    headers = apiHeaders(),
                )
                val data = tryParseJson<ArchiveResponse>(response.body.string())?.data ?: return null
                val label = if (lang == "en") genre.nameEN else genre.nameIT
                newHomePageResponse(
                    HomePageList(name = label, list = toSearchResponses(data)),
                    hasNext = page < 17
                )
            }

            else -> null
        }
    }

    // ============= RICERCA =============

    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        val payload = app.get("$mainUrl/search", params = mapOf("q" to query)).body.string()
        return toSearchResponses(parseTitles(payload))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        ensureDomain()
        val params = mutableMapOf("q" to query)
        if (page > 1) params["page"] = page.toString()
        val payload = app.get("$mainUrl/search", params = params).body.string()
        val items = toSearchResponses(parseTitles(payload))
        return newSearchResponseList(items, hasNext = items.size >= 60)
    }

    // ============= SCHEDA =============

    override suspend fun load(url: String): LoadResponse {
        ready()
        val actualUrl = withCurrentHost(url)
        val payload = app.get(actualUrl, headers = headers).body.string()

        val props = parseJson<InertiaResponse>(payload).props
        val title = props.title!!
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }.orEmpty()
        val poster = posterOf(title)
        val backdrop = title.getBackgroundImageId()?.let { "$cdnUrl/images/$it" }

        if (title.type == "tv") {
            val episodes = episodesOf(props)
            return newTvSeriesLoadResponse(title.name, actualUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { toSearchResponses(it) }
                title.imdbId?.let { addImdbId(it) }
                title.tmdbId?.let { addTMDbId(it.toString()) }
                addActors(title.mainActors?.map { it.name })
                addScore(title.score)
                if (trailers.isNotEmpty()) addTrailer(trailers)
            }
        }

        val data = LoadData("$mainUrl/iframe/${title.id}&canPlayFHD=1", "movie", title.tmdbId)
        return newMovieLoadResponse(title.name, actualUrl, TvType.Movie, dataUrl = data.toJson()) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.tags = genres
            this.year = year
            this.plot = title.plot
            title.age?.let { this.contentRating = "$it+" }
            this.recommendations = related?.titles?.let { toSearchResponses(it) }
            title.imdbId?.let { addImdbId(it) }
            title.tmdbId?.let { addTMDbId(it.toString()) }
            addActors(title.mainActors?.map { it.name })
            addScore(title.score)
            title.runtime?.let { this.duration = it }
            if (trailers.isNotEmpty()) addTrailer(trailers)
        }
    }

    /** La copertina del sito è tagliata: quando c'è l'id TMDb si prende quella, più grande. */
    private suspend fun posterOf(title: TitleProp): String? {
        if (title.tmdbId == null) {
            return title.getBackgroundImageId()?.let { "$cdnUrl/images/$it" }
        }
        val fromTmdb = runCatching {
            app.get("https://www.themoviedb.org/${title.type}/${title.tmdbId}")
                .document.select("img.poster.w-full").attr("srcset")
                .split(", ").lastOrNull()?.substringBefore(' ')
        }.getOrNull()
        return fromTmdb?.takeIf { it.startsWith("http") }
            ?: title.getPosterImageId()?.let { "$cdnUrl/images/$it" }
    }

    /**
     * I preferiti e la cronologia conservano l'indirizzo con il dominio di allora: va rimesso
     * quello di adesso, altrimenti la scheda non si apre più dopo un trasloco del sito.
     */
    private fun withCurrentHost(url: String): String {
        if (url.startsWith(mainUrl)) return url
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return url
        val replacement = if (url.contains("/it/") || url.contains("/en/")) {
            mainUrl.toHttpUrl().host
        } else {
            mainUrl.toHttpUrl().host + "/$lang"
        }
        return url.replace(host, replacement)
    }

    private suspend fun episodesOf(props: Props): List<Episode> {
        val title = props.title ?: return emptyList()
        val episodes = mutableListOf<Episode>()

        title.seasons?.forEach { season ->
            val seasonEpisodes = if (season.id == props.loadedSeason?.id) {
                props.loadedSeason.episodes.orEmpty()
            } else {
                if (inertiaVersion.isEmpty()) setupHeaders()
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                runCatching {
                    parseJson<InertiaResponse>(app.get(url, headers = headers).body.string())
                        .props.loadedSeason?.episodes
                }.getOrNull().orEmpty()
            }

            seasonEpisodes.forEach { ep ->
                val loadData = LoadData(
                    url = "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = season.number,
                    episodeNumber = ep.number
                )
                episodes += newEpisode(loadData.toJson()) {
                    this.name = ep.name
                    this.posterUrl = props.cdnUrl + "/images/" + ep.getCover()
                    this.description = ep.plot
                    this.episode = ep.number
                    this.season = season.number
                    this.runTime = ep.duration
                }
            }
        }
        return episodes
    }

    // ============= FLUSSI =============

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false
        ensureDomain()
        val loadData = parseJson<LoadData>(data)

        // Gli extractor vengono raccolti e non passati direttamente: in casting il link va
        // riscritto, e riscriverlo richiede una funzione sospesa che nel callback non si può usare.
        val found = mutableListOf<ExtractorLink>()

        runCatching {
            val iframeSrc = app.get(loadData.url).document.select("iframe").attr("src")
            VixCloudExtractor().getUrl(iframeSrc, siteRootUrl, subtitleCallback) { found += it }
        }

        runCatching {
            val vixsrcUrl = if (loadData.type == "movie") {
                "https://vixsrc.to/movie/${loadData.tmdbId}"
            } else {
                "https://vixsrc.to/tv/${loadData.tmdbId}/${loadData.seasonNumber}/${loadData.episodeNumber}"
            }
            VixSrcExtractor().getUrl(vixsrcUrl, "https://vixsrc.to/", subtitleCallback) { found += it }
        }

        if (found.isEmpty()) return false
        found.forEach { link -> forCast(link, isCasting).forEach(callback) }
        return true
    }

    /**
     * Cloudstream manda al Chromecast solo l'indirizzo (`CastHelper`: `MediaInfo.Builder(link.url)`)
     * e usa il receiver predefinito di Google, quindi `User-Agent`, `Referer` e `Origin`
     * dell'ExtractorLink non arrivano al televisore.
     *
     * Si passa quindi dal proxy locale, che rimette gli header e serve i segmenti al televisore.
     * Il link nudo resta come seconda voce: VixCloud e VixSrc autorizzano con un token dentro
     * l'indirizzo, quindi potrebbe bastare da solo, e in quel caso evita di far passare un film
     * intero dalla banda del telefono. Ma va per secondo, perché è quello non verificato.
     *
     * Le due voci non vanno scelte a mano: se la prima non parte Cloudstream passa da solo alla
     * successiva (`CastHelper.awaitLinks`, su `FAILED` richiama con `index + 1`).
     */
    private suspend fun forCast(link: ExtractorLink, isCasting: Boolean): List<ExtractorLink> {
        if (!isCasting || link.type != ExtractorLinkType.M3U8) return listOf(link)

        val proxied = LocalProxy.hls(
            url = link.url,
            headers = link.headers + mapOf("Referer" to link.referer),
            forCast = true
        )
        val viaProxy = newExtractorLink(
            source = link.source,
            name = "${link.name} (proxy)",
            url = proxied,
            type = link.type
        ) {
            this.quality = link.quality
        }
        return listOf(viaProxy, link)
    }
}
