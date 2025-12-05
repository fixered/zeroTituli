package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class Hattrick : MainAPI() {
    override var mainUrl = "https://hattrick.ws"
    override var name = "Hattrick"
    override val hasMainPage = true
    override var lang = "it"
    
    // I tipi supportati (Live TV)
    override val supportedTypes = setOf(TvType.Live)

    // Logo hardcoded come nel tuo script
    private val LOGO = "https://www.tuttotech.net/wp-content/uploads/2020/08/NOW-TV-Sky-On-Demand-logo-2.png"

    // -----------------------------------------------------------
    // 1️⃣ Main Page: Scarica HTML e raccoglie i canali (.htm)
    // -----------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        
        // Cerca tutti i bottoni con link che finiscono in .htm
        val channels = document.select("button a[href$='.htm']").mapNotNull { element ->
            val name = element.text().trim()
            val href = fixUrl(element.attr("href"))
            
            if (name.isNotEmpty()) {
                LiveSearchResponse(
                    name = name,
                    url = href,
                    apiName = this.name,
                    type = TvType.Live,
                    posterUrl = LOGO // Usa il logo fisso
                )
            } else null
        }

        return newHomePageResponse(
            list = HomePageResponse(
                "Canali Live",
                channels,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    // -----------------------------------------------------------
    // 2️⃣ & 3️⃣ Load: Estrae iframe e costruisce il flusso veloce
    // -----------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.title().replace("Hattrick", "").trim()

        // Logica replicata dallo script Python:
        // Cerca iframe che contengono "planetary" o "token="
        // Cloudstream non ha Playwright, quindi cerchiamo nel DOM statico
        // Se il sito usa JS per iniettare l'iframe, usiamo una Regex sul body
        
        var iframeUrl = document.select("iframe").attr("src")
        
        // Se l'iframe non è ovvio, cerca stringhe nel codice sorgente che sembrano URL con token
        if (iframeUrl.isEmpty() || (!iframeUrl.contains("planetary") && !iframeUrl.contains("token="))) {
            val html = document.html()
            // Regex per trovare URL tipo https://...token=...
            val regex = """(https?://[^\s"'<>]+token=[a-zA-Z0-9%\-_]+)""".toRegex()
            val match = regex.find(html)
            if (match != null) {
                iframeUrl = match.value
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Live, listOf()) {
            this.posterUrl = LOGO
            this.plot = "Diretta streaming di $title"
            // Passiamo l'iframe URL trovato come "data" per il passaggio successivo
            this.tags = listOf(iframeUrl) 
        }
    }

    // -----------------------------------------------------------
    // 4️⃣ Load Links: Genera l'M3U8 veloce
    // -----------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // "data" qui è l'URL della pagina del canale (passato dal load precedente)
        // Ma nel load ho salvato l'iframe nei tags o devo rifare il fetch? 
        // Per semplicità, rifaccio il fetch o uso l'iframe se l'ho passato correttamente.
        
        // Nota: In Cloudstream loadLinks riceve 'data' che è l'url della pagina .htm
        val document = app.get(data).document
        
        // 1. Trova l'iframe (ripetiamo la logica per sicurezza)
        var iframeUrl = document.select("iframe[src*='token=']").attr("src")
        if(iframeUrl.isEmpty()) iframeUrl = document.select("iframe[src*='planetary']").attr("src")
        
        // Fallback regex se non trovato nel DOM
        if (iframeUrl.isEmpty()) {
             val regex = """(https?://[^"']+(?:planetary|token=)[^"']+)""".toRegex()
             iframeUrl = regex.find(document.html())?.value ?: ""
        }

        if (iframeUrl.isNotEmpty()) {
            // Pulizia URL (a volte ci sono escape chars)
            iframeUrl = iframeUrl.replace("\\/", "/")

            // 2. Parsing URI per estrarre token e path
            val uri = URI(iframeUrl)
            val queryParams = uri.query.split("&").associate {
                val (key, value) = it.split("=")
                key to value
            }
            
            val token = queryParams["token"]

            if (token != null) {
                // 3. Costruzione URL "Veloce" (Planetary)
                // Logica Python: base_path = parsed.path.rsplit("/", 1)[0]
                val path = uri.path
                val basePath = path.substringBeforeLast("/")
                
                // canonical = f"{base_path}/index.fmp4.m3u8?token={token}"
                val m3u8Path = "$basePath/index.fmp4.m3u8?token=$token"
                
                // veloce = f"https://planetary.lovecdn.ru{canonical}"
                val finalUrl = "https://planetary.lovecdn.ru$m3u8Path"

                callback.invoke(
                    ExtractorLink(
                        source = "Hattrick (Veloce)",
                        name = "Hattrick (Veloce)",
                        url = finalUrl,
                        referer = "https://planetary.lovecdn.ru/", // Importante per bypassare controlli
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }
        }
        return false
    }
}