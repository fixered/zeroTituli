package it.zeroTituli

/**
 * Le etichette e la trama che si vedono sulla scheda. Trasformazioni pure su
 * `FeedEntry`, tenute fuori da `MediasetInfinity` — che estende `MainAPI` e non si
 * istanzia sulla JVM dei test — proprio perché la distinzione gratis/a pagamento è
 * quella che l'utente vede su ogni scheda a pagamento, e merita un test.
 */
object MediasetLabels {

    /**
     * Di che natura è un riquadro, secondo i chip che CloudStream mette in cima alla Home.
     *
     * Quei chip — Film, Serie TV, Cartoni, Documentari, Dirette — filtrano per `TvType`, e
     * i chip visibili li decide il provider (`HomeFragment.validateChips`). Finché ogni
     * scheda che non è un film si dichiara `TvSeries`, Kids e documentari finiscono sotto
     * "Serie TV" e i chip non filtrano niente. Il tipo si ricava quindi dalla categoria
     * della riga e non dalla voce di feed: la proiezione `fields=` delle righe di catalogo
     * non porta i `tags`, quindi `entry.categories` lì è vuoto, mentre la riga la sua
     * categoria la conosce già.
     *
     * Vive qui, fuori dai file che importano CloudStream, perché `TvType` è un tipo di
     * CloudStream e non esiste sulla JVM dei test: la scelta si prova, la traduzione in
     * `TvType` è una riga sola in `MediasetCatalog`.
     */
    enum class CardKind { MOVIE, SERIES, DOCUMENTARY, KIDS }

    /**
     * @param rowCategory la categoria della riga che mostra il riquadro (`Kids`,
     *   `Documentari`, …), oppure il nome del genere: le due vocabolari si sovrappongono
     *   dove conta. `null` per la ricerca, che non ha categoria.
     */
    fun kind(rowCategory: String?, programType: String?): CardKind = when {
        // Un film resta un film anche in Kids: nei chip di CloudStream "Cartoni" raccoglie
        // le serie animate, e un film per bambini lo si cerca sotto Film.
        programType == "movie" -> CardKind.MOVIE
        rowCategory.equals("Kids", ignoreCase = true) -> CardKind.KIDS
        rowCategory.equals("Documentari", ignoreCase = true) -> CardKind.DOCUMENTARY
        else -> CardKind.SERIES
    }

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
