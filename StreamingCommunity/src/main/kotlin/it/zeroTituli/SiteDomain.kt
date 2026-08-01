package it.zeroTituli

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.app
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/**
 * Il sito cambia dominio ogni pochi giorni (streamingcommunityz.vin → .team → .town → .support):
 * gli operatori bloccano quello in uso e ne viene acceso un altro. Chiedere il nuovo indirizzo
 * all'utente a ogni giro è la cosa che rompe di più, quindi lo si cerca da soli.
 *
 * La fonte buona è il sito stesso: nei props di Inertia c'è `app_url`, cioè il dominio che il sito
 * considera il proprio in questo momento. Basta quindi raggiungere **un** dominio qualsiasi della
 * famiglia per sapere qual è quello giusto. Due cose lo rendono affidabile:
 *
 *  - i domini bloccati non vengono spenti, rispondono `301` verso quello nuovo (verificato su
 *    `.vin`, `.team` e `.town`, che portano tutti a `.support`);
 *  - `auth_url` (`streamingunity.cc`) è l'host del login, non quello dello streaming: viene
 *    bloccato molto più di rado e dichiara comunque l'`app_url` corrente.
 *
 * Gli annunci sul canale Telegram sono la stessa informazione ma non sono leggibili da qui: il
 * canale è a inviti, quindi niente `t.me/s/...` da scaricare.
 */
object SiteDomain {

    const val DEFAULT_ROOT = "https://streamingcommunityz.support/"
    const val DEFAULT_CDN = "https://cdn.streamingcommunityz.support"

    /**
     * Domini da cui partire, in ordine di probabilità. `streamingunity.cc` sta in alto apposta:
     * regge più a lungo degli altri e serve solo a farsi dire qual è l'indirizzo buono.
     */
    private val seeds = listOf(
        "streamingcommunityz.support",
        "streamingunity.cc",
        "streamingcommunityz.town",
        "streamingcommunityz.team",
        "streamingcommunityz.vin"
    )

    private const val KEY_ROOT = "resolvedRoot"
    private const val KEY_CDN = "resolvedCdn"
    private const val KEY_TIME = "resolvedAt"
    private const val TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_HOPS = 3

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36"

    private val appUrlRegex = Regex(""""app_url"\s*:\s*"([^"]+)"""")
    private val cdnUrlRegex = Regex(""""cdn_url"\s*:\s*"([^"]+)"""")

    /** @param root radice con la barra finale, @param cdn radice del CDN senza barra finale. */
    data class Site(val root: String, val cdn: String)

    /** Riduce un indirizzo scritto a mano alla sola radice: `sito.tld` → `https://sito.tld/`. */
    fun normalize(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching {
            candidate.toHttpUrl().newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }.getOrNull()
    }

    fun cached(prefs: SharedPreferences?): Site? {
        val root = normalize(prefs?.getString(KEY_ROOT, null)) ?: return null
        val cdn = prefs?.getString(KEY_CDN, null)?.takeIf { it.isNotBlank() } ?: cdnFor(root)
        return Site(root, cdn)
    }

    /** Il CDN è sempre `cdn.` davanti all'host, ma quando il sito lo dichiara si usa il suo. */
    fun cdnFor(root: String): String =
        runCatching { "https://cdn." + root.toHttpUrl().host }.getOrNull() ?: DEFAULT_CDN

    fun manual(rawUrl: String?): Site? = normalize(rawUrl)?.let { Site(it, cdnFor(it)) }

    /**
     * @return il sito in questo momento. Se la ricerca non riesce (rete giù, tutti i semi
     *   irraggiungibili) torna l'ultimo indirizzo noto invece di niente, così il plugin riparte da
     *   solo appena la rete torna.
     */
    suspend fun current(prefs: SharedPreferences?, force: Boolean = false): Site {
        val known = cached(prefs) ?: Site(DEFAULT_ROOT, DEFAULT_CDN)
        if (!force && isFresh(prefs)) return known

        val found = discover(known.root)
        if (found != null) {
            prefs?.edit {
                putString(KEY_ROOT, found.root)
                putString(KEY_CDN, found.cdn)
                putLong(KEY_TIME, System.currentTimeMillis())
            }
            return found
        }
        return known
    }

    private fun isFresh(prefs: SharedPreferences?): Boolean {
        val at = prefs?.getLong(KEY_TIME, 0L) ?: 0L
        return at > 0L && System.currentTimeMillis() - at < TTL_MS
    }

    private suspend fun discover(preferred: String): Site? {
        val hosts = (listOfNotNull(hostOf(preferred)) + seeds).distinct()
        hosts.forEach { host ->
            probe("https://$host/", 0)?.let { return it }
        }
        return null
    }

    /**
     * Scarica una pagina e ne legge i props. Se `app_url` indica un altro dominio ci si sposta lì:
     * serve a prendere il CDN giusto, perché un seme di appoggio come `streamingunity.cc` dichiara
     * il proprio, non quello del sito corrente.
     */
    private suspend fun probe(url: String, hop: Int): Site? {
        if (hop > MAX_HOPS) return null
        val response = runCatching {
            app.get(url, headers = mapOf("User-Agent" to ua), timeout = 15)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null

        // `data-page` è il payload di Inertia. Serve anche a scartare le pagine di blocco degli
        // operatori, che rispondono 200 con un avviso al posto del sito.
        val props = runCatching {
            Jsoup.parse(response.text).selectFirst("#app")?.attr("data-page")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val declared = appUrlRegex.find(props)?.groupValues?.getOrNull(1)?.let { normalize(it) }
        // Senza dichiarazione vale l'indirizzo finale: i domini bloccati reindirizzano al nuovo.
        val root = declared ?: normalize(response.url) ?: return null

        if (hostOf(root) != hostOf(url) && declared != null) {
            probe(root, hop + 1)?.let { return it }
        }

        val cdn = cdnUrlRegex.find(props)?.groupValues?.getOrNull(1)
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("http") && hostOf(it)?.endsWith(hostOf(root).orEmpty()) == true }
            ?: cdnFor(root)

        return Site(root, cdn)
    }

    private fun hostOf(url: String): String? = runCatching { url.toHttpUrl().host }.getOrNull()
}
