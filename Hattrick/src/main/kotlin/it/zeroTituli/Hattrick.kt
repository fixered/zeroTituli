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

    data class Channel(val name: String, val url: String)

    data class Event(
        val title: String,
        val sport: String,
        val league: String,
        val timestamp: Long,
        val channels: List<Channel>,
        val logo: String
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
        val raw = fetchEvents()
        // Re-classify all events via inferSport on title+league (data-sport del sito è spesso sbagliato)
        val events = raw.map { ev ->
            ev.copy(sport = inferSport("${ev.title} ${ev.league}"))
        }.sortedBy { if (it.timestamp > 0L) it.timestamp else Long.MAX_VALUE }

        val sections = mutableListOf<HomePageList>()

        // ⭐ In Evidenza (match top-block con stream funzionanti)
        val featured = events.filter { it.channels.any { c -> isTopBlockChannel(c) } }
        if (featured.isNotEmpty()) {
            sections += HomePageList("⭐ In Evidenza", featured.map { toSearchResponse(it) })
        }

        // Day boundaries (Europe/Rome)
        val tz = TimeZone.getTimeZone("Europe/Rome")
        val cal = java.util.Calendar.getInstance(tz).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis / 1000L
        val tomorrowStart = todayStart + 86400L
        val dayAfterStart = tomorrowStart + 86400L
        val nowSec = System.currentTimeMillis() / 1000L

        // 🔴 Live Ora (inizio negli ultimi 3h)
        val live = events.filter {
            it.timestamp in (nowSec - 10_800L)..nowSec
        }
        if (live.isNotEmpty()) {
            sections += HomePageList("🔴 Live Ora", live.map { toSearchResponse(it) })
        }

        // ⏰ Prossime (oggi, dopo adesso)
        val upcomingToday = events.filter {
            it.timestamp in (nowSec + 1L)..(tomorrowStart - 1L)
        }
        if (upcomingToday.isNotEmpty()) {
            sections += HomePageList("⏰ Prossime · Oggi", upcomingToday.map { toSearchResponse(it) })
        }

        // 📅 Domani
        val tomorrow = events.filter {
            it.timestamp in tomorrowStart..(dayAfterStart - 1L)
        }
        if (tomorrow.isNotEmpty()) {
            sections += HomePageList("📅 Domani", tomorrow.map { toSearchResponse(it) })
        }

        // Sport sections (classificazione corretta)
        sportSections.forEach { (key, label) ->
            val items = events.filter { it.sport == key }.map { toSearchResponse(it) }
            if (items.isNotEmpty()) sections += HomePageList(label, items)
        }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchEvents().filter {
            it.title.contains(q, ignoreCase = true) || it.league.contains(q, ignoreCase = true)
        }.map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val ev = decodeEvent(url)
        val time = formatWhen(ev.timestamp)
        val plotLine = buildString {
            if (ev.league.isNotBlank()) append(ev.league).append(" • ")
            if (time.isNotBlank()) append(time)
            append("\n\nCanali disponibili:\n")
            ev.channels.forEachIndexed { i, c -> append("${i + 1}. ${c.name}\n") }
        }
        return newLiveStreamLoadResponse(name = ev.title, url = url, dataUrl = url) {
            this.plot = plotLine
            if (ev.logo.isNotBlank()) this.posterUrl = ev.logo
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

        // Top block: div.row containing a.game-name (featured matches)
        val topEvents = mutableListOf<Event>()
        doc.select("a.game-name").forEach { gameName ->
            val row = gameName.closest(".row") ?: return@forEach
            val title = gameName.selectFirst("span")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach
            val dateText = row.selectFirst("p.date")?.text()?.trim().orEmpty()
            val (time, league) = splitDateText(dateText)
            val logo = row.selectFirst("img.mascot")?.attr("src").orEmpty()
            val channels = row.select("button.btn a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                val label = a.text().trim()
                if (href.isBlank() || label.isBlank()) null
                else Channel(label, resolveRelative(href))
            }
            if (channels.isEmpty()) return@forEach
            topEvents += Event(
                title = title,
                sport = inferSport(league),
                league = league,
                timestamp = parseTimeToTs(time),
                channels = channels,
                logo = logo
            )
        }

        // Bottom block: div.event with data-sport
        val bottomEvents = doc.select("div.event").mapNotNull { el ->
            val sport = el.attr("data-sport").lowercase().ifBlank { return@mapNotNull null }
            val title = el.selectFirst("div.event-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val league = el.attr("data-league").trim()
            val ts = el.attr("data-ts").toLongOrNull() ?: 0L
            val channels = el.select("div.buttons a.btn.tv").mapNotNull { a ->
                val href = a.attr("href").trim()
                val chName = a.text().trim()
                if (href.isBlank() || chName.isBlank()) null
                else Channel(chName, resolveRelative(href))
            }
            if (channels.isEmpty()) null
            else Event(title, sport, league, ts, channels, "")
        }

        val merged = mergeEvents(topEvents, bottomEvents)
        eventsCache = merged
        cacheTime = now
        return merged
    }

    private fun mergeEvents(top: List<Event>, bottom: List<Event>): List<Event> {
        val byKey = mutableMapOf<String, Event>()
        bottom.forEach { ev -> byKey[eventKey(ev)] = ev }
        top.forEach { t ->
            val key = eventKey(t)
            val existing = byKey[key]
            if (existing != null) {
                val mergedCh = (t.channels + existing.channels)
                    .distinctBy { it.url }
                byKey[key] = existing.copy(
                    channels = mergedCh,
                    logo = existing.logo.ifBlank { t.logo }
                )
            } else {
                byKey[key] = t
            }
        }
        return byKey.values.sortedWith(compareBy({ it.timestamp }, { it.title }))
    }

    private fun eventKey(ev: Event): String {
        val teams = normalizeTitle(ev.title)
        return "${ev.timestamp / 60}|$teams"
    }

    private fun normalizeTitle(title: String): String {
        val normalized = title.lowercase()
            .replace("·", " - ")
            .replace(Regex("\\s+vs\\s+"), " - ")
            .replace(Regex("[^a-z0-9 -]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val parts = normalized.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.sorted().joinToString("|")
    }

    private fun splitDateText(s: String): Pair<String, String> {
        val cleaned = s.replace("·", "|").replace("•", "|")
        val parts = cleaned.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val time = parts.firstOrNull { it.matches(Regex("\\d{1,2}:\\d{2}")) }.orEmpty()
        val league = parts.firstOrNull { !it.matches(Regex("\\d{1,2}:\\d{2}")) }.orEmpty()
        return time to league
    }

    private fun parseTimeToTs(timeHHmm: String): Long {
        if (!timeHHmm.matches(Regex("\\d{1,2}:\\d{2}"))) return 0L
        return try {
            val fmt = SimpleDateFormat("HH:mm", Locale.ITALY).apply {
                timeZone = TimeZone.getTimeZone("Europe/Rome")
            }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).apply {
                timeZone = TimeZone.getTimeZone("Europe/Rome")
            }.format(Date())
            val full = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY).apply {
                timeZone = TimeZone.getTimeZone("Europe/Rome")
            }
            (full.parse("$today $timeHHmm")?.time ?: 0L) / 1000L
        } catch (_: Exception) { 0L }
    }

    private fun inferSport(text: String): String {
        val l = " ${text.lowercase()} "
        return when {
            l.contains("tennis") || l.contains(" atp ") || l.contains(" wta ") ||
                l.contains("roland garros") || l.contains("wimbledon") || l.contains("us open") ||
                l.contains("australian open") -> "tennis"
            l.contains("basket") || l.contains(" nba ") || l.contains("ncaa") ||
                l.contains("euroleague") || l.contains("eurolega") || l.contains("lba ") -> "basketball"
            l.contains("hockey") || l.contains(" nhl ") || l.contains(" khl ") ||
                l.contains("ice hockey") -> "hockey"
            l.contains("handball") || l.contains("pallamano") || l.contains(" ehf ") -> "handball"
            l.contains(" mma ") || l.contains(" ufc ") || l.contains("boxing") ||
                l.contains(" boxe") || l.contains("wrestling") || l.contains(" wwe ") -> "mma"
            l.contains("snooker") || l.contains("biliardo") -> "snooker"
            else -> "football"
        }
    }

    private fun resolveRelative(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$mainUrl$href"
        else -> "$mainUrl/$href"
    }

    private fun isTopBlockChannel(c: Channel): Boolean =
        c.url.contains("htsport.org") && c.url.endsWith(".htm")

    private fun toSearchResponse(ev: Event): LiveSearchResponse {
        val whenStr = formatWhen(ev.timestamp)
        val meta = when {
            ev.league.isNotBlank() && whenStr.isNotBlank() -> "$whenStr · ${ev.league}"
            ev.league.isNotBlank() -> ev.league
            else -> whenStr
        }
        val label = if (meta.isNotBlank()) "${ev.title}\n$meta" else ev.title
        return newLiveSearchResponse(
            name = label,
            url = encodeEvent(ev),
            type = TvType.Live
        ) {
            if (ev.logo.isNotBlank()) this.posterUrl = ev.logo
        }
    }

    private fun formatWhen(ts: Long): String {
        if (ts <= 0L) return ""
        val tz = TimeZone.getTimeZone("Europe/Rome")
        val cal = java.util.Calendar.getInstance(tz).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis / 1000L
        val tomorrowStart = todayStart + 86400L
        val dayAfterStart = tomorrowStart + 86400L
        val time = timeFmt.format(Date(ts * 1000L))
        return when {
            ts < todayStart -> "Ieri $time"
            ts < tomorrowStart -> time
            ts < dayAfterStart -> "Domani $time"
            else -> {
                val df = SimpleDateFormat("E d/M HH:mm", Locale.ITALY).apply { timeZone = tz }
                df.format(Date(ts * 1000L))
            }
        }
    }

    // ============= STREAM RESOLVERS =============

    private suspend fun resolveChannel(ch: Channel): ExtractorLink? {
        // Both .htm (htsport.org) and .php (abcsport.top) contain an iframe pointing to a player
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(ch.url, headers = headers, referer = "$mainUrl/").text
        val rawIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val iframeSrc = normalizeUrl(rawIframe, ch.url)
        return dispatch(iframeSrc, ch.name, ch.url)
    }

    private suspend fun dispatch(url: String, chName: String, refererUrl: String): ExtractorLink? {
        val lower = url.lowercase()
        return when {
            lower.contains("mediahosting.space") -> resolveMediahosting(url, chName)
            lower.contains("staypoor.net") -> resolveStaypoor(url, chName)
            lower.contains("sportssonline") || lower.contains("expectdynm") ->
                resolveSportssonline(url, chName, refererUrl)
            lower.contains("freeshot.live") -> resolveFreeshot(url, chName)
            lower.contains("popcdn.day") -> resolvePopcdn(url, chName, refererUrl)
            lower.contains("lovetier.bz") -> resolveLovetier(url, chName, refererUrl)
            // tigertestxtg.sbs + liveon4.zip: stream AES-128 con key obfuscata → ExoPlayer fallisce
            lower.contains("tigertestxtg.sbs") || lower.contains("maxsport.php") -> null
            lower.contains("liveon4.zip") || lower.contains("antena.php") -> null
            lower.contains("embed.php") -> null // bet365
            else -> null
        }
    }

    // mediahosting.space/embed/player?stream=N
    private suspend fun resolveMediahosting(url: String, chName: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(url, headers = headers, referer = "$mainUrl/").text
        val m3u8 = Regex("""https?:\\?/\\?/[a-zA-Z0-9.\-:]+/stream/[^"\\]+\.m3u8[^"\\]*""")
            .find(html)?.value?.replace("\\/", "/") ?: return null
        return buildM3u8Link(chName, m3u8, "https://mediahosting.space/")
    }

    // staypoor.net/embed/HASH → direct m3u8 in HTML
    private suspend fun resolveStaypoor(url: String, chName: String): ExtractorLink? {
        val host = hostOf(url) ?: "staypoor.net"
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(url, headers = headers, referer = "$mainUrl/").text
        val m3u8 = Regex("""https?://[a-zA-Z0-9.\-:]+/hls/[^"'\s]+\.m3u8[^"'\s]*""")
            .find(html)?.value ?: return null
        return buildM3u8Link(chName, m3u8, "https://$host/")
    }

    // sportssonline.click/channels/hd/hdN.php → iframe expectdynm.net/embed/HASH → m3u8
    private suspend fun resolveSportssonline(url: String, chName: String, referer: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to ua)
        var current = url
        // Follow iframe up to 3 levels
        repeat(3) {
            val html = app.get(current, headers = headers, referer = referer).text
            val direct = Regex("""https?://[a-zA-Z0-9.\-:]+/hls/[^"'\s]+\.m3u8[^"'\s]*""")
                .find(html)?.value
            if (direct != null) {
                val host = hostOf(current) ?: "sportssonline.click"
                return buildM3u8Link(chName, direct, "https://$host/")
            }
            val inner = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1) ?: return null
            current = normalizeUrl(inner, current)
        }
        return null
    }

    // freeshot.live/embed/NAME.php → iframe popcdn.day → lovetier
    private suspend fun resolveFreeshot(url: String, chName: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(url, headers = headers, referer = "$mainUrl/").text
        val inner = Regex("""<iframe[^>]+src=["']([^"']+popcdn\.day[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1) ?: return null
        val iframeUrl = normalizeUrl(inner, url)
        return dispatch(iframeUrl, chName, url)
    }

    // popcdn.day/go.php?stream=KEY → iframe lovetier.bz/player/KEY
    private suspend fun resolvePopcdn(iframeUrl: String, chName: String, originUrl: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to ua)
        val doc = app.get(iframeUrl, headers = headers, referer = originUrl).text
        val inner = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(doc)?.groupValues?.getOrNull(1) ?: return null
        return resolveLovetier(normalizeUrl(inner, iframeUrl), chName, iframeUrl)
    }

    private suspend fun resolveLovetier(playerUrl: String, chName: String, referer: String): ExtractorLink? {
        val host = hostOf(playerUrl) ?: "lovetier.bz"
        val headers = mapOf("User-Agent" to ua)
        val html = app.get(playerUrl, headers = headers, referer = referer).text
        val raw = Regex("""streamUrl\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val m3u8 = raw.replace("\\/", "/")
        return buildM3u8Link(chName, m3u8, "https://$host/")
    }

    // ============= HELPERS =============

    private suspend fun buildM3u8Link(chName: String, m3u8: String, refererUrl: String): ExtractorLink {
        val origin = Regex("""https?://[^/]+""").find(refererUrl)?.value ?: refererUrl.trimEnd('/')
        return newExtractorLink(
            source = this.name,
            name = chName,
            url = m3u8,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = refererUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to ua,
                "Origin" to origin
            )
        }
    }

    private fun hostOf(url: String): String? =
        Regex("""https?://([^/]+)""").find(url)?.groupValues?.getOrNull(1)

    private fun normalizeUrl(url: String, base: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> (hostOf(base)?.let { "https://$it$url" } ?: url)
        else -> {
            val baseDir = base.substringBeforeLast("/", base)
            "$baseDir/$url"
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
            chs,
            ev.logo
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
            channels = chs,
            logo = p.getOrElse(5) { "" }
        )
    }
}
