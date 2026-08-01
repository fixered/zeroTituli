package it.zeroTituli

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.app
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Il sito cambia dominio spesso, di solito tenendo il nome e cambiando il suffisso
 * (streamingunity.biz → .dog → .cc). Chiedere l'indirizzo all'utente a ogni cambio è la cosa che
 * rompe di più, quindi il dominio buono lo si cerca da soli.
 *
 * I domini vecchi restano in piedi e rispondono `301` verso quello nuovo: basta bussare a uno
 * qualunque di quelli conosciuti e guardare dove si finisce. Non serve nessun servizio di terzi e
 * la lista dei semi non va tenuta aggiornata, perché ogni dominio trovato diventa il seme
 * successivo.
 */
object SiteDomain {

    const val DEFAULT = "https://streamingunity.cc/"

    /** Domini già usati dal sito, dal più recente. Servono solo come punto di partenza. */
    private val seeds = listOf(
        "streamingunity.cc",
        "streamingunity.dog",
        "streamingunity.biz",
        "streamingcommunity.blog"
    )

    private const val KEY_URL = "resolvedBaseUrl"
    private const val KEY_TIME = "resolvedBaseUrlTime"
    private const val TTL_MS = 12L * 60 * 60 * 1000

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36"

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

    fun cached(prefs: SharedPreferences?): String? =
        normalize(prefs?.getString(KEY_URL, null))

    /**
     * @return la radice del sito in questo momento. Se la ricerca non riesce (rete giù, tutti i
     *   semi irraggiungibili) torna l'ultimo dominio noto invece di niente, così il plugin resta
     *   utilizzabile appena la rete torna.
     */
    suspend fun current(prefs: SharedPreferences?, force: Boolean = false): String {
        val known = cached(prefs) ?: DEFAULT
        if (!force && isFresh(prefs)) return known
        val found = discover(known)
        if (found != null) {
            prefs?.edit {
                putString(KEY_URL, found)
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

    /**
     * Prova i domini uno per uno e si ferma al primo che risponde con la pagina del sito. Il
     * controllo su `data-page` serve a scartare le pagine di blocco degli operatori, che
     * rispondono `200` con un avviso al posto del sito.
     */
    private suspend fun discover(preferred: String): String? {
        val hosts = (listOf(hostOf(preferred)) + seeds).filterNotNull().distinct()
        hosts.forEach { host ->
            val root = probe("https://$host/")
            if (root != null) return root
        }
        return null
    }

    private suspend fun probe(url: String): String? = runCatching {
        val response = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 15)
        if (!response.isSuccessful) return null
        if (!response.text.contains("data-page")) return null
        // `response.url` è l'indirizzo finale: se il seme era vecchio, qui c'è già quello nuovo.
        normalize(response.url)
    }.getOrNull()

    private fun hostOf(url: String): String? = runCatching { url.toHttpUrl().host }.getOrNull()
}
