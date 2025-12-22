package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Hattrick : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com"
    override var name = "Hattrick"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // URL del file M3U8 su GitHub (modifica con il tuo repository)
    private val m3u8Url = "$mainUrl/USERNAME/REPO/main/playlist.m3u8"

    data class Channel(
        val name: String,
        val url: String,
        val logo: String? = null,
        val group: String? = null
    )

    override val mainPage = mainPageOf(
        "" to "Canali Live"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = parseM3U8Playlist()
        val items = channels.map { channel ->
            newLiveSearchResponse(
                name = channel.name,
                url = channel.url,
                type = TvType.Live
            ) {
                this.posterUrl = channel.logo
            }
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = parseM3U8Playlist()
        return channels.filter { 
            it.name.contains(query, ignoreCase = true) 
        }.map { channel ->
            newLiveSearchResponse(
                name = channel.name,
                url = channel.url,
                type = TvType.Live
            ) {
                this.posterUrl = channel.logo
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channels = parseM3U8Playlist()
        val channel = channels.find { it.url == url } 
            ?: throw ErrorLoadingException("Canale non trovato")

        return newLiveStreamLoadResponse(
            name = channel.name,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = channel.logo
            this.plot = "Canale live: ${channel.name}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }

    private suspend fun parseM3U8Playlist(): List<Channel> {
        val channels = mutableListOf<Channel>()
        
        try {
            val response = app.get(m3u8Url).text
            val lines = response.lines()
            
            var currentName: String? = null
            var currentLogo: String? = null
            var currentGroup: String? = null

            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXTINF:")) {
                    // Parse EXTINF line
                    val nameMatch = Regex("""tvg-name="([^"]+)"""").find(line)
                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(line)
                    val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                    
                    // Se non c'è tvg-name, prendi il testo dopo l'ultima virgola
                    currentName = nameMatch?.groupValues?.get(1) 
                        ?: line.substringAfterLast(",").trim()
                    currentLogo = logoMatch?.groupValues?.get(1)
                    currentGroup = groupMatch?.groupValues?.get(1)
                    
                } else if (line.isNotEmpty() && !line.startsWith("#") && currentName != null) {
                    // Questo è l'URL dello stream
                    channels.add(
                        Channel(
                            name = currentName,
                            url = line,
                            logo = currentLogo,
                            group = currentGroup
                        )
                    )
                    currentName = null
                    currentLogo = null
                    currentGroup = null
                }
            }
        } catch (e: Exception) {
            // Log error silently
        }

        return channels
    }
}