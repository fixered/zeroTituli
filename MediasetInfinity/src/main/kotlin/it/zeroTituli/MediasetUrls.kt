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
