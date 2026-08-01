package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import it.zeroTituli.shared.Covers
import it.zeroTituli.shared.LocalProxy
import it.zeroTituli.shared.MatchFilter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * FCTV33 (www.fctv33hd.fit).
 *
 * Il sito è una SPA: partite e flussi arrivano da un'API protobuf aperta. L'm3u8 si ottiene in due
 * richieste (elenco dei canali della partita, poi dettaglio del canale) e passa dal proxy locale
 * che riscrive la playlist. Dettagli e numeri di campo del protobuf:
 * docs/superpowers/specs/2026-07-31-plugin-fctv33-design.md
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
    private val fallbackCountry = "IT"
    private val fallbackContinent = "EU"

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
        val country: String
    ) {
        val title: String get() = "$home - $away"
    }

    @Volatile private var matchCache: List<Match>? = null
    @Volatile private var matchCacheTime = 0L
    private val cacheTtlMs = 60_000L

    @Volatile private var playerDomainsCache: List<String>? = null
    @Volatile private var geoCache: Pair<String, String>? = null

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
        val channels = fetchChannels(match.id)
        if (channels.isEmpty()) return false
        channels.forEach { channel -> callback(buildLink(match, channel, isCasting)) }
        return true
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
     * (10.3.2 nome, 10.4 logo).
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

        return Match(
            id = id,
            timestamp = ts,
            home = names[0],
            away = names[1],
            homeLogo = logos.getOrElse(0) { "" },
            awayLogo = logos.getOrElse(1) { "" },
            league = league,
            country = country
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
        "https://jack31eo.mpcourageny9i9zzipper.my",
        "https://nadia33bc.mp77g69ainei3gx2voxygen.ru",
        "https://morgan01cg.006hndchurch05g7ifbreathing.sbs"
    )

    /**
     * I domini stanno nel campo `foth` di `g_player_domains`, che nella configurazione è una
     * stringa JSON annidata: le virgolette arrivano con la barra rovesciata davanti.
     */
    private suspend fun playerDomains(): List<String> {
        playerDomainsCache?.let { return it }
        val domains = runCatching {
            val raw = app.get(
                "$apiBase/api/common/params",
                headers = mapOf("User-Agent" to ua),
                referer = "$mainUrl/"
            ).text
            val decoded = rot47(raw)
            val fothBlock = Regex("""\\?"foth\\?"\s*:\s*\[(.*?)]""").find(decoded)
                ?.groupValues?.getOrNull(1)
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

    /** Paese e continente decidono quale mirror del CDN serve i segmenti. */
    private suspend fun geo(): Pair<String, String> {
        geoCache?.let { return it }
        val fetched = runCatching {
            val body = Pb.parse(
                app.get(
                    "$apiBase/api/user/info",
                    headers = mapOf("User-Agent" to ua),
                    referer = "$mainUrl/"
                ).body.bytes()
            ).message(10)
            val country = body?.string(2).orEmpty()
            val continent = body?.string(3).orEmpty()
            if (country.isBlank() || continent.isBlank()) null else country to continent
        }.getOrNull()

        val result = fetched ?: (fallbackCountry to fallbackContinent)
        geoCache = result
        return result
    }

    /** Un canale della partita: `1` id, `3` nome, `9` tipo di sorgente. */
    private data class Channel(val id: Long, val name: String, val siteType: Long)

    private suspend fun fetchChannels(matchId: Long): List<Channel> = runCatching {
        val bytes = app.get(
            "$apiBase/api/match/detail?matchId=$matchId&sportType=$footballSportType&stream=true",
            headers = mapOf("User-Agent" to ua),
            referer = "$mainUrl/"
        ).body.bytes()
        Pb.parse(bytes).message(10)?.messages(2).orEmpty().mapNotNull { s ->
            val id = s.long(1) ?: return@mapNotNull null
            val siteType = s.long(9) ?: return@mapNotNull null
            Channel(id, s.string(3).orEmpty(), siteType)
        }.distinctBy { it.id }
    }.getOrNull().orEmpty()

    /**
     * Il dettaglio del canale porta l'm3u8 nel campo `4`, in ROT47 e con otto caratteri di
     * riempimento davanti. Senza paese e continente risponde con l'indirizzo del CDN d'origine,
     * che rifiuta le richieste: servono entrambi.
     */
    private suspend fun playlistUrl(matchId: Long, channel: Channel): String? = runCatching {
        val (country, continent) = geo()
        val bytes = app.get(
            "$apiBase/api/stream/detail?streamId=${channel.id}&matchId=$matchId" +
                "&sportType=$footballSportType&siteType=${channel.siteType}" +
                "&country=$country&continent=$continent",
            headers = mapOf("User-Agent" to ua),
            referer = "$mainUrl/"
        ).body.bytes()
        val encoded = Pb.parse(bytes).message(10)?.message(2)?.string(4)
        encoded?.takeIf { it.length > 8 }?.let { rot47(it).substring(8) }
            ?.takeIf { it.startsWith("http") }
    }.getOrNull()

    /**
     * In riproduzione locale dal proxy passano solo le playlist: i segmenti li scarica il player
     * dai mirror con il `Referer` del dominio del player, che il CDN pretende. In casting il
     * `Referer` lo perderemmo (Cloudstream manda al televisore solo l'indirizzo), quindi passano
     * dal proxy anche i segmenti.
     */
    private suspend fun buildLink(m: Match, channel: Channel, isCasting: Boolean): ExtractorLink {
        val origin = playerDomains().firstOrNull() ?: fallbackPlayerDomains.first()
        val url = LocalProxy.playlist(
            source = { params -> playlist(params) },
            params = mapOf(
                "m" to m.id.toString(),
                "s" to channel.id.toString(),
                "t" to channel.siteType.toString(),
                "c" to if (isCasting) "1" else "0"
            ),
            forCast = isCasting
        )
        val label = listOf(channel.name, m.league).firstOrNull { it.isNotBlank() } ?: this.name
        return newExtractorLink(
            source = this.name,
            name = label,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "$origin/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to ua, "Origin" to origin)
        }
    }

    /** Risponde al proxy: ricalcola l'indirizzo del flusso, scarica la playlist e la riscrive. */
    private suspend fun playlist(params: Map<String, String>): String? {
        val (country, continent) = geo()
        val isCasting = params["c"] == "1"
        val upstream = params["u"] ?: run {
            val matchId = params["m"]?.toLongOrNull() ?: return null
            val streamId = params["s"]?.toLongOrNull() ?: return null
            val siteType = params["t"]?.toLongOrNull() ?: return null
            playlistUrl(matchId, Channel(streamId, "", siteType)) ?: return null
        }
        val origin = playerDomains().firstOrNull() ?: mainUrl
        val body = runCatching {
            app.get(upstream, headers = mapOf("User-Agent" to ua), referer = "$origin/").text
        }.getOrNull()?.takeIf { it.contains("#EXTM3U") } ?: return null

        val cdnHeaders = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        return Csl.rewrite(
            playlist = body,
            base = upstream,
            continent = continent,
            country = country,
            nested = { url ->
                LocalProxy.playlist(
                    source = { p -> playlist(p) },
                    params = mapOf("u" to url, "c" to params["c"].orEmpty()),
                    forCast = isCasting
                )
            },
            segment = { url -> if (isCasting) LocalProxy.raw(url, cdnHeaders, true) else url }
        )
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
