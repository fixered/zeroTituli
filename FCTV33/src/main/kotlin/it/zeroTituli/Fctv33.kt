package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import it.zeroTituli.shared.Covers
import it.zeroTituli.shared.MatchFilter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * FCTV33 (www.fctv33hd.fit).
 *
 * Il sito è una SPA: le partite arrivano da un'API protobuf aperta, i flussi si prendono dalla
 * pagina della partita sul dominio del player, intercettandoli in WebView. Dettagli e numeri di
 * campo del protobuf: docs/superpowers/specs/2026-07-31-plugin-fctv33-design.md
 */
class Fctv33 : MainAPI() {
    override var mainUrl = "https://www.fctv33hd.fit"
    override var name = "FCTV33"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiBase = "https://apis-data10.tcxru135mdqf.ru"
    private val logosBase = "https://logos1.tcxru135mdqf.ru"
    private val footballSportType = 1
    private val locale = "it"
    private val country = "IT"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36"

    private val romeTz: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    private val timeFmt by lazy {
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = romeTz }
    }

    data class Match(
        val id: Long,
        val timestamp: Long,        // secondi
        val home: String,
        val away: String,
        val homeLogo: String,
        val awayLogo: String,
        val league: String,
        val country: String,
        val leagueSlug: String,
        val matchSlug: String
    ) {
        val title: String get() = "$home - $away"
    }

    @Volatile private var matchCache: List<Match>? = null
    @Volatile private var matchCacheTime = 0L
    private val cacheTtlMs = 60_000L

    @Volatile private var playerDomainsCache: List<String>? = null

    // ============= HOME =============

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = fetchMatches()

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

        val live = matches.filter { it.timestamp in liveFrom..nowSec }.sortedByDescending { it.timestamp }
        if (live.isNotEmpty()) {
            sections += HomePageList("🔴 Live Ora", live.map { toSearchResponse(it) })
        }

        val today = matches.filter { it.timestamp in (nowSec + 1L)..(tomorrowStart - 1L) }
        addBandSections(sections, today, "⏰ Oggi")

        val tomorrow = matches.filter { it.timestamp in tomorrowStart..(dayAfterStart - 1L) }
        addBandSections(sections, tomorrow, "📅 Domani")

        val others = matches.filter { it.timestamp < liveFrom || it.timestamp >= dayAfterStart }
            .sortedBy { it.timestamp }
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

    private fun addBandSections(out: MutableList<HomePageList>, matches: List<Match>, prefix: String) {
        val emoji = mapOf("Mattina" to "🌅", "Pomeriggio" to "☀️", "Sera" to "🌙")
        listOf("Mattina", "Pomeriggio", "Sera").forEach { band ->
            val items = matches.filter { bandOf(it.timestamp) == band }
                .sortedBy { it.timestamp }
                .map { toSearchResponse(it) }
            if (items.isNotEmpty()) out += HomePageList("$prefix · ${emoji[band]} $band", items)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchMatches().filter {
            it.title.contains(q, ignoreCase = true) || it.league.contains(q, ignoreCase = true)
        }.map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val match = findMatch(url) ?: throw ErrorLoadingException("Partita non più in palinsesto")
        val time = formatWhen(match.timestamp)
        val plot = buildString {
            if (match.league.isNotBlank()) append(match.league)
            if (time.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(time)
            }
        }
        val id = matchId(match)
        return newLiveStreamLoadResponse(name = match.title, url = id, dataUrl = id) {
            this.plot = plot
            this.posterUrl = Covers.matchPoster(match.title, match.league, time, badgesOf(match))
            this.backgroundPosterUrl =
                Covers.matchBackdrop(match.title, match.league, time, badgesOf(match))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = findMatch(data) ?: return false
        playerDomains().forEach { domain ->
            val page = matchPageUrl(domain, match)
            val stream = runCatching { interceptStream(page) }.getOrNull()
            if (stream != null) {
                callback(buildLink(match, stream, domain))
                return true
            }
        }
        return false
    }

    // ============= API =============

    private suspend fun fetchMatches(): List<Match> {
        val now = System.currentTimeMillis()
        matchCache?.let { if (now - matchCacheTime < cacheTtlMs) return it }

        val bytes = app.get(
            "$apiBase/api/match/live?sportType=$footballSportType",
            headers = mapOf("User-Agent" to ua),
            referer = "$mainUrl/"
        ).body.bytes()

        val body = Pb.parse(bytes).message(10) ?: return emptyList()
        val parsed = body.messages(1).mapNotNull { parseMatch(it) }
            .filter { MatchFilter.isInteresting(it.league, it.country, it.home, it.away) }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.timestamp }, { it.title }))

        matchCache = parsed
        matchCacheTime = now
        return parsed
    }

    /**
     * Campi: 1 id, 3 orario in ms, 10 campionato (3.2 nome, 80.3.2 paese), 30 squadre
     * (10.3.2 nome, 10.4 logo), 150 slug (20 partita, 21 campionato).
     */
    private fun parseMatch(m: Pb): Match? {
        val id = m.long(1) ?: return null
        val ts = (m.long(3) ?: 0L) / 1000L
        val leagueBlock = m.message(10)
        val league = leagueBlock?.stringAt(3, 2).orEmpty()
        val country = leagueBlock?.stringAt(80, 3, 2).orEmpty()

        val teamBlocks = m.messages(30).mapNotNull { it.message(10) }
        val names = teamBlocks.map { it.stringAt(3, 2).orEmpty() }
        val logos = teamBlocks.map { fixLogoUrl(it.string(4).orEmpty()) }
        if (names.size < 2 || names[0].isBlank() || names[1].isBlank()) return null

        val seo = m.message(150)
        return Match(
            id = id,
            timestamp = ts,
            home = names[0],
            away = names[1],
            homeLogo = logos.getOrElse(0) { "" },
            awayLogo = logos.getOrElse(1) { "" },
            league = league,
            country = country,
            leagueSlug = seo?.string(21).orEmpty(),
            matchSlug = seo?.string(20).orEmpty()
        )
    }

    /**
     * L'API restituisce i loghi su un host che non risolve (logos1.tcrbg61levl.cfd): il percorso
     * è però lo stesso dell'host buono, quello della configurazione del sito. Il suffisso "!w80"
     * è un ridimensionamento che l'host ignora.
     */
    private fun fixLogoUrl(url: String): String {
        if (!url.startsWith("http")) return ""
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('!')
        return if (path.isBlank()) "" else "$logosBase/$path"
    }

    // ============= FLUSSO =============

    /**
     * I domini del player stanno nella configurazione del sito, che arriva come JSON codificato
     * ROT47. Se la richiesta non riesce si usa la lista cucinata qui, aggiornata a mano.
     */
    private val fallbackPlayerDomains = listOf(
        "https://jack29eo.mpcourageny9i9zzipper.my",
        "https://nadia31bc.mp77g69ainei3gx2voxygen.ru",
        "https://morgan97cf.006hndchurch05g7ifbreathing.sbs"
    )

    private suspend fun playerDomains(): List<String> {
        playerDomainsCache?.let { return it }
        val domains = runCatching {
            val raw = app.get(
                "$apiBase/api/common/params",
                headers = mapOf("User-Agent" to ua),
                referer = "$mainUrl/"
            ).text
            val decoded = rot47(raw)
            val fothBlock = Regex(""""foth"\s*:\s*\[(.*?)]""").find(decoded)?.groupValues?.getOrNull(1)
            Regex("""https?:\\?/\\?/[^"\\,\s]+""").findAll(fothBlock.orEmpty())
                .map { it.value.replace("\\/", "/").trimEnd('/') }
                .toList()
        }.getOrNull().orEmpty()

        val result = (domains + fallbackPlayerDomains).distinct()
        playerDomainsCache = result
        return result
    }

    /** La configurazione del sito è cifrata con ROT47, non è un formato binario. */
    private fun rot47(s: String): String = buildString(s.length) {
        s.forEach { c ->
            val code = c.code
            append(if (code in 33..126) ((code - 33 + 47) % 94 + 33).toChar() else c)
        }
    }

    /** {dominio}/{lingua}/football/{slugLega}-{matchId}/{slugPartita}.html?icg={paese} */
    private fun matchPageUrl(domain: String, m: Match): String {
        val leagueSlug = m.leagueSlug.ifBlank { "football" }
        val matchSlug = m.matchSlug.ifBlank { slugify("${m.home} vs ${m.away}") }
        return "$domain/$locale/football/$leagueSlug-${m.id}/$matchSlug.html?icg=$country"
    }

    private fun slugify(s: String): String = Covers.normalizeLoose(s).replace(" ", "-")

    private suspend fun interceptStream(pageUrl: String): String? {
        val request = WebViewResolver(
            interceptUrl = Regex("""\.(m3u8|flv)"""),
            useOkhttp = false,
            timeout = 25_000L
        ).resolveUsingWebView(url = pageUrl, referer = "$mainUrl/").first
        return request?.url?.toString()
    }

    private suspend fun buildLink(m: Match, streamUrl: String, domain: String): ExtractorLink {
        val origin = Regex("""https?://[^/]+""").find(domain)?.value ?: domain
        val type =
            if (streamUrl.substringBefore('?').endsWith(".flv")) ExtractorLinkType.VIDEO
            else ExtractorLinkType.M3U8
        return newExtractorLink(
            source = this.name,
            name = m.league.ifBlank { this.name },
            url = streamUrl,
            type = type
        ) {
            this.referer = "$origin/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to ua, "Origin" to origin)
        }
    }

    // ============= VOCI =============

    private fun toSearchResponse(m: Match): LiveSearchResponse {
        val whenStr = formatWhen(m.timestamp)
        val meta = listOf(whenStr, m.league).filter { it.isNotBlank() }.joinToString(" · ")
        val label = if (meta.isNotBlank()) "${m.title}\n$meta" else m.title
        return newLiveSearchResponse(
            name = label,
            url = matchId(m),
            type = TvType.Live,
            fix = false
        ) {
            this.posterUrl = Covers.matchPoster(m.title, m.league, whenStr, badgesOf(m))
        }
    }

    private fun badgesOf(m: Match): List<String> =
        listOf(m.homeLogo, m.awayLogo).filter { it.startsWith("http") }

    /** Nell'id non finiscono url: alcune schermate lo mostrano al posto del nome. */
    private fun matchId(m: Match): String = "${m.id}§${m.title}"

    private suspend fun findMatch(id: String): Match? {
        val wanted = id.substringBefore('§').toLongOrNull()
        val matches = fetchMatches()
        matches.firstOrNull { it.id == wanted }?.let { return it }
        val title = id.substringAfter('§', id)
        return matches.firstOrNull { it.title.equals(title, ignoreCase = true) }
    }

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
            ts < todayStart - 86400L || ts >= dayAfterStart ->
                SimpleDateFormat("E d/M HH:mm", Locale.ITALY).apply { timeZone = romeTz }
                    .format(Date(ts * 1000L))
            ts < todayStart -> "Ieri $time"
            ts < tomorrowStart -> time
            else -> "Domani $time"
        }
    }
}
