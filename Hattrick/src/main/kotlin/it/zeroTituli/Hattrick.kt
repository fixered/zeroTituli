package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import it.zeroTituli.shared.Covers
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Calendar
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

    private val romeTz: TimeZone = TimeZone.getTimeZone("Europe/Rome")

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
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = romeTz }
    }

    private val hhmmRegex = Regex("""\d{1,2}:\d{2}""")

    // Canali sempre attivi (card "Canali on Line"): non hanno orario
    private val alwaysOnLeague = "Canali 24/7"

    // ============= MAIN PAGE =============

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = fetchEvents()

        val cal = Calendar.getInstance(romeTz).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis / 1000L
        val tomorrowStart = todayStart + 86400L
        val dayAfterStart = tomorrowStart + 86400L
        val nowSec = System.currentTimeMillis() / 1000L
        val liveFrom = nowSec - 10_800L

        val sections = mutableListOf<HomePageList>()

        // 🔴 Live Ora — iniziate nelle ultime 3h
        val live = events.filter { it.timestamp in liveFrom..nowSec }
            .sortedByDescending { it.timestamp }
        if (live.isNotEmpty()) {
            sections += HomePageList("🔴 Live Ora", live.map { toSearchResponse(it) })
        }

        // 📺 Canali sempre attivi (senza orario)
        val channels = events.filter { it.timestamp <= 0L }
        if (channels.isNotEmpty()) {
            sections += HomePageList("📺 $alwaysOnLeague", channels.map { toSearchResponse(it) })
        }

        // ⏰ Prossime · Oggi — divise per fascia oraria
        val upcomingToday = events.filter { it.timestamp in (nowSec + 1L)..(tomorrowStart - 1L) }
        addBandSections(sections, upcomingToday, prefix = "⏰ Oggi")

        // 📅 Domani — divise per fascia oraria
        val tomorrow = events.filter { it.timestamp in tomorrowStart..(dayAfterStart - 1L) }
        addBandSections(sections, tomorrow, prefix = "📅 Domani")

        // 🗓 Tutto il resto: giorni successivi + palinsesto vecchio non ancora aggiornato dal sito.
        // Senza questa sezione un palinsesto non aggiornato farebbe apparire la home vuota.
        val others = events.filter {
            it.timestamp > 0L && (it.timestamp < liveFrom || it.timestamp >= dayAfterStart)
        }.sortedBy { it.timestamp }
        if (others.isNotEmpty()) {
            sections += HomePageList("🗓 Altre date", others.map { toSearchResponse(it) })
        }

        return newHomePageResponse(sections, false)
    }

    private fun bandOf(ts: Long): String {
        val c = Calendar.getInstance(romeTz).apply { timeInMillis = ts * 1000L }
        return when (c.get(Calendar.HOUR_OF_DAY)) {
            in 0..11 -> "Mattina"
            in 12..17 -> "Pomeriggio"
            else -> "Sera"
        }
    }

    private fun addBandSections(
        out: MutableList<HomePageList>,
        events: List<Event>,
        prefix: String
    ) {
        val bandEmoji = mapOf(
            "Mattina" to "🌅",
            "Pomeriggio" to "☀️",
            "Sera" to "🌙"
        )
        listOf("Mattina", "Pomeriggio", "Sera").forEach { band ->
            val items = events.filter { bandOf(it.timestamp) == band }
                .sortedBy { it.timestamp }
                .map { toSearchResponse(it) }
            if (items.isNotEmpty()) {
                out += HomePageList("$prefix · ${bandEmoji[band]} $band", items)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchEvents().filter { ev ->
            ev.title.contains(q, ignoreCase = true) ||
                ev.league.contains(q, ignoreCase = true) ||
                ev.channels.any { it.name.contains(q, ignoreCase = true) }
        }.map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val ev = findEvent(url) ?: throw ErrorLoadingException("Evento non più in palinsesto")
        val time = formatWhen(ev.timestamp)
        val plotLine = buildString {
            if (ev.league.isNotBlank()) append(ev.league).append(" • ")
            if (time.isNotBlank()) append(time)
            append("\n\nCanali disponibili:\n")
            ev.channels.forEachIndexed { i, c -> append("${i + 1}. ${c.name}\n") }
        }
        val id = eventId(ev)
        return newLiveStreamLoadResponse(name = ev.title, url = id, dataUrl = id) {
            this.plot = plotLine
            this.posterUrl = posterFor(ev)
            this.backgroundPosterUrl = backdropFor(ev)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ev = findEvent(data) ?: return false
        val any = AtomicBoolean(false)
        ev.channels.amap { ch ->
            val link = runCatching { resolveChannel(ch) }.getOrNull()
            if (link != null) {
                callback(link)
                any.set(true)
            }
        }
        // Se nessun canale è risolvibile leggendo l'HTML (player con JS offuscato) si ripiega
        // sulla WebView: costosa, quindi sequenziale e solo sui primi canali.
        if (!any.get()) {
            for (ch in ev.channels.take(webViewFallbackChannels)) {
                val link = runCatching { resolveChannelWithWebView(ch) }.getOrNull()
                if (link != null) {
                    callback(link)
                    any.set(true)
                    break
                }
            }
        }
        return any.get()
    }

    // ============= SCRAPING =============
    //
    // Struttura home (UI 2026): un unico flusso in ordine di documento
    //   .date-header      → giorno corrente ("MERCOLEDI 29/07")
    //   .category-label   → campionato ("CALCIO - AMICHEVOLI -")
    //   .match-card       → partita
    //       .time-box .ora-txt[data-timestamp]  → orario (o "Elenco Canali" per i canali 24/7)
    //       .teams-box                          → "Casa - Ospite" (+ .score-badge / .vs-txt da scartare)
    //       .btn-group a[href]                  → canali (pagine .htm relative)

    private suspend fun fetchEvents(): List<Event> {
        val now = System.currentTimeMillis()
        eventsCache?.let { if (now - cacheTime < cacheTtlMs) return it }

        val doc = app.get("$mainUrl/", headers = mapOf("User-Agent" to ua)).document

        var currentDate = ""
        var currentLabel = ""
        val parsed = mutableListOf<Event>()

        doc.select(".date-header, .category-label, .match-card").forEach { el ->
            when {
                el.hasClass("date-header") -> currentDate = extractDayMonth(el.text())
                el.hasClass("category-label") -> currentLabel = cleanLabel(el.text())
                else -> parsed += parseCard(el, currentLabel, currentDate)
            }
        }

        val merged = mergeEvents(parsed)
        eventsCache = merged
        cacheTime = now
        return merged
    }

    private fun parseCard(card: Element, league: String, dayMonth: String): List<Event> {
        val channels = card.select(".btn-group a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            val label = a.text().trim()
            // I canali "EXT CHROME" incorporano un'estensione del browser: non riproducibili
            if (href.isBlank() || label.isBlank() ||
                href.startsWith("chrome-extension:") ||
                label.contains("EXT CHROME", ignoreCase = true)
            ) null
            else Channel(label, resolveRelative(href))
        }.distinctBy { it.url }
        if (channels.isEmpty()) return emptyList()

        val ora = card.selectFirst(".ora-txt")
        val timeText = ora?.text()?.trim().orEmpty()
        val tsAttr = ora?.attr("data-timestamp")?.trim()?.toLongOrNull() ?: 0L

        // Card senza orario = elenco canali sempre attivi: un elemento per canale
        if (!hhmmRegex.matches(timeText)) {
            return channels.map { ch ->
                Event(
                    title = ch.name,
                    sport = inferSport(ch.name),
                    league = alwaysOnLeague,
                    timestamp = 0L,
                    channels = listOf(ch),
                    logo = ""
                )
            }
        }

        val title = card.selectFirst(".teams-box")?.let { box ->
            box.clone().also { it.select(".score-badge, .vs-txt").remove() }.text().trim()
        }.orEmpty()
        if (title.isBlank()) return emptyList()

        val ts = parseDayTimeToTs(dayMonth, timeText).takeIf { it > 0L } ?: tsAttr

        return listOf(
            Event(
                title = title,
                sport = inferSport("$title $league"),
                league = league,
                timestamp = ts,
                channels = channels,
                logo = ""
            )
        )
    }

    private fun cleanLabel(s: String): String =
        s.replace(Regex("""\s+"""), " ").trim().trim('-', '·', '•').trim()

    private fun extractDayMonth(s: String): String {
        val m = Regex("""(\d{1,2})/(\d{1,2})""").find(s) ?: return ""
        return "${m.groupValues[1]}/${m.groupValues[2]}"
    }

    private fun mergeEvents(events: List<Event>): List<Event> {
        val byKey = LinkedHashMap<String, Event>()
        events.forEach { ev ->
            val key = eventKey(ev)
            val existing = byKey[key]
            byKey[key] = if (existing == null) ev else existing.copy(
                channels = (existing.channels + ev.channels).distinctBy { it.url },
                logo = existing.logo.ifBlank { ev.logo }
            )
        }
        return byKey.values.sortedWith(compareBy({ it.timestamp }, { it.title }))
    }

    private fun eventKey(ev: Event): String = "${ev.timestamp / 60}|${normalizeTitle(ev.title)}"

    private fun normalizeTitle(title: String): String {
        val normalized = title.lowercase()
            .replace("·", " - ")
            .replace(Regex("""\s+vs\s+"""), " - ")
            .replace(Regex("[^a-z0-9 -]"), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val parts = normalized.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.sorted().joinToString("|")
    }

    /** "29/07" + "20:30" (ora di Roma) → epoch secondi. L'anno viene scelto come il più vicino a oggi. */
    private fun parseDayTimeToTs(dayMonth: String, timeHHmm: String): Long {
        if (dayMonth.isBlank() || !hhmmRegex.matches(timeHHmm)) return 0L
        val dm = Regex("""(\d{1,2})/(\d{1,2})""").find(dayMonth) ?: return 0L
        val hm = timeHHmm.split(":")
        val day = dm.groupValues[1].toIntOrNull() ?: return 0L
        val month = dm.groupValues[2].toIntOrNull() ?: return 0L
        val hour = hm.getOrNull(0)?.toIntOrNull() ?: return 0L
        val minute = hm.getOrNull(1)?.toIntOrNull() ?: return 0L

        val nowCal = Calendar.getInstance(romeTz)
        val nowSec = nowCal.timeInMillis / 1000L
        var best = 0L
        listOf(0, -1, 1).forEach { yearShift ->
            val c = Calendar.getInstance(romeTz).apply {
                set(Calendar.YEAR, nowCal.get(Calendar.YEAR) + yearShift)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val ts = c.timeInMillis / 1000L
            if (best == 0L || kotlin.math.abs(ts - nowSec) < kotlin.math.abs(best - nowSec)) best = ts
        }
        return best
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
            url = eventId(ev),
            type = TvType.Live,
            fix = false
        ) {
            this.posterUrl = posterFor(ev)
        }
    }

    // ============= COPERTINA =============

    /** Copertina verticale: è quella delle card, che ritagliano ai lati un'immagine 16:9. */
    private fun posterFor(ev: Event): String {
        if (ev.logo.isNotBlank()) return ev.logo
        return if (ev.league == alwaysOnLeague) Covers.channelPoster(ev.title)
        else Covers.matchPoster(ev.title, ev.league, formatWhen(ev.timestamp))
    }

    /** Copertina orizzontale: sfondo della pagina di dettaglio. */
    private fun backdropFor(ev: Event): String =
        if (ev.league == alwaysOnLeague) Covers.channelBackdrop(ev.title)
        else Covers.matchBackdrop(ev.title, ev.league, formatWhen(ev.timestamp))

    private fun formatWhen(ts: Long): String {
        if (ts <= 0L) return ""
        val cal = Calendar.getInstance(romeTz).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis / 1000L
        val tomorrowStart = todayStart + 86400L
        val dayAfterStart = tomorrowStart + 86400L
        val time = timeFmt.format(Date(ts * 1000L))
        return when {
            ts < todayStart - 86400L -> {
                SimpleDateFormat("E d/M HH:mm", Locale.ITALY).apply { timeZone = romeTz }
                    .format(Date(ts * 1000L))
            }
            ts < todayStart -> "Ieri $time"
            ts < tomorrowStart -> time
            ts < dayAfterStart -> "Domani $time"
            else -> {
                SimpleDateFormat("E d/M HH:mm", Locale.ITALY).apply { timeZone = romeTz }
                    .format(Date(ts * 1000L))
            }
        }
    }

    // ============= STREAM RESOLVERS =============
    //
    // Le pagine canale (.htm) incorporano un player di terze parti che cambia nome dominio
    // spesso. Invece di una whitelist di host si segue la catena di iframe e si prova a
    // estrarre lo stream a ogni livello; gli unici casi speciali sono i player che
    // espongono lo stream via API JSON invece che nell'HTML.

    private val maxHops = 4
    private val webViewFallbackChannels = 2
    private val webViewTimeoutMs = 20_000L

    private data class Hop(val url: String, val referer: String)

    private suspend fun resolveChannel(ch: Channel): ExtractorLink? =
        resolveDeep(ch.url, "$mainUrl/", ch.name, 0)

    /**
     * Ultima spiaggia: alcuni player (merithotdog.net, dyncompromise.net, …) costruiscono
     * l'url dello stream con JS offuscato, illeggibile lato scraping. Si carica la pagina più
     * interna in una WebView e si intercetta la richiesta della playlist.
     */
    private suspend fun resolveChannelWithWebView(ch: Channel): ExtractorLink? {
        val hops = mutableListOf<Hop>()
        resolveDeep(ch.url, "$mainUrl/", ch.name, 0, hops)?.let { return it }
        val last = hops.lastOrNull() ?: return null

        val intercepted = runCatching {
            WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mpd)"""),
                useOkhttp = false,
                timeout = webViewTimeoutMs
            ).resolveUsingWebView(url = last.url, referer = last.referer).first
        }.getOrNull() ?: return null

        return buildStreamLink(ch.name, intercepted.url.toString(), last.url)
    }

    private suspend fun resolveDeep(
        url: String,
        referer: String,
        chName: String,
        depth: Int,
        hops: MutableList<Hop>? = null
    ): ExtractorLink? {
        if (depth > maxHops) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        hops?.add(Hop(url, referer))

        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to ua), referer = referer).text
        }.getOrNull() ?: return null

        // Player con risoluzione via API JSON (famiglia damitv.st / ondemand.st)
        if (html.contains("/papi/tv/resolve/")) {
            resolvePapiPlayer(url, chName)?.let { return it }
        }

        // Player che carica l'iframe reale via api/player.php?id=N (famiglia nexa.st)
        if (html.contains("api/player.php?id=")) {
            resolveJsonHop(url)?.let { return resolveDeep(it, url, chName, depth + 1, hops) }
        }

        extractStreamUrl(html)?.let { stream ->
            return buildStreamLink(chName, stream, url)
        }

        val next = firstPlayerIframe(html)?.let { normalizeUrl(it, url) } ?: return null
        if (next == url) return null
        return resolveDeep(next, url, chName, depth + 1, hops)
    }

    /**
     * host/embed/channel/?id=CH → host/papi/tv/resolve/CH?t=TOKEN → {"stream":"...m3u8"}
     * Il token viene da ad-session/ad-verify; se non si ottiene si prova comunque a vuoto.
     */
    private suspend fun resolvePapiPlayer(embedUrl: String, chName: String): ExtractorLink? {
        val host = hostOf(embedUrl) ?: return null
        val id = Regex("""[?&]id=([^&#]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: embedUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null

        val headers = mapOf("User-Agent" to ua)
        val token = runCatching {
            val session = app.get("https://$host/papi/ad-session", headers = headers, referer = embedUrl).text
            val sid = Regex(""""s"\s*:\s*"([^"]+)"""").find(session)?.groupValues?.getOrNull(1).orEmpty()
            val verify = app.get(
                "https://$host/papi/ad-verify?s=$sid",
                headers = headers,
                referer = embedUrl
            ).text
            Regex(""""t"\s*:\s*"([^"]+)"""").find(verify)?.groupValues?.getOrNull(1).orEmpty()
        }.getOrNull().orEmpty()

        val json = runCatching {
            app.get(
                "https://$host/papi/tv/resolve/$id?t=$token",
                headers = headers,
                referer = embedUrl
            ).text
        }.getOrNull() ?: return null

        val stream = Regex(""""stream"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
            ?: Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
            ?: return null
        return buildStreamLink(chName, unescapeUrl(stream), embedUrl)
    }

    /** page?id=N → page_dir/api/player.php?id=N → {"url":"https://.../embed.php?..."} */
    private suspend fun resolveJsonHop(pageUrl: String): String? {
        val id = Regex("""[?&]id=([^&#]+)""").find(pageUrl)?.groupValues?.getOrNull(1) ?: return null
        val base = pageUrl.substringBefore('?').substringBeforeLast('/', "")
        if (base.isBlank()) return null
        val json = runCatching {
            app.get(
                "$base/api/player.php?id=$id",
                headers = mapOf("User-Agent" to ua),
                referer = pageUrl
            ).text
        }.getOrNull() ?: return null
        val next = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
            ?: return null
        return normalizeUrl(unescapeUrl(next), pageUrl)
    }

    private fun extractStreamUrl(html: String): String? =
        extractCharArrayUrl(html)
            ?: Regex("""streamUrl\s*[:=]\s*["']([^"']+)["']""").find(html)
                ?.groupValues?.getOrNull(1)?.let { unescapeUrl(it) }
            ?: Regex(
                """(?:file|source|src|hlsUrl|playlist)\s*[:=]\s*["']([^"']+\.(?:m3u8|mpd)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.let { unescapeUrl(it) }
            ?: Regex("""https?:(?:\\?/){2}[^"'\s\\<>]+\.(?:m3u8|mpd)[^"'\s\\<>]*""")
                .find(html)?.value?.let { unescapeUrl(it) }

    /**
     * Alcuni player costruiscono l'url spezzandolo in un array di caratteri:
     *   player.load({source: ["h","t","t","p",...].join("") + document.getElementById("x").innerHTML})
     */
    private fun extractCharArrayUrl(html: String): String? {
        val arrayRegex = Regex("""\[\s*((?:"(?:\\.|[^"\\])*"\s*,\s*)+"(?:\\.|[^"\\])*")\s*]\s*\.join\(\s*""\s*\)""")
        val stringRegex = Regex(""""((?:\\.|[^"\\])*)"""")
        val tailRegex = Regex("""getElementById\(\s*["']([^"']+)["']\s*\)\s*\.innerHTML""")

        arrayRegex.findAll(html).forEach { m ->
            val joined = stringRegex.findAll(m.groupValues[1])
                .joinToString("") { it.groupValues[1] }
                .let { unescapeUrl(it) }
            if (!joined.contains(".m3u8") && !joined.contains(".mpd")) return@forEach

            // eventuale coda presa dall'innerHTML di uno span nascosto
            val tail = tailRegex.find(html.substring(m.range.last + 1).take(300))
                ?.groupValues?.getOrNull(1)
                ?.let { id -> runCatching { Jsoup.parse(html).getElementById(id)?.text()?.trim() }.getOrNull() }
                .orEmpty()
            return joined + tail
        }
        return null
    }

    private val adIframePatterns = listOf(
        "/ads/", "/ad/", "adserver", "doubleclick", "300x250", "300v250", "728x90", "banner"
    )

    private fun firstPlayerIframe(html: String): String? {
        val iframes = runCatching { Jsoup.parse(html).select("iframe[src]") }.getOrNull().orEmpty()
        val candidates = iframes.map { it.attr("src").trim() }
            .filter { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("chrome-extension:") }
            .filter { src -> adIframePatterns.none { src.contains(it, ignoreCase = true) } }
        if (candidates.isEmpty()) return null
        val fullscreen = iframes.firstOrNull { el ->
            el.hasAttr("allowfullscreen") && candidates.contains(el.attr("src").trim())
        }?.attr("src")?.trim()
        return fullscreen ?: candidates.first()
    }

    // ============= HELPERS =============

    private suspend fun buildStreamLink(chName: String, streamUrl: String, pageUrl: String): ExtractorLink? {
        val clean = unescapeUrl(streamUrl)
        if (!clean.startsWith("http")) return null
        val host = hostOf(pageUrl) ?: return null
        return buildM3u8Link(chName, clean, "https://$host/")
    }

    private suspend fun buildM3u8Link(chName: String, streamUrl: String, refererUrl: String): ExtractorLink {
        val origin = Regex("""https?://[^/]+""").find(refererUrl)?.value ?: refererUrl.trimEnd('/')
        val linkType =
            if (streamUrl.substringBefore('?').endsWith(".mpd")) ExtractorLinkType.DASH
            else ExtractorLinkType.M3U8
        return newExtractorLink(
            source = this.name,
            name = chName,
            url = streamUrl,
            type = linkType
        ) {
            this.referer = refererUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to ua,
                "Origin" to origin
            )
        }
    }

    private fun unescapeUrl(url: String): String =
        url.replace("\\/", "/").replace("&amp;", "&").trim()

    private fun hostOf(url: String): String? =
        Regex("""https?://([^/]+)""").find(url)?.groupValues?.getOrNull(1)

    private fun normalizeUrl(url: String, base: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> (hostOf(base)?.let { "https://$it$url" } ?: url)
        else -> {
            val baseDir = base.substringBefore('?').substringBeforeLast("/", base)
            "$baseDir/$url"
        }
    }

    // ============= IDENTITÀ DELL'EVENTO =============
    //
    // L'id contiene solo orario e titolo: prima ci finivano dentro anche le url dei canali e
    // Cloudstream, che in alcune schermate mostra l'id al posto del nome, faceva comparire un
    // link di htsport come titolo. I canali vengono ripresi dal palinsesto al momento dell'uso.

    private fun eventId(ev: Event): String = "${ev.timestamp}§${ev.title}"

    private suspend fun findEvent(id: String): Event? {
        val events = fetchEvents()
        events.firstOrNull { eventId(it) == id }?.let { return it }
        // Se il sito ha corretto l'orario si ripiega sul titolo
        val title = id.substringAfter('§', id)
        return events.firstOrNull { it.title.equals(title, ignoreCase = true) }
    }
}
