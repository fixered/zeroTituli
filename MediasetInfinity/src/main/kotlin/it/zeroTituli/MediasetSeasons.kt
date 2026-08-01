package it.zeroTituli

/** Un episodio con la stagione e il numero già decisi, pronto per la scheda. */
data class EpisodeSlot(val entry: FeedEntry, val season: Int, val episode: Int?)

/**
 * Il feed restituisce gli episodi di un programma in ordine di pubblicazione e con
 * gli extra mescolati agli episodi veri. Qui diventano una lista ordinata per
 * stagione ed episodio, con gli extra in una stagione a parte in fondo: mescolarli
 * alla numerazione vera farebbe sembrare rotto il selettore delle stagioni.
 */
object MediasetSeasons {

    /**
     * La stagione degli extra e degli speciali. Un numero alto perché il selettore
     * di CloudStream ordina i numeri, e questi devono restare per ultimi.
     */
    const val EXTRAS_SEASON = 999

    /**
     * La stagione delle voci vere che il feed manda senza numero di stagione.
     *
     * Prima finivano in `EXTRAS_SEASON`, che il selettore chiama "Extra e speciali": un
     * film intero, o una puntata senza numerazione, veniva così presentato come materiale
     * di contorno. Nel feed vero i film arrivano **sempre** con `tvSeasonNumber` nullo
     * (verificato sui marchi 100000448 e 100010090, quattro voci `movie`, tutte senza
     * stagione), quindi ogni marchio con più di un film mostrava i film sotto quel nome.
     * Un numero a parte, appena sopra le stagioni vere e appena sotto gli extra: le due
     * cose sono diverse e meritano due nomi, invece di un nome vago che copra entrambe.
     */
    const val UNNUMBERED_SEASON = 998

    /** Il `programType` degli extra e dei promo nel feed. */
    private const val EXTRA = "extra"

    /** Il `programType` di un film: un marchio con dentro solo quello è un film. */
    private const val MOVIE = "movie"

    /** Il `programType` di una puntata intera. */
    private const val EPISODE = "episode"

    /**
     * Le voci che si guardano davvero. Gli extra non contano per capire cosa sia un
     * marchio: un film con due promo attaccati resta un film.
     */
    fun playable(entries: List<FeedEntry>): List<FeedEntry> =
        entries.filter { it.programType != EXTRA }

    /**
     * Sotto questa durata una voce **può** essere un promo. Dieci minuti perché nel feed
     * vero, sulle 7337 voci `movie` scaricate tutte, la più lunga che si chiami
     * "Trailer …" o "Promo trailer …" dura 439 secondi, e il programma non-promo più corto
     * sopra i cinque minuti ne dura 1445: in mezzo c'è un vuoto largo, e la soglia sta lì.
     */
    private const val PROMO_MAX_SECONDS = 600

    /**
     * ...e deve essere anche almeno quattro volte più corta della voce più lunga del
     * marchio. Le due condizioni valgono insieme, e non è pignoleria: la durata assoluta
     * da sola butterebbe via un cortometraggio che sta da solo nel suo marchio, cioè
     * l'unica cosa guardabile che ci sia. Col rapporto, la voce più lunga non è mai un
     * promo — il suo rapporto è 1 — quindi il filtro non svuota mai il marchio.
     */
    private const val PROMO_MAX_RATIO = 4

    /** La durata in secondi, da dove il feed la scrive; `null` se non la dichiara. */
    private fun seconds(entry: FeedEntry): Int? =
        (entry.durationSeconds ?: entry.runtime)?.takeIf { it > 0 }

    /**
     * Le voci che si guardano davvero, promo esclusi anche quando il feed li tipizza come
     * il contenuto che promuovono.
     *
     * `playable` non basta perché guarda solo `programType`, e un film col suo trailer
     * arriva con **due** voci `movie`: sul marchio 100000828 ("Seven") sono 7308 secondi
     * di film e 49 di "Promo trailer - Seven (di d. fincher)". Il trailer contava come
     * seconda voce guardabile, quindi il film si apriva come serie da due puntate, con la
     * stagione chiamata "Extra e speciali", mentre la scheda di ricerca aveva promesso un
     * film. Succede su 1015 dei 6315 marchi che hanno voci `movie`, cioè un film su sei.
     *
     * La durata è l'unico discrimine onesto: un trailer dura un paio di minuti, un film
     * un'ora e mezza. Due film interi nello stesso marchio restano due, perché nessuno
     * dei due è corto (verificato su 100000448) e nemmeno un evento in due parti si
     * spezza (100010090: 3415 e 2615 secondi, entrambi tenuti).
     */
    fun features(entries: List<FeedEntry>): List<FeedEntry> {
        val playable = playable(entries)
        // Senza nessuna durata dichiarata non si sa niente: si tiene tutto, come prima.
        val longest = playable.mapNotNull { seconds(it) }.maxOrNull() ?: return playable
        return playable.filter { entry ->
            val seconds = seconds(entry) ?: return@filter true
            !(seconds <= PROMO_MAX_SECONDS && seconds * PROMO_MAX_RATIO <= longest)
        }
    }

    /**
     * Il film di un marchio che contiene un film solo; `null` quando il marchio è una
     * serie o non ha film. È la decisione fra scheda film e scheda serie, e sta qui —
     * dove si può provare senza dispositivo — perché è quella che l'utente vede sbagliata
     * quando è sbagliata.
     */
    fun onlyMovie(entries: List<FeedEntry>): FeedEntry? =
        features(entries).singleOrNull()?.takeIf { it.programType == MOVIE }

    /**
     * La voce da cui la scheda prende nome, copertina, trama, anno, età e cast.
     *
     * Non è la prima del feed. Nel feed vero di "La promessa" le prime due voci sono un
     * promo e una clip sul cast: prendendo la prima, la scheda della serie si presentava
     * col titolo e la grafica di un trailer. Si preferisce una puntata intera, poi il
     * film, e solo se il marchio non ha altro si accetta un extra — meglio una scheda con
     * i dati di un promo che nessuna scheda.
     *
     * I promo si escludono da `features` e non da `programType`, perché un trailer
     * tipizzato `movie` passerebbe il filtro degli extra: sui sei marchi verificati il
     * trailer arriva dopo il film, ma l'ordine del feed non è garantito da niente, e con
     * [trailer, film] la scheda prendeva titolo e copertina dal trailer.
     */
    fun head(entries: List<FeedEntry>): FeedEntry? {
        val features = features(entries)
        return features.firstOrNull { it.programType == EPISODE }
            ?: features.firstOrNull { it.programType == MOVIE }
            ?: entries.firstOrNull()
    }

    fun arrange(entries: List<FeedEntry>): List<EpisodeSlot> = entries
        .filter { !it.guid.isNullOrBlank() }
        .distinctBy { it.guid }
        .map { entry ->
            EpisodeSlot(
                entry = entry,
                // Gli extra si riconoscono dal `programType`, non dalla stagione mancante:
                // nel feed vero portano `tvSeasonNumber = 1` come le puntate, e fidandosi
                // di quel numero finivano in mezzo alla prima stagione senza numero
                // d'episodio, cioè esattamente il buco che la stagione dedicata evita.
                season = if (entry.programType == EXTRA) {
                    EXTRAS_SEASON
                } else {
                    // Una voce vera senza numero di stagione non è un extra: mandandola in
                    // `EXTRAS_SEASON` un film intero finiva sotto "Extra e speciali". Ha
                    // una stagione sua, con un nome che dice la verità.
                    entry.tvSeasonNumber ?: UNNUMBERED_SEASON
                },
                episode = entry.tvSeasonEpisodeNumber,
            )
        }
        // Dentro la stagione i numerati vengono prima: un episodio senza numero non
        // sa dove stare, e in mezzo darebbe l'impressione di un buco.
        .sortedWith(
            compareBy(
                { it.season },
                { it.episode == null },
                { it.episode ?: Int.MAX_VALUE },
                { it.entry.title.orEmpty() },
            )
        )
}
