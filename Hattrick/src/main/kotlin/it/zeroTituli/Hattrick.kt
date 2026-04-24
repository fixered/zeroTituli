package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class Hattrick : MainAPI() {
    override var mainUrl = "https://htsport.org"
    override var name = "Hattrick Sport"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36"

    private val sportSections = listOf(
        "football" to "⚽ Calcio",
        "basketball" to "🏀 Basket",
        "tennis" to "🎾 Tennis",
        "handball" to "🤾 Pallamano",
        "hockey" to "🏒 Hockey",
        "mma" to "🥊 MMA / Boxe",
        "snooker" to "🎱 Snooker"
    )

    private val tigerLookupHosts = listOf(
        "chevy.tigertestxtg.sbs",
        "chevy.enviromentalanimal.horse",
        "chevy.soyspace.cyou"
    )

    data class Channel(val name: String, val url: String)

    data class Event(
        val title: String,
        val sport: String,
        val league: String,
        val timestamp: Long,
        val channels: List<Channel>
    )

    @Volatile private var eventsCache: List<Event>? = null
    @Volatile private var cacheTime: Long = 0L
    private val cacheTtlMs = 60_000L

    private val timeFmt by lazy {
        SimpleDateFormat("HH:mm", Locale.ITALY).apply {
            timeZone = TimeZone.getTimeZone("Europe/Rome")
        }
    }

    // ============= MAIN PAGE =============

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = fetchEvents()
        val sections = sportSections.mapNotNull { (key, label) ->
            val items = events.filter { it.sport == key }.map { toSearchResponse(it) }
            if (items.isEmpty()) null else HomePageList(label, items)
        }
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchEvents().filter {
            it.title.contains(q, ignoreCase = true) ||
                it.league.contains(q, ignoreCase = true)
        }.map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val ev = decodeEvent(url)
        val time = formatTime(ev.timestamp)
        val plotLine = buildString {
            if (ev.league.isNotBlank()) append(ev.league).append(" • ")
            if (time.isNotBlank()) append(time)
            append("\n${ev.channels.size} streams disponibili:\n")
            ev.channels.forEachIndexed { i, c -> append("${i + 1}. ${c.name}\n") }
        }
        return newLiveStreamLoadResponse(name = ev.title, url = url, dataUrl = url) {
            this.plot = plotLine
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ev = decodeEvent(data)
        var any = false
        ev.channels.amap { ch ->
            val link = runCatching { resolveChannel(ch) }.getOrNull()
            if (link != null) {
                callback(link)
                any = true
            }
        }
        return any
    }

    // ============= SCRAPING =============

    private suspend fun fetchEvents(): List<Event> {
        val now = System.currentTimeMillis()
        eventsCache?.let { if (now - cacheTime < cacheTtlMs) return it }

        val doc = app.get("$mainUrl/", headers = mapOf("User-Agent" to ua)).document
        val parsed = doc.select("div.event").mapNotNull { el ->
            val sport = el.attr("data-sport").lowercase().ifBlank { return@mapNotNull null }
            val title = el.selectFirst("div.event-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val league = el.attr("data-league").trim()
            val ts = el.attr("data-ts").toLongOrNull() ?: 0L
            val channels = el.select("div.buttons a.btn.tv").mapNotNull { a ->
                val href = a.attr("href").trim()
                val chName = a.text().trim()
                if (href.isBlank() || chName.isBlank()) null
                else Channel(chName, href)
            }
            if (channels.isEmpty()) null
            else Event(title, sport, league, ts, channels)
        }

        eventsCache = parsed
        cacheTime = now
        return parsed
    }

    private fun toSearchResponse(ev: Event): LiveSearchResponse {
        val time = formatTime(ev.timestamp)
        val meta = when {
            ev.league.isNotBlank() && time.isNotBlank() -> "$time · ${ev.league}"
            ev.league.isNotBlank() -> ev.league
            else -> time
        }
        val label = if (meta.isNotBlank()) "${ev.title}\n$meta" else ev.title
        return newLiveSearchResponse(
            name = label,
            url = encodeEvent(ev),
            type = TvType.Live
        ) {}
    }

    private fun formatTime(ts: Long): String =
        if (ts > 0L) timeFmt.format(Date(ts * 1000L)) else ""

    // ============= STREAM RESOLVERS =============

    private suspend fun resolveChannel(ch: Channel): ExtractorLink? {
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(ch.url, headers = headers, referer = "$mainUrl/").text
        val rawIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val iframeSrc = normalizeUrl(rawIframe, ch.url)

        val lower = iframeSrc.lowercase()
        return when {
            lower.contains("maxsport.php") -> resolveTigerMaxsport(iframeSrc, ch.name)
            lower.contains("popcdn.day") -> resolvePopcdn(iframeSrc, ch.name, ch.url)
            lower.contains("lovetier.bz") -> resolveLovetier(iframeSrc, ch.name, ch.url)
            lower.contains("embed.php") -> null // bet365 dinamico, non gestibile headless
            else -> null
        }
    }

    private fun normalizeUrl(url: String, base: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val baseHost = Regex("""https?://[^/]+""").find(base)?.value ?: return url
                "$baseHost$url"
            }
            else -> url
        }
    }

    // maxsport.php?id=KEY on tigertestxtg.sbs (or mirrors)
    private suspend fun resolveTigerMaxsport(iframeUrl: String, chName: String): ExtractorLink? {
        val key = Regex("""[?&]id=([^&#]+)""").find(iframeUrl)?.groupValues?.getOrNull(1)
            ?: return null
        val iframeHost = Regex("""https?://([^/]+)""").find(iframeUrl)?.groupValues?.getOrNull(1)
            ?: "tigertestxtg.sbs"
        val playerReferer = "https://$iframeHost/"

        val lookupHosts = buildList {
            add("chevy.$iframeHost")
            tigerLookupHosts.forEach { if (!contains(it)) add(it) }
        }

        val headers = mapOf(
            "User-Agent" to ua,
            "Origin" to playerReferer.trimEnd('/'),
            "Accept" to "application/json, text/plain, */*"
        )

        for (host in lookupHosts) {
            val serverKey = runCatching {
                val resp = app.get(
                    "https://$host/server_lookup?channel_id=$key",
                    headers = headers,
                    referer = playerReferer,
                    timeout = 8L
                )
                Regex(""""server_key"\s*:\s*"([^"]+)"""")
                    .find(resp.text)?.groupValues?.getOrNull(1)
            }.getOrNull() ?: continue

            val path = if (serverKey == "top1/cdn") "top1/cdn" else serverKey
            val m3u8 = "https://$host/proxy/$path/$key/mono.m3u8"

            return newExtractorLink(
                source = this.name,
                name = chName,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = playerReferer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Origin" to playerReferer.trimEnd('/')
                )
            }
        }
        return null
    }

    // popcdn.day/go.php?stream=KEY → iframe lovetier.bz/player/KEY
    private suspend fun resolvePopcdn(iframeUrl: String, chName: String, originUrl: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to ua)
        val doc = app.get(iframeUrl, headers = headers, referer = originUrl).text
        val inner = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(doc)?.groupValues?.getOrNull(1) ?: return null
        return resolveLovetier(inner, chName, iframeUrl)
    }

    private suspend fun resolveLovetier(playerUrl: String, chName: String, referer: String): ExtractorLink? {
        val host = Regex("""https?://([^/]+)""").find(playerUrl)?.groupValues?.getOrNull(1)
            ?: "lovetier.bz"
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(playerUrl, headers = headers, referer = referer).text
        val raw = Regex("""streamUrl\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val m3u8 = raw.replace("\\/", "/")
        return newExtractorLink(
            source = this.name,
            name = chName,
            url = m3u8,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://$host/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to ua,
                "Origin" to "https://$host"
            )
        }
    }

    // ============= EVENT ENCODE/DECODE =============

    private fun encodeEvent(ev: Event): String {
        val chs = ev.channels.joinToString("¤") { "${it.name}¦${it.url}" }
        return listOf(
            ev.title,
            ev.sport,
            ev.league,
            ev.timestamp.toString(),
            chs
        ).joinToString("§")
    }

    private fun decodeEvent(s: String): Event {
        val p = s.split("§")
        val chs = p.getOrNull(4).orEmpty()
            .split("¤")
            .mapNotNull {
                val parts = it.split("¦")
                if (parts.size == 2) Channel(parts[0], parts[1]) else null
            }
        return Event(
            title = p.getOrElse(0) { "" },
            sport = p.getOrElse(1) { "" },
            league = p.getOrElse(2) { "" },
            timestamp = p.getOrElse(3) { "0" }.toLongOrNull() ?: 0L,
            channels = chs
        )
    }
}
