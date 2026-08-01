package it.zeroTituli

enum class StreamKind { DASH, HLS, PROGRESSIVE }

sealed class SmilResult {
    data class Stream(val url: String, val kind: StreamKind) : SmilResult()

    /** `assetTypes` o `formats` non combaciano con nessuna copia disponibile. */
    object NoMatch : SmilResult()

    /** Fuori area: il CDN manda un video di cortesia. Vale per tutte le copie. */
    object GeoBlocked : SmilResult()

    /**
     * Il token è scaduto o non è valido. Diverso da `GeoBlocked` anche se il CDN manda lo
     * stesso cartello, perché questo si risolve rifacendo il login: confonderli teneva
     * l'utente fermo per le quattro ore di vita della sessione, senza un messaggio.
     */
    object TokenExpired : SmilResult()

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

        // L'eccezione dichiarata viene prima dell'indirizzo, e l'ordine è il punto.
        // theplatform manda lo stesso `cortesia/GEOLOCK-DEF_2.mp4` sia fuori area sia con
        // il token scaduto — nel secondo caso col titolo "Invalid Token" e
        // `exception="InvalidAuthToken"` — quindi l'indirizzo da solo non distingue i due
        // casi. Leggendolo per primo il ramo del token non veniva mai raggiunto: ogni
        // token scaduto passava per un blocco geografico, cioè per un errore definitivo,
        // e il nuovo login che il progetto promette non partiva mai.
        when (exception.find(payload)?.groupValues?.get(1)) {
            null -> Unit
            "NoAssetTypeFormatMatches" -> return SmilResult.NoMatch
            "InvalidAuthToken", "InvalidToken", "TokenExpired" -> return SmilResult.TokenExpired
            "GeoLocationBlocked" -> return SmilResult.GeoBlocked
            else -> return SmilResult.NoMatch
        }

        // Senza eccezione dichiarata resta l'indirizzo: il cartello arriva con esito
        // positivo, e senza riconoscerlo si guarda il cartello al posto del film.
        if (url.contains("/cortesia/") || url.contains("GEOLOCK")) return SmilResult.GeoBlocked

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
