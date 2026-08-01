package it.zeroTituli

import android.util.Base64
import it.zeroTituli.shared.M3u8

/**
 * Riscrittura delle playlist HLS di FCTV33.
 *
 * Il CDN non serve i segmenti all'indirizzo scritto nella playlist: quell'indirizzo risponde 302
 * verso se stesso, in ciclo. Ogni riga porta però i parametri `_ctump` (elenco dei mirror, uno per
 * area) e `_ctuph` (percorso firmato), codificati in ROT13 + base64 dopo otto caratteri di
 * riempimento; l'indirizzo vero è mirror + percorso. Sul sito la sostituzione la fa un service
 * worker, qui la fa il proxy locale (`it.zeroTituli.shared.LocalProxy`).
 */
internal object Csl {

    private const val PAD = 8

    /** ROT13 sulle lettere, cifre e simboli invariati. */
    private fun rot13(s: String): String = buildString(s.length) {
        s.forEach { c ->
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            )
        }
    }

    private fun decode(value: String): String? {
        if (value.length <= PAD) return null
        val body = rot13(value.substring(PAD)).let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching {
            Base64.decode(body, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Voci del tipo `EU-IT:cf@host,EU:cf@host,AS:cf@host`: si prende quella dell'area esatta, poi
     * quella del continente, altrimenti la prima.
     */
    private fun mirrorOf(ctump: String, continent: String, country: String): String? {
        val items = decode(ctump)?.split(',')?.mapNotNull { entry ->
            val code = entry.substringBefore(':', "")
            val host = entry.substringAfter('@', "")
            if (code.isBlank() || host.isBlank()) null else code to host
        }.orEmpty()
        if (items.isEmpty()) return null
        val wanted = listOf("$continent-$country", continent)
        wanted.forEach { code -> items.firstOrNull { it.first == code }?.let { return it.second } }
        return items.first().second
    }

    /**
     * I valori sono base64 con ROT13, quindi possono contenere `+`: si sciolgono solo le sequenze
     * `%XX`, perché il decodificatore standard trasformerebbe il `+` in uno spazio.
     */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.append(hex.toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun queryOf(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val name = part.substringBefore('=', "")
            if (name.isEmpty()) return@mapNotNull null
            name to percentDecode(part.substringAfter('=', ""))
        }.toMap()
    }

    /**
     * @param base indirizzo della playlist, per risolvere le righe relative.
     * @param nested le playlist annidate tornano al proxy, così anche le loro righe vengono
     *   riscritte.
     * @param segment indirizzo finale del segmento: in casting passa dal proxy, perché il CDN
     *   pretende il `Referer` del dominio del player e il Chromecast non lo manda.
     * @return la playlist con i segmenti sostituiti dagli indirizzi dei mirror.
     */
    fun rewrite(
        playlist: String,
        base: String,
        continent: String,
        country: String,
        nested: (String) -> String,
        segment: (String) -> String
    ): String = M3u8.rewrite(playlist, base) { url ->
        val query = queryOf(url)
        val ctump = query["_ctump"]
        val ctuph = query["_ctuph"]
        if (ctump != null && ctuph != null) {
            val host = mirrorOf(ctump, continent, country)
            val path = decode(ctuph)
            if (host != null && path != null) return@rewrite segment("https://$host$path")
        }
        if (url.substringBefore('?').endsWith(".m3u8")) nested(url) else segment(url)
    }
}
