package it.zeroTituli

/** Un canale Mediaset, col nome che l'API vuole e quello da mostrare. */
data class Channel(val callSign: String, val label: String)

data class LiveInfo(
    val title: String,
    val nowPlaying: String?,
    val logo: String?,
    val mediaUrl: String?,
)

/**
 * Le dirette sono l'unica parte di Mediaset che si casta: fra le varianti che
 * `nowNext` propone ce ne sono alcune senza `protectionScheme`, cioè in chiaro, con
 * il permesso già dentro l'indirizzo. Sono quelle da prendere, e solo quelle.
 */
object MediasetLive {

    /**
     * `nowNext` pretende un canale valido: senza parametro risponde `AG015`. La lista
     * è quindi scritta qui, verificata canale per canale contro l'API vera (vedi il
     * passo 8 del task): sei degli undici `callSign` indovinati erano sbagliati, e non
     * sempre nel modo ovvio. `KM`, `CI` e `LZ` rispondevano `AG015` e basta buttarli;
     * ma `KA`, `LB`, `KQ`, `LT` e `KI` rispondevano lo stesso, solo con un altro
     * canale dietro (`KA` è "La 5", non Iris; `LT` è "Top Crime", non TGCOM24). Un
     * canale così è peggio di uno assente: sembra funzionare e mostra il programma
     * sbagliato. I `callSign` giusti stanno nell'HTML della home di Mediaset, dentro
     * `cardLink.value` (`.../diretta/<slug>_c<callSign>`).
     */
    val CHANNELS = listOf(
        Channel("C5", "Canale 5"),
        Channel("I1", "Italia 1"),
        Channel("R4", "Rete 4"),
        Channel("KI", "Iris"),
        Channel("KA", "La5"),
        Channel("LB", "20"),
        Channel("FU", "Focus"),
        Channel("KQ", "Mediaset Extra"),
        Channel("B6", "Cine34"),
        Channel("KF", "TGCOM24"),
        Channel("KB", "Boing"),
        Channel("LA", "Cartoonito"),
    )

    private const val ANY = "urn:theplatform:tv:location:any"

    /**
     * L'indirizzo theplatform della variante in chiaro, da risolvere poi in SMIL.
     * Il DASH viene prima: è quello che il ricevitore predefinito del Chromecast legge.
     */
    fun clearMediaUrl(payload: String): String? {
        val tunings = body(payload)?.tuningInstruction?.get(ANY).orEmpty()
        val clear = tunings.filter { it.protectionScheme.isNullOrBlank() }
        val ordered = clear.sortedBy { if (it.format?.contains("dash", true) == true) 0 else 1 }
        return ordered.firstNotNullOfOrNull { it.publicUrls.firstOrNull()?.takeIf { u -> u.isNotBlank() } }
    }

    fun info(payload: String, fallbackLabel: String): LiveInfo? {
        val body = body(payload) ?: return null
        val station = body.stations.values.firstOrNull()
        return LiveInfo(
            title = station?.title?.takeIf { it.isNotBlank() } ?: fallbackLabel,
            nowPlaying = body.currentListing?.title?.takeIf { it.isNotBlank() },
            // La stazione porta il logo sotto `channel_logo`: le altre famiglie sono
            // quelle delle voci di programma, tenute come riserva se Mediaset cambia
            // il payload della stazione, non da "ripulire".
            logo = MediasetImages.best(
                station?.thumbnails.orEmpty(),
                listOf("channel_logo", "logo_horizontal", "brand_logo", "image_vertical")
            ),
            mediaUrl = clearMediaUrl(payload),
        )
    }

    private fun body(payload: String): NowNextBody? =
        MediasetJson.parse<NowNextResponse>(payload)?.response
}

/**
 * La parte che parla in rete: una chiamata per canale, e il SMIL della diretta che
 * non ha bisogno del token perché il flusso in chiaro è già autorizzato.
 */
class MediasetLiveApi(private val api: MediasetApi) {

    suspend fun info(callSign: String, label: String): LiveInfo? = runCatching {
        val payload = com.lagradost.cloudstream3.app.get(MediasetUrls.nowNext(callSign)).body.string()
        MediasetLive.info(payload, label)
    }.getOrNull()

    /** Dall'indirizzo theplatform al manifest vero. */
    suspend fun manifest(mediaUrl: String): String? = runCatching {
        val url = "$mediaUrl?format=SMIL&formats=mpeg-dash&tracking=false"
        val payload = com.lagradost.cloudstream3.app.get(url).body.string()
        (MediasetSmil.read(payload) as? SmilResult.Stream)?.url
    }.getOrNull()
}
