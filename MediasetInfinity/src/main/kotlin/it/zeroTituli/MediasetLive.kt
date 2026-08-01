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

    /** Il nome da mostrare per un canale; se il `callSign` non è in lista resta lui stesso. */
    fun labelFor(callSign: String): String =
        CHANNELS.firstOrNull { it.callSign == callSign }?.label ?: callSign

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
            logo = MediasetImages.channelLogo(station?.thumbnails.orEmpty()),
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
class MediasetLiveApi(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * `nowNext` veniva chiamato tre volte per ogni canale che si guarda: una per la riga
     * della home, una per la scheda, una per il flusso. Il programma in onda cambia ogni
     * mezz'ora, quindi mezzo minuto di memoria non invecchia niente di visibile e taglia
     * due chiamate su tre.
     */
    private val cache = mutableMapOf<String, Pair<Long, String>>()

    private suspend fun payload(callSign: String): String? {
        synchronized(cache) {
            cache[callSign]?.let { (bornAt, payload) ->
                if (clock() - bornAt in 0 until CACHE_MS) return payload
            }
        }
        val fresh = runCatching {
            com.lagradost.cloudstream3.app.get(MediasetUrls.nowNext(callSign)).body.string()
        }.getOrNull() ?: return null
        synchronized(cache) { cache[callSign] = clock() to fresh }
        return fresh
    }

    suspend fun info(callSign: String, label: String): LiveInfo? =
        payload(callSign)?.let { MediasetLive.info(it, label) }

    /** Dall'indirizzo theplatform al manifest vero. */
    suspend fun manifest(mediaUrl: String): String? = runCatching {
        val payload =
            com.lagradost.cloudstream3.app.get(MediasetUrls.liveSmil(mediaUrl)).body.string()
        // Il chiamante dichiara `ExtractorLinkType.DASH`: un `.m3u8` o un `.mp4` finito
        // qui per sbaglio partirebbe con il tipo sbagliato invece di non partire.
        (MediasetSmil.read(payload) as? SmilResult.Stream)
            ?.takeIf { it.kind == StreamKind.DASH }
            ?.url
    }.getOrNull()

    private companion object {
        const val CACHE_MS = 30_000L
    }
}
