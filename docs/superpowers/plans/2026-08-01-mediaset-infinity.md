# Plugin Mediaset Infinity — piano di implementazione

> **Per chi esegue:** SOTTO-SKILL RICHIESTA: usare `superpowers:subagent-driven-development`
> (consigliata) oppure `superpowers:executing-plans` per eseguire il piano un'attività
> alla volta. I passi usano caselle (`- [ ]`) per il tracciamento.

**Obiettivo:** un plugin CloudStream per Mediaset Infinity con catalogo diviso per
categorie, dirette TV castabili, schede complete con copertine e informazioni, e
serie a più stagioni navigabili dal selettore di CloudStream.

**Architettura:** un modulo Gradle nuovo (`MediasetInfinity/`) sul modello degli
altri del repo. La logica pura — lettura dei feed, scelta delle immagini,
raggruppamento delle stagioni, lettura dei SMIL, lettura del markup delle sezioni —
sta in file che **non toccano nessun tipo di CloudStream**, così gira nei test JVM;
la rete sta tutta in `MediasetApi`, e `MediasetInfinity` è il solo file che
implementa `MainAPI`.

**Tecnologie:** Kotlin, Android library, plugin Gradle CloudStream, Jackson
(lettura JSON), Jsoup (lettura HTML), JUnit 4 (test JVM).

**Progetto di riferimento:** `docs/superpowers/specs/2026-08-01-mediaset-infinity-design.md`.
Contiene tutti gli endpoint con i parametri esatti e il perché delle scelte. Chi
esegue questo piano lo legge prima.

## Vincoli generali

Valgono per ogni attività, senza ripeterli ogni volta.

- Package Kotlin: `it.zeroTituli`. Il `namespace` Android è `it.fixered.zeroTituli`,
  già impostato nel `build.gradle.kts` di radice per tutti i sottoprogetti.
- `minSdk = 21`, `compileSdk 35`, `jvmTarget = 1.8`. Impostati in radice: non
  ridichiararli nel modulo.
- **Jackson non oltre la 2.13.1**: versioni più nuove rompono i dispositivi vecchi.
  Il vincolo è già nel `build.gradle.kts` di radice, non aggiungere altre versioni.
- I file di logica pura (`MediasetDTOs`, `MediasetUrls`, `MediasetImages`,
  `MediasetSeasons`, `MediasetSmil`, `MediasetSections`) **non possono importare
  nulla da `com.lagradost`**: gli stub di CloudStream sono solo in compilazione, e
  a runtime nei test non esistono. Per la stessa ragione usano
  `jacksonObjectMapper()` proprio invece di `parseJson` di CloudStream.
- Ogni chiamata di rete dentro `runCatching`, come negli altri plugin del repo. Un
  feed che non risponde fa sparire una riga, non fa cadere la schermata.
- Commenti in italiano, che spiegano **perché**, non cosa: è lo stile del repo
  (vedi `StreamingCommunity.kt` e `shared/LocalProxy.kt`).
- **Non committare.** In questo repo i commit li fa l'utente a mano. Ogni attività
  finisce con la verifica, non con `git commit`. Se l'utente chiede di committare,
  allora sì.
- Costanti condivise, da scrivere una volta sola in `MediasetUrls`:
  - `APP_NAME = "web//mediasetplay-web/1.3.2-e49d465"`
  - `PLAY_API = "https://api-ott-prod-fe.mediaset.net/PROD/play/"`
  - `FEED = "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"`
  - `ACCOUNT_URI = "http://access.auth.theplatform.eu/data/Account/2702976343"`
  - `LICENSE = "https://widevine.entitlement.eu.theplatform.com/wv/web/ModularDrm/getRawWidevineLicense"`
  - `SITE = "https://mediasetinfinity.mediaset.it"`

---

## Struttura dei file

| File | Responsabilità | Puro? |
| --- | --- | --- |
| `MediasetInfinity/build.gradle.kts` | metadati del plugin, dipendenze del modulo | — |
| `MediasetInfinity/src/main/AndroidManifest.xml` | manifest minimo | — |
| `settings.gradle.kts` (modifica) | include il modulo | — |
| `MediasetDTOs.kt` | data class dei feed + estrazioni semplici da una voce | sì |
| `MediasetUrls.kt` | costruzione di tutti gli indirizzi e delle query | sì |
| `MediasetImages.kt` | scelta della variante d'immagine per ogni ruolo | sì |
| `MediasetSeasons.kt` | raggruppamento e ordinamento degli episodi | sì |
| `MediasetSmil.kt` | lettura del SMIL, riconoscimento del blocco geografico | sì |
| `MediasetSections.kt` | lettura delle righe dal markup delle pagine sezione | sì |
| `MediasetApi.kt` | rete: sessione anonima, feed, `playbackCheck`, SMIL | no |
| `MediasetLive.kt` | canali in diretta, `nowNext`, flusso in chiaro | no |
| `MediasetCatalog.kt` | righe della home, da voci di feed a `SearchResponse` | no |
| `MediasetInfinity.kt` | `MainAPI`: home, ricerca, scheda, link | no |
| `MediasetPlugin.kt` | registrazione | no |
| `src/test/kotlin/it/zeroTituli/*Test.kt` | test JVM sui file puri | — |
| `src/test/resources/*` | risposte vere salvate come campioni | — |
| `scripts/mediaset-recon.sh` | ricognizione degli endpoint | — |

---

### Task 1: Modulo che compila e test JVM attivi

**File:**
- Crea: `MediasetInfinity/build.gradle.kts`
- Crea: `MediasetInfinity/src/main/AndroidManifest.xml`
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetInfinity.kt`
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetPlugin.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/ScaffoldTest.kt`
- Modifica: `settings.gradle.kts`

**Interfacce:**
- Produce: la classe `MediasetInfinity : MainAPI()` con `name = "Mediaset Infinity"`,
  che le attività successive riempiono; il modulo `:MediasetInfinity` con il source
  set di test funzionante.

- [ ] **Passo 1: aggiungi il modulo a `settings.gradle.kts`**

```kotlin
include(
    "Hattrick",
    "FCTV33",
    "StreamingCommunity",
    "MediasetInfinity"
)
```

- [ ] **Passo 2: scrivi `MediasetInfinity/build.gradle.kts`**

Solo i metadati e le dipendenze del modulo: tutto il resto (namespace, sdk,
jvmTarget, stub di CloudStream, Jackson, Jsoup) arriva dal `build.gradle.kts` di
radice.

```kotlin
// use an integer for version numbers
version = 1

cloudstream {
    language = "it"
    description = "Mediaset Infinity: catalogo, dirette TV e serie con le stagioni."
    authors = listOf("fixered")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Documentary",
        "Cartoon",
        "Live"
    )

    iconUrl = "https://static3.mediasetplay.mediaset.it/static/images/mplay-logo-organization-v2.png"
}

dependencies {
    val testImplementation by configurations
    // I file puri del plugin girano anche fuori da Android: i test stanno sulla JVM,
    // senza dispositivo. Gli stub di CloudStream lì non esistono, ecco perché quei
    // file non li importano.
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Passo 3: scrivi il manifest**

`MediasetInfinity/src/main/AndroidManifest.xml`, identico a quello degli altri moduli:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest package="it.zeroTituli"/>
```

- [ ] **Passo 4: scrivi lo scheletro di `MediasetInfinity.kt`**

```kotlin
package it.zeroTituli

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/**
 * Mediaset Infinity.
 *
 * Il catalogo arriva dai feed theplatform di Mediaset, aperti; la riproduzione passa
 * da una sessione anonima. Le dirette sono in chiaro e si castano, il catalogo on
 * demand è protetto con Widevine e si vede solo sul dispositivo: il perché sta nel
 * progetto, in docs/superpowers/specs/2026-08-01-mediaset-infinity-design.md.
 */
class MediasetInfinity : MainAPI() {
    override var mainUrl = MediasetUrls.SITE
    override var name = "Mediaset Infinity"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Documentary,
        TvType.Cartoon,
        TvType.Live
    )
}
```

Questo file non compila ancora: `MediasetUrls` arriva nell'attività 3. Per chiudere
questa attività metti `override var mainUrl = "https://mediasetinfinity.mediaset.it"`
scritto a mano e sostituiscilo con la costante nell'attività 3.

- [ ] **Passo 5: scrivi `MediasetPlugin.kt`**

```kotlin
package it.zeroTituli

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MediasetInfinityPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MediasetInfinity())
    }
}
```

- [ ] **Passo 6: scrivi un test che dimostri che il source set di test funziona**

`MediasetInfinity/src/test/kotlin/it/zeroTituli/ScaffoldTest.kt`:

```kotlin
package it.zeroTituli

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serve solo a dimostrare che i test JVM del modulo girano: se questo passa, i test
 * delle attività successive hanno dove stare. Si può cancellare quando il modulo ha
 * test veri.
 */
class ScaffoldTest {
    @Test
    fun `i test girano`() {
        assertTrue(true)
    }
}
```

- [ ] **Passo 7: fai girare i test**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest`
Atteso: BUILD SUCCESSFUL, un test eseguito.

- [ ] **Passo 8: compila il plugin**

Esegui: `./gradlew :MediasetInfinity:make`
Atteso: BUILD SUCCESSFUL e il file `MediasetInfinity/build/MediasetInfinity.cs3`
esiste.

Se `make` si lamenta di `MediasetUrls`, hai lasciato la costante al posto della
stringa: guarda il passo 4.

---

### Task 2: Voci del feed e loro lettura

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetDTOs.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetDTOsTest.kt`
- Crea: `MediasetInfinity/src/test/resources/feed-entry.json`

**Interfacce:**
- Consuma: niente.
- Produce:
  - `object MediasetJson { val mapper: ObjectMapper; fun <reified T> parse(s: String): T? }`
  - `data class FeedResponse(entries: List<FeedEntry>, totalResults: Int?, entryCount: Int?)`
  - `data class FeedEntry(...)` con i campi elencati sotto
  - `data class Thumbnail(url: String?, width: Int?, height: Int?)`
  - `data class Tag(scheme: String?, title: String?)`
  - `data class Credit(creditType: String?, personName: String?)`
  - `data class Rating(scheme: String?, rating: String?)`
  - `data class Media(pid: String?, publicUrl: String?, guid: String?)`
  - `FeedEntry.genres: List<String>`, `.categories: List<String>`,
    `.actors: List<String>`, `.ageRating: String?`, `.isFree: Boolean`,
    `.durationMinutes: Int?`, `.seriesGuid: String?`, `.plot: String?`

- [ ] **Passo 1: salva una risposta vera come campione**

```bash
curl -s "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2?form=cjson&range=1-3&byCustomValue=%7BbrandId%7D%7B100001417%7D" \
  -o MediasetInfinity/src/test/resources/feed-entry.json
```

Deve contenere `"entries"` e almeno una voce con `thumbnails`, `tags` e
`mediasetprogram$brandId`. Se il feed cambiasse forma, il test lo dice subito: è il
motivo per cui il campione è una risposta vera e non scritta a mano.

- [ ] **Passo 2: scrivi i test che falliscono**

`MediasetDTOsTest.kt`:

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetDTOsTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val feed: FeedResponse =
        MediasetJson.parse<FeedResponse>(fixture("feed-entry.json"))!!

    @Test
    fun `legge le voci del feed`() {
        assertTrue(feed.entries.isNotEmpty())
        val e = feed.entries.first()
        assertNotNull(e.guid)
        assertNotNull(e.title)
        assertNotNull(e.brandId)
    }

    @Test
    fun `i campi con il dollaro finiscono nelle proprieta giuste`() {
        val e = feed.entries.first()
        assertEquals("100001417", e.brandId)
        assertNotNull(e.brandTitle)
    }

    @Test
    fun `i generi arrivano dai tag e dal campo generi`() {
        val e = feed.entries.first()
        assertTrue(e.genres.isNotEmpty())
    }

    @Test
    fun `la categoria arriva dai tag`() {
        val e = feed.entries.first()
        assertTrue(e.categories.contains("Documentari"))
    }

    @Test
    fun `la durata passa da secondi a minuti`() {
        // Il feed dà 3550 secondi: CloudStream vuole i minuti.
        val e = feed.entries.first()
        assertEquals(59, e.durationMinutes)
    }

    @Test
    fun `la classificazione italiana diventa una eta`() {
        val e = feed.entries.first()
        assertEquals("T", e.ageRating)
    }

    @Test
    fun `i contenuti con diritto AVOD sono gratuiti`() {
        assertTrue(feed.entries.first().isFree)
    }

    @Test
    fun `l identificativo della serie e l ultimo pezzo del seriesId`() {
        val e = feed.entries.first()
        assertEquals("SE000000000780", e.seriesGuid)
    }

    @Test
    fun `una risposta non valida non lancia`() {
        assertEquals(null, MediasetJson.parse<FeedResponse>("non json"))
    }

    @Test
    fun `un campo nuovo nel feed non rompe la lettura`() {
        val json = """{"entries":[{"guid":"X","title":"T","campoNuovo":123}]}"""
        val r = MediasetJson.parse<FeedResponse>(json)
        assertEquals("X", r!!.entries.first().guid)
    }
}
```

Se il campione scaricato al passo 1 avesse una durata o una classificazione
diverse, correggi i valori attesi nei due test relativi guardando il file: il
comportamento da fissare è la conversione, non il numero.

- [ ] **Passo 3: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest`
Atteso: fallimento in compilazione, `Unresolved reference: FeedResponse`.

- [ ] **Passo 4: scrivi `MediasetDTOs.kt`**

```kotlin
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
```

- [ ] **Passo 5: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest`
Atteso: BUILD SUCCESSFUL, tutti i test di `MediasetDTOsTest` verdi.

- [ ] **Passo 6: cancella `ScaffoldTest.kt`**

Ha fatto il suo lavoro nell'attività 1, ora ci sono test veri.

Esegui di nuovo: `./gradlew :MediasetInfinity:testDebugUnitTest`
Atteso: BUILD SUCCESSFUL.

---

### Task 3: Costruzione degli indirizzi

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetUrls.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetUrlsTest.kt`
- Modifica: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetInfinity.kt`

**Interfacce:**
- Consuma: niente.
- Produce `object MediasetUrls` con le costanti dei vincoli generali più:
  - `fun feed(params: Map<String, String>): String`
  - `fun byBrand(brandId: String, page: Int, perPage: Int = 100): String`
  - `fun bySeries(seriesGuid: String, page: Int, perPage: Int = 40): String`
  - `fun byCategory(category: String, page: Int, perPage: Int = 40): String`
  - `fun byGenre(genre: String, page: Int, perPage: Int = 40): String`
  - `fun alphabetical(category: String, page: Int, perPage: Int = 40): String`
  - `fun search(query: String, page: Int, perPage: Int = 40): String`
  - `fun byGuid(guid: String): String`
  - `fun smil(mediaUrl: String, assetTypes: String, token: String, formats: String = "mpeg-dash"): String`
  - `fun license(pid: String, token: String): String`
  - `fun nowNext(callSign: String): String`
  - `fun section(slug: String): String`
  - `val anonymousLogin: String`, `val playbackCheck: String`
  - `fun range(page: Int, perPage: Int): String`

- [ ] **Passo 1: scrivi i test che falliscono**

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetUrlsTest {

    @Test
    fun `la prima pagina parte da uno`() {
        // theplatform conta le voci da 1 e vuole gli estremi inclusi.
        assertEquals("1-40", MediasetUrls.range(page = 1, perPage = 40))
        assertEquals("41-80", MediasetUrls.range(page = 2, perPage = 40))
    }

    @Test
    fun `una pagina sotto uno vale come la prima`() {
        assertEquals("1-40", MediasetUrls.range(page = 0, perPage = 40))
    }

    @Test
    fun `la query per marchio filtra e ordina`() {
        val url = MediasetUrls.byBrand("100012714", page = 1)
        assertTrue(url.startsWith(MediasetUrls.FEED))
        assertTrue(url.contains("form=cjson"))
        assertTrue(url.contains("byCustomValue=%7BbrandId%7D%7B100012714%7D"))
        assertTrue(url.contains("range=1-100"))
    }

    @Test
    fun `la query per serie usa l indirizzo completo del programma`() {
        val url = MediasetUrls.bySeries("SE000000002040", page = 1)
        assertTrue(url.contains("bySeriesId="))
        assertTrue(url.contains("SE000000002040"))
        // L'indirizzo va codificato: i due punti e le barre non possono restare nudi.
        assertTrue(url.contains("http%3A%2F%2Fdata.entertainment.tv.theplatform.eu"))
    }

    @Test
    fun `la query per categoria usa i tag`() {
        val url = MediasetUrls.byCategory("Programmi Tv", page = 1)
        assertTrue(url.contains("byTags=category%7CProgrammi+Tv"))
    }

    @Test
    fun `la query alfabetica ordina per titolo del marchio`() {
        val url = MediasetUrls.alphabetical("Fiction", page = 2)
        assertTrue(url.contains("sort=mediasetprogram%24brandTitle%7Casc"))
        assertTrue(url.contains("range=41-80"))
    }

    @Test
    fun `la ricerca passa il testo cosi come e`() {
        val url = MediasetUrls.search("zelig party", page = 1)
        assertTrue(url.contains("q=zelig+party"))
    }

    @Test
    fun `il SMIL porta il token e gli assetTypes`() {
        val url = MediasetUrls.smil(
            mediaUrl = "https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC",
            assetTypes = "HR,widevine,geoIT|geoNo",
            token = "abc.def"
        )
        assertTrue(url.startsWith("https://link.api.eu.theplatform.com/s/PR1GhC/media/UXvEsmsZ1AvC?"))
        assertTrue(url.contains("format=SMIL"))
        assertTrue(url.contains("formats=mpeg-dash"))
        assertTrue(url.contains("assetTypes=HR%2Cwidevine%2CgeoIT%7CgeoNo"))
        assertTrue(url.contains("auth=abc.def"))
    }

    @Test
    fun `l indirizzo della licenza porta token account e pid`() {
        val url = MediasetUrls.license(pid = "UXvEsmsZ1AvC", token = "abc.def")
        assertTrue(url.startsWith(MediasetUrls.LICENSE))
        assertTrue(url.contains("token=abc.def"))
        assertTrue(url.contains("releasePid=UXvEsmsZ1AvC"))
        assertTrue(url.contains("account=http%3A%2F%2Faccess.auth.theplatform.eu%2Fdata%2FAccount%2F2702976343"))
        assertTrue(url.contains("form=json"))
        assertTrue(url.contains("schema=1.0"))
    }

    @Test
    fun `nowNext vuole il nome del canale`() {
        assertEquals(
            "https://api-ott-prod-fe.mediaset.net/PROD/play/alive/nownext/v1.0?channelId=C5",
            MediasetUrls.nowNext("C5")
        )
    }

    @Test
    fun `la pagina sezione sta sul sito`() {
        assertEquals("https://mediasetinfinity.mediaset.it/fiction", MediasetUrls.section("fiction"))
    }
}
```

- [ ] **Passo 2: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetUrlsTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetUrls`.

- [ ] **Passo 3: scrivi `MediasetUrls.kt`**

```kotlin
package it.zeroTituli

import java.net.URLEncoder

/**
 * Tutti gli indirizzi in un posto solo, e nessuna chiamata di rete: così le query si
 * possono provare senza dispositivo e senza toccare Mediaset.
 */
object MediasetUrls {

    const val SITE = "https://mediasetinfinity.mediaset.it"
    const val PLAY_API = "https://api-ott-prod-fe.mediaset.net/PROD/play/"
    const val FEED =
        "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"
    const val AZ_LISTING =
        "https://static3.mediasetplay.mediaset.it/cataloglisting/azListing.json"
    const val LICENSE =
        "https://widevine.entitlement.eu.theplatform.com/wv/web/ModularDrm/getRawWidevineLicense"
    const val ACCOUNT_URI = "http://access.auth.theplatform.eu/data/Account/2702976343"

    /**
     * La versione del sito, che il login anonimo pretende. Sta nell'HTML della home di
     * Mediaset: se un giorno il login risponde `AG005 VALIDATION_ERROR`, è questa da
     * aggiornare.
     */
    const val APP_NAME = "web//mediasetplay-web/1.3.2-e49d465"

    /** Il conto theplatform di Mediaset, dentro gli indirizzi di serie e stagioni. */
    private const val ACCOUNT_GUID = "2702976343"
    private const val PROGRAM_BASE =
        "http://data.entertainment.tv.theplatform.eu/entertainment/data/Program/guid/$ACCOUNT_GUID/"

    val anonymousLogin get() = PLAY_API + "idm/anonymous/login/v2.0"
    val playbackCheck get() = PLAY_API + "playback/check/v2.0"

    fun nowNext(callSign: String) = PLAY_API + "alive/nownext/v1.0?channelId=" + enc(callSign)

    fun section(slug: String) = "$SITE/$slug"

    /** theplatform conta da 1 e vuole gli estremi inclusi. */
    fun range(page: Int, perPage: Int): String {
        val p = if (page < 1) 1 else page
        val from = (p - 1) * perPage + 1
        return "$from-${from + perPage - 1}"
    }

    fun feed(params: Map<String, String>): String =
        FEED + "?" + query(mapOf("form" to "cjson") + params)

    fun byBrand(brandId: String, page: Int, perPage: Int = 100) = feed(
        mapOf(
            "byCustomValue" to "{brandId}{$brandId}",
            "byProgramType" to "episode",
            "range" to range(page, perPage),
            "count" to "true",
        )
    )

    fun bySeries(seriesGuid: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "bySeriesId" to PROGRAM_BASE + seriesGuid,
            "range" to range(page, perPage),
            "count" to "true",
        )
    )

    fun byCategory(category: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "byTags" to "category|$category",
            "sort" to "mediasetprogram\$publishInfo_lastPublished|desc",
            "range" to range(page, perPage),
        )
    )

    fun byGenre(genre: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "byTags" to "genre|$genre",
            "sort" to "mediasetprogram\$publishInfo_lastPublished|desc",
            "range" to range(page, perPage),
        )
    )

    fun alphabetical(category: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "byTags" to "category|$category",
            "sort" to "mediasetprogram\$brandTitle|asc",
            "range" to range(page, perPage),
        )
    )

    fun search(query: String, page: Int, perPage: Int = 40) = feed(
        mapOf(
            "q" to query,
            "range" to range(page, perPage),
        )
    )

    fun byGuid(guid: String) = feed(mapOf("byGuid" to guid, "range" to "1-1"))

    fun smil(
        mediaUrl: String,
        assetTypes: String,
        token: String,
        formats: String = "mpeg-dash",
    ): String = mediaUrl + "?" + query(
        mapOf(
            "format" to "SMIL",
            "formats" to formats,
            "assetTypes" to assetTypes,
            "auto" to "true",
            "tracking" to "false",
            "auth" to token,
        )
    )

    fun license(pid: String, token: String): String = LICENSE + "?" + query(
        mapOf(
            "form" to "json",
            "schema" to "1.0",
            "token" to token,
            "account" to ACCOUNT_URI,
            "releasePid" to pid,
        )
    )

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
```

Nota sul token: `URLEncoder` lascia passare punti e trattini, quindi il `beToken`
(un JWT) resta leggibile negli indirizzi, come si aspettano i test.

- [ ] **Passo 4: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetUrlsTest*'`
Atteso: BUILD SUCCESSFUL.

- [ ] **Passo 5: usa la costante in `MediasetInfinity.kt`**

Sostituisci l'indirizzo scritto a mano dell'attività 1:

```kotlin
    override var mainUrl = MediasetUrls.SITE
```

- [ ] **Passo 6: compila**

Esegui: `./gradlew :MediasetInfinity:make`
Atteso: BUILD SUCCESSFUL.

---

### Task 4: Scelta delle copertine

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetImages.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetImagesTest.kt`

**Interfacce:**
- Consuma: `FeedEntry`, `Thumbnail` (attività 2).
- Produce `object MediasetImages`:
  - `fun poster(entry: FeedEntry): String?`
  - `fun background(entry: FeedEntry): String?`
  - `fun still(entry: FeedEntry): String?`
  - `fun brandLogo(entry: FeedEntry): String?`
  - `fun best(thumbnails: Map<String, Thumbnail>, prefixes: List<String>): String?`

- [ ] **Passo 1: scrivi i test che falliscono**

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediasetImagesTest {

    private fun thumb(name: String, w: Int, h: Int) =
        name to Thumbnail(url = "https://img/$name.jpg", width = w, height = h)

    private fun entry(vararg t: Pair<String, Thumbnail>) =
        FeedEntry(thumbnails = t.toMap())

    @Test
    fun `il poster prende il verticale piu grande`() {
        val e = entry(
            thumb("image_vertical-140x210", 140, 210),
            thumb("image_vertical-264x396", 264, 396),
            thumb("image_vertical-192x288", 192, 288),
        )
        assertEquals("https://img/image_vertical-264x396.jpg", MediasetImages.poster(e))
    }

    @Test
    fun `senza verticale il poster ripiega sull orizzontale`() {
        val e = entry(thumb("image_header_poster-768x480", 768, 480))
        assertEquals("https://img/image_header_poster-768x480.jpg", MediasetImages.poster(e))
    }

    @Test
    fun `senza nessuna immagine il poster e nullo`() {
        assertNull(MediasetImages.poster(entry()))
    }

    @Test
    fun `lo sfondo preferisce il formato ampio`() {
        val e = entry(
            thumb("image_vertical-264x396", 264, 396),
            thumb("image_header_poster-1440x630", 1440, 630),
        )
        assertEquals("https://img/image_header_poster-1440x630.jpg", MediasetImages.background(e))
    }

    @Test
    fun `il fotogramma dell episodio preferisce il keyframe`() {
        val e = entry(
            thumb("image_keyframe_poster-1280x720", 1280, 720),
            thumb("image_keyframe_poster-240x135", 240, 135),
            thumb("image_horizontal_cover-704x396", 704, 396),
        )
        assertEquals(
            "https://img/image_keyframe_poster-1280x720.jpg",
            MediasetImages.still(e)
        )
    }

    @Test
    fun `il logo del canale e orizzontale`() {
        val e = entry(
            thumb("logo_horizontal-320x128", 320, 128),
            thumb("brand_logo-210x210", 210, 210),
        )
        assertEquals("https://img/logo_horizontal-320x128.jpg", MediasetImages.brandLogo(e))
    }

    @Test
    fun `una variante senza indirizzo viene saltata`() {
        val e = entry(
            "image_vertical-264x396" to Thumbnail(url = null, width = 264, height = 396),
            thumb("image_vertical-192x288", 192, 288),
        )
        assertEquals("https://img/image_vertical-192x288.jpg", MediasetImages.poster(e))
    }

    @Test
    fun `una variante senza larghezza vale meno di una con larghezza`() {
        val e = entry(
            "image_vertical-sconosciuta" to Thumbnail(url = "https://img/x.jpg"),
            thumb("image_vertical-140x210", 140, 210),
        )
        assertEquals("https://img/image_vertical-140x210.jpg", MediasetImages.poster(e))
    }
}
```

- [ ] **Passo 2: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetImagesTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetImages`.

- [ ] **Passo 3: scrivi `MediasetImages.kt`**

```kotlin
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
    private val LOGO = listOf("logo_horizontal", "brand_logo")

    fun poster(entry: FeedEntry): String? = best(entry.thumbnails, VERTICAL)

    fun background(entry: FeedEntry): String? = best(entry.thumbnails, WIDE)

    fun still(entry: FeedEntry): String? = best(entry.thumbnails, STILL)

    fun brandLogo(entry: FeedEntry): String? = best(entry.thumbnails, LOGO)

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
```

- [ ] **Passo 4: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetImagesTest*'`
Atteso: BUILD SUCCESSFUL.

---

### Task 5: Stagioni ed episodi

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetSeasons.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetSeasonsTest.kt`

**Interfacce:**
- Consuma: `FeedEntry` (attività 2).
- Produce:
  - `data class EpisodeSlot(val entry: FeedEntry, val season: Int, val episode: Int?)`
  - `object MediasetSeasons { fun arrange(entries: List<FeedEntry>): List<EpisodeSlot>;
    const val EXTRAS_SEASON: Int }`

- [ ] **Passo 1: scrivi i test che falliscono**

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSeasonsTest {

    private fun ep(
        guid: String,
        season: Int? = null,
        number: Int? = null,
        type: String = "Full Episode",
        subBrand: String? = null,
    ) = FeedEntry(
        guid = guid,
        title = "Ep $guid",
        tvSeasonNumber = season,
        tvSeasonEpisodeNumber = number,
        editorialType = type,
        subBrandId = subBrand,
    )

    @Test
    fun `ordina per stagione e poi per episodio`() {
        val out = MediasetSeasons.arrange(
            listOf(
                ep("c", season = 2, number = 1),
                ep("a", season = 1, number = 1),
                ep("b", season = 1, number = 2),
            )
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.entry.guid })
        assertEquals(listOf(1, 1, 2), out.map { it.season })
    }

    @Test
    fun `stagioni in sottomarchi diversi restano nella stessa serie`() {
        // Mediaset a volte spezza le stagioni in subBrand diversi: contano i numeri
        // di stagione, non da quale sottomarchio arrivano.
        val out = MediasetSeasons.arrange(
            listOf(
                ep("s2e1", season = 2, number = 1, subBrand = "200"),
                ep("s1e1", season = 1, number = 1, subBrand = "100"),
            )
        )
        assertEquals(listOf(1, 2), out.map { it.season })
        assertEquals(listOf("s1e1", "s2e1"), out.map { it.entry.guid })
    }

    @Test
    fun `gli extra senza stagione finiscono in fondo`() {
        val out = MediasetSeasons.arrange(
            listOf(
                ep("extra", type = "Extra"),
                ep("s1e1", season = 1, number = 1),
            )
        )
        assertEquals("s1e1", out.first().entry.guid)
        assertEquals("extra", out.last().entry.guid)
        assertEquals(MediasetSeasons.EXTRAS_SEASON, out.last().season)
    }

    @Test
    fun `un episodio senza numero mantiene la sua stagione e va dopo i numerati`() {
        val out = MediasetSeasons.arrange(
            listOf(
                ep("senza", season = 1),
                ep("s1e2", season = 1, number = 2),
            )
        )
        assertEquals(listOf("s1e2", "senza"), out.map { it.entry.guid })
        assertEquals(listOf(1, 1), out.map { it.season })
    }

    @Test
    fun `le voci doppie del feed compaiono una volta sola`() {
        val out = MediasetSeasons.arrange(
            listOf(ep("a", season = 1, number = 1), ep("a", season = 1, number = 1))
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `le voci senza guid vengono scartate`() {
        val out = MediasetSeasons.arrange(listOf(FeedEntry(title = "senza guid")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `una lista vuota da una lista vuota`() {
        assertTrue(MediasetSeasons.arrange(emptyList()).isEmpty())
    }
}
```

- [ ] **Passo 2: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSeasonsTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetSeasons`.

- [ ] **Passo 3: scrivi `MediasetSeasons.kt`**

```kotlin
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

    fun arrange(entries: List<FeedEntry>): List<EpisodeSlot> = entries
        .filter { !it.guid.isNullOrBlank() }
        .distinctBy { it.guid }
        .map { entry ->
            EpisodeSlot(
                entry = entry,
                season = entry.tvSeasonNumber ?: EXTRAS_SEASON,
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
```

- [ ] **Passo 4: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSeasonsTest*'`
Atteso: BUILD SUCCESSFUL.

---

### Task 6: Lettura del SMIL e blocco geografico

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetSmil.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetSmilTest.kt`

**Interfacce:**
- Consuma: niente.
- Produce:
  - `sealed class SmilResult` con `data class Stream(val url: String, val kind: StreamKind)`,
    `object NoMatch`, `object GeoBlocked`, `data class Failed(val reason: String)`
  - `enum class StreamKind { DASH, HLS, PROGRESSIVE }`
  - `object MediasetSmil { fun read(payload: String): SmilResult }`

- [ ] **Passo 1: salva i SMIL veri come campioni**

Tre casi, tutti visti davvero durante l'analisi. Crea a mano
`MediasetInfinity/src/test/resources/` con:

`smil-dash.xml`:

```xml
<smil xmlns="http://www.w3.org/2005/SMIL21/Language">
	<head>
	</head>
	<body>
	<seq>
		<video src="https://vod06.msf.cdn.mediaset.net/farmunica/2019/04/373226_16a43b7422a89c/dashrcenc/hr_wv_mpl.mpd" title="Stream" type="application/dash+xml"></video>
	</seq>
	</body>
</smil>
```

`smil-geoblock.xml`:

```xml
<smil xmlns="http://www.w3.org/2005/SMIL21/Language">
	<head>
	</head>
	<body>
	<seq>
		<ref src="https://vod06-mediaset-it.akamaized.net/cortesia/GEOLOCK-DEF_2.mp4" title="Invalid Token" abstract="This content requires a valid, unexpired auth token.">
			<param name="isException" value="true"/>
			<param name="exception" value="InvalidAuthToken"/>
			<param name="responseCode" value="403"/>
		</ref>
	</seq>
	</body>
</smil>
```

`smil-nomatch.xml`:

```xml
<smil xmlns="http://www.w3.org/2005/SMIL21/Language">
	<head>
	</head>
	<body>
	<seq>
		<ref src="http://link.theplatform.eu/s/errorFiles/Unavailable.flv" title="No AssetType/ProtectionScheme/Format Matches">
			<param name="isException" value="true"/>
			<param name="exception" value="NoAssetTypeFormatMatches"/>
			<param name="responseCode" value="412"/>
		</ref>
	</seq>
	</body>
</smil>
```

- [ ] **Passo 2: scrivi i test che falliscono**

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSmilTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `legge il flusso DASH`() {
        val r = MediasetSmil.read(fixture("smil-dash.xml"))
        assertTrue(r is SmilResult.Stream)
        r as SmilResult.Stream
        assertEquals(
            "https://vod06.msf.cdn.mediaset.net/farmunica/2019/04/373226_16a43b7422a89c/dashrcenc/hr_wv_mpl.mpd",
            r.url
        )
        assertEquals(StreamKind.DASH, r.kind)
    }

    @Test
    fun `il video di cortesia non e un flusso`() {
        // Il CDN risponde con esito positivo e un cartello: senza riconoscerlo si
        // guarderebbe il cartello al posto del film.
        assertEquals(SmilResult.GeoBlocked, MediasetSmil.read(fixture("smil-geoblock.xml")))
    }

    @Test
    fun `assetTypes sbagliato si distingue dal blocco geografico`() {
        assertEquals(SmilResult.NoMatch, MediasetSmil.read(fixture("smil-nomatch.xml")))
    }

    @Test
    fun `riconosce l HLS`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/index.m3u8"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.HLS, r.kind)
    }

    @Test
    fun `riconosce il file progressivo`() {
        val smil = """<smil><body><seq><video src="https://cdn/x/film.mp4"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.PROGRESSIVE, r.kind)
    }

    @Test
    fun `il manifest con parametri resta riconoscibile`() {
        val smil =
            """<smil><body><seq><video src="https://cdn/live/c5-clr.isml/manifest_sd.mpd?hdnts=st=1~exp=2"/></seq></body></smil>"""
        val r = MediasetSmil.read(smil) as SmilResult.Stream
        assertEquals(StreamKind.DASH, r.kind)
    }

    @Test
    fun `una risposta vuota non lancia`() {
        assertTrue(MediasetSmil.read("") is SmilResult.Failed)
    }

    @Test
    fun `una risposta che non e un SMIL non lancia`() {
        assertTrue(MediasetSmil.read("<html><body>errore</body></html>") is SmilResult.Failed)
    }
}
```

- [ ] **Passo 3: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSmilTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetSmil`.

- [ ] **Passo 4: scrivi `MediasetSmil.kt`**

Niente Jsoup e niente parser XML: bastano due espressioni regolari, e così il file
resta puro e veloce.

```kotlin
package it.zeroTituli

enum class StreamKind { DASH, HLS, PROGRESSIVE }

sealed class SmilResult {
    data class Stream(val url: String, val kind: StreamKind) : SmilResult()

    /** `assetTypes` o `formats` non combaciano con nessuna copia disponibile. */
    object NoMatch : SmilResult()

    /** Fuori area, o token non valido: il CDN manda un video di cortesia. */
    object GeoBlocked : SmilResult()

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

        // Il cartello di cortesia arriva con esito positivo: si riconosce dall'indirizzo.
        if (url.contains("/cortesia/") || url.contains("GEOLOCK")) return SmilResult.GeoBlocked

        when (exception.find(payload)?.groupValues?.get(1)) {
            null -> Unit
            "NoAssetTypeFormatMatches" -> return SmilResult.NoMatch
            "InvalidAuthToken", "GeoLocationBlocked" -> return SmilResult.GeoBlocked
            else -> return SmilResult.NoMatch
        }

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
```

- [ ] **Passo 5: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSmilTest*'`
Atteso: BUILD SUCCESSFUL.

---

### Task 7: Sessione anonima, feed e risoluzione del VOD

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetApi.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetSessionTest.kt`

**Interfacce:**
- Consuma: `MediasetUrls`, `MediasetJson`, `FeedResponse`, `FeedEntry`, `MediasetSmil`,
  `SmilResult`, `StreamKind`.
- Produce:
  - `data class Session(val beToken: String, val sid: String, val bornAt: Long)`
  - `object MediasetSession { fun isStale(session: Session?, now: Long): Boolean;
    const val LIFETIME_MS: Long }` — logica pura, testabile
  - `data class VodStream(val manifest: String, val licenseUrl: String)`
  - `class MediasetApi`:
    - `suspend fun entries(url: String): List<FeedEntry>`
    - `suspend fun entry(guid: String): FeedEntry?`
    - `suspend fun page(url: String): FeedResponse?`
    - `suspend fun vod(guid: String): VodResult`
  - `sealed class VodResult` con `data class Ok(val stream: VodStream)`,
    `object GeoBlocked`, `object NotAvailable`

- [ ] **Passo 1: scrivi il test della scadenza della sessione**

La rete non si prova nei test JVM: si prova la sola regola che decide quando
rifare il login. `MediasetSessionTest.kt`:

```kotlin
package it.zeroTituli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSessionTest {

    @Test
    fun `senza sessione va rifatto il login`() {
        assertTrue(MediasetSession.isStale(null, now = 1_000))
    }

    @Test
    fun `una sessione appena nata va bene`() {
        val s = Session(beToken = "t", sid = "s", bornAt = 1_000)
        assertFalse(MediasetSession.isStale(s, now = 1_000))
    }

    @Test
    fun `una sessione vecchia va rifatta`() {
        val s = Session(beToken = "t", sid = "s", bornAt = 0)
        assertTrue(MediasetSession.isStale(s, now = MediasetSession.LIFETIME_MS + 1))
    }

    @Test
    fun `poco prima della scadenza va ancora bene`() {
        val s = Session(beToken = "t", sid = "s", bornAt = 0)
        assertFalse(MediasetSession.isStale(s, now = MediasetSession.LIFETIME_MS - 1))
    }

    @Test
    fun `un orologio che va indietro non blocca tutto`() {
        // Se l'ora del dispositivo cambia, una sessione "nata nel futuro" non deve
        // restare valida per sempre né essere buttata a ogni chiamata.
        val s = Session(beToken = "t", sid = "s", bornAt = 10_000)
        assertTrue(MediasetSession.isStale(s, now = 0))
    }

    @Test
    fun `una sessione senza token non vale`() {
        val s = Session(beToken = "", sid = "s", bornAt = 1_000)
        assertTrue(MediasetSession.isStale(s, now = 1_000))
    }
}
```

- [ ] **Passo 2: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSessionTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetSession`.

- [ ] **Passo 3: scrivi `MediasetApi.kt`**

```kotlin
package it.zeroTituli

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** Sessione anonima: il token per i feed protetti e per la licenza. */
data class Session(val beToken: String, val sid: String, val bornAt: Long)

/**
 * Quando rifare il login. Regola separata dalla rete perché è l'unica parte che vale
 * la pena provare senza dispositivo.
 */
object MediasetSession {

    /**
     * Il token vale qualche ora. Si rinnova dopo quattro, con margine: un token
     * scaduto a metà film si vede, un login in più non si nota.
     */
    const val LIFETIME_MS = 4L * 60 * 60 * 1000

    fun isStale(session: Session?, now: Long): Boolean {
        if (session == null || session.beToken.isBlank()) return true
        val age = now - session.bornAt
        // Età negativa vuol dire che l'orologio è cambiato: meglio rifare il login
        // che fidarsi di una sessione nata nel futuro.
        return age < 0 || age >= LIFETIME_MS
    }
}

data class VodStream(val manifest: String, val licenseUrl: String)

sealed class VodResult {
    data class Ok(val stream: VodStream) : VodResult()
    object GeoBlocked : VodResult()
    object NotAvailable : VodResult()
}

/**
 * L'unico punto del plugin che parla in rete.
 *
 * Il catalogo sta su feed aperti; la riproduzione richiede tre passaggi (sessione
 * anonima, `playbackCheck`, SMIL) descritti nel progetto. Gli `assetTypes` vanno
 * provati in ordine: uno che non combacia non è un errore di rete, è un SMIL con
 * `NoAssetTypeFormatMatches` dentro.
 */
class MediasetApi(private val clock: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        /** Dal più definito al meno: la prima copia disponibile vince. */
        private val VOD_ASSET_TYPES = listOf(
            "HR,widevine,geoIT|geoNo",
            "SD,widevine,geoIT|geoNo",
            "SS,widevine,geoIT|geoNo",
        )
        private val JSON = "application/json".toMediaTypeOrNull()
    }

    @Volatile
    private var session: Session? = null

    /** L'identificativo di questa installazione: casuale, e sempre lo stesso finché il plugin vive. */
    private val deviceId: String = UUID.randomUUID().toString()

    // ============= SESSIONE =============

    private suspend fun session(): Session? {
        session.takeIf { !MediasetSession.isStale(it, clock()) }?.let { return it }
        val fresh = login()
        if (fresh != null) session = fresh
        return fresh
    }

    private suspend fun login(): Session? = runCatching {
        val body = MediasetJson.mapper.writeValueAsString(
            mapOf("client_id" to deviceId, "appName" to MediasetUrls.APP_NAME)
        )
        val response = app.post(
            MediasetUrls.anonymousLogin,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin" to MediasetUrls.SITE,
                "Referer" to "${MediasetUrls.SITE}/",
            ),
            requestBody = body.toRequestBody(JSON)
        ).body.string()

        val parsed = MediasetJson.parse<LoginResponse>(response)?.response ?: return@runCatching null
        val token = parsed.beToken?.takeIf { it.isNotBlank() } ?: return@runCatching null
        Session(beToken = token, sid = parsed.sid.orEmpty(), bornAt = clock())
    }.getOrNull()

    // ============= CATALOGO =============

    suspend fun page(url: String): FeedResponse? = runCatching {
        MediasetJson.parse<FeedResponse>(app.get(url).body.string())
    }.getOrNull()

    suspend fun entries(url: String): List<FeedEntry> = page(url)?.entries.orEmpty()

    suspend fun entry(guid: String): FeedEntry? =
        entries(MediasetUrls.byGuid(guid)).firstOrNull()

    // ============= RIPRODUZIONE =============

    /**
     * @param guid l'identificativo del contenuto, cioè il `guid` della voce di feed.
     */
    suspend fun vod(guid: String): VodResult {
        val first = resolve(guid)
        if (first != VodResult.NotAvailable) return first

        // Il token può essere scaduto prima del tempo previsto: si butta la sessione e
        // si riprova **una volta sola**. Se cade di nuovo, il contenuto non è
        // disponibile per davvero e insistere farebbe solo girare a vuoto.
        session = null
        return resolve(guid)
    }

    private suspend fun resolve(guid: String): VodResult {
        val session = session() ?: return VodResult.NotAvailable
        val mediaUrl = mediaSelectorUrl(guid, session) ?: return VodResult.NotAvailable

        VOD_ASSET_TYPES.forEach { assetTypes ->
            val payload = runCatching {
                app.get(MediasetUrls.smil(mediaUrl, assetTypes, session.beToken)).body.string()
            }.getOrNull() ?: return@forEach

            when (val result = MediasetSmil.read(payload)) {
                is SmilResult.Stream -> {
                    if (result.kind != StreamKind.DASH) return@forEach
                    val pid = mediaUrl.substringAfterLast('/')
                    return VodResult.Ok(
                        VodStream(
                            manifest = result.url,
                            licenseUrl = MediasetUrls.license(pid, session.beToken)
                        )
                    )
                }
                // Fuori area vale per tutte le copie: insistere non cambia niente.
                SmilResult.GeoBlocked -> return VodResult.GeoBlocked
                SmilResult.NoMatch -> Unit
                is SmilResult.Failed -> Unit
            }
        }
        return VodResult.NotAvailable
    }

    /** `playbackCheck` dice se il contenuto è riproducibile e dove sta la sua copia. */
    private suspend fun mediaSelectorUrl(guid: String, session: Session): String? = runCatching {
        val body = MediasetJson.mapper.writeValueAsString(
            mapOf("contentId" to guid, "streamType" to "VOD")
        )
        val response = app.post(
            MediasetUrls.playbackCheck,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${session.beToken}",
                "sid" to session.sid,
                "Origin" to MediasetUrls.SITE,
            ),
            requestBody = body.toRequestBody(JSON)
        ).body.string()

        // Senza `mediaSelector` il contenuto vuole un abbonamento o un noleggio.
        MediasetJson.parse<PlaybackCheckResponse>(response)
            ?.response?.mediaSelector?.url?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
```

- [ ] **Passo 4: aggiungi a `MediasetDTOs.kt` le risposte di login e playbackCheck**

In coda al file dell'attività 2:

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginResponse(val response: LoginBody? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginBody(val beToken: String? = null, val sid: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackCheckResponse(val response: PlaybackCheckBody? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackCheckBody(
    val mediaSelector: MediaSelector? = null,
    val channelsRights: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaSelector(val url: String? = null)
```

- [ ] **Passo 5: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSessionTest*'`
Atteso: BUILD SUCCESSFUL.

- [ ] **Passo 6: compila il modulo**

Esegui: `./gradlew :MediasetInfinity:make`
Atteso: BUILD SUCCESSFUL. Se `app.post` non accetta `requestBody`, guarda come lo
chiama `StreamingCommunity.kt:219-222`: è la stessa firma.

---

### Task 8: Dirette TV

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetLive.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetLiveTest.kt`
- Crea: `MediasetInfinity/src/test/resources/nownext-c5.json`
- Modifica: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetDTOs.kt`

**Interfacce:**
- Consuma: `MediasetUrls`, `MediasetJson`, `MediasetApi`, `MediasetSmil`.
- Produce:
  - `data class Channel(val callSign: String, val label: String)`
  - `data class LiveInfo(val title: String, val nowPlaying: String?, val logo: String?, val mediaUrl: String?)`
  - `object MediasetLive { val CHANNELS: List<Channel>; fun clearMediaUrl(payload: String): String?;
    fun info(payload: String, fallbackLabel: String): LiveInfo? }`
  - `class MediasetLiveApi(api: MediasetApi) { suspend fun info(callSign: String, label: String): LiveInfo?;
    suspend fun manifest(mediaUrl: String): String? }`

- [ ] **Passo 1: salva la risposta vera come campione**

```bash
curl -s "https://api-ott-prod-fe.mediaset.net/PROD/play/alive/nownext/v1.0?channelId=C5" \
  -o MediasetInfinity/src/test/resources/nownext-c5.json
```

Deve contenere `tuningInstruction` con più voci, alcune con `protectionScheme`
valorizzato e almeno una con `protectionScheme` vuoto: quella in chiaro è la sola
che serve.

- [ ] **Passo 2: scrivi i test che falliscono**

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetLiveTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val payload = fixture("nownext-c5.json")

    @Test
    fun `sceglie la variante in chiaro`() {
        val url = MediasetLive.clearMediaUrl(payload)
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://link.api.eu.theplatform.com/s/PR1GhC/"))
    }

    @Test
    fun `scarta le varianti protette`() {
        val json = """
        {"response":{"tuningInstruction":{"urn:theplatform:tv:location:any":[
          {"format":"application/dash+xml","protectionScheme":"commonEncryption","publicUrls":["https://x/protetto"]},
          {"format":"application/x-mpegURL","protectionScheme":"fairplay","publicUrls":["https://x/fairplay"]}
        ]}}}
        """.trimIndent()
        assertNull(MediasetLive.clearMediaUrl(json))
    }

    @Test
    fun `preferisce il DASH in chiaro all HLS in chiaro`() {
        // Il ricevitore predefinito del Chromecast legge il DASH; l'HLS in chiaro
        // sulle dirette Mediaset non esiste, ma se comparisse resta la seconda scelta.
        val json = """
        {"response":{"tuningInstruction":{"urn:theplatform:tv:location:any":[
          {"format":"application/x-mpegURL","protectionScheme":"","publicUrls":["https://x/hls"]},
          {"format":"application/dash+xml","protectionScheme":"","publicUrls":["https://x/dash"]}
        ]}}}
        """.trimIndent()
        assertEquals("https://x/dash", MediasetLive.clearMediaUrl(json))
    }

    @Test
    fun `legge il programma in onda`() {
        val info = MediasetLive.info(payload, fallbackLabel = "Canale 5")
        assertNotNull(info)
        assertEquals("Canale 5", info!!.title)
        assertNotNull(info.nowPlaying)
    }

    @Test
    fun `senza programma in onda resta il nome del canale`() {
        val json = """{"response":{"tuningInstruction":{"urn:theplatform:tv:location:any":[]}}}"""
        val info = MediasetLive.info(json, fallbackLabel = "Italia 1")
        assertEquals("Italia 1", info!!.title)
        assertNull(info.nowPlaying)
    }

    @Test
    fun `una risposta di errore non da nessun canale`() {
        val json = """{"error":{"code":"AG015","message":"Channel id not found"},"isOk":false}"""
        assertNull(MediasetLive.info(json, fallbackLabel = "X"))
    }

    @Test
    fun `la lista dei canali non ha doppioni`() {
        val signs = MediasetLive.CHANNELS.map { it.callSign }
        assertEquals(signs.size, signs.distinct().size)
        assertTrue(MediasetLive.CHANNELS.any { it.callSign == "C5" })
    }
}
```

- [ ] **Passo 3: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetLiveTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetLive`.

- [ ] **Passo 4: aggiungi a `MediasetDTOs.kt` le risposte di `nowNext`**

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class NowNextResponse(val response: NowNextBody? = null, val isOk: Boolean? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NowNextBody(
    val tuningInstruction: Map<String, List<Tuning>> = emptyMap(),
    val currentListing: Listing? = null,
    val stations: Map<String, Station> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tuning(
    val format: String? = null,
    val protectionScheme: String? = null,
    val publicUrls: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Listing(val title: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Station(
    val title: String? = null,
    val callSign: String? = null,
    val thumbnails: Map<String, Thumbnail> = emptyMap(),
)
```

Se il campione mostra il titolo del programma in un campo diverso da
`currentListing.title`, adegua `Listing` a quello che c'è: il campione è la verità.

- [ ] **Passo 5: scrivi `MediasetLive.kt`**

```kotlin
package it.zeroTituli

/** Un canale Mediaset, col nome che l'API vuole e quello da mostrare. */
data class Channel(val callSign: String, val label: String)

data class LiveInfo(
    val title: String,
    val nowPlaying: String?,
    val logo: String?,
    val mediaUrl: String?,
)

/**
 * Le dirette sono l'unica parte di Mediaset che si casta: fra le varianti che
 * `nowNext` propone ce ne sono alcune senza `protectionScheme`, cioè in chiaro, con
 * il permesso già dentro l'indirizzo. Sono quelle da prendere, e solo quelle.
 */
object MediasetLive {

    /**
     * `nowNext` pretende un canale valido: senza parametro risponde `AG015`. La lista
     * è quindi scritta qui. I canali che non rispondono restano fuori dalla home
     * invece di comparire e non partire (vedi `MediasetLiveApi.info`).
     */
    val CHANNELS = listOf(
        Channel("C5", "Canale 5"),
        Channel("I1", "Italia 1"),
        Channel("R4", "Rete 4"),
        Channel("KA", "Iris"),
        Channel("LB", "La5"),
        Channel("KQ", "20"),
        Channel("FU", "Focus"),
        Channel("KM", "Mediaset Extra"),
        Channel("CI", "Cine34"),
        Channel("LT", "TGCOM24"),
        Channel("KI", "Boing"),
        Channel("LZ", "Cartoonito"),
    )

    private const val ANY = "urn:theplatform:tv:location:any"

    /**
     * L'indirizzo theplatform della variante in chiaro, da risolvere poi in SMIL.
     * Il DASH viene prima: è quello che il ricevitore predefinito del Chromecast legge.
     */
    fun clearMediaUrl(payload: String): String? {
        val tunings = body(payload)?.tuningInstruction?.get(ANY).orEmpty()
        val clear = tunings.filter { it.protectionScheme.isNullOrBlank() }
        val ordered = clear.sortedBy { if (it.format?.contains("dash", true) == true) 0 else 1 }
        return ordered.firstNotNullOfOrNull { it.publicUrls.firstOrNull()?.takeIf { u -> u.isNotBlank() } }
    }

    fun info(payload: String, fallbackLabel: String): LiveInfo? {
        val body = body(payload) ?: return null
        val station = body.stations.values.firstOrNull()
        return LiveInfo(
            title = station?.title?.takeIf { it.isNotBlank() } ?: fallbackLabel,
            nowPlaying = body.currentListing?.title?.takeIf { it.isNotBlank() },
            logo = MediasetImages.best(
                station?.thumbnails.orEmpty(),
                listOf("logo_horizontal", "brand_logo", "image_vertical")
            ),
            mediaUrl = clearMediaUrl(payload),
        )
    }

    private fun body(payload: String): NowNextBody? =
        MediasetJson.parse<NowNextResponse>(payload)?.response
}
```

- [ ] **Passo 6: aggiungi in coda a `MediasetLive.kt` la parte di rete**

```kotlin
/**
 * La parte che parla in rete: una chiamata per canale, e il SMIL della diretta che
 * non ha bisogno del token perché il flusso in chiaro è già autorizzato.
 */
class MediasetLiveApi(private val api: MediasetApi) {

    suspend fun info(callSign: String, label: String): LiveInfo? = runCatching {
        val payload = com.lagradost.cloudstream3.app.get(MediasetUrls.nowNext(callSign)).body.string()
        MediasetLive.info(payload, label)
    }.getOrNull()

    /** Dall'indirizzo theplatform al manifest vero. */
    suspend fun manifest(mediaUrl: String): String? = runCatching {
        val url = "$mediaUrl?format=SMIL&formats=mpeg-dash&tracking=false"
        val payload = com.lagradost.cloudstream3.app.get(url).body.string()
        (MediasetSmil.read(payload) as? SmilResult.Stream)?.url
    }.getOrNull()
}
```

- [ ] **Passo 7: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetLiveTest*'`
Atteso: BUILD SUCCESSFUL.

- [ ] **Passo 8: controlla i nomi dei canali contro l'API vera**

I `callSign` oltre a `C5` sono da confermare. Per ognuno:

```bash
for ch in C5 I1 R4 KA LB KQ FU KM CI LT KI LZ; do
  printf "%-4s -> " "$ch"
  curl -s -m 15 "https://api-ott-prod-fe.mediaset.net/PROD/play/alive/nownext/v1.0?channelId=$ch" \
    | head -c 120
  echo
done
```

Chi risponde `AG015 Channel id not found` va **togliesto** da `CHANNELS`, oppure
corretto se trovi il nome giusto nell'HTML di `https://mediasetinfinity.mediaset.it`
(gli indirizzi dei canali stanno in `mediasetstation$pageUrl`). Un canale che
resta nella lista senza rispondere è una voce che non parte.

Esegui poi: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetLiveTest*'`
Atteso: BUILD SUCCESSFUL.

---

### Task 9: Righe della home dalle pagine sezione

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetSections.kt`
- Crea: `MediasetInfinity/src/test/kotlin/it/zeroTituli/MediasetSectionsTest.kt`
- Crea: `MediasetInfinity/src/test/resources/section-fiction.html`

**Interfacce:**
- Consuma: niente (usa Jsoup, che nei test JVM c'è).
- Produce:
  - `data class SectionItem(val title: String, val href: String, val seriesGuid: String?, val poster: String?)`
  - `data class SectionRow(val title: String, val items: List<SectionItem>)`
  - `object MediasetSections { fun read(html: String): List<SectionRow>; val SLUGS: List<Pair<String, String>> }`

- [ ] **Passo 1: salva la pagina vera come campione**

```bash
curl -s "https://mediasetinfinity.mediaset.it/fiction" \
  -o MediasetInfinity/src/test/resources/section-fiction.html
```

Il file è grande (circa 1 MB) ed è il punto del piano più esposto ai cambi del
sito: è la ragione per cui esiste il ripiego dell'attività 10.

- [ ] **Passo 2: scrivi i test che falliscono**

```kotlin
package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSectionsTest {

    private val html: String =
        javaClass.classLoader!!.getResourceAsStream("section-fiction.html")!!
            .bufferedReader().readText()

    private val rows = MediasetSections.read(html)

    @Test
    fun `trova piu righe`() {
        assertTrue(rows.size >= 3)
    }

    @Test
    fun `ogni riga ha un titolo e almeno una voce`() {
        rows.forEach { row ->
            assertTrue("riga senza titolo", row.title.isNotBlank())
            assertTrue("riga vuota: ${row.title}", row.items.isNotEmpty())
        }
    }

    @Test
    fun `le voci hanno titolo indirizzo e copertina`() {
        val item = rows.first().items.first()
        assertTrue(item.title.isNotBlank())
        assertTrue(item.href.startsWith("/"))
        assertNotNull(item.poster)
    }

    @Test
    fun `l identificativo della serie viene estratto dall indirizzo`() {
        val item = rows.flatMap { it.items }.first { it.href.contains("_SE") }
        assertNotNull(item.seriesGuid)
        assertTrue(item.seriesGuid!!.startsWith("SE"))
    }

    @Test
    fun `le entita HTML nei titoli vengono sciolte`() {
        // Nel markup i titoli arrivano con &#x27; al posto dell'apostrofo.
        assertTrue(rows.flatMap { it.items }.none { it.title.contains("&#") })
    }

    @Test
    fun `un markup senza caroselli da nessuna riga`() {
        assertTrue(MediasetSections.read("<html><body><p>niente</p></body></html>").isEmpty())
    }

    @Test
    fun `una pagina vuota non lancia`() {
        assertTrue(MediasetSections.read("").isEmpty())
    }

    @Test
    fun `le sezioni previste hanno slug e nome`() {
        assertTrue(MediasetSections.SLUGS.any { it.first == "fiction" })
        MediasetSections.SLUGS.forEach { (slug, label) ->
            assertTrue(slug.isNotBlank())
            assertTrue(label.isNotBlank())
        }
    }
}
```

- [ ] **Passo 3: fai girare i test e verifica che falliscano**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSectionsTest*'`
Atteso: fallimento in compilazione, `Unresolved reference: MediasetSections`.

- [ ] **Passo 4: scrivi `MediasetSections.kt`**

Le pagine sezione sono rese lato server: i caroselli sono nel markup, con
`data-testid="carousel-title"` per i titoli e `data-testid="poster-card-link"` o
`keyframe-card-link` per le voci.

```kotlin
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
     * contenitori e si prende il titolo più vicino.
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
            // da lì si risale al programma con una query al feed.
            seriesGuid = href.substringAfterLast('_').takeIf { it.startsWith("SE") },
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
```

Se il test sui titoli fallisce perché `carousel-title` non è dove lo cerca
`titleOf`, apri il campione e guarda dov'è davvero rispetto a `ul.ulCarousel`:
il comportamento da ottenere è "il titolo più vicino sopra il carosello".

- [ ] **Passo 5: fai girare i test e verifica che passino**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest --tests '*MediasetSectionsTest*'`
Atteso: BUILD SUCCESSFUL.

---

### Task 10: Home e ricerca

**File:**
- Crea: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetCatalog.kt`
- Modifica: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetInfinity.kt`

**Interfacce:**
- Consuma: tutto quello prodotto dalle attività 2-9.
- Produce:
  - `class MediasetCatalog(api: MediasetApi, live: MediasetLiveApi)`, i cui metodi
    sono estensioni di `MainAPI` (i costruttori delle schede di CloudStream vogliono
    quel ricevitore) e si chiamano da dentro `MediasetInfinity` con
    `with(catalog) { ... }`:
    - `suspend fun MainAPI.liveRow(): HomePageList?`
    - `suspend fun MainAPI.sectionRows(slug: String, label: String): List<HomePageList>`
    - `suspend fun MainAPI.genreRow(genre: String, page: Int): HomePageList?`
    - `suspend fun MainAPI.alphabeticalRow(category: String, page: Int): HomePageList?`
    - `fun MainAPI.toSearchResponse(entry: FeedEntry): SearchResponse?`
  - in `MediasetInfinity`: `mainPage`, `getMainPage`, `search`
  - formato di `data` per la scheda: `"brand:<brandId>"`, `"guid:<guid>"`,
    `"live:<callSign>"`; l'attività 11 legge questi stessi prefissi.

- [ ] **Passo 1: scrivi `MediasetCatalog.kt`**

```kotlin
package it.zeroTituli

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse

/**
 * Le righe della home e la conversione da voce di feed a scheda.
 *
 * Le sezioni si leggono dal markup del sito; quando quello cambia, la riga non
 * sparisce: si ripiega sul feed per categoria, che ha lo stesso contenuto in ordine
 * di pubblicazione invece che nell'ordine scelto dalla redazione.
 */
class MediasetCatalog(
    private val api: MediasetApi,
    private val live: MediasetLiveApi,
) {

    /** Una scheda apribile per ogni canale che risponde. */
    suspend fun MainAPI.liveRow(): HomePageList? {
        val items = MediasetLive.CHANNELS.mapNotNull { channel ->
            val info = live.info(channel.callSign, channel.label) ?: return@mapNotNull null
            if (info.mediaUrl == null) return@mapNotNull null
            newLiveSearchResponse(
                name = info.nowPlaying?.let { "${info.title} · $it" } ?: info.title,
                url = "live:${channel.callSign}",
                type = TvType.Live,
                fix = false,
            ) {
                this.posterUrl = info.logo
            }
        }
        return if (items.isEmpty()) null else HomePageList("Dirette TV", items, isHorizontalImages = true)
    }

    /** Le righe di una sezione, lette dal sito; vuote se il markup è cambiato. */
    suspend fun MainAPI.sectionRows(slug: String, label: String): List<HomePageList> {
        val html = runCatching {
            com.lagradost.cloudstream3.app.get(MediasetUrls.section(slug)).body.string()
        }.getOrNull().orEmpty()

        val rows = MediasetSections.read(html).mapNotNull { row ->
            val items = row.items.mapNotNull { toSearchResponse(it) }
            if (items.isEmpty()) null else HomePageList("$label · ${row.title}", items)
        }
        if (rows.isNotEmpty()) return rows

        // Ripiego: la sezione resta, cambia l'ordine.
        val entries = api.entries(MediasetUrls.byCategory(categoryOf(slug), page = 1))
        val items = brandCards(entries)
        return if (items.isEmpty()) emptyList() else listOf(HomePageList(label, items))
    }

    suspend fun MainAPI.genreRow(genre: String, page: Int): HomePageList? {
        val items = brandCards(api.entries(MediasetUrls.byGenre(genre, page)))
        return if (items.isEmpty()) null else HomePageList(genre, items)
    }

    suspend fun MainAPI.alphabeticalRow(category: String, page: Int): HomePageList? {
        val items = brandCards(api.entries(MediasetUrls.alphabetical(category, page)))
        return if (items.isEmpty()) null else HomePageList("$category dalla A alla Z", items)
    }

    /**
     * Il feed elenca episodi, non programmi: per il catalogo interessa un riquadro per
     * programma, quindi si tiene la prima voce di ogni marchio.
     */
    private fun MainAPI.brandCards(entries: List<FeedEntry>): List<SearchResponse> = entries
        .filter { !it.brandId.isNullOrBlank() }
        .distinctBy { it.brandId }
        .mapNotNull { toSearchResponse(it) }

    fun MainAPI.toSearchResponse(entry: FeedEntry): SearchResponse? {
        val brandId = entry.brandId ?: return null
        val name = entry.brandTitle?.takeIf { it.isNotBlank() } ?: entry.title ?: return null
        val poster = MediasetImages.poster(entry)
        // `fix = false` è obbligatorio: per default questi costruttori passano
        // l'indirizzo per `fixUrl`, che a una stringa senza `http` mette davanti
        // `mainUrl`. `brand:123` diventerebbe
        // `https://mediasetinfinity.mediaset.it/brand:123` e `load` non lo
        // riconoscerebbe più. Vale per tutte e tre le `new*SearchResponse`.
        return if (entry.programType == "movie") {
            newMovieSearchResponse(name, "brand:$brandId", TvType.Movie, fix = false) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(name, "brand:$brandId", TvType.TvSeries, fix = false) {
                this.posterUrl = poster
            }
        }
    }

    /**
     * Le voci lette dal sito portano l'identificativo della stagione: il marchio si
     * ricava alla prima apertura della scheda, quindi qui basta passarlo com'è.
     */
    private fun MainAPI.toSearchResponse(item: SectionItem): SearchResponse? {
        val guid = item.seriesGuid ?: return null
        return newTvSeriesSearchResponse(item.title, "series:$guid", TvType.TvSeries, fix = false) {
            this.posterUrl = item.poster
        }
    }

    /** Le sezioni del sito e le categorie del catalogo non hanno gli stessi nomi. */
    private fun categoryOf(slug: String): String = when (slug) {
        "fiction" -> "Fiction"
        "cinema" -> "Cinema"
        "programmitv" -> "Programmi Tv"
        "kids" -> "Kids"
        "documentari" -> "Documentari"
        "news-e-sport" -> "Calcio e Sport"
        else -> "Fiction"
    }
}
```

- [ ] **Passo 2: aggiungi home e ricerca a `MediasetInfinity.kt`**

```kotlin
    private val api = MediasetApi()
    private val liveApi = MediasetLiveApi(api)
    private val catalog = MediasetCatalog(api, liveApi)

    /**
     * Le righe della home. Il `data` dice a `getMainPage` cosa caricare, il nome della
     * riga arriva dal contenuto: le sezioni portano i titoli scelti dalla redazione.
     */
    override val mainPage = mainPageOf(
        "live" to "Dirette",
        "section:fiction" to "Sezione",
        "section:cinema" to "Sezione",
        "section:programmitv" to "Sezione",
        "section:kids" to "Sezione",
        "section:documentari" to "Sezione",
        "section:news-e-sport" to "Sezione",
        "genre:Commedia" to "Genere",
        "genre:Thriller" to "Genere",
        "genre:Documentari" to "Genere",
        "genre:Serie Tv" to "Genere",
        "az:Fiction" to "Alfabetico",
        "az:Cinema" to "Alfabetico",
        "az:Programmi Tv" to "Alfabetico",
        "az:Kids" to "Alfabetico",
        "az:Documentari" to "Alfabetico",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val data = request.data
        val argument = data.substringAfter(':', "")

        return when {
            // Le dirette non hanno pagine: sono dodici canali.
            data == "live" -> if (page > 1) null else with(catalog) {
                liveRow()?.let { newHomePageResponse(it, hasNext = false) }
            }

            data.startsWith("section:") -> {
                if (page > 1) return null
                val label = MediasetSections.SLUGS.firstOrNull { it.first == argument }?.second
                    ?: argument
                val rows = with(catalog) { sectionRows(argument, label) }
                if (rows.isEmpty()) null else newHomePageResponse(rows, hasNext = false)
            }

            data.startsWith("genre:") -> with(catalog) {
                genreRow(argument, page)?.let { newHomePageResponse(it, hasNext = true) }
            }

            data.startsWith("az:") -> with(catalog) {
                alphabeticalRow(argument, page)?.let { newHomePageResponse(it, hasNext = true) }
            }

            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = with(catalog) {
        api.entries(MediasetUrls.search(query, page = 1))
            .filter { !it.brandId.isNullOrBlank() }
            .distinctBy { it.brandId }
            .mapNotNull { toSearchResponse(it) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList = with(catalog) {
        val items = api.entries(MediasetUrls.search(query, page))
            .filter { !it.brandId.isNullOrBlank() }
            .distinctBy { it.brandId }
            .mapNotNull { toSearchResponse(it) }
        newSearchResponseList(items, hasNext = items.isNotEmpty())
    }
```

Aggiungi gli import che servono: `HomePageResponse`, `MainPageRequest`,
`SearchResponse`, `SearchResponseList`, `mainPageOf`, `newHomePageResponse`,
`newSearchResponseList`. Il modello è `StreamingCommunity.kt:1-41`.

- [ ] **Passo 3: compila**

Esegui: `./gradlew :MediasetInfinity:make`
Atteso: BUILD SUCCESSFUL.

- [ ] **Passo 4: fai girare tutti i test**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest`
Atteso: BUILD SUCCESSFUL, nessun test rotto dalle aggiunte.

---

### Task 11: Scheda e flussi

**File:**
- Modifica: `MediasetInfinity/src/main/kotlin/it/zeroTituli/MediasetInfinity.kt`

**Interfacce:**
- Consuma: `MediasetApi.entries`, `.entry`, `.vod`, `MediasetSeasons.arrange`,
  `MediasetImages`, `MediasetLiveApi`, i prefissi `brand:`, `series:`, `guid:`,
  `live:` prodotti dall'attività 10.
- Produce: `load`, `loadLinks` completi. Il `dataUrl` degli episodi è
  `"vod:<guid>"`, quello delle dirette `"live:<callSign>"`.

- [ ] **Passo 1: scrivi `load`**

```kotlin
    override suspend fun load(url: String): LoadResponse? {
        val key = key(url)
        return when {
            key.startsWith("live:") -> loadLive(key.substringAfter(':'))
            key.startsWith("series:") -> loadSeries(key.substringAfter(':'))
            key.startsWith("brand:") -> loadBrand(key.substringAfter(':'))
            key.startsWith("guid:") -> loadSingle(key.substringAfter(':'))
            else -> null
        }
    }

    /**
     * Le schede non hanno un indirizzo web: l'identificativo è una chiave tipo
     * `brand:100012714`. Se un preferito salvato porta davanti l'indirizzo del sito —
     * capita quando una `new*SearchResponse` viene costruita senza `fix = false` — la
     * chiave si recupera invece di aprire una scheda vuota.
     */
    private fun key(url: String): String =
        url.removePrefix("$mainUrl/").removePrefix(mainUrl)

    private suspend fun loadLive(callSign: String): LoadResponse? {
        val label = MediasetLive.CHANNELS.firstOrNull { it.callSign == callSign }?.label ?: callSign
        val info = liveApi.info(callSign, label) ?: return null
        return newLiveStreamLoadResponse(
            name = info.title,
            url = "live:$callSign",
            dataUrl = "live:$callSign"
        ) {
            this.posterUrl = info.logo
            this.plot = info.nowPlaying?.let { "Ora in onda: $it" }
        }
    }

    /** Le voci lette dal sito portano la stagione: da lì si risale al marchio. */
    private suspend fun loadSeries(seriesGuid: String): LoadResponse? {
        val brandId = api.entries(MediasetUrls.bySeries(seriesGuid, page = 1))
            .firstNotNullOfOrNull { it.brandId }
            ?: return null
        return loadBrand(brandId)
    }

    private suspend fun loadBrand(brandId: String): LoadResponse? {
        val entries = allEpisodes(brandId)
        val head = entries.firstOrNull() ?: return null
        val name = head.brandTitle?.takeIf { it.isNotBlank() } ?: head.title ?: return null

        // Un solo episodio senza numerazione è un film, non una serie da una puntata.
        val slots = MediasetSeasons.arrange(entries)
        if (slots.size == 1 && head.programType == "movie") return loadSingle(head.guid ?: return null)

        val episodes = slots.map { slot ->
            newEpisode("vod:${slot.entry.guid}") {
                this.name = slot.entry.title
                this.season = slot.season
                this.episode = slot.episode
                this.description = slot.entry.plot
                this.posterUrl = MediasetImages.still(slot.entry)
                this.runTime = slot.entry.durationMinutes
            }
        }

        return newTvSeriesLoadResponse(name, "brand:$brandId", TvType.TvSeries, episodes) {
            this.posterUrl = MediasetImages.poster(head)
            this.backgroundPosterUrl = MediasetImages.background(head)
            this.plot = describe(head)
            this.tags = tagsOf(head)
            this.year = head.year
            this.contentRating = head.ageRating
            addActors(head.actors)
        }
    }

    private suspend fun loadSingle(guid: String): LoadResponse? {
        val entry = api.entry(guid) ?: return null
        val name = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.brandTitle
            ?: return null
        return newMovieLoadResponse(name, "guid:$guid", TvType.Movie, dataUrl = "vod:$guid") {
            this.posterUrl = MediasetImages.poster(entry)
            this.backgroundPosterUrl = MediasetImages.background(entry)
            this.plot = describe(entry)
            this.tags = tagsOf(entry)
            this.year = entry.year
            this.duration = entry.durationMinutes
            this.contentRating = entry.ageRating
            addActors(entry.actors)
        }
    }

    /**
     * Le soap hanno migliaia di puntate: si scaricano a blocchi finché il feed ne dà,
     * con un tetto, perché una scheda con diecimila episodi non si scorre e intanto
     * l'app resta ad aspettare.
     */
    private suspend fun allEpisodes(brandId: String): List<FeedEntry> {
        val all = mutableListOf<FeedEntry>()
        var page = 1
        while (page <= MAX_EPISODE_PAGES) {
            val batch = api.entries(MediasetUrls.byBrand(brandId, page))
            all += batch
            if (batch.size < EPISODES_PER_PAGE) break
            page++
        }
        return all
    }

    /**
     * I generi, più l'etichetta "Abbonamento" quando il contenuto non è gratuito: si
     * vede in cima alla scheda, prima di provare ad aprirlo.
     */
    private fun tagsOf(entry: FeedEntry): List<String> =
        if (entry.isFree) entry.genres else listOf("Abbonamento") + entry.genres

    /** Alla trama si aggiunge l'avviso quando il contenuto non è gratuito. */
    private fun describe(entry: FeedEntry): String? {
        val plot = entry.plot
        if (entry.isFree) return plot
        val warning = "Serve un abbonamento o un noleggio Mediaset Infinity: " +
            "con la sessione anonima questo contenuto non parte."
        return listOfNotNull(warning, plot).joinToString("\n\n")
    }
```

Aggiungi in fondo alla classe:

```kotlin
    private companion object {
        const val EPISODES_PER_PAGE = 100
        /** Cento pagine da cento: diecimila episodi, oltre i quali non serve andare. */
        const val MAX_EPISODE_PAGES = 100
    }
```

- [ ] **Passo 2: scrivi `loadLinks`**

```kotlin
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("live:")) return liveLink(data.substringAfter(':'), callback)
        if (data.startsWith("vod:")) return vodLink(data.substringAfter(':'), isCasting, callback)
        return false
    }

    /**
     * Le dirette sono in chiaro e il permesso è già dentro l'indirizzo: il Chromecast
     * le apre da solo, senza proxy e senza header da rimettere.
     */
    private suspend fun liveLink(callSign: String, callback: (ExtractorLink) -> Unit): Boolean {
        val label = MediasetLive.CHANNELS.firstOrNull { it.callSign == callSign }?.label ?: callSign
        val mediaUrl = liveApi.info(callSign, label)?.mediaUrl ?: return false
        val manifest = liveApi.manifest(mediaUrl) ?: return false
        callback(
            newExtractorLink(
                source = name,
                name = "$label (diretta)",
                url = manifest,
                type = ExtractorLinkType.DASH
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /**
     * Il catalogo on demand è protetto con Widevine: ExoPlayer lo apre col CDM del
     * dispositivo, il Chromecast no, perché CloudStream gli manda solo l'indirizzo.
     * Il nome del link lo dice, così in casting si legge invece di trovare un errore
     * muto sul televisore.
     */
    private suspend fun vodLink(
        guid: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = when (val result = api.vod(guid)) {
            is VodResult.Ok -> result.stream
            VodResult.GeoBlocked -> return false
            VodResult.NotAvailable -> return false
        }

        val label = if (isCasting) "Widevine — solo sul telefono" else "Widevine"
        callback(
            newDrmExtractorLink(
                source = name,
                name = label,
                url = stream.manifest,
                type = ExtractorLinkType.DASH,
                uuid = WIDEVINE_DRM_UUID
            ) {
                this.licenseUrl = stream.licenseUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
```

Import da aggiungere: `Episode`, `LoadResponse`, `SubtitleFile`, `newEpisode`,
`newLiveStreamLoadResponse`, `newMovieLoadResponse`, `newTvSeriesLoadResponse`,
`LoadResponse.Companion.addActors`, `utils.ExtractorLink`, `utils.ExtractorLinkType`,
`utils.Qualities`, `utils.newExtractorLink`, `utils.newDrmExtractorLink`,
`utils.WIDEVINE_DRM_UUID`.

- [ ] **Passo 3: compila**

Esegui: `./gradlew :MediasetInfinity:make`
Atteso: BUILD SUCCESSFUL.

Se `newDrmExtractorLink` risulta ambiguo fra la versione con `java.util.UUID` e
quella con `kotlin.uuid.Uuid`, usa `WIDEVINE_DRM_UUID` così com'è: è già un
`kotlin.uuid.Uuid` e sceglie la firma nuova.

- [ ] **Passo 4: fai girare tutti i test**

Esegui: `./gradlew :MediasetInfinity:testDebugUnitTest`
Atteso: BUILD SUCCESSFUL.

---

### Task 12: Script di ricognizione

**File:**
- Crea: `scripts/mediaset-recon.sh`

**Interfacce:**
- Consuma: le costanti dell'attività 3 (ripetute nello script: è uno strumento a
  parte, deve girare anche senza il plugin).
- Produce: uno script che dice quali endpoint funzionano.

- [ ] **Passo 1: scrivi lo script**

```bash
#!/usr/bin/env bash
# Ricognizione degli endpoint di Mediaset Infinity.
#
# Quando il plugin smette di funzionare, questo dice quale pezzo è caduto senza
# aprire l'app. Serve `curl`; `python3` solo per leggere il JSON, se c'è.
set -u

FEED="https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"
PLAY="https://api-ott-prod-fe.mediaset.net/PROD/play"
SITE="https://mediasetinfinity.mediaset.it"
APP_NAME="web//mediasetplay-web/1.3.2-e49d465"

ok()   { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mCADUTO\033[0m %s\n' "$1"; FAILED=1; }
FAILED=0

echo "1. Feed del catalogo"
if curl -sf -m 20 "$FEED?form=cjson&range=1-1" | grep -q '"entries"'; then
  ok "mediaset-prod-all-programs-v2 risponde"
else
  fail "il feed del catalogo non risponde: senza questo non c'è catalogo"
fi

echo "2. Categorie e lettere"
if curl -sf -m 20 "https://static3.mediasetplay.mediaset.it/cataloglisting/azListing.json" \
   | grep -q '"categories"'; then
  ok "azListing.json risponde"
else
  fail "azListing.json non risponde: le categorie vanno prese dai tag del feed"
fi

echo "3. Dirette"
for ch in C5 I1 R4; do
  body=$(curl -sf -m 20 "$PLAY/alive/nownext/v1.0?channelId=$ch")
  if printf '%s' "$body" | grep -q 'tuningInstruction'; then
    if printf '%s' "$body" | grep -q '"protectionScheme": *""'; then
      ok "$ch: c'è una variante in chiaro (castabile)"
    else
      fail "$ch: risponde ma nessuna variante in chiaro: il cast delle dirette cade"
    fi
  else
    fail "$ch: nowNext non risponde"
  fi
done

echo "4. Sessione anonima"
LOGIN=$(curl -sf -m 20 -X POST "$PLAY/idm/anonymous/login/v2.0" \
  -H 'Content-Type: application/json' \
  -H "Origin: $SITE" \
  -d "{\"client_id\":\"$(uuidgen 2>/dev/null || echo 11111111-2222-3333-4444-555555555555)\",\"appName\":\"$APP_NAME\"}")
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"beToken": *"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
  ok "login anonimo: token ottenuto"
else
  fail "login anonimo fallito: se dice VALIDATION_ERROR, l'appName è da aggiornare (MediasetUrls.APP_NAME)"
fi

echo "5. Riproduzione di un contenuto gratuito"
GUID=$(curl -sf -m 20 "$FEED?form=cjson&range=1-1&byProgramType=episode&sort=mediasetprogram\$publishInfo_lastPublished|desc" \
  | sed -n 's/.*"guid": *"\([^"]*\)".*/\1/p' | head -1)
if [ -z "$GUID" ] || [ -z "$TOKEN" ]; then
  fail "salto la prova: manca il guid o il token"
else
  MEDIA=$(curl -sf -m 20 -X POST "$PLAY/playback/check/v2.0" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"contentId\":\"$GUID\",\"streamType\":\"VOD\"}" \
    | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p' | head -1)
  if [ -z "$MEDIA" ]; then
    fail "playbackCheck non dà mediaSelector per $GUID"
  else
    ok "playbackCheck risponde per $GUID"
    SMIL=$(curl -sf -m 25 -G "$MEDIA" \
      --data-urlencode 'format=SMIL' \
      --data-urlencode 'formats=mpeg-dash' \
      --data-urlencode 'assetTypes=HR,widevine,geoIT|geoNo' \
      --data-urlencode "auth=$TOKEN")
    case "$SMIL" in
      *cortesia*|*GEOLOCK*) fail "SMIL: blocco geografico, serve una rete italiana" ;;
      *NoAssetTypeFormatMatches*) fail "SMIL: nessuna copia per questi assetTypes, la catena è da rivedere" ;;
      *.mpd*) ok "SMIL: manifest DASH risolto" ;;
      *) fail "SMIL: risposta inattesa" ;;
    esac
  fi
fi

echo "6. Licenza Widevine"
if [ -n "$TOKEN" ]; then
  # Senza un CDM non si ottiene una licenza vera: qui si controlla solo che il
  # servizio accetti token e conto. Un 401 vuol dire token rifiutato.
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 20 -X POST \
    "https://widevine.entitlement.eu.theplatform.com/wv/web/ModularDrm/getRawWidevineLicense?form=json&schema=1.0&token=$TOKEN&account=http%3A%2F%2Faccess.auth.theplatform.eu%2Fdata%2FAccount%2F2702976343&releasePid=UXvEsmsZ1AvC" \
    -H 'Content-Type: application/octet-stream' --data-binary 'sonda')
  case "$CODE" in
    401) fail "licenza: token rifiutato" ;;
    422) ok  "licenza: token e conto accettati (422 atteso senza CDM)" ;;
    *)   ok  "licenza: risposta $CODE, da guardare a mano se il VOD non parte" ;;
  esac
fi

echo "7. Markup delle sezioni"
if curl -sf -m 30 "$SITE/fiction" | grep -q 'ulCarousel'; then
  ok "le pagine sezione hanno ancora i caroselli nel markup"
else
  fail "markup delle sezioni cambiato: la home usa il ripiego sul feed per categoria"
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "Tutto in piedi."
else
  echo "Qualcosa è caduto: vedi sopra."
fi
exit "$FAILED"
```

- [ ] **Passo 2: rendilo eseguibile e fallo girare**

```bash
chmod +x scripts/mediaset-recon.sh
./scripts/mediaset-recon.sh
```

Atteso: i punti da 1 a 7 con esito. Da una rete italiana devono essere tutti `OK`;
il punto 6 dà `422`, che è l'esito giusto senza un CDM. Se il punto 5 dice blocco
geografico, sei fuori dall'Italia: non è un difetto del plugin.

---

### Task 13: Prova sul dispositivo

**File:** nessuno. È la verifica finale, quella che i test JVM non possono fare.

- [ ] **Passo 1: installa il plugin**

```bash
./gradlew :MediasetInfinity:deployWithAdb
```

Se `deployWithAdb` non trova il dispositivo, copia
`MediasetInfinity/build/MediasetInfinity.cs3` nella cartella dei plugin locali di
CloudStream (vedi il README del repo per l'accesso ai file su Android 11 e oltre).

- [ ] **Passo 2: prova per prima cosa la licenza Widevine**

Apri un episodio recente di una fiction gratuita e fallo partire **sul
dispositivo**. È il controllo che decide se il catalogo on demand è riproducibile:
tutto il resto del plugin non serve a niente se qui non parte.

Atteso: il video parte. Se dà errore di licenza, guarda il log
(`adb logcat | grep -i -e drm -e widevine -e license`) e confronta l'indirizzo
costruito da `MediasetUrls.license` con quello del punto 6 dello script di
ricognizione.

- [ ] **Passo 3: prova la home**

Atteso: la riga "Dirette TV" con i canali e il programma in onda; le righe delle
sezioni con i titoli della redazione; le righe per genere; le righe alfabetiche
che caricano altre pagine scorrendo. Nessuna riga vuota.

- [ ] **Passo 4: prova una serie con più stagioni**

Apri una soap lunga (per esempio "La promessa"). Atteso: il selettore delle
stagioni con le stagioni in ordine, gli episodi numerati dentro ciascuna, gli
extra nella stagione in fondo, e ogni episodio col suo fotogramma.

- [ ] **Passo 5: prova la ricerca**

Cerca un programma per nome. Atteso: un riquadro per programma, non uno per
episodio.

- [ ] **Passo 6: prova un contenuto da abbonamento**

Apri un film che sul sito è a noleggio. Atteso: la scheda si apre con l'avviso
nella trama, e il video non parte: nessun errore muto.

- [ ] **Passo 7: prova il rinnovo della sessione**

Lascia l'app aperta qualche ora, poi apri un episodio. Atteso: parte. Il rinnovo del
token e il nuovo tentativo dopo un rifiuto non hanno test JVM, perché passano dalla
rete: questo è il solo modo di vederli funzionare. In alternativa, per non aspettare,
abbassa `MediasetSession.LIFETIME_MS` a un minuto, ricompila, e verifica che dopo
qualche minuto un episodio parta ancora — poi rimetti il valore giusto.

- [ ] **Passo 8: prova il Chromecast**

Casta una diretta. Atteso: parte sul televisore.

Poi prova a castare un episodio on demand. Atteso: nell'elenco delle sorgenti
compare "Widevine — solo sul telefono", e il televisore non parte. È il
comportamento previsto, non un difetto: il perché sta nel progetto.

- [ ] **Passo 9: riporta com'è andata**

Scrivi cosa funziona e cosa no, punto per punto. Se il passo 2 fallisce, il
plugin resta utile come catalogo più dirette e va detto subito, prima di
sistemare altro.

---

## Note su cosa si discosta dal progetto

Tre scelte prese scrivendo il piano, tutte in direzione di quello che il progetto
chiedeva:

1. **File puri separati.** Il progetto metteva la scelta delle immagini dentro
   `MediasetCatalog` e le stagioni dentro `MediasetInfinity`. Qui stanno in
   `MediasetImages` e `MediasetSeasons`, che non toccano nessun tipo di CloudStream:
   è la condizione perché i test JVM possano girare.
2. **Test JVM.** Il progetto prevedeva compilazione, script di ricognizione e prova
   sul dispositivo. Il piano aggiunge un source set di test per la logica pura —
   lettura dei feed, immagini, stagioni, SMIL, markup delle sezioni — perché sono
   le parti dove un errore non si vede a occhio.
3. **A-Z senza scelta della lettera.** Il progetto diceva "per categoria e lettera
   iniziale". Il feed non filtra per iniziale (`byCustomValue` con l'asterisco non
   funziona) e CloudStream non ha righe navigabili a cartelle: le righe alfabetiche
   sono quindi ordinate per titolo e si scorrono a pagine. Le lettere di
   `azListing.json` restano non usate.
