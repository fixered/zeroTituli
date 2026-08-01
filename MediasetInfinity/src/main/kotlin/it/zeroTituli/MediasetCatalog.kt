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
                url = MediasetKeys.live(channel.callSign),
                type = TvType.Live,
                fix = false,
            ) {
                this.posterUrl = info.logo
            }
        }
        return if (items.isEmpty()) null else HomePageList("Dirette TV", items, isHorizontalImages = true)
    }

    /** Le righe di una sezione, lette dal sito; vuote se il markup è cambiato. */
    suspend fun MainAPI.sectionRows(section: MediasetSection): List<HomePageList> {
        val html = runCatching {
            com.lagradost.cloudstream3.app.get(MediasetUrls.section(section.slug)).body.string()
        }.getOrNull().orEmpty()

        val rows = MediasetSections.read(html).mapNotNull { row ->
            val items = row.items.mapNotNull { toSearchResponse(it) }
            if (items.isEmpty()) null else HomePageList("${section.label} · ${row.title}", items)
        }
        if (rows.isNotEmpty()) return rows

        // Ripiego: la sezione resta, cambia l'ordine.
        val entries = api.entries(MediasetUrls.byCategory(section.feedCategory, page = 1))
        val items = brandCards(entries)
        return if (items.isEmpty()) emptyList() else listOf(HomePageList(section.label, items))
    }

    /**
     * I consigliati di una scheda.
     *
     * Il progetto li chiama "consigliati dallo stesso marchio", ma alla lettera non
     * esistono: le voci di un marchio **sono** l'elenco degli episodi della scheda, e
     * ripeterle sotto non consiglia niente. L'unico legame che il feed offre fra marchi
     * diversi è la categoria, quindi si consigliano gli altri programmi della stessa
     * categoria, escluso quello aperto.
     */
    suspend fun MainAPI.recommendations(
        category: String,
        excludeBrandId: String?,
    ): List<SearchResponse> {
        val entries = api.entries(
            // Meno voci della riga di catalogo: qui bastano venti riquadri, e la richiesta
            // si aggiunge all'apertura di ogni scheda, quindi va tenuta leggera.
            MediasetUrls.byCategory(category, page = 1, perPage = RECOMMENDATIONS_PER_PAGE)
        ).filter { it.brandId != excludeBrandId }
        return brandCards(entries).take(RECOMMENDATIONS)
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
     * programma, quindi si tiene la prima voce di ogni chiave.
     *
     * Il raggruppamento è per `MediasetKeys.cardKeyFor`, non più per `brandId`: cinque
     * marchi tutti intitolati "Temptation Island" davano prima cinque riquadri
     * identici — uno a stagione — perché de-duplicare per `brandId` non li vedeva come
     * lo stesso programma. Filtrare le voci senza chiave **prima** di `distinctBy`, e non
     * dopo con `mapNotNull`, evita che tutte finiscano raggruppate sotto la chiave `null`.
     */
    fun MainAPI.brandCards(entries: List<FeedEntry>): List<SearchResponse> = entries
        .mapNotNull { entry -> MediasetKeys.cardKeyFor(entry)?.let { key -> key to entry } }
        .distinctBy { (key, _) -> key }
        .mapNotNull { (_, entry) -> toSearchResponse(entry) }

    fun MainAPI.toSearchResponse(entry: FeedEntry): SearchResponse? {
        val key = MediasetKeys.cardKeyFor(entry) ?: return null
        val name = entry.brandTitle?.takeIf { it.isNotBlank() } ?: entry.title ?: return null
        val poster = MediasetImages.poster(entry)
        // `fix = false` è obbligatorio: per default questi costruttori passano
        // l'indirizzo per `fixUrl`, che a una stringa senza `http` mette davanti
        // `mainUrl`. `brand:123` diventerebbe
        // `https://mediasetinfinity.mediaset.it/brand:123` e `load` non lo
        // riconoscerebbe più. Vale per tutte e tre le `new*SearchResponse`.
        //
        // La chiave qui e quella con cui `load` ricostruisce la scheda devono essere la
        // stessa stringa (`cardKeyFor`), altrimenti aprire un preferito da questo riquadro
        // porterebbe a una scheda diversa da quella che l'ha generato.
        return if (entry.programType == "movie") {
            newMovieSearchResponse(name, key, TvType.Movie, fix = false) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(name, key, TvType.TvSeries, fix = false) {
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
        return newTvSeriesSearchResponse(
            item.title,
            MediasetKeys.series(guid),
            TvType.TvSeries,
            fix = false
        ) {
            this.posterUrl = item.poster
        }
    }

    private companion object {
        /** Una riga di consigliati, non un secondo catalogo. */
        const val RECOMMENDATIONS = 20

        /**
         * Cento voci di feed danno circa ventidue programmi distinti nella Fiction, misurato
         * sul feed vero: abbastanza per riempire i venti riquadri quasi sempre.
         */
        const val RECOMMENDATIONS_PER_PAGE = 100
    }
}
