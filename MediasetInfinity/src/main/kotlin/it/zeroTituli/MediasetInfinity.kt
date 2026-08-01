package it.zeroTituli

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList

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
}
