package it.zeroTituli

/**
 * Le chiavi con cui il plugin nomina schede, flussi e righe della home.
 *
 * Mediaset non dà un indirizzo web utile alle sue schede, quindi al posto dell'URL
 * gira una chiave con un prefisso (`brand:100012714`). Prima queste stringhe si
 * componevano e si smontavano a mano in `MediasetInfinity` e `MediasetCatalog`, che
 * importano CloudStream e quindi non si provano sulla JVM: un prefisso sbagliato in un
 * ramo poco battuto non lo vedeva nessuno. Qui invece la stessa aritmetica si prova.
 *
 * **Il formato è un impegno verso l'utente**: le chiavi delle schede finiscono nei
 * preferiti salvati sul dispositivo. Cambiare un prefisso non rompe la compilazione,
 * rompe i preferiti di chi ha già il plugin: si aggiunge, non si rinomina.
 */
object MediasetKeys {

    private const val BRAND = "brand:"
    private const val SERIES = "series:"
    private const val SINGLE = "guid:"
    private const val LIVE = "live:"
    private const val VOD = "vod:"
    private const val SECTION = "section:"
    private const val GENRE = "genre:"
    private const val AZ = "az:"

    /** La riga delle dirette non ha argomento: di dirette ce n'è una lista sola. */
    const val LIVE_ROW = "live"

    // ============= SCHEDE =============

    /** Quello che `load` riceve: una scheda di catalogo o un canale. */
    sealed class Card {
        /** Un programma intero, con tutte le sue stagioni. */
        data class Brand(val brandId: String) : Card()

        /** Una stagione letta dal markup del sito: il marchio si ricava poi dal feed. */
        data class Series(val seriesGuid: String) : Card()

        /** Un contenuto singolo, di solito un film. */
        data class Single(val guid: String) : Card()

        data class Live(val callSign: String) : Card()
    }

    fun brand(brandId: String): String = BRAND + brandId

    fun series(seriesGuid: String): String = SERIES + seriesGuid

    fun single(guid: String): String = SINGLE + guid

    /** Per le dirette la chiave della scheda e quella del flusso sono la stessa. */
    fun live(callSign: String): String = LIVE + callSign

    fun card(raw: String): Card? = when {
        raw.startsWith(BRAND) -> raw.removePrefix(BRAND).filled()?.let(Card::Brand)
        raw.startsWith(SERIES) -> raw.removePrefix(SERIES).filled()?.let(Card::Series)
        raw.startsWith(SINGLE) -> raw.removePrefix(SINGLE).filled()?.let(Card::Single)
        raw.startsWith(LIVE) -> raw.removePrefix(LIVE).filled()?.let(Card::Live)
        else -> null
    }

    // ============= FLUSSI =============

    /** Quello che `loadLinks` riceve nel suo `data`. */
    sealed class Data {
        data class Vod(val guid: String) : Data()
        data class Live(val callSign: String) : Data()
    }

    fun vod(guid: String): String = VOD + guid

    fun data(raw: String): Data? = when {
        raw.startsWith(VOD) -> raw.removePrefix(VOD).filled()?.let(Data::Vod)
        raw.startsWith(LIVE) -> raw.removePrefix(LIVE).filled()?.let(Data::Live)
        else -> null
    }

    // ============= RIGHE DELLA HOME =============

    /** Quello che `getMainPage` riceve come `request.data`. */
    sealed class Row {
        object Live : Row()
        data class Section(val slug: String) : Row()
        data class Genre(val name: String) : Row()
        data class Az(val category: String) : Row()
    }

    fun section(slug: String): String = SECTION + slug

    fun genre(name: String): String = GENRE + name

    fun az(category: String): String = AZ + category

    fun row(raw: String): Row? = when {
        raw == LIVE_ROW -> Row.Live
        raw.startsWith(SECTION) -> raw.removePrefix(SECTION).filled()?.let(Row::Section)
        raw.startsWith(GENRE) -> raw.removePrefix(GENRE).filled()?.let(Row::Genre)
        raw.startsWith(AZ) -> raw.removePrefix(AZ).filled()?.let(Row::Az)
        else -> null
    }

    // ============= PREFERITI VECCHI =============

    /**
     * Un preferito salvato può portare davanti l'indirizzo del sito — capita quando una
     * `new*SearchResponse` viene costruita senza `fix = false`, perché `fixUrl` mette
     * `mainUrl` davanti a tutto quello che non inizia per `http`. La chiave si recupera
     * invece di aprire una scheda vuota.
     */
    fun strip(url: String, mainUrl: String): String =
        url.removePrefix("$mainUrl/").removePrefix(mainUrl)

    /** Un prefisso senza niente dietro non è una chiave: è una chiave storpiata. */
    private fun String.filled(): String? = takeIf { it.isNotBlank() }
}
