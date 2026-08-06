package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import it.zeroTituli.shared.Covers
import it.zeroTituli.shared.LocalProxy
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
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

    /** Sito di partenza dei canali "premium", da cui si recuperano quelli con il player morto. */
    private val daddyLiveUrl = "https://dlhd.st"

    private val retryDelayMs = 400L

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

        // I player rimasti fuori dalla strada veloce hanno un controllo del browser che vuole
        // JavaScript (le famiglie meritend/dynriver chiedono una prova di lavoro e la verificano
        // lato server). La WebView lo passa, ma costa venti secondi: ci passa solo chi è rimasto
        // fuori, uno alla volta e con un tetto di tempo. Il criterio sta in ChannelFanout.
        val emitted = ChannelFanout.emitAll(
            channels = ev.channels,
            fast = { ch ->
                val stream = resolveChannel(ch) ?: return@emitAll false
                callback(link(ch, stream, isCasting) { resolveChannel(ch)?.url })
                true
            },
            slow = { ch ->
                val stream = resolveChannelWithWebView(ch) ?: return@emitAll false
                callback(link(ch, stream, isCasting) { resolveChannelWithWebView(ch)?.url })
                true
            },
            slowLimit = webViewFallbackChannels,
            slowBudgetMs = webViewBudgetMs,
        )
        return emitted > 0
    }

    /**
     * Il link che finisce nel player.
     *
     * Passa sempre dal proxy locale, per tre motivi diversi che qui coincidono. Gli indirizzi di
     * questi CDN scadono in pochi minuti e il proxy li rinnova senza fermare il flusso, che è la
     * differenza fra vedere la partita e vedere il primo minuto. Gli header (`User-Agent`,
     * `Referer`, `Origin`) li mette il proxy, e in casting è l'unico modo perché Cloudstream manda
     * al televisore solo l'indirizzo (`CastHelper`: `MediaInfo.Builder(link.url)`) e il receiver di
     * Google non li aggiunge. E i manifest DASH arrivano senza `default_KID`, che il proxy inietta.
     */
    private suspend fun link(
        ch: Channel,
        stream: Stream,
        isCasting: Boolean,
        refresh: LocalProxy.LiveSource,
    ): ExtractorLink {
        val clearKey = stream.clearKey
        val proxied = LocalProxy.live(
            key = "${name}:${ch.url}",
            master = stream.url,
            headers = streamHeaders(stream),
            source = refresh,
            // ClearKey vive nel player del telefono: al Chromecast arriverebbe un flusso cifrato
            // e nessuna chiave, quindi non vale la pena esporre il proxy sulla rete locale.
            forCast = isCasting && clearKey == null,
            maxAgeMs = refreshAgeMs(stream.url),
            kid = clearKey?.kid,
        )
        val label = when {
            stream.staleKey -> "${ch.name} · chiavi del sito scadute"
            clearKey != null && isCasting -> "${ch.name} · ClearKey (solo sul telefono)"
            clearKey != null -> "${ch.name} · ClearKey"
            else -> ch.name
        }
        if (clearKey == null) {
            return newExtractorLink(
                source = this.name,
                name = label,
                url = proxied,
                type = stream.type,
            ) {
                this.quality = Qualities.Unknown.value
            }
        }
        return newDrmExtractorLink(
            source = this.name,
            name = label,
            url = proxied,
            type = stream.type,
            uuid = CLEARKEY_UUID,
        ) {
            this.kid = HattrickPlayers.hexToBase64Url(clearKey.kid)
            this.key = HattrickPlayers.hexToBase64Url(clearKey.key)
            this.kty = "oct"
            this.quality = Qualities.Unknown.value
        }
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
            // Le voci "EXT CHROME" rimandano a un'estensione del browser, ma l'indirizzo del
            // flusso e le sue chiavi sono scritti nella pagina: si aprono anche da qui.
            if (href.isBlank() || label.isBlank() || href.startsWith("chrome-extension:")) null
            else Channel(cleanChannelName(label), resolveRelative(href))
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

    /**
     * "Eurosport 1 EXT CHROME 🇮🇹" → "Eurosport 1 🇮🇹". La sigla è un'istruzione per chi guarda
     * dal browser e nell'elenco dei canali non dice niente.
     */
    private fun cleanChannelName(label: String): String =
        label.replace(Regex("""\s*EXT\s*CHROME\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

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
    // Le pagine canale (.htm) incorporano un player di terze parti che cambia nome dominio spesso.
    // Invece di una lista di host si segue la catena di iframe e si prova a estrarre lo stream a
    // ogni livello (i modi in cui lo nascondono stanno in HattrickPlayers). I casi che la catena
    // sola non copre sono quattro, e sono le quattro strade in più di qui sotto:
    //
    //  - player che rispondono via API JSON invece che nell'HTML (famiglie damitv.st e nexa.st);
    //  - pagine "EXT CHROME", che rimandano a un'estensione del browser: nell'iframe c'è
    //    l'indirizzo vero e in coda le chiavi ClearKey, quindi si aprono anche senza estensione,
    //    ma solo se le chiavi pubblicate sono ancora quelle del flusso (vedi `verify`);
    //  - player morti o con il proprio proxy fuori servizio: si ripiega su DaddyLive, quando negli
    //    indirizzi visti c'è il numero del canale;
    //  - player dietro un controllo del browser: quelli richiedono la WebView.

    private val maxHops = 4
    private val webViewFallbackChannels = 3
    private val webViewTimeoutMs = 20_000L

    /** Tetto complessivo per i tentativi con la WebView: oltre, l'attesa non si giustifica. */
    private val webViewBudgetMs = 45_000L
    private val pageAttempts = 2

    /** Pagina intermedia già visitata: serve a ricostruire il referer e a cercare l'id DaddyLive. */
    private data class Hop(val url: String, val referer: String)

    /**
     * Un flusso pronto da suonare.
     *
     * @param referer dominio della pagina dove l'indirizzo è stato trovato: quasi tutti questi CDN
     *   lo pretendono e senza di lui rispondono 403.
     */
    private data class Stream(
        val url: String,
        val referer: String,
        val type: ExtractorLinkType,
        val clearKey: HattrickPlayers.ClearKey? = null,
        /** Il flusso risponde ma le chiavi pubblicate dal sito non sono le sue: vedi [Health]. */
        val staleKey: Boolean = false,
    )

    /**
     * Cosa si è trovato in fondo alla catena.
     *
     * [STALE_KEY] è il caso delle pagine "EXT CHROME": il flusso c'è ed è vivo, ma le chiavi
     * ClearKey che il sito pubblica accanto all'indirizzo sono di una rotazione precedente (le
     * ripubblicano a mano e restano indietro). Prima quei canali venivano scartati insieme a
     * quelli morti e nell'elenco non compariva niente: ora si offrono comunque, con l'avviso nel
     * nome, perché "non funziona e si vede perché" è meglio di "non c'è".
     */
    private enum class Health { PLAYABLE, STALE_KEY, DEAD }

    /**
     * Il flusso di un canale, verificato.
     *
     * Un indirizzo trovato non è un indirizzo che funziona: la catena può finire su un player il
     * cui dominio è stato sequestrato, o su un proxy fuori servizio, o su un canale che non è in
     * onda. Quando la strada normale non porta a niente di suonabile si prova quella di DaddyLive,
     * dove finiscono quasi tutti questi canali e dove il numero lo abbiamo già visto passare.
     */
    private suspend fun resolveChannel(ch: Channel): Stream? {
        val hops = mutableListOf<Hop>()
        val direct = resolveDeep(ch.url, "$mainUrl/", 0, hops)
        var stale: Stream? = null
        if (direct != null) when (health(direct)) {
            Health.PLAYABLE -> return direct
            Health.STALE_KEY -> stale = direct.copy(staleKey = true)
            Health.DEAD -> Unit
        }

        val seen = hops.map { it.url } + listOfNotNull(direct?.url)
        val daddy = resolveDaddy(seen)
        if (daddy != null && health(daddy) == Health.PLAYABLE) return daddy
        // Meglio del niente: il flusso è vivo, le chiavi del sito no.
        return stale
    }

    private suspend fun resolveDaddy(seen: List<String>): Stream? {
        val id = HattrickPlayers.daddyId(seen) ?: return null
        val page = "$daddyLiveUrl/stream/stream-$id.php"
        if (seen.any { it == page }) return null
        return resolveDeep(page, "$daddyLiveUrl/", 0)
    }

    /**
     * Ultima spiaggia: la pagina più interna finisce in una WebView e si intercetta la richiesta
     * della playlist. Serve per i player che costruiscono l'indirizzo con JavaScript offuscato e
     * per quelli protetti da un controllo del browser (`meritend.net`, `*.dynriver.net`: prova di
     * lavoro Cap.js, con verifica lato server di dati che solo un browser vero produce).
     */
    private suspend fun resolveChannelWithWebView(ch: Channel): Stream? {
        // La discesa serve solo ad arrivare all'ultima pagina della catena: quello che
        // eventualmente trova è già stato scartato da resolveChannel, che verifica.
        val hops = mutableListOf<Hop>()
        resolveDeep(ch.url, "$mainUrl/", 0, hops)
        val last = hops.lastOrNull() ?: return null

        val intercepted = runCatching {
            WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mpd)"""),
                useOkhttp = false,
                timeout = webViewTimeoutMs
            ).resolveUsingWebView(url = last.url, referer = last.referer).first
        }.getOrNull() ?: return null

        return streamOf(intercepted.url.toString(), last.url, null)
            ?.takeIf { health(it) == Health.PLAYABLE }
    }

    private suspend fun resolveDeep(
        url: String,
        referer: String,
        depth: Int,
        hops: MutableList<Hop>? = null
    ): Stream? {
        if (depth > maxHops) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        hops?.add(Hop(url, referer))

        val html = fetch(url, referer) ?: return null

        // Player con risoluzione via API JSON (famiglia damitv.st / ondemand.st)
        if (html.contains("/papi/tv/resolve/")) {
            resolvePapiPlayer(url)?.let { return it }
        }

        // Player che carica l'iframe reale via api/player.php?id=N (famiglia nexa.st)
        if (html.contains("api/player.php?id=")) {
            resolveJsonHop(url)?.let { return resolveDeep(it, url, depth + 1, hops) }
        }

        HattrickPlayers.stream(html)?.let { found ->
            streamOf(normalizeUrl(found.url, url), url, found.clearKey)?.let { return it }
        }

        val next = HattrickPlayers.playerIframe(html)?.let { normalizeUrl(it, url) } ?: return null
        if (next == url) return null
        return resolveDeep(next, url, depth + 1, hops)
    }

    /**
     * host/embed/channel/?id=CH → host/papi/tv/resolve/CH?t=TOKEN → {"stream":"...m3u8"}
     *
     * Il token viene da ad-session/ad-verify. Non è obbligatorio (l'API risponde anche a mani
     * vuote) ma costa poco, e quando l'API sbaglia un colpo — capita, è un proxy davanti a
     * DaddyLive — si riprova, perché rinunciare qui vuol dire perdere il canale.
     */
    private suspend fun resolvePapiPlayer(embedUrl: String): Stream? {
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

        repeat(pageAttempts) { attempt ->
            val json = fetch("https://$host/papi/tv/resolve/$id?t=$token", embedUrl, attempts = 1)
            val stream = json?.let {
                Regex(""""stream"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.getOrNull(1)
                    ?: Regex(""""url"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.getOrNull(1)
            }
            if (stream != null) {
                streamOf(HattrickPlayers.unescape(stream), embedUrl, null)?.let { return it }
            }
            if (attempt < pageAttempts - 1) delay(retryDelayMs)
        }
        return null
    }

    /** page?id=N → page_dir/api/player.php?id=N → {"url":"https://.../embed.php?..."} */
    private suspend fun resolveJsonHop(pageUrl: String): String? {
        val id = Regex("""[?&]id=([^&#]+)""").find(pageUrl)?.groupValues?.getOrNull(1) ?: return null
        val base = pageUrl.substringBefore('?').substringBeforeLast('/', "")
        if (base.isBlank()) return null
        val json = fetch("$base/api/player.php?id=$id", pageUrl) ?: return null
        val next = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
            ?: return null
        return normalizeUrl(HattrickPlayers.unescape(next), pageUrl)
    }

    // ============= HELPERS =============

    /**
     * Una GET, ritentata una volta.
     *
     * Questi domini sbagliano un colpo di tanto in tanto (un timeout, un 502 dal proxy davanti al
     * CDN) e senza il secondo tentativo un canale sano sparisce dall'elenco.
     */
    private suspend fun fetch(
        url: String,
        referer: String,
        attempts: Int = pageAttempts,
        headers: Map<String, String> = mapOf("User-Agent" to ua),
    ): String? {
        repeat(attempts) { attempt ->
            val body = runCatching {
                app.get(url, headers = headers, referer = referer).text
            }.getOrNull()
            if (!body.isNullOrBlank()) return body
            if (attempt < attempts - 1) delay(retryDelayMs)
        }
        return null
    }

    private fun streamOf(
        url: String,
        pageUrl: String,
        clearKey: HattrickPlayers.ClearKey?,
    ): Stream? {
        val clean = HattrickPlayers.unescape(url)
        if (!clean.startsWith("http")) return null
        val host = hostOf(pageUrl) ?: return null
        val type =
            if (clean.substringBefore('?').endsWith(".mpd")) ExtractorLinkType.DASH
            else ExtractorLinkType.M3U8
        return Stream(clean, "https://$host/", type, clearKey)
    }

    /**
     * Ogni quanto rifare la strada dall'inizio, prima che l'indirizzo scada.
     *
     * I gettoni scritti nella query vivono cinque minuti (lovetier ne rinnova uno ogni 300
     * secondi): rinnovarli a metà strada evita che il player incontri un rifiuto mentre ha ancora
     * poco buffer da parte. Le firme dentro il percorso valgono ore, e per quelle basta accorgersi
     * del rifiuto quando arriva.
     */
    private fun refreshAgeMs(url: String): Long =
        if (Regex("""[?&](token|tk|md5|hash|sig|key|auth)=""").containsMatchIn(url)) 150_000L
        else 900_000L

    private fun streamHeaders(stream: Stream): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to stream.referer,
        "Origin" to stream.referer.trimEnd('/'),
    )

    /**
     * Controlla che il flusso risponda davvero, prima di offrirlo.
     *
     * La richiesta parte con gli stessi header con cui poi il proxy scarica il flusso, `Origin`
     * compreso: alcuni di questi CDN lo pretendono, e con un header in meno un canale sano
     * rispondeva 403 e veniva buttato via.
     *
     * Per i DASH cifrati controlla anche che la chiave pubblicata sul sito sia una di quelle del
     * flusso: quelle pagine ripubblicano le chiavi a mano e restano indietro quando il canale le
     * cambia, e una chiave sbagliata dà un errore di decifratura, non un messaggio leggibile.
     */
    private suspend fun health(stream: Stream): Health {
        val body = fetch(stream.url, stream.referer, headers = streamHeaders(stream))
            ?: return Health.DEAD
        if (stream.type == ExtractorLinkType.M3U8) {
            return if (body.contains("#EXTM3U")) Health.PLAYABLE else Health.DEAD
        }
        if (!body.contains("<MPD")) return Health.DEAD
        val wanted = stream.clearKey?.kid ?: return Health.PLAYABLE
        val declared = HattrickPlayers.manifestKids(body)
        val kids = declared.ifEmpty { setOfNotNull(contentKid(stream, body)) }
        // Manifest che non dichiara chiavi e segmento di inizializzazione illeggibile: non si sa,
        // e nel dubbio il canale si offre.
        if (kids.isEmpty()) return Health.PLAYABLE
        return if (wanted in kids) Health.PLAYABLE else Health.STALE_KEY
    }

    /** L'identificativo di chiave dei segmenti, quando il manifest non lo dichiara. */
    private suspend fun contentKid(stream: Stream, manifest: String): String? {
        val path = HattrickPlayers.initPath(manifest) ?: return null
        val url = normalizeUrl(path, stream.url)
        val bytes = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to ua),
                referer = stream.referer
            ).body.bytes()
        }.getOrNull() ?: return null
        return HattrickPlayers.tencKid(bytes)
    }

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
