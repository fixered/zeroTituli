package it.zeroTituli

import java.text.Normalizer

/**
 * In che ordine si vedono i riquadri: prima quelli che si aprono davvero, e nella
 * ricerca prima quelli che c'entrano con quello che è stato scritto.
 *
 * Due regole misurate sul servizio vero, non due preferenze.
 *
 * **Gratis prima.** Il plugin ha in mano una sessione anonima, quindi apre solo quello
 * che porta `AVOD` in `mediasetprogram$channelsRights`. La riga *Cinema* di quel diritto
 * ne ha in abbondanza — 122 voci gratuite su 200, misurate — ma **non in testa**: la
 * pagina 1 di `byCategory("Cinema")`, ordinata per novità come la mostra il plugin,
 * comincia con `Delitto ai Caraibi`, `Una figlia in vendita`, `Un'estate al mare`,
 * `We were young`, tutte `SVOD`. Chi apriva Cinema trovava quindi una fila di schede che
 * non partono, e la segnalazione è stata "i film non funzionano per niente". Con questa
 * regola le stesse 200 voci cominciano con `Il prescelto` e `Alaska, la terra degli
 * orsi`, che sono gratuite. Le voci a pagamento restano visibili, con l'etichetta
 * "Abbonamento" e l'avviso nella trama che `MediasetLabels` già mette, ma vanno in coda.
 *
 * **Rilevanza prima di tutto, nella ricerca.** Il `q=` del feed cerca nei metadati delle
 * *puntate*, non nei nomi dei programmi, e il risultato è che il programma cercato
 * sprofonda: `q=la promessa` dava, in ordine, `Arriva Cristina`, `Terra promessa`,
 * `Striscia la notizia`, `Forum`, `Casa Vianello 2`, `Che campioni Holly e Benji!`,
 * `Stargate SG-1 8` e solo **ottavo** `La promessa`. Qui i risultati si riordinano
 * confrontando la richiesta con il **nome del programma**, senza buttare via niente: chi
 * cerca il titolo di una puntata invece del nome del programma trova comunque la sua
 * voce, in fondo.
 *
 * Fra le due regole comanda la rilevanza, e il gratis/a pagamento decide **a pari
 * rilevanza**: un risultato esatto a pagamento è più utile da vedere di un programma
 * gratuito che non c'entra niente — quello a pagamento si riconosce dall'etichetta, quello
 * che non c'entra fa solo perdere il posto in cima.
 *
 * File puro: niente `com.lagradost`, quindi si prova sulla JVM.
 */
object MediasetRanking {

    /**
     * Quanto bene il nome di un programma risponde alla richiesta. L'ordine di
     * dichiarazione **è** la scala: `ordinal` viene usato per ordinare, quindi spostare
     * una voce cambia il risultato della ricerca.
     */
    enum class Match {
        /** Il nome del programma è la richiesta: `grande fratello` → `Grande Fratello`. */
        EXACT,

        /** Il nome comincia con la richiesta: `Grande Fratello VIP`, `Grande Fratello - L'inizio`. */
        PREFIX,

        /** La richiesta compare dentro il nome: `promessa` → `Terra promessa`. */
        CONTAINS,

        /** Tutte le parole cercate ci sono, in un ordine qualunque. */
        ALL_WORDS,

        /** Qualche parola sì e qualcuna no: `temptation island` → `Temptation`. */
        SOME_WORDS,

        /** Nessuna. La voce resta, ma in fondo: la richiesta può essere il titolo di una puntata. */
        NONE,
    }

    /**
     * Il nome che il riquadro mostra, cioè quello su cui ha senso ordinare: è la stessa
     * scelta di `MediasetCatalog.toSearchResponse`, e devono restare la stessa, altrimenti
     * si ordinerebbe per una stringa e se ne mostrerebbe un'altra.
     */
    fun cardTitle(entry: FeedEntry): String? =
        entry.brandTitle?.takeIf { it.isNotBlank() } ?: entry.title

    /**
     * Minuscole, senza accenti, senza spazi di troppo: `Città  Segrète` e `citta segrete`
     * devono contare come la stessa cosa, perché nessuno cerca con gli accenti giusti.
     *
     * `Normalizer.NFD` stacca il segno diacritico dalla lettera e il filtro toglie i segni
     * (`\p{Mn}`, marchi non spaziatori): è la strada che funziona anche su Android, dove il
     * blocco `InCombiningDiacriticalMarks` non è affidabile.
     */
    fun normalize(text: String): String =
        COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase()
            .replace(SPACES, " ")
            .trim()

    /** Le parole di un testo già normalizzato: si spezza su tutto ciò che non è lettera o cifra. */
    fun words(normalized: String): List<String> =
        NON_WORD.split(normalized).filter { it.isNotEmpty() }

    /** Il gradino della scala su cui cade `title` rispetto a `query`. */
    fun match(query: String, title: String?): Match =
        rank(normalize(query), title).match

    /**
     * L'ordine dei riquadri.
     *
     * @param query il testo cercato. `null` o vuoto per le righe di catalogo, che una
     *   richiesta non ce l'hanno: lì resta la sola regola gratis-prima.
     *
     * L'ordinamento è **stabile** (`sortedWith` lo è) e questo non è un dettaglio: dentro
     * ogni gruppo l'ordine è quello che il feed ha dato, che vuol dire per novità nelle
     * righe per categoria e per genere e alfabetico nelle righe A-Z. Riordinare lì
     * dentro butterebbe via l'unica cosa che quelle query chiedono al feed.
     */
    fun ordered(entries: List<FeedEntry>, query: String? = null): List<FeedEntry> {
        val normalized = query?.let(::normalize).orEmpty()
        // Nessuna richiesta: una chiave sola, il prezzo. Niente `Rank` da calcolare per
        // voce, e soprattutto niente rilevanza inventata su una riga di catalogo.
        if (normalized.isEmpty()) return entries.sortedBy { paid(it) }

        return entries
            .map { entry -> rank(normalized, cardTitle(entry)) to entry }
            .sortedWith(
                compareBy(
                    { (rank, _) -> rank.match.ordinal },
                    // A pari gradino vince chi ha in comune più *lettere* con la richiesta,
                    // non più parole: su `la promessa`, `Terra promessa` e `Striscia la
                    // notizia` hanno una parola per uno, ma la prima la parola che conta.
                    { (rank, _) -> -rank.matchedChars },
                    // E solo qui, a pari rilevanza, il prezzo.
                    { (_, entry) -> paid(entry) },
                )
            )
            .map { (_, entry) -> entry }
    }

    /** 0 se si apre con la sessione anonima, 1 se serve un abbonamento: è la chiave d'ordine. */
    private fun paid(entry: FeedEntry): Int = if (entry.isFree) 0 else 1

    private data class Rank(val match: Match, val matchedChars: Int)

    private fun rank(query: String, title: String?): Rank {
        val name = normalize(title.orEmpty())
        val queryWords = words(query)
        if (query.isEmpty() || name.isEmpty() || queryWords.isEmpty()) return Rank(Match.NONE, 0)

        val nameWords = words(name)
        // Una parola cercata è "trovata" se una parola del nome le combacia o comincia con
        // essa: `promess` deve pescare `promessa`, mentre pretendere l'uguaglianza esatta
        // farebbe fallire ogni ricerca scritta a metà.
        val found = queryWords.filter { word -> nameWords.any { it.startsWith(word) } }
        val matchedChars = found.sumOf { it.length }

        val match = when {
            name == query -> Match.EXACT
            name.startsWith(query) -> Match.PREFIX
            name.contains(query) -> Match.CONTAINS
            found.size == queryWords.size -> Match.ALL_WORDS
            found.isNotEmpty() -> Match.SOME_WORDS
            else -> Match.NONE
        }
        return Rank(match, matchedChars)
    }

    /** I segni diacritici staccati dalla lettera da `Normalizer.NFD`. */
    private val COMBINING = Regex("\\p{Mn}+")

    /** Tutto ciò che non è lettera o cifra separa due parole, apostrofi e trattini compresi. */
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    private val SPACES = Regex("\\s+")
}
