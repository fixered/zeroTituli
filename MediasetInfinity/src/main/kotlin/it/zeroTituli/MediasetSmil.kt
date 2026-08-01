package it.zeroTituli

enum class StreamKind { DASH, HLS, PROGRESSIVE }

sealed class SmilResult {
    data class Stream(val url: String, val kind: StreamKind) : SmilResult()

    /** `assetTypes` o `formats` non combaciano con nessuna copia disponibile. */
    object NoMatch : SmilResult()

    /** Fuori area, o token non valido: il CDN manda un video di cortesia. */
    object GeoBlocked : SmilResult()

    data class Failed(val reason: String) : SmilResult()
}

/**
 * theplatform risponde sempre con un SMIL, anche quando le cose vanno male: gli
 * errori arrivano come `<ref>` con un `param` `exception`, e il `src` punta a un
 * video di cortesia. Riconoscerli è l'unico modo per non far partire un cartello al
 * posto del contenuto.
 */
object MediasetSmil {

    private val src = Regex("""<(?:video|ref)[^>]*\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val exception = Regex("""name="exception"\s+value="([^"]*)"""", RegexOption.IGNORE_CASE)

    fun read(payload: String): SmilResult {
        if (payload.isBlank()) return SmilResult.Failed("risposta vuota")
        if (!payload.contains("<smil", ignoreCase = true)) {
            return SmilResult.Failed("la risposta non è un SMIL")
        }

        val url = src.find(payload)?.groupValues?.get(1)
            ?: return SmilResult.Failed("nessun flusso nel SMIL")

        // Il cartello di cortesia arriva con esito positivo: si riconosce dall'indirizzo.
        if (url.contains("/cortesia/") || url.contains("GEOLOCK")) return SmilResult.GeoBlocked

        when (exception.find(payload)?.groupValues?.get(1)) {
            null -> Unit
            "NoAssetTypeFormatMatches" -> return SmilResult.NoMatch
            "InvalidAuthToken", "GeoLocationBlocked" -> return SmilResult.GeoBlocked
            else -> return SmilResult.NoMatch
        }

        if (url.contains("errorFiles")) return SmilResult.NoMatch

        val path = url.substringBefore('?')
        val kind = when {
            path.endsWith(".mpd") -> StreamKind.DASH
            path.endsWith(".m3u8") -> StreamKind.HLS
            else -> StreamKind.PROGRESSIVE
        }
        return SmilResult.Stream(url, kind)
    }
}
