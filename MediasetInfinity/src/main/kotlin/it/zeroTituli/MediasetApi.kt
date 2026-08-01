package it.zeroTituli

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** Sessione anonima: il token per i feed protetti e per la licenza. */
data class Session(val beToken: String, val sid: String, val bornAt: Long)

/**
 * Quando rifare il login. Regola separata dalla rete perché è l'unica parte che vale
 * la pena provare senza dispositivo.
 */
object MediasetSession {

    /**
     * Il token vale qualche ora. Si rinnova dopo quattro, con margine: un token
     * scaduto a metà film si vede, un login in più non si nota.
     */
    const val LIFETIME_MS = 4L * 60 * 60 * 1000

    fun isStale(session: Session?, now: Long): Boolean {
        if (session == null || session.beToken.isBlank()) return true
        val age = now - session.bornAt
        // Età negativa vuol dire che l'orologio è cambiato: meglio rifare il login
        // che fidarsi di una sessione nata nel futuro.
        return age < 0 || age >= LIFETIME_MS
    }
}

data class VodStream(val manifest: String, val licenseUrl: String)

sealed class VodResult {
    data class Ok(val stream: VodStream) : VodResult()
    object GeoBlocked : VodResult()
    object NotAvailable : VodResult()
}

/**
 * L'unico punto del plugin che parla in rete.
 *
 * Il catalogo sta su feed aperti; la riproduzione richiede tre passaggi (sessione
 * anonima, `playbackCheck`, SMIL) descritti nel progetto. Gli `assetTypes` vanno
 * provati in ordine: uno che non combacia non è un errore di rete, è un SMIL con
 * `NoAssetTypeFormatMatches` dentro.
 */
class MediasetApi(private val clock: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        /** Dal più definito al meno: la prima copia disponibile vince. */
        private val VOD_ASSET_TYPES = listOf(
            "HR,widevine,geoIT|geoNo",
            "SD,widevine,geoIT|geoNo",
            "SS,widevine,geoIT|geoNo",
        )
        private val JSON = "application/json".toMediaTypeOrNull()
    }

    @Volatile
    private var session: Session? = null

    /** L'identificativo di questa installazione: casuale, e sempre lo stesso finché il plugin vive. */
    private val deviceId: String = UUID.randomUUID().toString()

    // ============= SESSIONE =============

    private suspend fun session(): Session? {
        session.takeIf { !MediasetSession.isStale(it, clock()) }?.let { return it }
        val fresh = login()
        if (fresh != null) session = fresh
        return fresh
    }

    private suspend fun login(): Session? = runCatching {
        val body = MediasetJson.mapper.writeValueAsString(
            mapOf("client_id" to deviceId, "appName" to MediasetUrls.APP_NAME)
        )
        val response = app.post(
            MediasetUrls.anonymousLogin,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin" to MediasetUrls.SITE,
                "Referer" to "${MediasetUrls.SITE}/",
            ),
            requestBody = body.toRequestBody(JSON)
        ).body.string()

        val parsed = MediasetJson.parse<LoginResponse>(response)?.response ?: return@runCatching null
        val token = parsed.beToken?.takeIf { it.isNotBlank() } ?: return@runCatching null
        Session(beToken = token, sid = parsed.sid.orEmpty(), bornAt = clock())
    }.getOrNull()

    // ============= CATALOGO =============

    suspend fun page(url: String): FeedResponse? = runCatching {
        MediasetJson.parse<FeedResponse>(app.get(url).body.string())
    }.getOrNull()

    suspend fun entries(url: String): List<FeedEntry> = page(url)?.entries.orEmpty()

    suspend fun entry(guid: String): FeedEntry? =
        entries(MediasetUrls.byGuid(guid)).firstOrNull()

    // ============= RIPRODUZIONE =============

    /**
     * @param guid l'identificativo del contenuto, cioè il `guid` della voce di feed.
     */
    suspend fun vod(guid: String): VodResult {
        val first = resolve(guid)
        if (first != VodResult.NotAvailable) return first

        // Il token può essere scaduto prima del tempo previsto: si butta la sessione e
        // si riprova **una volta sola**. Se cade di nuovo, il contenuto non è
        // disponibile per davvero e insistere farebbe solo girare a vuoto.
        session = null
        return resolve(guid)
    }

    private suspend fun resolve(guid: String): VodResult {
        val session = session() ?: return VodResult.NotAvailable
        val mediaUrl = mediaSelectorUrl(guid, session) ?: return VodResult.NotAvailable

        VOD_ASSET_TYPES.forEach { assetTypes ->
            val payload = runCatching {
                app.get(MediasetUrls.smil(mediaUrl, assetTypes, session.beToken)).body.string()
            }.getOrNull() ?: return@forEach

            when (val result = MediasetSmil.read(payload)) {
                is SmilResult.Stream -> {
                    if (result.kind != StreamKind.DASH) return@forEach
                    val pid = mediaUrl.substringAfterLast('/')
                    return VodResult.Ok(
                        VodStream(
                            manifest = result.url,
                            licenseUrl = MediasetUrls.license(pid, session.beToken)
                        )
                    )
                }
                // Fuori area vale per tutte le copie: insistere non cambia niente.
                SmilResult.GeoBlocked -> return VodResult.GeoBlocked
                SmilResult.NoMatch -> Unit
                is SmilResult.Failed -> Unit
            }
        }
        return VodResult.NotAvailable
    }

    /** `playbackCheck` dice se il contenuto è riproducibile e dove sta la sua copia. */
    private suspend fun mediaSelectorUrl(guid: String, session: Session): String? = runCatching {
        val body = MediasetJson.mapper.writeValueAsString(
            mapOf("contentId" to guid, "streamType" to "VOD")
        )
        val response = app.post(
            MediasetUrls.playbackCheck,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${session.beToken}",
                "sid" to session.sid,
                "Origin" to MediasetUrls.SITE,
            ),
            requestBody = body.toRequestBody(JSON)
        ).body.string()

        // Senza `mediaSelector` il contenuto vuole un abbonamento o un noleggio.
        MediasetJson.parse<PlaybackCheckResponse>(response)
            ?.response?.mediaSelector?.url?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
