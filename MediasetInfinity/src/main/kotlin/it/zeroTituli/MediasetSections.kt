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
 * Le righe delle pagine sezione, lette dal markup.
 *
 * L'API che le compone (`ares-be...`) non è raggiungibile da fuori, quindi si legge
 * quello che il sito ha già reso. È la parte più fragile del plugin: chi la usa deve
 * avere un ripiego pronto, non fidarsi.
 */
object MediasetSections {

    /** Le sezioni del sito, con il nome da mostrare nella home. */
    val SLUGS = listOf(
        "fiction" to "Fiction",
        "cinema" to "Cinema",
        "programmitv" to "Programmi TV",
        "kids" to "Kids",
        "documentari" to "Documentari",
        "news-e-sport" to "News e Sport",
    )

    fun read(html: String): List<SectionRow> {
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
