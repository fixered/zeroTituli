package it.zeroTituli

/** Un episodio con la stagione e il numero già decisi, pronto per la scheda. */
data class EpisodeSlot(val entry: FeedEntry, val season: Int, val episode: Int?)

/**
 * Il feed restituisce gli episodi di un programma in ordine di pubblicazione e con
 * gli extra mescolati agli episodi veri. Qui diventano una lista ordinata per
 * stagione ed episodio, con gli extra in una stagione a parte in fondo: mescolarli
 * alla numerazione vera farebbe sembrare rotto il selettore delle stagioni.
 */
object MediasetSeasons {

    /**
     * La stagione degli extra e degli speciali. Un numero alto perché il selettore
     * di CloudStream ordina i numeri, e questi devono restare per ultimi.
     */
    const val EXTRAS_SEASON = 999

    /** Il `programType` degli extra e dei promo nel feed. */
    private const val EXTRA = "extra"

    /** Il `programType` di un film: un marchio con dentro solo quello è un film. */
    private const val MOVIE = "movie"

    /** Il `programType` di una puntata intera. */
    private const val EPISODE = "episode"

    /**
     * Le voci che si guardano davvero. Gli extra non contano per capire cosa sia un
     * marchio: un film con due promo attaccati resta un film.
     */
    fun playable(entries: List<FeedEntry>): List<FeedEntry> =
        entries.filter { it.programType != EXTRA }

    /**
     * La voce da cui la scheda prende nome, copertina, trama, anno, età e cast.
     *
     * Non è la prima del feed. Nel feed vero di "La promessa" le prime due voci sono un
     * promo e una clip sul cast: prendendo la prima, la scheda della serie si presentava
     * col titolo e la grafica di un trailer. Si preferisce una puntata intera, poi il
     * film, e solo se il marchio non ha altro si accetta un extra — meglio una scheda con
     * i dati di un promo che nessuna scheda.
     */
    fun head(entries: List<FeedEntry>): FeedEntry? =
        entries.firstOrNull { it.programType == EPISODE }
            ?: entries.firstOrNull { it.programType == MOVIE }
            ?: entries.firstOrNull()

    fun arrange(entries: List<FeedEntry>): List<EpisodeSlot> = entries
        .filter { !it.guid.isNullOrBlank() }
        .distinctBy { it.guid }
        .map { entry ->
            EpisodeSlot(
                entry = entry,
                // Gli extra si riconoscono dal `programType`, non dalla stagione mancante:
                // nel feed vero portano `tvSeasonNumber = 1` come le puntate, e fidandosi
                // di quel numero finivano in mezzo alla prima stagione senza numero
                // d'episodio, cioè esattamente il buco che la stagione dedicata evita.
                season = if (entry.programType == EXTRA) {
                    EXTRAS_SEASON
                } else {
                    entry.tvSeasonNumber ?: EXTRAS_SEASON
                },
                episode = entry.tvSeasonEpisodeNumber,
            )
        }
        // Dentro la stagione i numerati vengono prima: un episodio senza numero non
        // sa dove stare, e in mezzo darebbe l'impressione di un buco.
        .sortedWith(
            compareBy(
                { it.season },
                { it.episode == null },
                { it.episode ?: Int.MAX_VALUE },
                { it.entry.title.orEmpty() },
            )
        )
}
