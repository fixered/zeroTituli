package it.zeroTituli

import java.net.URLEncoder

/**
 * Tutti gli indirizzi in un posto solo, e nessuna chiamata di rete: così le query si
 * possono provare senza dispositivo e senza toccare Mediaset.
 */
object MediasetUrls {

    const val SITE = "https://mediasetinfinity.mediaset.it"
    const val PLAY_API = "https://api-ott-prod-fe.mediaset.net/PROD/play/"
    const val FEED =
        "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"
    /**
     * Da qui vengono le sei categorie e le lettere che hanno contenuti. Il plugin non la
     * chiama: le categorie cambiano una volta ogni mai, quindi stanno scritte in
     * `MediasetInfinity` e nella tabella delle sezioni, e questo indirizzo è quello che
     * lo script di ricognizione ricontrolla quando qualcosa non torna.
     */
    const val AZ_LISTING =
        "https://static3.mediasetplay.mediaset.it/cataloglisting/azListing.json"
    const val LICENSE =
        "https://widevine.entitlement.eu.theplatform.com/wv/web/ModularDrm/getRawWidevineLicense"
    const val ACCOUNT_URI = "http://access.auth.theplatform.eu/data/Account/2702976343"

    /**
     * La versione del sito, che il login anonimo pretende. Sta nell'HTML della home di
     * Mediaset: se un giorno il login risponde `AG005 VALIDATION_ERROR`, è questa da
     * aggiornare.
     */
    const val APP_NAME = "web//mediasetplay-web/1.3.2-e49d465"

    /**
     * Quante voci per pagina chiede `byBrand`. Sta qui, con la query che la usa, perché
     * il ciclo che scarica le puntate deve confrontare il blocco ricevuto con **questo**
     * numero: prima ce n'erano due, uno per file, uguali per combinazione, e cambiarne
     * uno avrebbe fatto fermare le serie lunghe alla prima pagina senza dire niente.
     */
    const val EPISODES_PER_PAGE = 100

    /**
     * Quante voci per pagina chiedono le righe di catalogo (generi, A-Z, ripiego per
     * categoria). Alto di proposito: il feed elenca episodi, non programmi, e chi legge
     * la riga tiene un riquadro per marchio. Con 40 voci la Fiction dalla A alla Z
     * mostrava 5 programmi, perché le prime 40 puntate in ordine di titolo appartengono
     * a quattro o cinque programmi soli; con 200 ne mostra 19. Misure vere sul feed, non
     * stime.
     */
    const val CARDS_PER_PAGE = 200

    /**
     * I tipi di contenuto che una scheda di programma deve raccogliere.
     *
     * Chiedere solo `episode` sembrava giusto e invece nascondeva due cose: i film hanno
     * `programType=movie` e stanno in un marchio tutto loro, quindi la loro scheda
     * arrivava **vuota** e CloudStream mostrava un errore di caricamento; e gli extra non
     * arrivavano mai, quindi la stagione dedicata agli extra non si riempiva mai.
     */
    const val BRAND_PROGRAM_TYPES = "episode|movie|extra"

    /**
     * I soli campi che un riquadro di catalogo legge (vedi `MediasetCatalog.brandCards`):
     * marchio, titolo, tipo e immagini. Chiedendo solo questi la risposta da 200 voci
     * scende da 2,3 MB a 1,5 MB, che è il prezzo di alzare le voci per pagina senza
     * peggiorare la memoria. Se un riquadro inizia a leggere un campo nuovo va aggiunto
     * qui, altrimenti arriva vuoto e non se ne accorge nessuno.
     */
    private val CARD_FIELDS = listOf(
        "guid",
        "title",
        "programType",
        "thumbnails",
        "mediasetprogram\$brandId",
        "mediasetprogram\$brandTitle",
    ).joinToString(",")

    /**
     * I campi che la scheda di un programma legge, e nessun altro.
     *
     * Sono più di quelli di un riquadro perché da questa sola risposta `loadBrand` ricava
     * due cose: l'elenco delle puntate e l'intestazione della scheda. Uno per uno, chi li
     * legge:
     * - `guid`: la chiave di riproduzione di ogni puntata, e lo scarto dei doppioni in
     *   `MediasetSeasons.arrange`;
     * - `title`: il nome della puntata, e l'ordine a pari numero d'episodio;
     * - `description`, `longDescription`: la trama, via `FeedEntry.plot`;
     * - `programType`: extra, film o puntata, cioè la stagione degli extra e la decisione
     *   fra scheda film e scheda serie;
     * - `year`, `ratings`: anno e semaforo dell'età dell'intestazione;
     * - `credits.personName`: il cast. Il sottocampo non è un vezzo: chiedendo `credits`
     *   liscio il feed risponde `"credits": []` — verificato sulla voce F312561801000104,
     *   22 nomi senza proiezione e zero con `fields=credits` — e la scheda avrebbe perso il
     *   cast in silenzio. `creditType` non lo legge nessuno, quindi resta fuori;
     * - `runtime`: la durata di riserva quando `mediasetprogram$duration` manca;
     * - `tvSeasonNumber`, `tvSeasonEpisodeNumber`: la numerazione;
     * - `tags`: la categoria da cui nascono i consigliati, e i generi di riserva;
     * - `thumbnails`: le copertine (`MediasetImages.still`, `poster`, `background`);
     * - `mediasetprogram$brandId`: il marchio da escludere dai consigliati;
     * - `mediasetprogram$brandTitle`: il nome della scheda;
     * - `mediasetprogram$duration`: la durata mostrata, e la regola che distingue un film
     *   dal suo trailer (`MediasetSeasons.features`);
     * - `mediasetprogram$genres`: i tag;
     * - `mediasetprogram$channelsRights`: il diritto AVOD, cioè l'etichetta "Abbonamento"
     *   e l'avviso nella trama (`MediasetLabels`).
     *
     * Restano fuori `media`, `seriesId`, `tvSeasonId`, i sottomarchi, `editorialType` e
     * `pageUrl`: nessuno di questi viene letto su questa strada.
     *
     * Misura vera su "La promessa" (`brandId=100012714`, 2699 voci con questo filtro): una
     * pagina da 100 voci passa da 1 170 623 a 959 336 byte, cioè 31,6 MB di scheda che
     * diventano 25,9. Il taglio è del 18% e non di più perché `thumbnails` da solo pesa
     * 768 388 byte del totale, e il feed rifiuta di restringerlo:
     * `fields=thumbnails.url` risponde `BadParameterException`. Quel che resta, quindi,
     * sono quasi solo immagini che la scheda usa davvero.
     */
    private val BRAND_FIELDS = listOf(
        "guid",
        "title",
        "description",
        "longDescription",
        "programType",
        "year",
        "runtime",
        "tvSeasonNumber",
        "tvSeasonEpisodeNumber",
        "credits.personName",
        "ratings",
        "tags",
        "thumbnails",
        "mediasetprogram\$brandId",
        "mediasetprogram\$brandTitle",
        "mediasetprogram\$duration",
        "mediasetprogram\$genres",
        "mediasetprogram\$channelsRights",
    ).joinToString(",")

    /** Il conto theplatform di Mediaset, dentro gli indirizzi di serie e stagioni. */
    private const val ACCOUNT_GUID = "2702976343"
    private const val PROGRAM_BASE =
        "http://data.entertainment.tv.theplatform.eu/entertainment/data/Program/guid/$ACCOUNT_GUID/"

    val anonymousLogin get() = PLAY_API + "idm/anonymous/login/v2.0"
    val playbackCheck get() = PLAY_API + "playback/check/v2.0"

    fun nowNext(callSign: String) = PLAY_API + "alive/nownext/v1.0?channelId=" + enc(callSign)

    fun section(slug: String) = "$SITE/$slug"

    /** theplatform conta da 1 e vuole gli estremi inclusi. */
    fun range(page: Int, perPage: Int): String {
        val p = if (page < 1) 1 else page
        val from = (p - 1) * perPage + 1
        return "$from-${from + perPage - 1}"
    }

    fun feed(params: Map<String, String>): String =
        FEED + "?" + query(mapOf("form" to "cjson") + params)

    fun byBrand(brandId: String, page: Int, perPage: Int = EPISODES_PER_PAGE) = feed(
        mapOf(
            "byCustomValue" to "{brandId}{$brandId}",
            "byProgramType" to BRAND_PROGRAM_TYPES,
            // Nessun `sort`: provato `tvSeasonNumber|asc,tvSeasonEpisodeNumber|asc` sul
            // feed vero e mette davanti gli extra, che di numero non ne hanno. L'ordine
            // di default tiene gli episodi in prima pagina, e a ordinarli ci pensa
            // `MediasetSeasons.arrange` quando sono tutti arrivati.
            "range" to range(page, perPage),
            "count" to "true",
            // L'unica query di catalogo che era rimasta senza proiezione, e quella che
            // scarica di più: "La promessa" sono 2699 voci, cioè 27 pagine da 100 aperte
            // in fila ogni volta che si apre quella scheda, su un telefono da `minSdk 21`.
            "fields" to BRAND_FIELDS,
        )
    )

    /**
     * Le puntate di un programma raggruppato per titolo del marchio invece che per
     * `brandId` (vedi `MediasetKeys.cardKeyFor`): stessa forma di [byBrand], stesso
     * filtro sul tipo, stessa proiezione — cambia solo il campo del filtro, `brandTitle`
     * al posto di `brandId`, così una query sola ritrova tutte le edizioni di un
     * programma che Mediaset spezza su marchi diversi (`Temptation Island`: cinque
     * `brandId` diversi, tutti con questo stesso titolo).
     */
    fun byProgramTitle(title: String, page: Int, perPage: Int = EPISODES_PER_PAGE) = feed(
        mapOf(
            "byCustomValue" to "{brandTitle}{$title}",
            "byProgramType" to BRAND_PROGRAM_TYPES,
            "range" to range(page, perPage),
            "count" to "true",
            "fields" to BRAND_FIELDS,
        )
    )

    fun bySeries(seriesGuid: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "bySeriesId" to PROGRAM_BASE + seriesGuid,
            "range" to range(page, perPage),
            "count" to "true",
        )
    )

    fun byCategory(category: String, page: Int, perPage: Int = CARDS_PER_PAGE) = feed(
        mapOf(
            "byTags" to "category|$category",
            "sort" to "mediasetprogram\$publishInfo_lastPublished|desc",
            "range" to range(page, perPage),
            "fields" to CARD_FIELDS,
        )
    )

    fun byGenre(genre: String, page: Int, perPage: Int = CARDS_PER_PAGE) = feed(
        mapOf(
            "byTags" to "genre|$genre",
            "sort" to "mediasetprogram\$publishInfo_lastPublished|desc",
            "range" to range(page, perPage),
            "fields" to CARD_FIELDS,
        )
    )

    fun alphabetical(category: String, page: Int, perPage: Int = CARDS_PER_PAGE) = feed(
        mapOf(
            "byTags" to "category|$category",
            "sort" to "mediasetprogram\$brandTitle|asc",
            "range" to range(page, perPage),
            "fields" to CARD_FIELDS,
        )
    )

    fun search(query: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "q" to query,
            "range" to range(page, perPage),
        )
    )

    fun byGuid(guid: String) = feed(mapOf("byGuid" to guid, "range" to "1-1"))

    fun smil(
        mediaUrl: String,
        assetTypes: String,
        token: String,
        formats: String = "mpeg-dash",
    ): String = withQuery(
        mediaUrl,
        mapOf(
            "format" to "SMIL",
            "formats" to formats,
            "assetTypes" to assetTypes,
            "auto" to "true",
            "tracking" to "false",
            "auth" to token,
        )
    )

    /**
     * Il SMIL di una diretta. Senza `auth` e senza `assetTypes`: il flusso in chiaro è
     * già autorizzato dal token che `nowNext` ha messo nell'indirizzo.
     */
    fun liveSmil(mediaUrl: String): String = withQuery(
        mediaUrl,
        mapOf(
            "format" to "SMIL",
            "formats" to "mpeg-dash",
            "tracking" to "false",
        )
    )

    fun license(pid: String, token: String): String = withQuery(
        LICENSE,
        mapOf(
            "form" to "json",
            "schema" to "1.0",
            "token" to token,
            "account" to ACCOUNT_URI,
            "releasePid" to pid,
        )
    )

    /**
     * `mediaUrl` arriva da `playbackCheck` e da `nowNext`, cioè da fuori: il giorno che
     * arriva con una query già attaccata, incollare un `?` fisso darebbe un indirizzo con
     * due `?` e theplatform lo rifiuterebbe.
     */
    private fun withQuery(url: String, params: Map<String, String>): String {
        val separator = if (url.contains('?')) "&" else "?"
        return url + separator + query(params)
    }

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
