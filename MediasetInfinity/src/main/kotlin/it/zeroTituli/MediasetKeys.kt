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
 * rompe i preferiti di chi ha già il plugin: si aggiunge, non si rinomina. Per questo
 * `program:` (vedi [Card.Program]) si è aggiunto accanto a `brand:` invece di sostituirlo:
 * `brand:` deve continuare a funzionare esattamente come prima.
 */
object MediasetKeys {

    private const val BRAND = "brand:"
    private const val PROGRAM = "program:"
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
        /**
         * Un programma intero, con tutte le sue stagioni, raggiunto per `brandId`.
         *
         * Resta per compatibilità con i preferiti già salvati e per i film: un `brandId`
         * senza titolo utilizzabile (vedi [cardKeyFor]) finisce ancora qui.
         */
        data class Brand(val brandId: String) : Card()

        /**
         * Lo stesso concetto di [Brand], ma raggiunto per titolo del marchio invece che per
         * `brandId`. Mediaset spezza le edizioni di un programma che torna ogni anno
         * (`Temptation Island`, `Uomini e Donne`, `Verissimo`, …) su `brandId` diversi: una
         * scheda per titolo li ritrova tutti con una query sola, mentre una per `brandId`
         * ne vedrebbe solo uno.
         */
        data class Program(val title: String) : Card()

        /** Una stagione letta dal markup del sito: il marchio si ricava poi dal feed. */
        data class Series(val seriesGuid: String) : Card()

        /** Un contenuto singolo, di solito un film. */
        data class Single(val guid: String) : Card()

        data class Live(val callSign: String) : Card()
    }

    fun brand(brandId: String): String = BRAND + brandId

    fun program(title: String): String = PROGRAM + title

    fun series(seriesGuid: String): String = SERIES + seriesGuid

    fun single(guid: String): String = SINGLE + guid

    /** Per le dirette la chiave della scheda e quella del flusso sono la stessa. */
    fun live(callSign: String): String = LIVE + callSign

    fun card(raw: String): Card? = when {
        raw.startsWith(BRAND) -> raw.removePrefix(BRAND).filled()?.let(Card::Brand)
        raw.startsWith(PROGRAM) -> raw.removePrefix(PROGRAM).filled()?.let(Card::Program)
        raw.startsWith(SERIES) -> raw.removePrefix(SERIES).filled()?.let(Card::Series)
        raw.startsWith(SINGLE) -> raw.removePrefix(SINGLE).filled()?.let(Card::Single)
        raw.startsWith(LIVE) -> raw.removePrefix(LIVE).filled()?.let(Card::Live)
        else -> null
    }

    /**
     * I caratteri della sintassi dei filtri del feed (`byCustomValue={campo}{valore}`): un
     * titolo che ne portasse uno costruirebbe una query storpiata, quindi non si può usare
     * come chiave di raggruppamento.
     */
    private val RESERVED_TITLE_CHARS = charArrayOf('{', '}', '|')

    /**
     * La chiave della scheda per una voce di feed, decisa **sempre dal titolo**, non da
     * quante edizioni capitano sulla stessa pagina.
     *
     * Mediaset dà un `brandId` diverso a ogni edizione di un programma che torna ogni
     * anno: cinque marchi si chiamano tutti "Temptation Island" (100013024…100017150,
     * stagioni 10-14), e raggrupparli per `brandId` come faceva prima dava cinque schede
     * identiche invece di una con cinque stagioni. La regola:
     *
     * - un **film** (`programType == "movie"`) resta su `brand:<brandId>`: raggruppare i
     *   film per titolo non porta nulla — non hanno stagioni da unire — e due film senza
     *   parentela possono chiamarsi uguale (basti pensare ai remake);
     * - altrimenti, se `brandTitle` non è vuoto e non porta `{`, `}` o `|` — la sintassi
     *   dei filtri del feed — si usa `program:<brandTitle>`;
     * - altrimenti si torna su `brand:<brandId>`, e se manca anche quello non c'è scheda.
     *
     * Decidere sempre per titolo, e non solo quando una pagina contiene già due marchi con
     * lo stesso nome, è voluto: una pagina di catalogo spesso porta una sola edizione, e
     * una regola "per pagina" darebbe comunque una scheda da una stagione sola. Decidendo
     * per titolo, è `load` a scoprire quante edizioni esistono interrogando il feed per
     * titolo — corretto a prescindere da cosa conteneva quella pagina.
     */
    fun cardKeyFor(entry: FeedEntry): String? {
        val brandId = entry.brandId?.takeIf { it.isNotBlank() }
        if (entry.programType == "movie") return brandId?.let(::brand)

        val title = entry.brandTitle
        if (!title.isNullOrBlank() && RESERVED_TITLE_CHARS.none { title.contains(it) }) {
            return program(title)
        }
        return brandId?.let(::brand)
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
