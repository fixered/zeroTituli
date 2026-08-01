package it.zeroTituli

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Lettura del JSON con Jackson diretto e non con `parseJson` di CloudStream: così i
 * file di lettura girano anche nei test sulla JVM, dove gli stub dell'app non ci sono.
 */
object MediasetJson {
    val mapper: ObjectMapper = jacksonObjectMapper()

    inline fun <reified T> parse(payload: String): T? =
        runCatching { mapper.readValue<T>(payload) }.getOrNull()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeedResponse(
    val entries: List<FeedEntry> = emptyList(),
    val totalResults: Int? = null,
    val entryCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Thumbnail(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tag(val scheme: String? = null, val title: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Credit(val creditType: String? = null, val personName: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Rating(val scheme: String? = null, val rating: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Media(
    val pid: String? = null,
    val publicUrl: String? = null,
    val guid: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeedEntry(
    val guid: String? = null,
    val title: String? = null,
    val description: String? = null,
    val longDescription: String? = null,
    val programType: String? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val seriesId: String? = null,
    val tvSeasonId: String? = null,
    val tvSeasonNumber: Int? = null,
    val tvSeasonEpisodeNumber: Int? = null,
    val credits: List<Credit> = emptyList(),
    val ratings: List<Rating> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val thumbnails: Map<String, Thumbnail> = emptyMap(),
    val media: List<Media> = emptyList(),
    // I campi propri di Mediaset hanno il dollaro nel nome: in Kotlin va protetto.
    @JsonProperty("mediasetprogram\$brandId") val brandId: String? = null,
    @JsonProperty("mediasetprogram\$brandTitle") val brandTitle: String? = null,
    @JsonProperty("mediasetprogram\$subBrandId") val subBrandId: String? = null,
    @JsonProperty("mediasetprogram\$subBrandTitle") val subBrandTitle: String? = null,
    @JsonProperty("mediasetprogram\$duration") val durationSeconds: Int? = null,
    @JsonProperty("mediasetprogram\$genres") val genreList: List<String> = emptyList(),
    @JsonProperty("mediasetprogram\$editorialType") val editorialType: String? = null,
    @JsonProperty("mediasetprogram\$channelsRights") val rights: List<String> = emptyList(),
    @JsonProperty("mediasetprogram\$pageUrl") val pageUrl: String? = null,
) {
    private fun tagsOf(scheme: String): List<String> =
        tags.filter { it.scheme == scheme }.mapNotNull { it.title }.distinct()

    /** I generi stanno in due posti e non sempre negli stessi due: si prendono entrambi. */
    val genres: List<String> get() = (genreList + tagsOf("genre")).distinct()

    val categories: List<String> get() = tagsOf("category")

    val actors: List<String>
        get() = credits.filter { it.personName != null }.map { it.personName!! }.distinct()

    /**
     * Il semaforo italiano: verde per tutti, giallo dai dodici, rosso dai sedici. Le
     * sigle sono quelle che CloudStream mostra nella scheda.
     */
    val ageRating: String?
        get() = when (ratings.firstOrNull { it.scheme == "urn.ita" }?.rating?.lowercase()) {
            "verde" -> "T"
            "giallo" -> "12+"
            "rosso" -> "16+"
            else -> null
        }

    /** Senza il diritto AVOD serve un abbonamento o un noleggio: si segnala, non si finge. */
    val isFree: Boolean get() = rights.any { it.contains("AVOD") }

    val durationMinutes: Int?
        get() = (durationSeconds ?: runtime)?.takeIf { it > 0 }?.let { it / 60 }

    /** `seriesId` è un indirizzo: dell'identificativo serve solo l'ultimo pezzo. */
    val seriesGuid: String? get() = seriesId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    val plot: String? get() = longDescription?.takeIf { it.isNotBlank() } ?: description
}
