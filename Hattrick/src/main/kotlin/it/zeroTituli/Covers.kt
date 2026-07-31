package it.zeroTituli

import java.net.URLEncoder
import java.text.Normalizer

/**
 * Copertine degli eventi.
 *
 * htsport.org non pubblica immagini, quindi la copertina viene composta al volo da tabelle
 * raccolte offline (vedi docs/superpowers/specs/2026-07-31-copertine-eventi-design.md): loghi
 * squadra, badge competizione, loghi dei canali e colori sociali. Il plugin non fa nessuna
 * richiesta HTTP: costruisce solo l'url, che il caricatore di immagini dell'app scarica una
 * volta per card e tiene in cache.
 *
 * Tutto sta in una sola immagine di placehold.jp: il parametro css porta i loghi come
 * background-image, il fondo diviso a metà con i due colori delle squadre e una banda scura
 * dietro al testo, così il titolo resta leggibile su qualsiasi colore.
 *
 * Due formati: [matchPoster] verticale per le card della home (che ritagliano il 16:9) e
 * [matchBackdrop] orizzontale per lo sfondo della pagina di dettaglio.
 */
internal object Covers {

    private const val TEXT_COLOR = "ffffff"
    private const val CHANNEL_BG = "141821"

    /** Scudo generico per le squadre che non sono in tabella. */
    private const val STOCK_BADGE =
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/svg/1f6e1.svg"

    private val palette = listOf(
        "1f6f8b", "8b1f3f", "2e6f40", "6b3fa0", "a85b00", "1b4f8f", "8f6b1b", "4a4a6a"
    )

    /** Misure e posizioni dei layer, in percentuale sulla tela. */
    private data class Layout(
        val size: String,
        val font: Int,
        val logoSize: String,
        val logoLeft: String,
        val logoRight: String,
        val logoOneSize: String,
        val logoOnePos: String,
        val bandSize: String,
        val bandPos: String,
        val titleChars: Int,
        val metaChars: Int
    )

    private val portrait = Layout(
        size = "400x600", font = 22,
        logoSize = "44% auto", logoLeft = "4% 22%", logoRight = "96% 22%",
        logoOneSize = "40% auto", logoOnePos = "50% 22%",
        bandSize = "100% 22%", bandPos = "0 44%",
        titleChars = 24, metaChars = 28
    )

    private val landscape = Layout(
        size = "600x338", font = 22,
        logoSize = "26% auto", logoLeft = "6% 14%", logoRight = "94% 14%",
        logoOneSize = "24% auto", logoOnePos = "50% 12%",
        bandSize = "100% 30%", bandPos = "0 52%",
        titleChars = 30, metaChars = 34
    )

    // ============= API =============

    fun matchPoster(title: String, league: String, whenLabel: String): String =
        matchCover(portrait, title, league, whenLabel)

    fun matchBackdrop(title: String, league: String, whenLabel: String): String =
        matchCover(landscape, title, league, whenLabel)

    fun channelPoster(channelName: String): String = channelCover(portrait, channelName)

    fun channelBackdrop(channelName: String): String = channelCover(landscape, channelName)

    // ============= COMPOSIZIONE =============

    private fun matchCover(l: Layout, title: String, league: String, whenLabel: String): String {
        val meta = listOf(whenLabel, shortLeague(league))
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val teams = splitTeams(title)

        if (teams.size == 2) {
            val keyA = teamKey(teams[0])
            val keyB = teamKey(teams[1])
            // Basta che una delle due sia riconosciuta: l'altra prende lo scudo generico.
            if (keyA != null || keyB != null) {
                val badges = listOf(
                    keyA?.let { teamLogos[it] } ?: STOCK_BADGE,
                    keyB?.let { teamLogos[it] } ?: STOCK_BADGE
                )
                val colors = pickColors(keyA, teams[0], keyB, teams[1])
                return build(l, badges, colors, title, meta)
            }
        }

        val badge = leagueBadge(league) ?: STOCK_BADGE
        val nameA = teams.firstOrNull() ?: title
        val nameB = teams.getOrNull(1) ?: league
        return build(l, listOf(badge), pickColors(null, nameA, null, nameB), title, meta)
    }

    private fun channelCover(l: Layout, channelName: String): String {
        val badge = channelBadge(channelName)
        val logos = if (badge != null) listOf(badge) else emptyList()
        return build(l, logos, CHANNEL_BG to CHANNEL_BG, channelName, "")
    }

    private fun build(
        l: Layout,
        logos: List<String>,
        colors: Pair<String, String>,
        title: String,
        meta: String
    ): String {
        val layers = mutableListOf<String>()
        val sizes = mutableListOf<String>()
        val positions = mutableListOf<String>()

        when {
            logos.size >= 2 -> {
                layers += "url(${logos[0]})"
                layers += "url(${logos[1]})"
                sizes += l.logoSize
                sizes += l.logoSize
                positions += l.logoLeft
                positions += l.logoRight
            }
            logos.size == 1 -> {
                layers += "url(${logos[0]})"
                sizes += l.logoOneSize
                positions += l.logoOnePos
            }
        }

        // banda scura dietro al testo: il titolo deve restare leggibile su qualunque colore
        layers += "linear-gradient(rgba(0,0,0,0.72), rgba(0,0,0,0.72))"
        sizes += l.bandSize
        positions += l.bandPos

        val (colorA, colorB) = colors
        layers += if (colorA == colorB) "linear-gradient(#$colorA, #$colorA)"
        else "linear-gradient(90deg, #$colorA 50%, #$colorB 50%)"
        sizes += "100% 100%"
        positions += "center"

        val css = buildString {
            append("{")
            append("\"background-image\":\"").append(layers.joinToString(", ")).append("\",")
            append("\"background-size\":\"").append(sizes.joinToString(", ")).append("\",")
            append("\"background-position\":\"").append(positions.joinToString(", ")).append("\",")
            append("\"background-repeat\":\"no-repeat\",")
            append("\"text-shadow\":\"0 2px 4px rgba(0,0,0,0.9)\"")
            append("}")
        }

        val lines = wrap(cleanText(title), l.titleChars, 2) +
            (if (cleanText(meta).isNotBlank()) wrap(cleanText(meta), l.metaChars, 1) else emptyList())
        val text = lines.joinToString("\n")

        return "https://placehold.jp/${l.font}/000000/$TEXT_COLOR/${l.size}.png" +
            "?text=${enc(text)}&css=${enc(css)}"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Sul fondo non devono finire url o separatori interni. */
    private fun cleanText(s: String): String = s
        .replace(Regex("""https?://\S+"""), " ")
        .replace(Regex("""[§¤¦]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Il sito scrive "CALCIO - AMICHEVOLI": sulla copertina basta la parte specifica. */
    private fun shortLeague(league: String): String {
        val parts = league.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.drop(1).joinToString(" - ") else league.trim()
    }

    private fun wrap(text: String, lineChars: Int, maxLines: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        text.split(" ").forEach { word ->
            when {
                current.isEmpty() -> current.append(word)
                current.length + 1 + word.length <= lineChars -> current.append(' ').append(word)
                else -> {
                    lines += current.toString()
                    current.setLength(0)
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        val last = kept[maxLines - 1]
        kept[maxLines - 1] = if (last.length < lineChars) "$last…" else last.dropLast(1) + "…"
        return kept
    }

    // ============= COLORI =============

    /**
     * Un colore per squadra, mai due uguali: si scorrono i colori sociali (fino a tre per
     * squadra) preferendo i primari e si tiene la prima coppia abbastanza distante. Se nessuna
     * coppia lo è, il secondo colore viene schiarito o scurito.
     */
    private fun pickColors(
        keyA: String?,
        nameA: String,
        keyB: String?,
        nameB: String
    ): Pair<String, String> {
        val listA = colorsFor(keyA, nameA)
        val listB = colorsFor(keyB, nameB)
        var best: Pair<String, String>? = null
        var bestRank = Int.MAX_VALUE
        listA.forEachIndexed { i, a ->
            listB.forEachIndexed { j, b ->
                val d = distance(a, b)
                if (d >= minColorDistance) {
                    if (i + j < bestRank) {
                        bestRank = i + j
                        best = a to b
                    }
                }
            }
        }
        best?.let { return it }
        val a = listA.first()
        val b = listB.first()
        return a to shiftColor(if (a == b) b else b)
    }

    private const val minColorDistance = 150

    private fun colorsFor(key: String?, name: String): List<String> {
        val stored = key?.let { teamColors[it] }?.split(",")?.filter { it.length == 6 }
        return if (stored != null && stored.isNotEmpty()) stored else listOf(paletteFor(name))
    }

    private fun paletteFor(name: String): String {
        val basis = normalize(name).ifBlank { name }
        var h = 0
        basis.forEach { c -> h = (h * 31 + c.code) and 0xFFFFFF }
        return palette[h % palette.size]
    }

    private fun distance(a: String, b: String): Int {
        fun comp(s: String, i: Int) = s.substring(i, i + 2).toIntOrNull(16) ?: 0
        return (0..4 step 2).sumOf { kotlin.math.abs(comp(a, it) - comp(b, it)) }
    }

    /** Scurisce i colori chiari e schiarisce quelli scuri, per staccare dall'altra metà. */
    private fun shiftColor(color: String): String {
        fun comp(i: Int) = color.substring(i, i + 2).toIntOrNull(16) ?: 0
        val r = comp(0)
        val g = comp(2)
        val b = comp(4)
        val luminance = (r * 299 + g * 587 + b * 114) / 255000.0
        val factor = if (luminance > 0.5) 0.45 else 2.2
        fun shift(v: Int) = (v * factor).toInt().coerceIn(20, 255)
        return "%02x%02x%02x".format(shift(r), shift(g), shift(b))
    }

    // ============= RICERCA LOGHI =============

    private fun splitTeams(title: String): List<String> {
        val cleaned = cleanText(title)
            .replace(Regex("""\s+vs\.?\s+""", RegexOption.IGNORE_CASE), " - ")
            .replace(" – ", " - ")
            .replace(" · ", " - ")
        val parts = cleaned.split(" - ").map { it.trim() }.filter { it.length >= 2 }
        return if (parts.size == 2) parts else emptyList()
    }

    /**
     * Il sito scrive i nomi in mille modi ("AS Roma", "FC Internazionale", "Man Utd"): si
     * normalizza togliendo sigle e anni, poi si prova match esatto, per parole intere e per
     * sottoinsieme di parole.
     */
    private fun teamKey(rawName: String): String? {
        val key = normalize(rawName)
        if (key.length < 3) return null
        if (teamLogos.containsKey(key)) return key

        // parole intere: "Romania" non deve pescare la Roma
        val padded = " $key "
        teamLogos.keys
            .filter { it.length >= 5 && (padded.contains(" $it ") || " $it ".contains(padded)) }
            .maxByOrNull { it.length }
            ?.let { return it }

        // sottoinsieme di parole: "FC Internazionale Milano" → "internazionale"
        val queryWords = key.split(" ").toSet()
        var best: String? = null
        var bestShared = 0
        teamLogos.keys.forEach { candidate ->
            val words = candidate.split(" ").toSet()
            if (!words.containsAll(queryWords) && !queryWords.containsAll(words)) return@forEach
            val shared = words.intersect(queryWords)
            if (shared.none { it.length >= 4 }) return@forEach
            if (shared.size > bestShared) {
                bestShared = shared.size
                best = candidate
            }
        }
        return best
    }

    private fun leagueBadge(league: String): String? {
        val key = normalizeLoose(league)
        if (key.isBlank()) return null
        return leagueLogos.firstOrNull { (kw, _) -> containsWords(key, kw) }?.second
    }

    private fun channelBadge(channelName: String): String? {
        val key = normalizeLoose(channelName)
        if (key.isBlank()) return null
        return channelLogos.firstOrNull { (kw, _) -> containsWords(key, kw) }?.second
    }

    /**
     * Confronto su parole intere: senza questo "Eurosport 1" pescherebbe il logo di
     * "Sport 1", perché la keyword ci finisce dentro come sottostringa.
     */
    private fun containsWords(haystack: String, needle: String): Boolean =
        needle.isNotBlank() && " $haystack ".contains(" $needle ")

    private val noiseWords = setOf(
        "fc", "ac", "as", "cf", "sc", "ss", "ssc", "us", "usd", "asd", "ssd", "fbc", "ogc",
        "sv", "vfl", "vfb", "vfr", "bsc", "tsg", "tsv", "calcio", "club", "cp", "sad",
        "afc", "cfc", "hsv", "if", "ff", "bk", "sk", "rc", "rcd", "cd", "ud", "sd",
        "fk", "nk", "hk", "kv", "rsc", "rkc", "acf", "aca"
    )

    private val yearRegex = Regex("""^(1[89]|20)\d\d$""")

    /** Normalizzazione dei nomi squadra: stessa usata per generare le chiavi della tabella. */
    fun normalize(s: String): String {
        val tokens = normalizeLoose(s).split(" ").filter { it.isNotBlank() && !yearRegex.matches(it) }
        val kept = tokens.filter { it !in noiseWords }
        return (if (kept.isNotEmpty()) kept else tokens).joinToString(" ")
    }

    /**
     * Variante senza rimozione delle sigle, per campionati e canali: lì "calcio" è
     * informazione, non rumore.
     */
    fun normalizeLoose(s: String): String {
        val noAccents = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return noAccents
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    // ============= TABELLE (generate offline) =============

    /** Badge squadre (TheSportsDB), chiave = nome normalizzato, alias italiani inclusi. */
    private val teamLogos: Map<String, String> = mapOf(
        "aberdeen" to "https://r2.thesportsdb.com/images/media/team/badge/f9s6vg1781155578.png",
        "aek atene" to "https://r2.thesportsdb.com/images/media/team/badge/4nogst1602773624.png",
        "aek athens" to "https://r2.thesportsdb.com/images/media/team/badge/4nogst1602773624.png",
        "ajax" to "https://r2.thesportsdb.com/images/media/team/badge/zg9tii1755495289.png",
        "alaves" to "https://r2.thesportsdb.com/images/media/team/badge/mfn99h1734673842.png",
        "albania" to "https://r2.thesportsdb.com/images/media/team/badge/vonacg1717365654.png",
        "algeria" to "https://r2.thesportsdb.com/images/media/team/badge/rrwpry1455460218.png",
        "alkmaar" to "https://r2.thesportsdb.com/images/media/team/badge/wtqwvv1473534757.png",
        "amburgo" to "https://r2.thesportsdb.com/images/media/team/badge/tvtppt1473453296.png",
        "anadolu efes" to "https://r2.thesportsdb.com/images/media/team/badge/uldz0d1782050729.png",
        "anderlecht" to "https://r2.thesportsdb.com/images/media/team/badge/auindn1771129464.png",
        "angers" to "https://r2.thesportsdb.com/images/media/team/badge/ix6q4w1678808069.png",
        "antwerp" to "https://r2.thesportsdb.com/images/media/team/badge/gawwcf1691182178.png",
        "anversa" to "https://r2.thesportsdb.com/images/media/team/badge/gawwcf1691182178.png",
        "apoel" to "https://r2.thesportsdb.com/images/media/team/badge/j5m0pu1779579095.png",
        "apoel nicosia" to "https://r2.thesportsdb.com/images/media/team/badge/j5m0pu1779579095.png",
        "arabia saudita" to "https://r2.thesportsdb.com/images/media/team/badge/24xwpq1594125742.png",
        "argentina" to "https://r2.thesportsdb.com/images/media/team/badge/3zplhu1726167477.png",
        "armani milano" to "https://www.thesportsdb.com/images/media/team/badge/ju4ohs1782551366.png",
        "arsenal" to "https://r2.thesportsdb.com/images/media/team/badge/uyhbfe1612467038.png",
        "aston villa" to "https://www.thesportsdb.com/images/media/team/badge/97mehy1784645865.png",
        "asvel" to "https://r2.thesportsdb.com/images/media/team/badge/qbaoia1602706639.png",
        "atalanta" to "https://r2.thesportsdb.com/images/media/team/badge/qix5ku1780561327.png",
        "athletic bilbao" to "https://r2.thesportsdb.com/images/media/team/badge/68w7fe1639408210.png",
        "atlanta hawks" to "https://r2.thesportsdb.com/images/media/team/badge/7o1noy1778226315.png",
        "atletico" to "https://r2.thesportsdb.com/images/media/team/badge/0ulh3q1719984315.png",
        "atletico madrid" to "https://r2.thesportsdb.com/images/media/team/badge/0ulh3q1719984315.png",
        "augsburg" to "https://r2.thesportsdb.com/images/media/team/badge/xqyyvq1473453233.png",
        "augusta" to "https://r2.thesportsdb.com/images/media/team/badge/xqyyvq1473453233.png",
        "australia" to "https://r2.thesportsdb.com/images/media/team/badge/eylq8x1781926138.png",
        "austria" to "https://r2.thesportsdb.com/images/media/team/badge/874p631628721400.png",
        "auxerre" to "https://r2.thesportsdb.com/images/media/team/badge/lzdtbf1658753355.png",
        "az alkmaar" to "https://r2.thesportsdb.com/images/media/team/badge/wtqwvv1473534757.png",
        "barcellona" to "https://r2.thesportsdb.com/images/media/team/badge/wq9sir1639406443.png",
        "barcelona" to "https://r2.thesportsdb.com/images/media/team/badge/wq9sir1639406443.png",
        "bari" to "https://r2.thesportsdb.com/images/media/team/badge/isfrtg1579724972.png",
        "basel" to "https://r2.thesportsdb.com/images/media/team/badge/xppxwr1473791183.png",
        "basilea" to "https://r2.thesportsdb.com/images/media/team/badge/xppxwr1473791183.png",
        "baskonia" to "https://r2.thesportsdb.com/images/media/team/badge/p4x3o61767366090.png",
        "bayer leverkusen" to "https://r2.thesportsdb.com/images/media/team/badge/3x9k851726760113.png",
        "bayern" to "https://r2.thesportsdb.com/images/media/team/badge/01ogkh1716960412.png",
        "bayern monaco" to "https://r2.thesportsdb.com/images/media/team/badge/01ogkh1716960412.png",
        "bayern munich" to "https://r2.thesportsdb.com/images/media/team/badge/01ogkh1716960412.png",
        "bc zalgiris" to "https://r2.thesportsdb.com/images/media/team/badge/dn7ouv1703960565.png",
        "belgio" to "https://r2.thesportsdb.com/images/media/team/badge/8xlvxv1592062265.png",
        "belgium" to "https://r2.thesportsdb.com/images/media/team/badge/8xlvxv1592062265.png",
        "benfica" to "https://r2.thesportsdb.com/images/media/team/badge/hj4kyc1781152436.png",
        "berna" to "https://r2.thesportsdb.com/images/media/team/badge/9mxdoo1534784569.png",
        "besiktas" to "https://r2.thesportsdb.com/images/media/team/badge/svo05k1776827439.png",
        "betis" to "https://r2.thesportsdb.com/images/media/team/badge/2oqulv1663245386.png",
        "bilbao" to "https://r2.thesportsdb.com/images/media/team/badge/68w7fe1639408210.png",
        "birmingham" to "https://r2.thesportsdb.com/images/media/team/badge/wufs551672950865.png",
        "birmingham city" to "https://r2.thesportsdb.com/images/media/team/badge/wufs551672950865.png",
        "bod glimt" to "https://r2.thesportsdb.com/images/media/team/badge/uqpwwx1449165943.png",
        "bodo glimt" to "https://r2.thesportsdb.com/images/media/team/badge/uqpwwx1449165943.png",
        "bologna" to "https://r2.thesportsdb.com/images/media/team/badge/2qi1u31655592366.png",
        "borussia dortmund" to "https://r2.thesportsdb.com/images/media/team/badge/tqo8ge1716960353.png",
        "borussia m gladbach" to "https://r2.thesportsdb.com/images/media/team/badge/sysurw1473453380.png",
        "borussia monchengladbach" to "https://r2.thesportsdb.com/images/media/team/badge/sysurw1473453380.png",
        "boston celtics" to "https://r2.thesportsdb.com/images/media/team/badge/4j85bn1667936589.png",
        "bournemouth" to "https://r2.thesportsdb.com/images/media/team/badge/y08nak1534071116.png",
        "br ndby" to "https://www.thesportsdb.com/images/media/team/badge/usxoux1784825763.png",
        "braga" to "https://r2.thesportsdb.com/images/media/team/badge/8g4aod1678717261.png",
        "brasile" to "https://r2.thesportsdb.com/images/media/team/badge/jl6dip1726167280.png",
        "brazil" to "https://r2.thesportsdb.com/images/media/team/badge/jl6dip1726167280.png",
        "brema" to "https://r2.thesportsdb.com/images/media/team/badge/tkvqan1716960454.png",
        "brentford" to "https://r2.thesportsdb.com/images/media/team/badge/grv1aw1546453779.png",
        "brest" to "https://r2.thesportsdb.com/images/media/team/badge/z69be41598797026.png",
        "brighton" to "https://r2.thesportsdb.com/images/media/team/badge/ywypts1448810904.png",
        "brighton and hove albion" to "https://r2.thesportsdb.com/images/media/team/badge/ywypts1448810904.png",
        "brondby" to "https://www.thesportsdb.com/images/media/team/badge/usxoux1784825763.png",
        "brooklyn nets" to "https://r2.thesportsdb.com/images/media/team/badge/hkafe61739948361.png",
        "bruges" to "https://r2.thesportsdb.com/images/media/team/badge/mz8y0q1771129880.png",
        "brugge" to "https://r2.thesportsdb.com/images/media/team/badge/mz8y0q1771129880.png",
        "bulgaria" to "https://r2.thesportsdb.com/images/media/team/badge/0bee7p1552858893.png",
        "burnley" to "https://r2.thesportsdb.com/images/media/team/badge/ql7nl31686893820.png",
        "cagliari" to "https://r2.thesportsdb.com/images/media/team/badge/wvsvxt1447534471.png",
        "cameroon" to "https://r2.thesportsdb.com/images/media/team/badge/txqspw1455463989.png",
        "camerun" to "https://r2.thesportsdb.com/images/media/team/badge/txqspw1455463989.png",
        "canada" to "https://r2.thesportsdb.com/images/media/team/badge/2t631f1595154867.png",
        "catanzaro" to "https://r2.thesportsdb.com/images/media/team/badge/byrc5e1691995858.png",
        "cechia" to "https://r2.thesportsdb.com/images/media/team/badge/1o0cx31654205806.png",
        "celta" to "https://r2.thesportsdb.com/images/media/team/badge/xfjtku1690436219.png",
        "celta vigo" to "https://r2.thesportsdb.com/images/media/team/badge/xfjtku1690436219.png",
        "celtic" to "https://r2.thesportsdb.com/images/media/team/badge/3uv1641758780002.png",
        "charlotte hornets" to "https://r2.thesportsdb.com/images/media/team/badge/xqtvvp1422380623.png",
        "chelsea" to "https://www.thesportsdb.com/images/media/team/badge/pbf4ul1782638263.png",
        "chicago bulls" to "https://r2.thesportsdb.com/images/media/team/badge/ehq8l31778197349.png",
        "chile" to "https://r2.thesportsdb.com/images/media/team/badge/5xjsy41591988732.png",
        "china" to "https://r2.thesportsdb.com/images/media/team/badge/pycrnz1710606856.png",
        "cile" to "https://r2.thesportsdb.com/images/media/team/badge/5xjsy41591988732.png",
        "cina" to "https://r2.thesportsdb.com/images/media/team/badge/pycrnz1710606856.png",
        "cleveland cavaliers" to "https://r2.thesportsdb.com/images/media/team/badge/pch9ct1778195828.png",
        "colombia" to "https://r2.thesportsdb.com/images/media/team/badge/4ymyku1691180081.png",
        "colonia" to "https://r2.thesportsdb.com/images/media/team/badge/2j1sc91566049407.png",
        "como" to "https://r2.thesportsdb.com/images/media/team/badge/02x81t1627405841.png",
        "copenaghen" to "https://r2.thesportsdb.com/images/media/team/badge/styqtr1473535513.png",
        "copenhagen" to "https://r2.thesportsdb.com/images/media/team/badge/styqtr1473535513.png",
        "corea del sud" to "https://r2.thesportsdb.com/images/media/team/badge/a8nqfs1589564916.png",
        "costa d avorio" to "https://r2.thesportsdb.com/images/media/team/badge/rwxuuu1455465643.png",
        "cremonese" to "https://r2.thesportsdb.com/images/media/team/badge/6ng2vy1579708291.png",
        "croatia" to "https://r2.thesportsdb.com/images/media/team/badge/vvtsyu1455465317.png",
        "croazia" to "https://r2.thesportsdb.com/images/media/team/badge/vvtsyu1455465317.png",
        "crvena zvezda" to "https://r2.thesportsdb.com/images/media/team/badge/osgmbz1781157114.png",
        "crystal palace" to "https://r2.thesportsdb.com/images/media/team/badge/ia6i3m1656014992.png",
        "czech republic" to "https://r2.thesportsdb.com/images/media/team/badge/1o0cx31654205806.png",
        "dallas mavericks" to "https://r2.thesportsdb.com/images/media/team/badge/s5dx7c1778197536.png",
        "danimarca" to "https://r2.thesportsdb.com/images/media/team/badge/e13arj1717365623.png",
        "denmark" to "https://r2.thesportsdb.com/images/media/team/badge/e13arj1717365623.png",
        "denver nuggets" to "https://r2.thesportsdb.com/images/media/team/badge/s8ch7m1778197814.png",
        "deportivo alaves" to "https://r2.thesportsdb.com/images/media/team/badge/mfn99h1734673842.png",
        "detroit pistons" to "https://r2.thesportsdb.com/images/media/team/badge/lg7qrc1621594751.png",
        "dinamo kiev" to "https://r2.thesportsdb.com/images/media/team/badge/ktbncx1781158762.png",
        "dinamo minsk" to "https://r2.thesportsdb.com/images/media/team/badge/j99ran1615576087.png",
        "dinamo zagreb" to "https://www.thesportsdb.com/images/media/team/badge/zcb6f61784988620.png",
        "djurgarden" to "https://r2.thesportsdb.com/images/media/team/badge/yuuwru1425411493.png",
        "dortmund" to "https://r2.thesportsdb.com/images/media/team/badge/tqo8ge1716960353.png",
        "dynamo kyiv" to "https://r2.thesportsdb.com/images/media/team/badge/ktbncx1781158762.png",
        "ea7 milano" to "https://www.thesportsdb.com/images/media/team/badge/ju4ohs1782551366.png",
        "ecuador" to "https://r2.thesportsdb.com/images/media/team/badge/47wv2y1591989301.png",
        "efes" to "https://r2.thesportsdb.com/images/media/team/badge/uldz0d1782050729.png",
        "egitto" to "https://r2.thesportsdb.com/images/media/team/badge/uheyzo1742102234.png",
        "egypt" to "https://r2.thesportsdb.com/images/media/team/badge/uheyzo1742102234.png",
        "eindhoven" to "https://r2.thesportsdb.com/images/media/team/badge/xfsz6i1721297428.png",
        "eintracht" to "https://r2.thesportsdb.com/images/media/team/badge/rurwpy1473453269.png",
        "eintracht frankfurt" to "https://r2.thesportsdb.com/images/media/team/badge/rurwpy1473453269.png",
        "elche" to "https://r2.thesportsdb.com/images/media/team/badge/e4vaw51655594332.png",
        "empoli" to "https://r2.thesportsdb.com/images/media/team/badge/c1ie6b1622561483.png",
        "england" to "https://r2.thesportsdb.com/images/media/team/badge/vf5ttc1726166739.png",
        "espanyol" to "https://r2.thesportsdb.com/images/media/team/badge/867nzz1681703222.png",
        "everton" to "https://r2.thesportsdb.com/images/media/team/badge/eqayrf1523184794.png",
        "fcsb" to "https://r2.thesportsdb.com/images/media/team/badge/123g021759420850.png",
        "fenerbahce" to "https://r2.thesportsdb.com/images/media/team/badge/twxxvs1448199691.png",
        "ferencvaros" to "https://r2.thesportsdb.com/images/media/team/badge/wk17od1688115265.png",
        "feyenoord" to "https://r2.thesportsdb.com/images/media/team/badge/uturtx1473534803.png",
        "finland" to "https://r2.thesportsdb.com/images/media/team/badge/wgvfd21730303889.png",
        "finlandia" to "https://r2.thesportsdb.com/images/media/team/badge/wgvfd21730303889.png",
        "fiorentina" to "https://r2.thesportsdb.com/images/media/team/badge/hc8nhu1656098030.png",
        "france" to "https://r2.thesportsdb.com/images/media/team/badge/p3n0z51726166851.png",
        "francia" to "https://r2.thesportsdb.com/images/media/team/badge/p3n0z51726166851.png",
        "francoforte" to "https://r2.thesportsdb.com/images/media/team/badge/rurwpy1473453269.png",
        "freiburg" to "https://r2.thesportsdb.com/images/media/team/badge/urwtup1473453288.png",
        "friburgo" to "https://r2.thesportsdb.com/images/media/team/badge/urwtup1473453288.png",
        "frosinone" to "https://r2.thesportsdb.com/images/media/team/badge/a7xa151603170120.png",
        "fulham" to "https://r2.thesportsdb.com/images/media/team/badge/xwwvyt1448811086.png",
        "galatasaray" to "https://r2.thesportsdb.com/images/media/team/badge/io7jk21767941298.png",
        "galles" to "https://r2.thesportsdb.com/images/media/team/badge/pdayn21591983222.png",
        "gand" to "https://r2.thesportsdb.com/images/media/team/badge/48e27o1750703124.png",
        "genk" to "https://r2.thesportsdb.com/images/media/team/badge/tp06te1534875918.png",
        "genoa" to "https://r2.thesportsdb.com/images/media/team/badge/52s8dn1655553600.png",
        "gent" to "https://r2.thesportsdb.com/images/media/team/badge/48e27o1750703124.png",
        "germania" to "https://r2.thesportsdb.com/images/media/team/badge/1xysi51726167152.png",
        "germany" to "https://r2.thesportsdb.com/images/media/team/badge/1xysi51726167152.png",
        "getafe" to "https://r2.thesportsdb.com/images/media/team/badge/eyh2891655594452.png",
        "ghana" to "https://r2.thesportsdb.com/images/media/team/badge/j589xw1751526124.png",
        "giappone" to "https://r2.thesportsdb.com/images/media/team/badge/ffsyxz1591989843.png",
        "girona" to "https://r2.thesportsdb.com/images/media/team/badge/kfu7zu1659897499.png",
        "glasgow rangers" to "https://r2.thesportsdb.com/images/media/team/badge/ti24j61614290048.png",
        "golden state warriors" to "https://r2.thesportsdb.com/images/media/team/badge/xokycb1778197905.png",
        "grecia" to "https://r2.thesportsdb.com/images/media/team/badge/xtxtts1455465601.png",
        "greece" to "https://r2.thesportsdb.com/images/media/team/badge/xtxtts1455465601.png",
        "hajduk" to "https://r2.thesportsdb.com/images/media/team/badge/23mvtk1579955412.png",
        "hajduk split" to "https://r2.thesportsdb.com/images/media/team/badge/23mvtk1579955412.png",
        "hamburg" to "https://r2.thesportsdb.com/images/media/team/badge/tvtppt1473453296.png",
        "heart of midlothian" to "https://r2.thesportsdb.com/images/media/team/badge/twqvyt1447597939.png",
        "hearts" to "https://r2.thesportsdb.com/images/media/team/badge/twqvyt1447597939.png",
        "heidenheim" to "https://r2.thesportsdb.com/images/media/team/badge/lbj7g01608236988.png",
        "hellas verona" to "https://r2.thesportsdb.com/images/media/team/badge/p6camf1593457737.png",
        "hjk" to "https://r2.thesportsdb.com/images/media/team/badge/z43x021775498790.png",
        "hjk helsinki" to "https://r2.thesportsdb.com/images/media/team/badge/z43x021775498790.png",
        "hoffenheim" to "https://r2.thesportsdb.com/images/media/team/badge/9hwvb21621593919.png",
        "houston rockets" to "https://r2.thesportsdb.com/images/media/team/badge/azoktf1780729261.png",
        "hungary" to "https://r2.thesportsdb.com/images/media/team/badge/ihaoit1717365719.png",
        "iceland" to "https://r2.thesportsdb.com/images/media/team/badge/xc6kuy1742982312.png",
        "indiana pacers" to "https://r2.thesportsdb.com/images/media/team/badge/y3lutb1778226511.png",
        "inghilterra" to "https://r2.thesportsdb.com/images/media/team/badge/vf5ttc1726166739.png",
        "inter" to "https://r2.thesportsdb.com/images/media/team/badge/ryhu6d1617113103.png",
        "inter milan" to "https://r2.thesportsdb.com/images/media/team/badge/ryhu6d1617113103.png",
        "internazionale" to "https://r2.thesportsdb.com/images/media/team/badge/ryhu6d1617113103.png",
        "ipswich" to "https://r2.thesportsdb.com/images/media/team/badge/mdj1ey1634670785.png",
        "ipswich town" to "https://r2.thesportsdb.com/images/media/team/badge/mdj1ey1634670785.png",
        "iran" to "https://r2.thesportsdb.com/images/media/team/badge/uttpvw1455465617.png",
        "ireland" to "https://r2.thesportsdb.com/images/media/team/badge/3vq2cl1679073105.png",
        "irlanda" to "https://r2.thesportsdb.com/images/media/team/badge/3vq2cl1679073105.png",
        "islanda" to "https://r2.thesportsdb.com/images/media/team/badge/xc6kuy1742982312.png",
        "israel" to "https://r2.thesportsdb.com/images/media/team/badge/me4lfd1515700405.png",
        "israele" to "https://r2.thesportsdb.com/images/media/team/badge/me4lfd1515700405.png",
        "italia" to "https://r2.thesportsdb.com/images/media/team/badge/fxijcp1726167035.png",
        "italy" to "https://r2.thesportsdb.com/images/media/team/badge/fxijcp1726167035.png",
        "ivory coast" to "https://r2.thesportsdb.com/images/media/team/badge/rwxuuu1455465643.png",
        "japan" to "https://r2.thesportsdb.com/images/media/team/badge/ffsyxz1591989843.png",
        "juve" to "https://r2.thesportsdb.com/images/media/team/badge/uxf0gr1742983727.png",
        "juventus" to "https://r2.thesportsdb.com/images/media/team/badge/uxf0gr1742983727.png",
        "kairat" to "https://r2.thesportsdb.com/images/media/team/badge/sz5y1m1579285078.png",
        "kairat almaty" to "https://r2.thesportsdb.com/images/media/team/badge/sz5y1m1579285078.png",
        "koln" to "https://r2.thesportsdb.com/images/media/team/badge/2j1sc91566049407.png",
        "la clippers" to "https://r2.thesportsdb.com/images/media/team/badge/3gtb8s1719303125.png",
        "la lakers" to "https://r2.thesportsdb.com/images/media/team/badge/d8uoxw1714254511.png",
        "lazio" to "https://r2.thesportsdb.com/images/media/team/badge/rwqyvs1448806608.png",
        "le havre" to "https://r2.thesportsdb.com/images/media/team/badge/aikowk1546475003.png",
        "lecce" to "https://r2.thesportsdb.com/images/media/team/badge/j4vznr1567365249.png",
        "lech" to "https://r2.thesportsdb.com/images/media/team/badge/8zfxyx1685597440.png",
        "lech poznan" to "https://r2.thesportsdb.com/images/media/team/badge/8zfxyx1685597440.png",
        "leeds" to "https://r2.thesportsdb.com/images/media/team/badge/jcgrml1756649030.png",
        "leeds united" to "https://r2.thesportsdb.com/images/media/team/badge/jcgrml1756649030.png",
        "legia" to "https://r2.thesportsdb.com/images/media/team/badge/c969ez1632775656.png",
        "legia varsavia" to "https://r2.thesportsdb.com/images/media/team/badge/c969ez1632775656.png",
        "legia warsaw" to "https://r2.thesportsdb.com/images/media/team/badge/c969ez1632775656.png",
        "leicester" to "https://r2.thesportsdb.com/images/media/team/badge/xtxwtu1448813356.png",
        "leicester city" to "https://r2.thesportsdb.com/images/media/team/badge/xtxwtu1448813356.png",
        "leipzig" to "https://r2.thesportsdb.com/images/media/team/badge/zjgapo1594244951.png",
        "lens" to "https://r2.thesportsdb.com/images/media/team/badge/3pxoum1598797195.png",
        "levante" to "https://r2.thesportsdb.com/images/media/team/badge/xwtxsx1473503739.png",
        "leverkusen" to "https://r2.thesportsdb.com/images/media/team/badge/3x9k851726760113.png",
        "lilla" to "https://r2.thesportsdb.com/images/media/team/badge/txcsg21741597962.png",
        "lille" to "https://r2.thesportsdb.com/images/media/team/badge/txcsg21741597962.png",
        "lione" to "https://r2.thesportsdb.com/images/media/team/badge/blk9771656932845.png",
        "lipsia" to "https://r2.thesportsdb.com/images/media/team/badge/zjgapo1594244951.png",
        "liverpool" to "https://r2.thesportsdb.com/images/media/team/badge/kfaher1737969724.png",
        "lorient" to "https://r2.thesportsdb.com/images/media/team/badge/sxsttw1473504748.png",
        "los angeles clippers" to "https://r2.thesportsdb.com/images/media/team/badge/3gtb8s1719303125.png",
        "los angeles lakers" to "https://r2.thesportsdb.com/images/media/team/badge/d8uoxw1714254511.png",
        "ludogorets" to "https://r2.thesportsdb.com/images/media/team/badge/35cw6l1752946925.png",
        "ludogorets razgrad" to "https://r2.thesportsdb.com/images/media/team/badge/35cw6l1752946925.png",
        "lyon" to "https://r2.thesportsdb.com/images/media/team/badge/blk9771656932845.png",
        "lyon villeurbanne" to "https://r2.thesportsdb.com/images/media/team/badge/qbaoia1602706639.png",
        "maccabi" to "https://r2.thesportsdb.com/images/media/team/badge/oeer261781239315.png",
        "maccabi tel aviv" to "https://r2.thesportsdb.com/images/media/team/badge/oeer261781239315.png",
        "magonza" to "https://r2.thesportsdb.com/images/media/team/badge/fhm9v51552134916.png",
        "mainz" to "https://r2.thesportsdb.com/images/media/team/badge/fhm9v51552134916.png",
        "maiorca" to "https://r2.thesportsdb.com/images/media/team/badge/ssptsx1473503730.png",
        "mallorca" to "https://r2.thesportsdb.com/images/media/team/badge/ssptsx1473503730.png",
        "malmo" to "https://r2.thesportsdb.com/images/media/team/badge/429jzd1779940315.png",
        "malmoe" to "https://r2.thesportsdb.com/images/media/team/badge/429jzd1779940315.png",
        "man city" to "https://r2.thesportsdb.com/images/media/team/badge/vwpvry1467462651.png",
        "man united" to "https://r2.thesportsdb.com/images/media/team/badge/xzqdr11517660252.png",
        "man utd" to "https://r2.thesportsdb.com/images/media/team/badge/xzqdr11517660252.png",
        "manchester city" to "https://r2.thesportsdb.com/images/media/team/badge/vwpvry1467462651.png",
        "manchester united" to "https://r2.thesportsdb.com/images/media/team/badge/xzqdr11517660252.png",
        "marocco" to "https://r2.thesportsdb.com/images/media/team/badge/hbmwkj1731791275.png",
        "marseille" to "https://r2.thesportsdb.com/images/media/team/badge/c6bazh1779212287.png",
        "marsiglia" to "https://r2.thesportsdb.com/images/media/team/badge/c6bazh1779212287.png",
        "memphis grizzlies" to "https://r2.thesportsdb.com/images/media/team/badge/v44qp21778197640.png",
        "messico" to "https://r2.thesportsdb.com/images/media/team/badge/3rmosi1748525208.png",
        "metz" to "https://r2.thesportsdb.com/images/media/team/badge/1iuew61688452857.png",
        "mexico" to "https://r2.thesportsdb.com/images/media/team/badge/3rmosi1748525208.png",
        "miami heat" to "https://r2.thesportsdb.com/images/media/team/badge/b9tye31778226616.png",
        "midtjylland" to "https://r2.thesportsdb.com/images/media/team/badge/s5bpcr1755712262.png",
        "milan" to "https://r2.thesportsdb.com/images/media/team/badge/wvspur1448806617.png",
        "milwaukee bucks" to "https://r2.thesportsdb.com/images/media/team/badge/olhug01621594702.png",
        "minnesota timberwolves" to "https://r2.thesportsdb.com/images/media/team/badge/epenxg1781034811.png",
        "monaco" to "https://r2.thesportsdb.com/images/media/team/badge/exjf5l1678808044.png",
        "monchengladbach" to "https://r2.thesportsdb.com/images/media/team/badge/sysurw1473453380.png",
        "monza" to "https://r2.thesportsdb.com/images/media/team/badge/bxearg1603170113.png",
        "morocco" to "https://r2.thesportsdb.com/images/media/team/badge/hbmwkj1731791275.png",
        "nantes" to "https://r2.thesportsdb.com/images/media/team/badge/mla9x61678808018.png",
        "napoli" to "https://r2.thesportsdb.com/images/media/team/badge/l8qyxv1742982541.png",
        "netherlands" to "https://r2.thesportsdb.com/images/media/team/badge/1p0hr41593787110.png",
        "new orleans pelicans" to "https://r2.thesportsdb.com/images/media/team/badge/xoxjsr1778226698.png",
        "new york knicks" to "https://r2.thesportsdb.com/images/media/team/badge/4k8obt1778226764.png",
        "newcastle" to "https://r2.thesportsdb.com/images/media/team/badge/lhwuiz1621593302.png",
        "newcastle united" to "https://r2.thesportsdb.com/images/media/team/badge/lhwuiz1621593302.png",
        "nice" to "https://r2.thesportsdb.com/images/media/team/badge/msy7ly1621593859.png",
        "nigeria" to "https://r2.thesportsdb.com/images/media/team/badge/qruyxr1455466056.png",
        "nizza" to "https://r2.thesportsdb.com/images/media/team/badge/msy7ly1621593859.png",
        "norvegia" to "https://r2.thesportsdb.com/images/media/team/badge/gyfn811591973155.png",
        "norway" to "https://r2.thesportsdb.com/images/media/team/badge/gyfn811591973155.png",
        "norwich" to "https://r2.thesportsdb.com/images/media/team/badge/pabczm1679951464.png",
        "norwich city" to "https://r2.thesportsdb.com/images/media/team/badge/pabczm1679951464.png",
        "nottingham" to "https://r2.thesportsdb.com/images/media/team/badge/1i2kvh1719918076.png",
        "nottingham forest" to "https://r2.thesportsdb.com/images/media/team/badge/1i2kvh1719918076.png",
        "oklahoma city thunder" to "https://r2.thesportsdb.com/images/media/team/badge/bkhj5p1778199006.png",
        "olanda" to "https://r2.thesportsdb.com/images/media/team/badge/1p0hr41593787110.png",
        "olimpia milano" to "https://www.thesportsdb.com/images/media/team/badge/ju4ohs1782551366.png",
        "olympiacos" to "https://r2.thesportsdb.com/images/media/team/badge/xckasq1721291508.png",
        "olympiakos" to "https://r2.thesportsdb.com/images/media/team/badge/xckasq1721291508.png",
        "olympique lione" to "https://r2.thesportsdb.com/images/media/team/badge/blk9771656932845.png",
        "olympique marsiglia" to "https://r2.thesportsdb.com/images/media/team/badge/c6bazh1779212287.png",
        "omonia" to "https://r2.thesportsdb.com/images/media/team/badge/nmd7vt1779579017.png",
        "omonia nicosia" to "https://r2.thesportsdb.com/images/media/team/badge/nmd7vt1779579017.png",
        "orlando magic" to "https://r2.thesportsdb.com/images/media/team/badge/j0mpu81778198371.png",
        "osasuna" to "https://r2.thesportsdb.com/images/media/team/badge/rvspvt1473502960.png",
        "oviedo" to "https://r2.thesportsdb.com/images/media/team/badge/yuwqus1447590681.png",
        "paesi bassi" to "https://r2.thesportsdb.com/images/media/team/badge/1p0hr41593787110.png",
        "pafos" to "https://r2.thesportsdb.com/images/media/team/badge/xom9xf1579036010.png",
        "palermo" to "https://r2.thesportsdb.com/images/media/team/badge/zi1tb01579708939.png",
        "panathinaikos" to "https://r2.thesportsdb.com/images/media/team/badge/vtpwwt1448208397.png",
        "paok" to "https://r2.thesportsdb.com/images/media/team/badge/m15zsh1602774126.png",
        "paraguay" to "https://r2.thesportsdb.com/images/media/team/badge/khgav41553419195.png",
        "parigi" to "https://r2.thesportsdb.com/images/media/team/badge/rwqrrq1473504808.png",
        "paris" to "https://r2.thesportsdb.com/images/media/team/badge/yuvtsy1447594254.png",
        "paris saint germain" to "https://r2.thesportsdb.com/images/media/team/badge/rwqrrq1473504808.png",
        "paris sg" to "https://r2.thesportsdb.com/images/media/team/badge/rwqrrq1473504808.png",
        "parma" to "https://r2.thesportsdb.com/images/media/team/badge/6yiaxs1627406063.png",
        "partizan" to "https://r2.thesportsdb.com/images/media/team/badge/xe41k11781157208.png",
        "partizan belgrade" to "https://r2.thesportsdb.com/images/media/team/badge/xe41k11781157208.png",
        "peru" to "https://r2.thesportsdb.com/images/media/team/badge/unszat1529144812.png",
        "philadelphia 76ers" to "https://r2.thesportsdb.com/images/media/team/badge/j6rlbi1778226857.png",
        "phoenix suns" to "https://r2.thesportsdb.com/images/media/team/badge/xfyknc1778198971.png",
        "pisa" to "https://r2.thesportsdb.com/images/media/team/badge/2eso9w1579708309.png",
        "plzen" to "https://r2.thesportsdb.com/images/media/team/badge/at8i2h1679265942.png",
        "poland" to "https://r2.thesportsdb.com/images/media/team/badge/ttvrxy1455466076.png",
        "polonia" to "https://r2.thesportsdb.com/images/media/team/badge/ttvrxy1455466076.png",
        "portland trail blazers" to "https://r2.thesportsdb.com/images/media/team/badge/umehtv1778226952.png",
        "porto" to "https://r2.thesportsdb.com/images/media/team/badge/xu47rb1628855600.png",
        "portogallo" to "https://r2.thesportsdb.com/images/media/team/badge/swqvpy1455466083.png",
        "portugal" to "https://r2.thesportsdb.com/images/media/team/badge/swqvpy1455466083.png",
        "psg" to "https://r2.thesportsdb.com/images/media/team/badge/rwqrrq1473504808.png",
        "psv" to "https://r2.thesportsdb.com/images/media/team/badge/xfsz6i1721297428.png",
        "psv eindhoven" to "https://r2.thesportsdb.com/images/media/team/badge/xfsz6i1721297428.png",
        "qarabag" to "https://r2.thesportsdb.com/images/media/team/badge/f9h2by1725001244.png",
        "qatar" to "https://r2.thesportsdb.com/images/media/team/badge/rs3ir31642708685.png",
        "rakow" to "https://r2.thesportsdb.com/images/media/team/badge/vy8paa1579458598.png",
        "rakow czestochowa" to "https://r2.thesportsdb.com/images/media/team/badge/vy8paa1579458598.png",
        "rangers" to "https://r2.thesportsdb.com/images/media/team/badge/ti24j61614290048.png",
        "rapid vienna" to "https://r2.thesportsdb.com/images/media/team/badge/87y8id1779342351.png",
        "rayo" to "https://r2.thesportsdb.com/images/media/team/badge/nzhu941655595465.png",
        "rayo vallecano" to "https://r2.thesportsdb.com/images/media/team/badge/nzhu941655595465.png",
        "rb leipzig" to "https://r2.thesportsdb.com/images/media/team/badge/zjgapo1594244951.png",
        "real betis" to "https://r2.thesportsdb.com/images/media/team/badge/2oqulv1663245386.png",
        "real madrid" to "https://r2.thesportsdb.com/images/media/team/badge/vwvwrw1473502969.png",
        "real oviedo" to "https://r2.thesportsdb.com/images/media/team/badge/yuwqus1447590681.png",
        "real sociedad" to "https://r2.thesportsdb.com/images/media/team/badge/vptvpr1473502986.png",
        "real valladolid" to "https://r2.thesportsdb.com/images/media/team/badge/bnhu8b1719983736.png",
        "red bull salzburg" to "https://r2.thesportsdb.com/images/media/team/badge/nc2cua1781541639.png",
        "red star belgrade" to "https://r2.thesportsdb.com/images/media/team/badge/osgmbz1781157114.png",
        "rennes" to "https://r2.thesportsdb.com/images/media/team/badge/ypturx1473504818.png",
        "rep ceca" to "https://r2.thesportsdb.com/images/media/team/badge/1o0cx31654205806.png",
        "rfs" to "https://r2.thesportsdb.com/images/media/team/badge/cep7qg1644854951.png",
        "roma" to "https://r2.thesportsdb.com/images/media/team/badge/jwro2s1760820674.png",
        "romania" to "https://r2.thesportsdb.com/images/media/team/badge/w903wb1689198300.png",
        "rosenborg" to "https://r2.thesportsdb.com/images/media/team/badge/z483ps1764866361.png",
        "royal antwerp" to "https://r2.thesportsdb.com/images/media/team/badge/gawwcf1691182178.png",
        "sacramento kings" to "https://r2.thesportsdb.com/images/media/team/badge/k2buwo1778227426.png",
        "saint etienne" to "https://r2.thesportsdb.com/images/media/team/badge/m4ej831656423694.png",
        "salernitana" to "https://r2.thesportsdb.com/images/media/team/badge/nmi7mk1603170517.png",
        "salisburgo" to "https://r2.thesportsdb.com/images/media/team/badge/nc2cua1781541639.png",
        "salonicco" to "https://r2.thesportsdb.com/images/media/team/badge/m15zsh1602774126.png",
        "salzburg" to "https://r2.thesportsdb.com/images/media/team/badge/nc2cua1781541639.png",
        "sampdoria" to "https://r2.thesportsdb.com/images/media/team/badge/pr6co21655592769.png",
        "san antonio spurs" to "https://r2.thesportsdb.com/images/media/team/badge/yc5qfx1781459158.png",
        "sassuolo" to "https://r2.thesportsdb.com/images/media/team/badge/xystvp1448806138.png",
        "saudi arabia" to "https://r2.thesportsdb.com/images/media/team/badge/24xwpq1594125742.png",
        "schalke" to "https://r2.thesportsdb.com/images/media/team/badge/hnci291621593978.png",
        "schalke 04" to "https://r2.thesportsdb.com/images/media/team/badge/hnci291621593978.png",
        "scotland" to "https://r2.thesportsdb.com/images/media/team/badge/3691i11552945146.png",
        "scozia" to "https://r2.thesportsdb.com/images/media/team/badge/3691i11552945146.png",
        "senegal" to "https://r2.thesportsdb.com/images/media/team/badge/slayb01780546342.png",
        "serbia" to "https://r2.thesportsdb.com/images/media/team/badge/oxvynb1689195538.png",
        "servette" to "https://r2.thesportsdb.com/images/media/team/badge/440wv71692206330.png",
        "sevilla" to "https://r2.thesportsdb.com/images/media/team/badge/vpsqqx1473502977.png",
        "shakhtar" to "https://r2.thesportsdb.com/images/media/team/badge/sqrxsr1421791799.png",
        "shakhtar donetsk" to "https://r2.thesportsdb.com/images/media/team/badge/sqrxsr1421791799.png",
        "sheffield united" to "https://r2.thesportsdb.com/images/media/team/badge/w7f8pj1672950689.png",
        "siviglia" to "https://r2.thesportsdb.com/images/media/team/badge/vpsqqx1473502977.png",
        "slavia praga" to "https://r2.thesportsdb.com/images/media/team/badge/l7kl4n1759252139.png",
        "slavia prague" to "https://r2.thesportsdb.com/images/media/team/badge/l7kl4n1759252139.png",
        "slovacchia" to "https://r2.thesportsdb.com/images/media/team/badge/njbw8n1717365638.png",
        "slovakia" to "https://r2.thesportsdb.com/images/media/team/badge/njbw8n1717365638.png",
        "slovenia" to "https://r2.thesportsdb.com/images/media/team/badge/s7k1x51552909864.png",
        "sociedad" to "https://r2.thesportsdb.com/images/media/team/badge/vptvpr1473502986.png",
        "south korea" to "https://r2.thesportsdb.com/images/media/team/badge/a8nqfs1589564916.png",
        "southampton" to "https://r2.thesportsdb.com/images/media/team/badge/ggqtd01621593274.png",
        "spagna" to "https://r2.thesportsdb.com/images/media/team/badge/ncgqyr1726166942.png",
        "spain" to "https://r2.thesportsdb.com/images/media/team/badge/ncgqyr1726166942.png",
        "sparta praga" to "https://r2.thesportsdb.com/images/media/team/badge/j00qct1718287150.png",
        "sparta prague" to "https://r2.thesportsdb.com/images/media/team/badge/j00qct1718287150.png",
        "spezia" to "https://r2.thesportsdb.com/images/media/team/badge/3wgebp1749146364.png",
        "sporting" to "https://www.thesportsdb.com/images/media/team/badge/5hiuk71783137875.png",
        "sporting lisbona" to "https://www.thesportsdb.com/images/media/team/badge/5hiuk71783137875.png",
        "st pauli" to "https://r2.thesportsdb.com/images/media/team/badge/5qupxa1608237013.png",
        "stati uniti" to "https://r2.thesportsdb.com/images/media/team/badge/21f0oi1597948195.png",
        "steaua" to "https://r2.thesportsdb.com/images/media/team/badge/123g021759420850.png",
        "steaua bucharest" to "https://r2.thesportsdb.com/images/media/team/badge/123g021759420850.png",
        "stella rossa" to "https://r2.thesportsdb.com/images/media/team/badge/osgmbz1781157114.png",
        "stoccarda" to "https://r2.thesportsdb.com/images/media/team/badge/yppyux1473454085.png",
        "strasbourg" to "https://r2.thesportsdb.com/images/media/team/badge/b8k77w1766625501.png",
        "strasburgo" to "https://r2.thesportsdb.com/images/media/team/badge/b8k77w1766625501.png",
        "sturm graz" to "https://r2.thesportsdb.com/images/media/team/badge/ppg0j71578585847.png",
        "stuttgart" to "https://r2.thesportsdb.com/images/media/team/badge/yppyux1473454085.png",
        "sunderland" to "https://r2.thesportsdb.com/images/media/team/badge/tprtus1448813498.png",
        "svezia" to "https://r2.thesportsdb.com/images/media/team/badge/h5adzg1591981772.png",
        "svizzera" to "https://r2.thesportsdb.com/images/media/team/badge/mb7yqe1717365808.png",
        "sweden" to "https://r2.thesportsdb.com/images/media/team/badge/h5adzg1591981772.png",
        "switzerland" to "https://r2.thesportsdb.com/images/media/team/badge/mb7yqe1717365808.png",
        "tolosa" to "https://r2.thesportsdb.com/images/media/team/badge/17eqox1688449282.png",
        "torino" to "https://r2.thesportsdb.com/images/media/team/badge/xxprty1448806802.png",
        "toronto raptors" to "https://r2.thesportsdb.com/images/media/team/badge/lct96a1778227205.png",
        "tottenham" to "https://r2.thesportsdb.com/images/media/team/badge/dfyfhl1604094109.png",
        "tottenham hotspur" to "https://r2.thesportsdb.com/images/media/team/badge/dfyfhl1604094109.png",
        "toulouse" to "https://r2.thesportsdb.com/images/media/team/badge/17eqox1688449282.png",
        "trabzonspor" to "https://r2.thesportsdb.com/images/media/team/badge/96s34o1776827629.png",
        "tunisia" to "https://r2.thesportsdb.com/images/media/team/badge/7r89rg1526727277.png",
        "turchia" to "https://r2.thesportsdb.com/images/media/team/badge/70c4oo1591982459.png",
        "turkey" to "https://r2.thesportsdb.com/images/media/team/badge/70c4oo1591982459.png",
        "twente" to "https://r2.thesportsdb.com/images/media/team/badge/rsrxrt1473534783.png",
        "ucraina" to "https://r2.thesportsdb.com/images/media/team/badge/k36g2e1591982718.png",
        "udinese" to "https://r2.thesportsdb.com/images/media/team/badge/vwvstr1448806811.png",
        "ukraine" to "https://r2.thesportsdb.com/images/media/team/badge/k36g2e1591982718.png",
        "ungheria" to "https://r2.thesportsdb.com/images/media/team/badge/ihaoit1717365719.png",
        "union berlin" to "https://r2.thesportsdb.com/images/media/team/badge/q0o5001599679795.png",
        "union berlino" to "https://r2.thesportsdb.com/images/media/team/badge/q0o5001599679795.png",
        "union saint gilloise" to "https://r2.thesportsdb.com/images/media/team/badge/ljszp41654601742.png",
        "union sg" to "https://r2.thesportsdb.com/images/media/team/badge/ljszp41654601742.png",
        "united states" to "https://r2.thesportsdb.com/images/media/team/badge/21f0oi1597948195.png",
        "uruguay" to "https://r2.thesportsdb.com/images/media/team/badge/6vjbr11726167756.png",
        "usa" to "https://r2.thesportsdb.com/images/media/team/badge/21f0oi1597948195.png",
        "utah jazz" to "https://r2.thesportsdb.com/images/media/team/badge/trct8w1778198868.png",
        "utrecht" to "https://r2.thesportsdb.com/images/media/team/badge/yuhha71625167104.png",
        "valencia" to "https://r2.thesportsdb.com/images/media/team/badge/dm8l6o1655594864.png",
        "venezia" to "https://r2.thesportsdb.com/images/media/team/badge/vbiget1781026964.png",
        "verona" to "https://r2.thesportsdb.com/images/media/team/badge/p6camf1593457737.png",
        "viktoria plzen" to "https://r2.thesportsdb.com/images/media/team/badge/at8i2h1679265942.png",
        "villarreal" to "https://r2.thesportsdb.com/images/media/team/badge/vrypqy1473503073.png",
        "villeurbanne" to "https://r2.thesportsdb.com/images/media/team/badge/qbaoia1602706639.png",
        "virtus bologna" to "https://r2.thesportsdb.com/images/media/team/badge/bwm0an1739659116.png",
        "virtus pallacanestro bologna" to "https://r2.thesportsdb.com/images/media/team/badge/bwm0an1739659116.png",
        "wales" to "https://r2.thesportsdb.com/images/media/team/badge/pdayn21591983222.png",
        "washington wizards" to "https://r2.thesportsdb.com/images/media/team/badge/dxac7a1778227278.png",
        "watford" to "https://r2.thesportsdb.com/images/media/team/badge/rsuswy1448813519.png",
        "werder" to "https://r2.thesportsdb.com/images/media/team/badge/tkvqan1716960454.png",
        "werder bremen" to "https://r2.thesportsdb.com/images/media/team/badge/tkvqan1716960454.png",
        "west ham" to "https://r2.thesportsdb.com/images/media/team/badge/yutyxs1467459956.png",
        "west ham united" to "https://r2.thesportsdb.com/images/media/team/badge/yutyxs1467459956.png",
        "wolfsburg" to "https://r2.thesportsdb.com/images/media/team/badge/ci9trv1778399557.png",
        "wolverhampton" to "https://r2.thesportsdb.com/images/media/team/badge/u9qr031621593327.png",
        "wolverhampton wanderers" to "https://r2.thesportsdb.com/images/media/team/badge/u9qr031621593327.png",
        "wolves" to "https://r2.thesportsdb.com/images/media/team/badge/u9qr031621593327.png",
        "young boys" to "https://r2.thesportsdb.com/images/media/team/badge/9mxdoo1534784569.png",
        "zagabria" to "https://www.thesportsdb.com/images/media/team/badge/zcb6f61784988620.png",
        "zalgiris" to "https://r2.thesportsdb.com/images/media/team/badge/4x8gap1579201999.png",
        "zalgiris kaunas" to "https://r2.thesportsdb.com/images/media/team/badge/dn7ouv1703960565.png",
        "zalgiris vilnius" to "https://r2.thesportsdb.com/images/media/team/badge/4x8gap1579201999.png",
        "zurich" to "https://r2.thesportsdb.com/images/media/team/badge/af50gk1779213314.png",
        "zurigo" to "https://r2.thesportsdb.com/images/media/team/badge/af50gk1779213314.png"
    )

    /** Colori sociali per squadra (fino a tre, dal primario), chiavi come sopra. */
    private val teamColors: Map<String, String> = mapOf(
        "ajax" to "d2122e,ffffff",
        "alaves" to "0761af,ffffff,009ad7",
        "algeria" to "00792c,ffffff,dc002e",
        "alkmaar" to "db0021,ffffff",
        "amburgo" to "0a3f86,ffffff",
        "anderlecht" to "512e8f,ffffff",
        "angers" to "000000,ffffff,d9c395",
        "antwerp" to "df172b,ffffff",
        "anversa" to "df172b,ffffff",
        "arabia saudita" to "125b34,7ec8ae",
        "argentina" to "43a1d5,d5b048",
        "arsenal" to "ef0107,fbffff,013373",
        "aston villa" to "490125,93bde4,fee407",
        "atalanta" to "1e71b8,000000",
        "athletic bilbao" to "ee2523,ffffff,000000",
        "atlanta hawks" to "e03a3e,ffcd00",
        "atletico" to "cb3524,272e61,ffffff",
        "atletico madrid" to "cb3524,272e61,ffffff",
        "augsburg" to "ba3733,ffffff,46714d",
        "augusta" to "ba3733,ffffff,46714d",
        "australia" to "ffcd00,00843d",
        "auxerre" to "4087bf,ffffff",
        "az alkmaar" to "db0021,ffffff",
        "barcellona" to "004d98,a50044",
        "barcelona" to "004d98,a50044",
        "bayer leverkusen" to "e32221,000000,ffffff",
        "bayern" to "dc052d,ffffff,0066b2",
        "bayern monaco" to "dc052d,ffffff,0066b2",
        "bayern munich" to "dc052d,ffffff,0066b2",
        "belgio" to "e30613,ffd500,000000",
        "belgium" to "e30613,ffd500,000000",
        "benfica" to "e83030,ffffff,000000",
        "betis" to "0bb363,ffffff",
        "bilbao" to "ee2523,ffffff,000000",
        "birmingham" to "0000ff,ffffff",
        "birmingham city" to "0000ff,ffffff",
        "bologna" to "a21c26,1a2f48",
        "borussia dortmund" to "fde100,000000",
        "borussia m gladbach" to "000000,ffffff",
        "borussia monchengladbach" to "000000,ffffff",
        "boston celtics" to "007a33,ffffff",
        "bournemouth" to "b50e12,000000",
        "br ndby" to "fff200,074d84",
        "braga" to "c82b20,868257,ffffff",
        "brasile" to "ffdc02,193375,19ae47",
        "brazil" to "ffdc02,193375,19ae47",
        "brema" to "1d9053,ffffff",
        "brentford" to "e30613,ffffff,fbb800",
        "brest" to "ed1c24,ffffff,000000",
        "brighton" to "0057b8,ffffff",
        "brighton and hove albion" to "0057b8,ffffff",
        "brondby" to "fff200,074d84",
        "brooklyn nets" to "000000,ffffff",
        "bruges" to "0078bf,000000,ffffff",
        "brugge" to "0078bf,000000,ffffff",
        "burnley" to "6c1d45,99d6ea",
        "cagliari" to "002350,ad002a,ffffff",
        "cameroon" to "479a50,ed1c24,fbd81b",
        "camerun" to "479a50,ed1c24,fbd81b",
        "canada" to "c5281c,ffffff",
        "celta" to "8ac3ee,e5254e",
        "celta vigo" to "8ac3ee,e5254e",
        "celtic" to "249b48",
        "charlotte hornets" to "1d1160,00788c",
        "chelsea" to "034694,ffffff,dba111",
        "chicago bulls" to "ce1141,000000",
        "cleveland cavaliers" to "860038,041e42,fdbb30",
        "colonia" to "ed1c24,ffffff,000000",
        "como" to "114169,114169,ffffff",
        "copenaghen" to "ffffff,0d34b5,000000",
        "copenhagen" to "ffffff,0d34b5,000000",
        "corea del sud" to "ec0f32,ffffff,021858",
        "costa d avorio" to "f5821f,26ad90,ffffff",
        "cremonese" to "808285,ed1c24",
        "croatia" to "ed1c24,ffffff,0457a2",
        "croazia" to "ed1c24,ffffff,0457a2",
        "crystal palace" to "1b458f,c4122e",
        "dallas mavericks" to "00538c,002b5e",
        "danimarca" to "cd181e,ffffff",
        "denmark" to "cd181e,ffffff",
        "denver nuggets" to "0e2240,fec524",
        "deportivo alaves" to "0761af,ffffff,009ad7",
        "detroit pistons" to "c8102e,1d42ba",
        "dortmund" to "fde100,000000",
        "ecuador" to "ffce00,002255",
        "egitto" to "c8102e,000000,ffffff",
        "egypt" to "c8102e,000000,ffffff",
        "eindhoven" to "e11f26,ffffff,bb955e",
        "eintracht" to "e1000f,ffffff,000000",
        "eintracht frankfurt" to "e1000f,ffffff,000000",
        "elche" to "05642c,ffffff,e6c777",
        "empoli" to "00579c,ffffff",
        "england" to "ffffff,5aadd6,181b3a",
        "espanyol" to "007fc8,ffffff,df1116",
        "everton" to "003399,ffffff",
        "feyenoord" to "e2001a,ffffff,ae9962",
        "fiorentina" to "482e92,ffffff,a29160",
        "france" to "21304d,ed2939",
        "francia" to "21304d,ed2939",
        "francoforte" to "e1000f,ffffff,000000",
        "freiburg" to "000000,ffffff,fd1220",
        "friburgo" to "000000,ffffff,fd1220",
        "frosinone" to "ffdd00,004393,006ab3",
        "fulham" to "ffffff,000000,cc0000",
        "galles" to "174a3f,ae2630,cf1e26",
        "gand" to "004794,ffffff",
        "genk" to "00468f,ffffff,007ec3",
        "genoa" to "ad1919,05232f,ffd400",
        "gent" to "004794,ffffff",
        "germania" to "ffffff,000000,d71016",
        "germany" to "ffffff,000000,d71016",
        "getafe" to "005999,c43a2f,fced0b",
        "ghana" to "ffffff,d40023,ffe500",
        "giappone" to "000080,ffffff",
        "girona" to "cd2534,ffffff,edda35",
        "golden state warriors" to "1d428a,ffc72c",
        "hamburg" to "0a3f86,ffffff",
        "heidenheim" to "e2001a,003b79,ffffff",
        "hellas verona" to "ffe74a,005395",
        "hjk" to "00469b",
        "hjk helsinki" to "00469b",
        "hoffenheim" to "1961b5,ffffff",
        "houston rockets" to "ce1141,000000",
        "indiana pacers" to "002d62,fdbb30",
        "inghilterra" to "ffffff,5aadd6,181b3a",
        "inter" to "010e80,000000,ffffff",
        "inter milan" to "010e80,000000,ffffff",
        "internazionale" to "010e80,000000,ffffff",
        "ipswich" to "3a64a3,de2c37,ffffff",
        "ipswich town" to "3a64a3,de2c37,ffffff",
        "iran" to "239f40,ffffff,da0000",
        "italia" to "1a57b8",
        "italy" to "1a57b8",
        "ivory coast" to "f5821f,26ad90,ffffff",
        "japan" to "000080,ffffff",
        "juve" to "000000,ffffff,ffc9e1",
        "juventus" to "000000,ffffff,ffc9e1",
        "koln" to "ed1c24,ffffff,000000",
        "la clippers" to "c8102e,1d428a",
        "la lakers" to "fdb927,3a0078",
        "lazio" to "87d8f7,ffffff",
        "le havre" to "79bce7,193260,ffffff",
        "lecce" to "fff200,ed1b23",
        "leeds" to "ffffff,1d428a,ffcd00",
        "leeds united" to "ffffff,1d428a,ffcd00",
        "leicester" to "003090,ffffff",
        "leicester city" to "003090,ffffff",
        "leipzig" to "dd013f,ffffff,0c2043",
        "lens" to "fff200,ec1c24",
        "levante" to "c60b46",
        "leverkusen" to "e32221,000000,ffffff",
        "lione" to "ffffff,0f23aa,f40043",
        "lipsia" to "dd013f,ffffff,0c2043",
        "liverpool" to "c8102e,ffffff",
        "lorient" to "f58113,000000,ffffff",
        "los angeles clippers" to "c8102e,1d428a",
        "los angeles lakers" to "fdb927,3a0078",
        "lyon" to "ffffff,0f23aa,f40043",
        "magonza" to "c3141e,ffffff,918f90",
        "mainz" to "c3141e,ffffff,918f90",
        "maiorca" to "e20613,000000",
        "mallorca" to "e20613,000000",
        "man city" to "6cabdd,ffffff,1c2c5b",
        "man united" to "da291c,ffffff,000000",
        "man utd" to "da291c,ffffff,000000",
        "manchester city" to "6cabdd,ffffff,1c2c5b",
        "manchester united" to "da291c,ffffff,000000",
        "marocco" to "c1272d,17a376,d29d63",
        "marseille" to "00a1df,ffffff,bea064",
        "marsiglia" to "00a1df,ffffff,bea064",
        "memphis grizzlies" to "5d76a9,12173f",
        "messico" to "00933b,f5313e,334d45",
        "metz" to "6e0f12,ffffff",
        "mexico" to "00933b,f5313e,334d45",
        "miami heat" to "000000,98002e",
        "midtjylland" to "000000,ff0d00,ffffff",
        "milan" to "fb090b,000000",
        "milwaukee bucks" to "00471b,eee1c6",
        "minnesota timberwolves" to "0c2340,236192",
        "monaco" to "e51b22,cb9f18,ffffff",
        "monchengladbach" to "000000,ffffff",
        "monza" to "ec173a,ffffff",
        "morocco" to "c1272d,17a376,d29d63",
        "nantes" to "fcd405,1b8f3a",
        "napoli" to "12a0d7,003c82,ffffff",
        "netherlands" to "f36c21,ffffff",
        "new orleans pelicans" to "002b5c,b4975a",
        "new york knicks" to "006bb6,f58426",
        "newcastle" to "ffffff,241f20",
        "newcastle united" to "ffffff,241f20",
        "nice" to "000000,ed1c24,b59a54",
        "nigeria" to "00a651,e41d25,ffffff",
        "nizza" to "000000,ed1c24,b59a54",
        "norwich" to "fff200,00a650,000000",
        "norwich city" to "fff200,00a650,000000",
        "oklahoma city thunder" to "007ac1,ef3b24,002d62",
        "olanda" to "f36c21,ffffff",
        "olympique lione" to "ffffff,0f23aa,f40043",
        "olympique marsiglia" to "00a1df,ffffff,bea064",
        "orlando magic" to "0077c0,c4ced4,000000",
        "osasuna" to "0a346f,d91a21",
        "paesi bassi" to "f36c21,ffffff",
        "panathinaikos" to "00793f,ffffff",
        "paraguay" to "ff000f",
        "parigi" to "004170,ffffff,da291c",
        "paris saint germain" to "004170,ffffff,da291c",
        "paris sg" to "004170,ffffff,da291c",
        "parma" to "ffffff,1b4094,ffd200",
        "philadelphia 76ers" to "006bb6,ffffff",
        "phoenix suns" to "1d1160,e56020",
        "poland" to "dc143c,ffffff",
        "polonia" to "dc143c,ffffff",
        "portland trail blazers" to "e03a3e,000000",
        "porto" to "00428c,ffffff,d60019",
        "portogallo" to "ff0000,006200,ffffff",
        "portugal" to "ff0000,006200,ffffff",
        "psg" to "004170,ffffff,da291c",
        "psv" to "e11f26,ffffff,bb955e",
        "psv eindhoven" to "e11f26,ffffff,bb955e",
        "qarabag" to "000000,ffffff",
        "qatar" to "8a1538,ffffff",
        "rayo" to "e53027,ffffff",
        "rayo vallecano" to "e53027,ffffff",
        "rb leipzig" to "dd013f,ffffff,0c2043",
        "real betis" to "0bb363,ffffff",
        "real madrid" to "ffffff,00529f",
        "real sociedad" to "0067b1,ffffff",
        "real valladolid" to "931b89,ffffff",
        "rennes" to "e13327,000000,fcbc17",
        "roma" to "8e1f2f,f0bc42,cacacc",
        "royal antwerp" to "df172b,ffffff",
        "sacramento kings" to "5a2b81,63727a",
        "saint etienne" to "1d995b,ffffff",
        "salernitana" to "681a12,ffffff",
        "sampdoria" to "1b5497,c21718,000000",
        "san antonio spurs" to "000000,c4ced4",
        "sassuolo" to "00a752,000000",
        "saudi arabia" to "125b34,7ec8ae",
        "schalke" to "004d9d,ffffff",
        "schalke 04" to "004d9d,ffffff",
        "senegal" to "11a335,ffdc00,e63f23",
        "serbia" to "b72e3e,b49d5a,ffffff",
        "sevilla" to "ffffff,f43333,c79100",
        "sheffield united" to "ee2737,ffffff,0d171a",
        "siviglia" to "ffffff,f43333,c79100",
        "sociedad" to "0067b1,ffffff",
        "south korea" to "ec0f32,ffffff,021858",
        "southampton" to "d71920,ffffff,000000",
        "spagna" to "8b0d11,fcb507,021250",
        "spain" to "8b0d11,fcb507,021250",
        "spezia" to "ffffff,99834a,1f1a17",
        "sporting" to "008057,ffffff,f3c242",
        "sporting lisbona" to "008057,ffffff,f3c242",
        "st pauli" to "624839,e30613,ffffff",
        "stati uniti" to "1f2742,ffffff,bb2533",
        "stoccarda" to "e32219,ffffff,ffed00",
        "strasbourg" to "009fe3,dc2f34,ffffff",
        "strasburgo" to "009fe3,dc2f34,ffffff",
        "stuttgart" to "e32219,ffffff,ffed00",
        "sunderland" to "ff0000,ffffff,000000",
        "svizzera" to "ff0000,ffffff,740c14",
        "switzerland" to "ff0000,ffffff,740c14",
        "tolosa" to "492359,ffffff,f0194e",
        "torino" to "8a1e03,ffffff,eeb111",
        "toronto raptors" to "ce1141,000000",
        "tottenham" to "ffffff,132257",
        "tottenham hotspur" to "ffffff,132257",
        "toulouse" to "492359,ffffff,f0194e",
        "tunisia" to "e70013,ffffff",
        "twente" to "e6001a,ffffff",
        "udinese" to "ffffff,000000,8b7d37",
        "union berlin" to "eb1923,ffffff,fddc02",
        "union berlino" to "eb1923,ffffff,fddc02",
        "union saint gilloise" to "f5d128,11719a",
        "union sg" to "f5d128,11719a",
        "united states" to "1f2742,ffffff,bb2533",
        "uruguay" to "55b5e5,ffffff,000000",
        "usa" to "1f2742,ffffff,bb2533",
        "utah jazz" to "002b5c,00471b,f9a01b",
        "utrecht" to "d82333,ffffff,000000",
        "valencia" to "ff671f,000000",
        "venezia" to "101010,ff6b00,006937",
        "verona" to "ffe74a,005395",
        "villarreal" to "005187,ffe667",
        "virtus bologna" to "ffffff,000000",
        "virtus pallacanestro bologna" to "ffffff,000000",
        "wales" to "174a3f,ae2630,cf1e26",
        "washington wizards" to "002b5c,e31837",
        "watford" to "fbee23,11210c,ed2127",
        "werder" to "1d9053,ffffff",
        "werder bremen" to "1d9053,ffffff",
        "west ham" to "7c2c3b,2dafe5,ffffff",
        "west ham united" to "7c2c3b,2dafe5,ffffff",
        "wolfsburg" to "09633d,ffffff",
        "wolverhampton" to "fdb913,231f20,ffffff",
        "wolverhampton wanderers" to "fdb913,231f20,ffffff",
        "wolves" to "fdb913,231f20,ffffff"
    )

    /** Badge competizioni: keyword cercata nella label del sito, dalla più lunga. */
    private val leagueLogos: List<Pair<String, String>> = listOf(
        "nations league" to "https://r2.thesportsdb.com/images/media/league/badge/cwsp321698386224.png",
        "premier league" to "https://r2.thesportsdb.com/images/media/league/badge/gasy9d1737743125.png",
        "qualificazioni" to "https://r2.thesportsdb.com/images/media/league/badge/iyk1861730133378.png",
        "europa league" to "https://r2.thesportsdb.com/images/media/league/badge/mlsr7d1718774547.png",
        "championship" to "https://r2.thesportsdb.com/images/media/league/badge/ty5a681688770169.png",
        "copa del rey" to "https://r2.thesportsdb.com/images/media/league/badge/2ikh3a1671782958.png",
        "coppa italia" to "https://r2.thesportsdb.com/images/media/league/badge/hrm1vo1692679408.png",
        "premiership" to "https://r2.thesportsdb.com/images/media/league/badge/72d3zc1688333496.png",
        "amichevoli" to "https://r2.thesportsdb.com/images/media/league/badge/gb18781565430778.png",
        "bundesliga" to "https://r2.thesportsdb.com/images/media/league/badge/teqh1b1679952008.png",
        "conference" to "https://r2.thesportsdb.com/images/media/league/badge/ymfo5j1718775759.png",
        "eredivisie" to "https://r2.thesportsdb.com/images/media/league/badge/5cdsu21725984946.png",
        "euroleague" to "https://r2.thesportsdb.com/images/media/league/badge/7xjtuy1554397263.png",
        "pro league" to "https://r2.thesportsdb.com/images/media/league/badge/mjit7n1593634474.png",
        "supercoppa" to "https://r2.thesportsdb.com/images/media/league/badge/12hc5x1730129564.png",
        "champions" to "https://r2.thesportsdb.com/images/media/league/badge/facv1u1742998896.png",
        "super lig" to "https://r2.thesportsdb.com/images/media/league/badge/ifm3zc1779990699.png",
        "world cup" to "https://r2.thesportsdb.com/images/media/league/badge/e7er5g1696521789.png",
        "eurolega" to "https://r2.thesportsdb.com/images/media/league/badge/7xjtuy1554397263.png",
        "mondiali" to "https://r2.thesportsdb.com/images/media/league/badge/e7er5g1696521789.png",
        "primeira" to "https://www.thesportsdb.com/images/media/league/badge/3tgdke1782689102.png",
        "ligue 1" to "https://r2.thesportsdb.com/images/media/league/badge/9f7z9d1742983155.png",
        "serie a" to "https://r2.thesportsdb.com/images/media/league/badge/67q3q21679951383.png",
        "serie b" to "https://r2.thesportsdb.com/images/media/league/badge/uf5kph1598011132.png",
        "fa cup" to "https://r2.thesportsdb.com/images/media/league/badge/vk7isd1598802862.png",
        "liga" to "https://r2.thesportsdb.com/images/media/league/badge/ja4it51687628717.png",
        "nba" to "https://r2.thesportsdb.com/images/media/league/badge/frdjqy1536585083.png"
    )

    /** Loghi canali TV (repo tv-logo/tv-logos), keyword cercata nel nome del canale. */
    private val channelLogos: List<Pair<String, String>> = listOf(
        "rai sport" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/rai-sport-it.png",
        "sport max" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-max-it.png",
        "sport mix" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-mix-it.png",
        "cinema 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-cinema-uno-it.png",
        "football" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-football-it.png",
        "sport 24" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-24-it.png",
        "serie a" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-serie-a-it.png",
        "sport 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-uno-it.png",
        "action" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-action-it.png",
        "basket" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-nba-it.png",
        "calcio" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-calcio-it.png",
        "tennis" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-tennis-it.png",
        "arena" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-arena-it.png",
        "rai 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/rai-1-it.png",
        "rai 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/rai-2-it.png",
        "rai 3" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/rai-3-it.png",
        "dazn" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/zona-dazn-it.png",
        "golf" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-golf-it.png",
        "moto" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-motogp-it.png",
        "f1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-f1-it.png"
    )
}
