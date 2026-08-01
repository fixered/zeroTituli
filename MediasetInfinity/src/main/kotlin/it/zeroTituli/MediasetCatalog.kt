package it.zeroTituli

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse

/**
 * Le righe della home e la conversione da voce di feed a scheda.
 *
 * Le sezioni si leggono dal markup del sito; quando quello cambia, la riga non
 * sparisce: si ripiega sul feed per categoria, che ha lo stesso contenuto in ordine
 * di pubblicazione invece che nell'ordine scelto dalla redazione.
 */
class MediasetCatalog(
    private val api: MediasetApi,
    private val live: MediasetLiveApi,
) {

    /** Una scheda apribile per ogni canale che risponde. */
    suspend fun MainAPI.liveRow(): HomePageList? {
        val items = MediasetLive.CHANNELS.mapNotNull { channel ->
            val info = live.info(channel.callSign, channel.label) ?: return@mapNotNull null
            if (info.mediaUrl == null) return@mapNotNull null
            newLiveSearchResponse(
                name = info.nowPlaying?.let { "${info.title} · $it" } ?: info.title,
                url = "live:${channel.callSign}",
                type = TvType.Live,
                fix = false,
            ) {
                this.posterUrl = info.logo
            }
        }
        return if (items.isEmpty()) null else HomePageList("Dirette TV", items, isHorizontalImages = true)
    }

    /** Le righe di una sezione, lette dal sito; vuote se il markup è cambiato. */
    suspend fun MainAPI.sectionRows(slug: String, label: String): List<HomePageList> {
        val html = runCatching {
            com.lagradost.cloudstream3.app.get(MediasetUrls.section(slug)).body.string()
        }.getOrNull().orEmpty()

        val rows = MediasetSections.read(html).mapNotNull { row ->
            val items = row.items.mapNotNull { toSearchResponse(it) }
            if (items.isEmpty()) null else HomePageList("$label · ${row.title}", items)
        }
        if (rows.isNotEmpty()) return rows

        // Ripiego: la sezione resta, cambia l'ordine.
        val entries = api.entries(MediasetUrls.byCategory(categoryOf(slug), page = 1))
        val items = brandCards(entries)
        return if (items.isEmpty()) emptyList() else listOf(HomePageList(label, items))
    }

    suspend fun MainAPI.genreRow(genre: String, page: Int): HomePageList? {
        val items = brandCards(api.entries(MediasetUrls.byGenre(genre, page)))
        return if (items.isEmpty()) null else HomePageList(genre, items)
    }

    suspend fun MainAPI.alphabeticalRow(category: String, page: Int): HomePageList? {
        val items = brandCards(api.entries(MediasetUrls.alphabetical(category, page)))
        return if (items.isEmpty()) null else HomePageList("$category dalla A alla Z", items)
    }

    /**
     * Il feed elenca episodi, non programmi: per il catalogo interessa un riquadro per
     * programma, quindi si tiene la prima voce di ogni marchio.
     */
    private fun MainAPI.brandCards(entries: List<FeedEntry>): List<SearchResponse> = entries
        .filter { !it.brandId.isNullOrBlank() }
        .distinctBy { it.brandId }
        .mapNotNull { toSearchResponse(it) }

    fun MainAPI.toSearchResponse(entry: FeedEntry): SearchResponse? {
        val brandId = entry.brandId ?: return null
        val name = entry.brandTitle?.takeIf { it.isNotBlank() } ?: entry.title ?: return null
        val poster = MediasetImages.poster(entry)
        // `fix = false` è obbligatorio: per default questi costruttori passano
        // l'indirizzo per `fixUrl`, che a una stringa senza `http` mette davanti
        // `mainUrl`. `brand:123` diventerebbe
        // `https://mediasetinfinity.mediaset.it/brand:123` e `load` non lo
        // riconoscerebbe più. Vale per tutte e tre le `new*SearchResponse`.
        return if (entry.programType == "movie") {
            newMovieSearchResponse(name, "brand:$brandId", TvType.Movie, fix = false) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(name, "brand:$brandId", TvType.TvSeries, fix = false) {
                this.posterUrl = poster
            }
        }
    }

    /**
     * Le voci lette dal sito portano l'identificativo della stagione: il marchio si
     * ricava alla prima apertura della scheda, quindi qui basta passarlo com'è.
     */
    private fun MainAPI.toSearchResponse(item: SectionItem): SearchResponse? {
        val guid = item.seriesGuid ?: return null
        return newTvSeriesSearchResponse(item.title, "series:$guid", TvType.TvSeries, fix = false) {
            this.posterUrl = item.poster
        }
    }

    /** Le sezioni del sito e le categorie del catalogo non hanno gli stessi nomi. */
    private fun categoryOf(slug: String): String = when (slug) {
        "fiction" -> "Fiction"
        "cinema" -> "Cinema"
        "programmitv" -> "Programmi Tv"
        "kids" -> "Kids"
        "documentari" -> "Documentari"
        "news-e-sport" -> "Calcio e Sport"
        else -> "Fiction"
    }
}
