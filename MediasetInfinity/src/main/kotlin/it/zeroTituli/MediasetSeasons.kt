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

    fun arrange(entries: List<FeedEntry>): List<EpisodeSlot> = entries
        .filter { !it.guid.isNullOrBlank() }
        .distinctBy { it.guid }
        .map { entry ->
            EpisodeSlot(
                entry = entry,
                season = entry.tvSeasonNumber ?: EXTRAS_SEASON,
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
