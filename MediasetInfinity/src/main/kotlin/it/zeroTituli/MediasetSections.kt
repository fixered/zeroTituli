package it.zeroTituli

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class SectionItem(
    val title: String,
    val href: String,
    val seriesGuid: String?,
    val poster: String?,
)

data class SectionRow(val title: String, val items: List<SectionItem>)

/**
 * Una sezione del sito: lo slug della pagina, il nome da mostrare nella home e la
 * categoria del feed a cui ripiegare quando il markup cambia.
 *
 * Le tre cose stavano in tre posti diversi — l'elenco delle righe, la tabella dei nomi
 * e una `when` che traduceva slug in categoria — e chi aggiungeva una sezione ne
 * aggiornava due su tre.
 */
data class MediasetSection(val slug: String, val label: String, val feedCategory: String)

/**
 * Le righe delle pagine sezione, lette dal markup.
 *
 * L'API che le compone (`ares-be...`) non è raggiungibile da fuori, quindi si legge
 * quello che il sito ha già reso. È la parte più fragile del plugin: chi la usa deve
 * avere un ripiego pronto, non fidarsi.
 */
object MediasetSections {

    /**
     * `<script ...>` fino al suo `</script>`. Il punto deve valere anche il capo di riga,
     * perché lo stato di Next.js è un blocco lungo su più righe; la chiusura è pigra, o
     * il primo `<script>` si porterebbe via tutta la pagina fino all'ultimo.
     */
    private val SCRIPT = Regex(
        """<script\b[^>]*>.*?</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * Le sezioni del sito. Le categorie del feed non si chiamano come gli slug: la
     * pagina è `/programmitv`, la categoria è `Programmi Tv`, e `/news-e-sport` pesca da
     * `Calcio e Sport`.
     *
     * Manca `serie-tv`, che il progetto elencava fra le sezioni: la pagina non esiste
     * (`https://mediasetinfinity.mediaset.it/serie-tv` risponde 404) e nemmeno
     * `azListing` conosce una categoria "Serie Tv". Del sito esistono solo gli indirizzi
     * dei singoli programmi (`/serie-tv/themiddle_SE000000002712`). Quel contenuto resta
     * raggiungibile dalla riga di genere "Serie Tv", che il feed serve davvero.
     */
    val SLUGS = listOf(
        MediasetSection("fiction", "Fiction", "Fiction"),
        MediasetSection("cinema", "Cinema", "Cinema"),
        MediasetSection("programmitv", "Programmi TV", "Programmi Tv"),
        MediasetSection("kids", "Kids", "Kids"),
        MediasetSection("documentari", "Documentari", "Documentari"),
        MediasetSection("news-e-sport", "News e Sport", "Calcio e Sport"),
    )

    /**
     * Uno slug che non è in tabella non ha un ripiego: chi chiama salta la riga invece
     * di servire Fiction sotto l'intestazione di un'altra sezione.
     */
    fun sectionOf(slug: String): MediasetSection? = SLUGS.firstOrNull { it.slug == slug }

    /**
     * Le pagine sezione sono Next.js: nel campione la Fiction pesa 6 020 473 byte, di
     * cui 3 614 867 (il 60%) stanno dentro `<script>` che il parser non guarda
     * nemmeno — è lo stato dell'applicazione, non markup. Buttarli prima di `Jsoup.parse`
     * risparmia all'albero DOM più della metà del lavoro e della memoria, e su
     * `minSdk 21`, con sei righe di sezione nella home, quella metà si sente.
     */
    fun stripScripts(html: String): String = SCRIPT.replace(html, "")

    fun read(html: String): List<SectionRow> = parse(stripScripts(html))

    /**
     * La lettura vera, senza lo sfoltimento: serve solo al test, che confronta le righe
     * trovate nel markup intero con quelle trovate in quello sfoltito. Se un giorno
     * `stripScripts` mangiasse una riga, quel confronto lo direbbe.
     */
    internal fun parse(html: String): List<SectionRow> {
        if (html.isBlank()) return emptyList()
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()

        return document.select("ul.ulCarousel").mapNotNull { carousel ->
            val title = titleOf(carousel) ?: return@mapNotNull null
            val items = carousel
                .select("a[data-testid=poster-card-link], a[data-testid=keyframe-card-link]")
                .mapNotNull { itemOf(it) }
            if (items.isEmpty()) null else SectionRow(title, items)
        }
    }

    /**
     * Il titolo della riga sta prima del carosello, non dentro: si risale ai
     * contenitori (il `<ul>` è annidato in un `div.slider`, fratello di
     * `div.titleCarousel`) e si prende il titolo più vicino sopra.
     */
    private fun titleOf(carousel: Element): String? {
        var node: Element? = carousel
        while (node != null) {
            node.previousElementSiblings().forEach { sibling ->
                val found = sibling.select("[data-testid=carousel-title]").firstOrNull()
                    ?: sibling.takeIf { it.attr("data-testid") == "carousel-title" }
                val text = found?.text()?.trim()
                if (!text.isNullOrBlank()) return text
            }
            node = node.parent()
        }
        return null
    }

    private fun itemOf(link: Element): SectionItem? {
        val href = link.attr("href").takeIf { it.startsWith("/") } ?: return null
        // Il titolo è nell'intestazione dentro la scheda; se manca, resta lo slug.
        val title = link.select("h2, h3").firstOrNull()?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: href.substringAfterLast('/').substringBefore('_')
        return SectionItem(
            title = title,
            href = href,
            // `/fiction/lapromessa_SE000000002040`: l'ultimo pezzo è la stagione, e
            // da lì si risale al programma con una query al feed. Alcune schede portano
            // un secondo identificativo dopo una virgola (es. `SE...,ST...`): va tenuto
            // solo il pezzo `SE...`, perché è l'unico che la query al feed accetta.
            seriesGuid = href.substringAfterLast('_')
                .split(',')
                .firstOrNull { it.startsWith("SE") },
            poster = posterOf(link),
        )
    }

    private fun posterOf(link: Element): String? {
        val srcSet = link.select("picture source").firstOrNull()?.attr("srcSet")
        val fromSet = srcSet?.split(",")?.firstOrNull()?.trim()?.substringBefore(' ')
        return fromSet?.takeIf { it.startsWith("http") }
            ?: link.select("img").firstOrNull()?.attr("src")?.takeIf { it.startsWith("http") }
    }
}
