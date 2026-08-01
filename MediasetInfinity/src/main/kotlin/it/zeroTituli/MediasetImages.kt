package it.zeroTituli

/**
 * Ogni voce del feed porta fino a ventinove varianti della stessa immagine, con il
 * formato scritto nel nome della chiave (`image_vertical-264x396`). Qui si sceglie
 * quale serve per ogni ruolo e si prende la più grande di quel gruppo, scendendo di
 * gruppo se il primo manca: una scheda senza copertina, quando il feed ne ha una, è
 * un difetto che si vede subito.
 */
object MediasetImages {

    private val VERTICAL = listOf("image_vertical", "brand_cover", "image_header_poster")
    private val WIDE = listOf("image_header_poster", "img_s_master_16_9", "brand_cover", "image_horizontal_cover")
    private val STILL = listOf("image_keyframe_poster", "image_horizontal_cover", "img_s_master_16_9")
    /**
     * Il logo di un canale. La stazione di `nowNext` lo porta sotto `channel_logo`; le
     * altre famiglie sono quelle delle voci di programma, tenute come riserva se Mediaset
     * cambia il payload della stazione. La lista stava scritta due volte, qui e dentro
     * `MediasetLive`, e solo una delle due conosceva `channel_logo`.
     */
    private val LOGO = listOf("channel_logo", "logo_horizontal", "brand_logo", "image_vertical")

    fun poster(entry: FeedEntry): String? = best(entry.thumbnails, VERTICAL)

    fun background(entry: FeedEntry): String? = best(entry.thumbnails, WIDE)

    fun still(entry: FeedEntry): String? = best(entry.thumbnails, STILL)

    /** Il logo prende le miniature così come arrivano: la stazione non è una `FeedEntry`. */
    fun channelLogo(thumbnails: Map<String, Thumbnail>): String? = best(thumbnails, LOGO)

    /**
     * Il primo gruppo che ha almeno un'immagine vince, e dentro il gruppo vince la
     * larghezza maggiore. Le varianti senza indirizzo non contano; quelle senza
     * larghezza restano ultime, perché non si sa quanto valgano.
     */
    fun best(thumbnails: Map<String, Thumbnail>, prefixes: List<String>): String? {
        prefixes.forEach { prefix ->
            val found = thumbnails.entries
                .filter { it.key.startsWith(prefix) && !it.value.url.isNullOrBlank() }
                .maxByOrNull { it.value.width ?: 0 }
            if (found != null) return found.value.url
        }
        return null
    }
}
