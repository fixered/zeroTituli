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

    fun byBrand(brandId: String, page: Int, perPage: Int = 100) = feed(
        mapOf(
            "byCustomValue" to "{brandId}{$brandId}",
            "byProgramType" to "episode",
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

    fun byCategory(category: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "byTags" to "category|$category",
            "sort" to "mediasetprogram\$publishInfo_lastPublished|desc",
            "range" to range(page, perPage),
        )
    )

    fun byGenre(genre: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "byTags" to "genre|$genre",
            "sort" to "mediasetprogram\$publishInfo_lastPublished|desc",
            "range" to range(page, perPage),
        )
    )

    fun alphabetical(category: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "byTags" to "category|$category",
            "sort" to "mediasetprogram\$brandTitle|asc",
            "range" to range(page, perPage),
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
    ): String = mediaUrl + "?" + query(
        mapOf(
            "format" to "SMIL",
            "formats" to formats,
            "assetTypes" to assetTypes,
            "auto" to "true",
            "tracking" to "false",
            "auth" to token,
        )
    )

    fun license(pid: String, token: String): String = LICENSE + "?" + query(
        mapOf(
            "form" to "json",
            "schema" to "1.0",
            "token" to token,
            "account" to ACCOUNT_URI,
            "releasePid" to pid,
        )
    )

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
