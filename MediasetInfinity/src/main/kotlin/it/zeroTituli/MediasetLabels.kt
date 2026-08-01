package it.zeroTituli

/**
 * Le etichette e la trama che si vedono sulla scheda. Trasformazioni pure su
 * `FeedEntry`, tenute fuori da `MediasetInfinity` — che estende `MainAPI` e non si
 * istanzia sulla JVM dei test — proprio perché la distinzione gratis/a pagamento è
 * quella che l'utente vede su ogni scheda a pagamento, e merita un test.
 */
object MediasetLabels {

    /**
     * I generi, più l'etichetta "Abbonamento" quando il contenuto non è gratuito: si
     * vede in cima alla scheda, prima di provare ad aprirlo.
     */
    fun tags(entry: FeedEntry): List<String> =
        if (entry.isFree) entry.genres else listOf("Abbonamento") + entry.genres

    /**
     * Il nome della stagione nel selettore di CloudStream. Serve perché la stagione degli
     * extra è il numero 999, scelto per farla restare in fondo, e senza un nome il
     * selettore la annuncia come "Season 999".
     */
    fun seasonName(season: Int): String = when (season) {
        MediasetSeasons.EXTRAS_SEASON -> "Extra e speciali"
        // Le voci vere che il feed manda senza numero di stagione — i film, sempre.
        // Portavano anche loro il nome degli extra, cioè un film intero annunciato come
        // materiale di contorno: due casi diversi, due nomi.
        MediasetSeasons.UNNUMBERED_SEASON -> "Senza stagione"
        else -> "Stagione $season"
    }

    /** Alla trama si aggiunge l'avviso quando il contenuto non è gratuito. */
    fun description(entry: FeedEntry): String? {
        val plot = entry.plot
        if (entry.isFree) return plot
        val warning = "Serve un abbonamento o un noleggio Mediaset Infinity: " +
            "con la sessione anonima questo contenuto non parte."
        return listOfNotNull(warning, plot).joinToString("\n\n")
    }
}
