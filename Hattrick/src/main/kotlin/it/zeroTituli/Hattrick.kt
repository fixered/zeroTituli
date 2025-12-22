package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.*

class Hattrick : MainAPI() {
    override var mainUrl = "https://www.hattrick.ws"
    override var name = "Hattrick Sport"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // URL del file M3U8 su GitHub
    private val m3u8Url = "https://raw.githubusercontent.com/USERNAME/REPO/main/hattrick.m3u8"

    // Mappa canali: nome normalizzato -> stream URL
    private var channelStreams = mutableMapOf<String, String>()

    data class Match(
        val homeTeam: String,
        val awayTeam: String,
        val competition: String,
        val time: String,
        val channels: List<String>,
        val logo: String
    )

    override val mainPage = mainPageOf(
        "" to "Partite Live Oggi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Carica gli stream dal M3U8
        if (channelStreams.isEmpty()) {
            loadM3U8Streams()
        }

        // Scraping delle partite da hattrick.ws
        val matches = scrapeMatches()
        
        val items = matches.map { match ->
            val matchName = "${match.homeTeam} - ${match.awayTeam}"
            val posterUrl = match.logo.ifEmpty { 
                "https://resource-m.calcionapoli24.it/www/thumbs/1200x/1590651555_987.jpg" 
            }
            
            newLiveSearchResponse(
                name = "$matchName\n${match.competition} • ${match.time}",
                url = encodeMatchData(match),
                type = TvType.Live
            ) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (channelStreams.isEmpty()) {
            loadM3U8Streams()
        }

        val matches = scrapeMatches()
        return matches.filter { 
            it.homeTeam.contains(query, ignoreCase = true) ||
            it.awayTeam.contains(query, ignoreCase = true) ||
            it.competition.contains(query, ignoreCase = true)
        }.map { match ->
            val matchName = "${match.homeTeam} - ${match.awayTeam}"
            newLiveSearchResponse(
                name = "$matchName\n${match.competition} • ${match.time}",
                url = encodeMatchData(match),
                type = TvType.Live
            ) {
                this.posterUrl = match.logo.ifEmpty { 
                    "https://resource-m.calcionapoli24.it/www/thumbs/1200x/1590651555_987.jpg" 
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val match = decodeMatchData(url)
        val matchName = "${match.homeTeam} - ${match.awayTeam}"
        
        return newLiveStreamLoadResponse(
            name = matchName,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = match.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = decodeMatchData(data)
        var linksAdded = false

        // Per ogni canale della partita, cerca lo stream corrispondente
        match.channels.forEach { channelName ->
            val normalizedChannel = normalizeChannelName(channelName)
            val streamUrl = findStreamForChannel(normalizedChannel)
            
            if (streamUrl != null) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = channelName,
                        url = streamUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                linksAdded = true
            }
        }

        return linksAdded
    }

    // ============= FUNZIONI DI SUPPORTO =============

    private suspend fun loadM3U8Streams() {
        try {
            val response = app.get(m3u8Url).text
            val lines = response.lines()
            
            var currentName: String? = null
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXTINF:")) {
                    // Estrai il nome del canale
                    val nameMatch = Regex(""",\s*(.+)$""").find(line)
                    currentName = nameMatch?.groupValues?.get(1)?.trim()
                    
                } else if (line.isNotEmpty() && !line.startsWith("#") && currentName != null) {
                    // URL dello stream
                    val normalizedName = normalizeChannelName(currentName)
                    channelStreams[normalizedName] = line
                    currentName = null
                }
            }
            
            println("Caricati ${channelStreams.size} canali dal M3U8")
        } catch (e: Exception) {
            println("Errore caricamento M3U8: ${e.message}")
        }
    }

    private suspend fun scrapeMatches(): List<Match> {
        val matches = mutableListOf<Match>()
        
        try {
            val doc: Document = app.get(mainUrl).document
            
            // Seleziona tutte le righe degli eventi
            doc.select(".events .row").forEach { row ->
                try {
                    // Estrai informazioni partita
                    val gameNameElement = row.selectFirst(".game-name span")
                    val gameName = gameNameElement?.text() ?: return@forEach
                    
                    // Dividi in squadre (formato: "Team1 - Team2")
                    val teams = gameName.split("-").map { it.trim() }
                    if (teams.size != 2) return@forEach
                    
                    val homeTeam = teams[0]
                    val awayTeam = teams[1]
                    
                    // Estrai competizione e orario
                    val dateText = row.selectFirst(".date")?.text() ?: ""
                    val parts = dateText.split("·").map { it.trim() }
                    val time = if (parts.isNotEmpty()) parts[0] else ""
                    val competition = if (parts.size > 1) parts[1] else ""
                    
                    // Estrai logo
                    val logo = row.selectFirst(".mascot")?.attr("src") ?: ""
                    
                    // Estrai canali dai pulsanti
                    val channels = row.select(".btn").mapNotNull { btn ->
                        val link = btn.selectFirst("a")
                        link?.text()?.trim()
                    }.filter { it.isNotEmpty() }
                    
                    if (channels.isNotEmpty()) {
                        matches.add(Match(
                            homeTeam = homeTeam,
                            awayTeam = awayTeam,
                            competition = competition,
                            time = time,
                            channels = channels,
                            logo = logo
                        ))
                    }
                } catch (e: Exception) {
                    println("Errore parsing riga: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Errore scraping: ${e.message}")
        }
        
        return matches
    }

    private fun normalizeChannelName(name: String): String {
        // Normalizza i nomi dei canali per il matching
        val normalized = name.lowercase()
            .replace("sky sport ", "")
            .replace("sport ", "")
            .replace(" hd", "")
            .replace(" (backup)", "")
            .replace("(backup)", "")
            .replace("  ", " ")
            .trim()
        
        // Mapping speciali
        return when {
            normalized.contains("1") || normalized == "uno" -> "uno"
            normalized.contains("calcio") -> "calcio"
            normalized.contains("mix") -> "mix"
            normalized.contains("max") -> "max"
            normalized.contains("arena") -> "arena"
            normalized.contains("24") -> "24"
            normalized.contains("tennis") -> "tennis"
            normalized.contains("motogp") || normalized.contains("moto gp") -> "motogp"
            normalized.contains("f1") || normalized.contains("formula") -> "f1"
            normalized.contains("dazn") -> "dazn"
            else -> normalized
        }
    }

    private fun findStreamForChannel(normalizedChannel: String): String? {
        // Cerca lo stream corrispondente al canale
        return channelStreams[normalizedChannel] ?: 
               channelStreams.entries.firstOrNull { (key, _) ->
                   key.contains(normalizedChannel) || normalizedChannel.contains(key)
               }?.value
    }

    private fun encodeMatchData(match: Match): String {
        // Codifica i dati della partita in una stringa
        return "${match.homeTeam}|${match.awayTeam}|${match.competition}|${match.time}|${match.channels.joinToString(",")}|${match.logo}"
    }

    private fun decodeMatchData(data: String): Match {
        val parts = data.split("|")
        return Match(
            homeTeam = parts.getOrNull(0) ?: "",
            awayTeam = parts.getOrNull(1) ?: "",
            competition = parts.getOrNull(2) ?: "",
            time = parts.getOrNull(3) ?: "",
            channels = parts.getOrNull(4)?.split(",") ?: emptyList(),
            logo = parts.getOrNull(5) ?: ""
        )
    }
}