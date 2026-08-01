package it.zeroTituli

import it.zeroTituli.shared.M3u8

/**
 * Il manifest DASH delle dirette, riscritto perché il lettore possa prenderlo dal proxy.
 *
 * **Il perché.** Il CDN di Mediaset autorizza i segmenti in due modi alternativi: il `hdnts`
 * dentro la query, oppure il cookie `hdntl` che il manifest stesso rilascia con `Set-Cookie`.
 * Un browser — e quindi anche il ricevitore del Chromecast, che è una pagina web — tiene il
 * cookie e non si accorge di niente. La sorgente dati di ExoPlayer no: non conserva cookie e
 * non ricopia la query del manifest sulle richieste dei segmenti, quindi ogni segmento parte
 * nudo e il CDN risponde `403`. Misurato su `ch-i1`: lo stesso segmento è `403` senza query e
 * `200` con la query del manifest attaccata, sia per la rappresentazione audio sia per quella
 * video (il permesso è `acl=/live/ch-i1/i1-clr.isml*`, cioè per cartella, non per traccia).
 *
 * **La cura.** Il manifest — pochi kB — passa dal proxy locale con gli indirizzi già scritti
 * per esteso; i segmenti restano diretti al CDN, ma si portano il permesso addosso. Il flusso
 * vero non attraversa il telefono due volte, che è il motivo per cui non si proxa tutto.
 *
 * **Il dettaglio che decide.** Servito dal proxy, ogni indirizzo relativo dentro il manifest
 * si risolverebbe su `127.0.0.1`, non sul CDN: il lettore chiederebbe i segmenti al proxy, che
 * non li conosce. Quindi l'indirizzamento va reso assoluto:
 *
 *  - `<BaseURL>dash/</BaseURL>` diventa la cartella assoluta sul CDN;
 *  - `initialization` e `media` di ogni `SegmentTemplate` si portano dietro la query;
 *  - `$RepresentationID$` e `$Time$` restano intatti: li sostituisce il lettore.
 *
 * File puro, senza niente di CloudStream, perché è tutto lavoro su stringhe e come tale si
 * prova sulla JVM contro un manifest vero (`mpd-live-i1.xml`).
 */
object MediasetMpd {

    private val baseUrl = Regex("""<BaseURL>([^<]*)</BaseURL>""", RegexOption.IGNORE_CASE)
    private val segmentTemplate = Regex("""<SegmentTemplate\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * Ristretta a dentro il tag `SegmentTemplate` di proposito: cercata su tutto il manifest,
     * `media="..."` prenderebbe attributi omonimi di altri elementi che non sono indirizzi.
     */
    private val urlAttribute =
        Regex("""\b(initialization|media)\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    /**
     * @param manifest il corpo scaricato dal CDN.
     * @param manifestUrl l'indirizzo da cui arriva, **con la query**: è da lì che si ricavano
     *   sia la cartella su cui risolvere il relativo sia il permesso da attaccare ai segmenti.
     */
    fun rewrite(manifest: String, manifestUrl: String): String {
        val query = manifestUrl.substringAfter('?', "")

        // Con un `<BaseURL>` i template restano relativi a lui, e basta rendere assoluto quello.
        // Senza, la base è l'indirizzo del manifest — che dal proxy è il proxy stesso — e allora
        // sono i template a doversi allungare. Nei dodici canali il `<BaseURL>` c'è sempre ed è
        // sempre `dash/`, ma il ramo senza costa tre parole ed evita che un manifest di forma
        // diversa parta muto.
        val hasBaseUrl = baseUrl.containsMatchIn(manifest)

        // Un `<BaseURL>` già assoluto punta dove deve: `M3u8.absolute` lo lascia stare, e va bene
        // così. Un secondo `<BaseURL>` annidato a un livello diverso andrebbe invece risolto sul
        // primo e non sul manifest: non capita qui, e distinguerlo vorrebbe dire leggere l'albero
        // XML invece delle stringhe.
        val withBase = baseUrl.replace(manifest) { match ->
            "<BaseURL>" + M3u8.absolute(match.groupValues[1].trim(), manifestUrl) + "</BaseURL>"
        }

        return segmentTemplate.replace(withBase) { tag ->
            urlAttribute.replace(tag.value) { attribute ->
                val name = attribute.groupValues[1]
                val value = attribute.groupValues[2]
                val target = if (hasBaseUrl) value else M3u8.absolute(value, manifestUrl)
                // La forma con la lambda restituisce testo letterale. Con la forma a stringa di
                // sostituzione il `$` sarebbe un riferimento a gruppo: `$RepresentationID$` e
                // `$Time$` sparirebbero e il lettore chiederebbe segmenti che non esistono.
                name + "=\"" + withQuery(target, query) + "\""
            }
        }
    }

    /** La query del manifest attaccata al segmento: è lei il permesso. */
    private fun withQuery(url: String, query: String): String = when {
        // TGCOM24 (`KF`) arriva su un altro host e senza token, e i suoi segmenti sono aperti:
        // un `?` vuoto attaccato per simmetria sarebbe solo un indirizzo diverso da cachare.
        query.isEmpty() -> url
        url.contains('?') -> "$url&$query"
        else -> "$url?$query"
    }
}
