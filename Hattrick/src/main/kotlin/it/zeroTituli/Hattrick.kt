package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Hattrick : MainAPI() {
    override var mainUrl = "https://hattrick.ws"
    override var name = "Hattrick Sky Sport"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val logo = "https://resource-m.calcionapoli24.it/www/thumbs/1200x/1590651555_987.jpg"

    // Regole per rinominare i canali
    private val renameRules = listOf(
        listOf("1", "uno") to "Sky Sport Uno",
        listOf("calcio") to "Sky Sport Calcio",
        listOf("mix") to "Sky Sport Mix",
        listOf("max") to "Sky Sport Max",
        listOf("arena") to "Sky Sport Arena",
        listOf("24") to "Sky Sport 24",
        listOf("tennis") to "Sky Sport Tennis",
        listOf("motogp", "moto gp") to "Sky Sport MotoGP",
        listOf("f1", "formula") to "Sky Sport Formula 1",
        listOf("dazn") to "Dazn 1"
    )

    private fun normalizzaNomeCanale(nome: String): String {
        val nomeLower = nome.lowercase()
        for ((keys, nuovoNome) in renameRules) {
            if (keys.any { it in nomeLower }) {
                return nuovoNome
            }
        }
        return nome.trim()
    }

    override val mainPage = mainPageOf("" to "Sky Sport")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val canali = estraiCanali()
        
        val homePageList = canali.map { canale ->
            LiveSearchResponse(
                name = canale.nome,
                url = canale.url,
                apiName = this.name,
                type = TvType.Live,
                posterUrl = logo
            )
        }

        return HomePageResponse(
            listOf(HomePageList("Canali Live", homePageList)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val canali = estraiCanali()
        return canali.filter { 
            it.nome.contains(query, ignoreCase = true) 
        }.map { canale ->
            LiveSearchResponse(
                name = canale.nome,
                url = canale.url,
                apiName = this.name,
                type = TvType.Live,
                posterUrl = logo
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val nomeCanale = estraiCanali().find { it.url == url }?.nome ?: "Sky Sport"
        
        return LiveStreamLoadResponse(
            name = nomeCanale,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = logo
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframeUrl = estraiIframe(data)
        val streamUrl = costruisciStreamVeloce(iframeUrl)
        
        if (streamUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Hattrick Stream",
                    url = streamUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }
        return false
    }

    // Estrae la lista dei canali dalla pagina principale
    private suspend fun estraiCanali(): List<Canale> {
        val canali = mutableListOf<Canale>()
        
        try {
            val doc = app.get(mainUrl).document
            
            doc.select("button").forEach { button ->
                val link = button.selectFirst("a[href$=.htm]")
                if (link != null) {
                    val href = link.attr("href")
                    val nome = normalizzaNomeCanale(link.text())
                    val urlCompleto = if (href.startsWith("http")) href else "$mainUrl/$href"
                    
                    canali.add(Canale(nome, urlCompleto))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return canali
    }

    // Estrae l'URL dell'iframe dalla pagina del canale
    private suspend fun estraiIframe(url: String): String? {
        return try {
            val doc = app.get(url).document
            
            // Cerca iframe con planetary.lovecdn.ru
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("planetary.lovecdn.ru") && src.contains("token=")) {
                    return src
                }
            }
            
            // Cerca qualsiasi iframe con token
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("token=")) {
                    return src
                }
            }
            
            // Cerca negli script per URL con token
            doc.select("script").forEach { script ->
                val scriptText = script.html()
                val tokenPattern = """(https?://[^"'\s]+token=[^"'\s]+)""".toRegex()
                val match = tokenPattern.find(scriptText)
                if (match != null) {
                    return match.value
                }
            }
            
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Costruisce l'URL stream veloce da iframe
    private fun costruisciStreamVeloce(iframeUrl: String?): String? {
        if (iframeUrl == null) return null
        
        try {
            val uri = java.net.URI(iframeUrl)
            val query = uri.query ?: return null
            
            // Estrai token
            val token = query.split("&")
                .find { it.startsWith("token=") }
                ?.substringAfter("=") ?: return null
            
            // Costruisci path base
            val path = uri.path
            val basePath = path.substringBeforeLast("/")
            
            // URL veloce (planetary.lovecdn.ru)
            return "https://planetary.lovecdn.ru$basePath/index.fmp4.m3u8?token=$token"
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    data class Canale(
        val nome: String,
        val url: String
    )
}