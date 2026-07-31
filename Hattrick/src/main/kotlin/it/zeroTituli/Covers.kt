package it.zeroTituli

import java.net.URLEncoder
import java.text.Normalizer

/**
 * Copertine degli eventi.
 *
 * htsport.org non pubblica immagini, quindi le copertine vengono composte al volo a partire da
 * tabelle di loghi raccolte offline (vedi docs/superpowers/specs/2026-07-31-copertine-eventi-design.md):
 * il plugin non fa nessuna richiesta HTTP, costruisce solo l'url. Il download è quello della
 * card, uno solo, fatto e messo in cache dal caricatore di immagini dell'app.
 *
 * Il fondo con il testo arriva da placehold.jp (dimensione del font nel path, così il testo non
 * viene ingigantito per riempire la larghezza); i loghi ci vengono sovrapposti da
 * quickchart.io/watermark, annidato due volte quando le squadre sono due.
 */
internal object Covers {

    private const val CANVAS = "600x338"
    private const val BG = "1a1a2e"
    private const val FG = "ffffff"
    private const val FONT = 22
    private const val LINE_CHARS = 26
    private const val META_LINE_CHARS = 30
    private const val TITLE_MAX_LINES = 2

    // QuickChart accetta solo topLeft/topRight/bottomLeft/bottomRight/center:
    // gli altri valori vengono ignorati e il logo finisce in basso a destra.
    private const val POS_LEFT = "topLeft"
    private const val POS_RIGHT = "topRight"
    private const val POS_CENTER = "center"

    private const val RATIO_PAIR = "0.26"
    private const val RATIO_SINGLE = "0.26"
    private const val RATIO_CHANNEL = "0.40"
    private const val MARGIN_PAIR = 70

    /** Righe vuote in cima: spingono il testo in basso, sotto i loghi. */
    private const val BLANK_LINES_PAIR = 6
    private const val BLANK_LINES_CENTER = 9

    // ============= API =============

    /** Copertina di un match: loghi delle due squadre, o del campionato, o solo testo. */
    fun forMatch(title: String, league: String, whenLabel: String): String {
        val meta = listOf(whenLabel, shortLeague(league))
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val badges = teamBadges(title)
        return when {
            badges.size >= 2 -> {
                val base = textCover(title, meta, BLANK_LINES_PAIR)
                val left = watermark(base, badges[0], POS_LEFT, RATIO_PAIR, MARGIN_PAIR)
                watermark(left, badges[1], POS_RIGHT, RATIO_PAIR, MARGIN_PAIR)
            }
            badges.size == 1 -> centered(title, meta, badges[0], RATIO_SINGLE)
            else -> {
                val leagueBadge = leagueBadge(league)
                if (leagueBadge != null) centered(title, meta, leagueBadge, RATIO_SINGLE)
                else textCover(title, meta, 0)
            }
        }
    }

    /** Copertina di un canale sempre attivo: logo della rete, nome sotto. */
    fun forChannel(channelName: String): String {
        val badge = channelBadge(channelName)
        return if (badge != null) centered(channelName, "", badge, RATIO_CHANNEL)
        else textCover(channelName, "", 0)
    }

    // ============= COSTRUZIONE URL =============

    /** Il sito scrive "CALCIO - AMICHEVOLI": sulla copertina basta la parte specifica. */
    private fun shortLeague(league: String): String {
        val parts = league.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.drop(1).joinToString(" - ") else league.trim()
    }

    private fun centered(title: String, meta: String, badge: String, ratio: String): String =
        watermark(textCover(title, meta, BLANK_LINES_CENTER), badge, POS_CENTER, ratio, 0)

    private fun textCover(title: String, meta: String, blankLines: Int): String {
        val lines = mutableListOf<String>()
        repeat(blankLines) { lines += "" }
        lines += wrap(cleanText(title), LINE_CHARS, TITLE_MAX_LINES)
        val cleanMeta = cleanText(meta)
        if (cleanMeta.isNotBlank()) lines += wrap(cleanMeta, META_LINE_CHARS, 1)
        val text = lines.joinToString("\n")
        return "https://placehold.jp/$FONT/$BG/$FG/$CANVAS.png?text=${enc(text)}"
    }

    private fun watermark(
        main: String,
        mark: String,
        position: String,
        ratio: String,
        margin: Int
    ): String = "https://quickchart.io/watermark?mainImageUrl=${enc(main)}" +
        "&markImageUrl=${enc(mark)}&markRatio=$ratio&position=$position&margin=$margin&opacity=1"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Sul fondo non devono finire url o separatori interni. */
    private fun cleanText(s: String): String = s
        .replace(Regex("""https?://\S+"""), " ")
        .replace(Regex("""[§¤¦]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

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
        // il carattere si toglie solo se non c'è posto per i puntini
        kept[maxLines - 1] = if (last.length < lineChars) "$last…" else last.dropLast(1) + "…"
        return kept
    }

    // ============= RICERCA LOGHI =============

    /** "Casa - Ospite" → badge trovati, nell'ordine in cui compaiono nel titolo. */
    private fun teamBadges(title: String): List<String> {
        val parts = splitTeams(title)
        if (parts.isEmpty()) return emptyList()
        return parts.mapNotNull { teamBadge(it) }.distinct()
    }

    private fun splitTeams(title: String): List<String> {
        val cleaned = cleanText(title)
            .replace(Regex("""\s+vs\.?\s+""", RegexOption.IGNORE_CASE), " - ")
            .replace(" – ", " - ")
            .replace(" · ", " - ")
        val parts = cleaned.split(" - ").map { it.trim() }.filter { it.length >= 2 }
        return if (parts.size == 2) parts else emptyList()
    }

    private fun teamBadge(rawName: String): String? {
        val key = normalize(rawName)
        if (key.length < 3) return null
        teamLogos[key]?.let { return it }
        // Ricerca tollerante: il sito abbrevia ("Man Utd") o aggiunge parole ("Inter Milan").
        // Il confronto è su parole intere, altrimenti "Romania" pescherebbe il logo della Roma.
        val padded = " $key "
        val candidate = teamLogos.keys
            .filter { k -> k.length >= 5 && (padded.contains(" $k ") || " $k ".contains(padded)) }
            .maxByOrNull { it.length }
        return candidate?.let { teamLogos[it] }
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
        "fc", "ac", "as", "cf", "sc", "ss", "us", "sv", "vfl", "vfb", "bsc", "tsg",
        "calcio", "club", "cp", "sad", "afc", "cfc", "hsv", "if", "ff", "bk", "sk"
    )

    /** Normalizzazione dei nomi squadra: stessa usata per generare le chiavi della tabella. */
    fun normalize(s: String): String =
        normalizeLoose(s).split(" ").filter { it.isNotBlank() && it !in noiseWords }.joinToString(" ")

    /**
     * Variante senza rimozione delle parole rumore, per campionati e canali: lì "calcio" è
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
        "alaves" to "https://r2.thesportsdb.com/images/media/team/badge/0aaifo1734673843.png",
        "alaves gloriosas" to "https://r2.thesportsdb.com/images/media/team/badge/0aaifo1734673843.png",
        "albania" to "https://r2.thesportsdb.com/images/media/team/badge/vonacg1717365654.png",
        "algeria" to "https://r2.thesportsdb.com/images/media/team/badge/rrwpry1455460218.png",
        "alkmaar" to "https://r2.thesportsdb.com/images/media/team/badge/wtqwvv1473534757.png",
        "amburgo" to "https://r2.thesportsdb.com/images/media/team/badge/tvtppt1473453296.png",
        "anadolu efes" to "https://r2.thesportsdb.com/images/media/team/badge/uldz0d1782050729.png",
        "anderlecht" to "https://r2.thesportsdb.com/images/media/team/badge/auindn1771129464.png",
        "angers" to "https://r2.thesportsdb.com/images/media/team/badge/ix6q4w1678808069.png",
        "antwerp" to "https://r2.thesportsdb.com/images/media/team/badge/gawwcf1691182178.png",
        "anversa" to "https://r2.thesportsdb.com/images/media/team/badge/gawwcf1691182178.png",
        "apoel" to "https://r2.thesportsdb.com/images/media/team/badge/yki8pi1637980869.png",
        "apoel b c" to "https://r2.thesportsdb.com/images/media/team/badge/yki8pi1637980869.png",
        "apoel nicosia" to "https://r2.thesportsdb.com/images/media/team/badge/yki8pi1637980869.png",
        "arabia saudita" to "https://r2.thesportsdb.com/images/media/team/badge/24xwpq1594125742.png",
        "argentina" to "https://r2.thesportsdb.com/images/media/team/badge/3zplhu1726167477.png",
        "armani milano" to "https://www.thesportsdb.com/images/media/team/badge/ju4ohs1782551366.png",
        "arsenal" to "https://r2.thesportsdb.com/images/media/team/badge/uyhbfe1612467038.png",
        "aston villa" to "https://www.thesportsdb.com/images/media/team/badge/97mehy1784645865.png",
        "asvel" to "https://r2.thesportsdb.com/images/media/team/badge/830lrz1757669895.png",
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
        "brighton" to "https://r2.thesportsdb.com/images/media/team/badge/zn0x7h1605371909.png",
        "brighton wfc" to "https://r2.thesportsdb.com/images/media/team/badge/zn0x7h1605371909.png",
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
        "columbus clippers" to "https://r2.thesportsdb.com/images/media/team/badge/pzomwi1655402625.png",
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
        "hearts" to "https://r2.thesportsdb.com/images/media/team/badge/5kduda1657283329.png",
        "hearts b" to "https://r2.thesportsdb.com/images/media/team/badge/5kduda1657283329.png",
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
        "inter" to "https://www.thesportsdb.com/images/media/team/badge/plo1hz1784764806.png",
        "inter milan" to "https://www.thesportsdb.com/images/media/team/badge/plo1hz1784764806.png",
        "intercity" to "https://www.thesportsdb.com/images/media/team/badge/plo1hz1784764806.png",
        "internazionale" to "https://www.thesportsdb.com/images/media/team/badge/plo1hz1784764806.png",
        "ipswich" to "https://r2.thesportsdb.com/images/media/team/badge/8wvpj61701761979.png",
        "ipswich jets" to "https://r2.thesportsdb.com/images/media/team/badge/8wvpj61701761979.png",
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
        "la clippers" to "https://r2.thesportsdb.com/images/media/team/badge/pzomwi1655402625.png",
        "la lakers" to "https://r2.thesportsdb.com/images/media/team/badge/d8uoxw1714254511.png",
        "lazio" to "https://r2.thesportsdb.com/images/media/team/badge/rwqyvs1448806608.png",
        "le havre" to "https://r2.thesportsdb.com/images/media/team/badge/aikowk1546475003.png",
        "lecce" to "https://r2.thesportsdb.com/images/media/team/badge/j4vznr1567365249.png",
        "lech" to "https://r2.thesportsdb.com/images/media/team/badge/8zfxyx1685597440.png",
        "lech poznan" to "https://r2.thesportsdb.com/images/media/team/badge/8zfxyx1685597440.png",
        "leeds" to "https://r2.thesportsdb.com/images/media/team/badge/wwypqp1425326584.png",
        "leeds force" to "https://r2.thesportsdb.com/images/media/team/badge/wwypqp1425326584.png",
        "leeds united" to "https://r2.thesportsdb.com/images/media/team/badge/wwypqp1425326584.png",
        "legia" to "https://r2.thesportsdb.com/images/media/team/badge/c969ez1632775656.png",
        "legia varsavia" to "https://r2.thesportsdb.com/images/media/team/badge/c969ez1632775656.png",
        "legia warsaw" to "https://r2.thesportsdb.com/images/media/team/badge/c969ez1632775656.png",
        "leicester" to "https://r2.thesportsdb.com/images/media/team/badge/wo3udr1586957093.png",
        "leicester u21" to "https://r2.thesportsdb.com/images/media/team/badge/wo3udr1586957093.png",
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
        "los angeles clippers" to "https://r2.thesportsdb.com/images/media/team/badge/pzomwi1655402625.png",
        "los angeles lakers" to "https://r2.thesportsdb.com/images/media/team/badge/d8uoxw1714254511.png",
        "ludogorets" to "https://r2.thesportsdb.com/images/media/team/badge/35cw6l1752946925.png",
        "ludogorets razgrad" to "https://r2.thesportsdb.com/images/media/team/badge/35cw6l1752946925.png",
        "lyon" to "https://r2.thesportsdb.com/images/media/team/badge/blk9771656932845.png",
        "lyon feminin" to "https://r2.thesportsdb.com/images/media/team/badge/830lrz1757669895.png",
        "maccabi" to "https://r2.thesportsdb.com/images/media/team/badge/oeer261781239315.png",
        "maccabi tel aviv" to "https://r2.thesportsdb.com/images/media/team/badge/oeer261781239315.png",
        "magonza" to "https://r2.thesportsdb.com/images/media/team/badge/fhm9v51552134916.png",
        "mainz" to "https://r2.thesportsdb.com/images/media/team/badge/fhm9v51552134916.png",
        "maiorca" to "https://r2.thesportsdb.com/images/media/team/badge/ssptsx1473503730.png",
        "mallorca" to "https://r2.thesportsdb.com/images/media/team/badge/ssptsx1473503730.png",
        "malmo" to "https://r2.thesportsdb.com/images/media/team/badge/bpx8gk1738226331.png",
        "malmo women" to "https://r2.thesportsdb.com/images/media/team/badge/bpx8gk1738226331.png",
        "malmoe" to "https://r2.thesportsdb.com/images/media/team/badge/bpx8gk1738226331.png",
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
        "midtjylland" to "https://www.thesportsdb.com/images/media/team/badge/lvkl8g1783533167.png",
        "midtjylland women" to "https://www.thesportsdb.com/images/media/team/badge/lvkl8g1783533167.png",
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
        "newcastle" to "https://r2.thesportsdb.com/images/media/team/badge/5jevk91653487832.png",
        "newcastle jets" to "https://r2.thesportsdb.com/images/media/team/badge/5jevk91653487832.png",
        "newcastle united" to "https://r2.thesportsdb.com/images/media/team/badge/5jevk91653487832.png",
        "nice" to "https://r2.thesportsdb.com/images/media/team/badge/msy7ly1621593859.png",
        "nigeria" to "https://r2.thesportsdb.com/images/media/team/badge/qruyxr1455466056.png",
        "nizza" to "https://r2.thesportsdb.com/images/media/team/badge/msy7ly1621593859.png",
        "norvegia" to "https://r2.thesportsdb.com/images/media/team/badge/gyfn811591973155.png",
        "norway" to "https://r2.thesportsdb.com/images/media/team/badge/gyfn811591973155.png",
        "norwich" to "https://r2.thesportsdb.com/images/media/team/badge/tm4llu1654601705.png",
        "norwich u23" to "https://r2.thesportsdb.com/images/media/team/badge/tm4llu1654601705.png",
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
        "parigi" to "https://r2.thesportsdb.com/images/media/team/badge/has8b01763050866.png",
        "paris" to "https://r2.thesportsdb.com/images/media/team/badge/yuvtsy1447594254.png",
        "paris saint germain" to "https://r2.thesportsdb.com/images/media/team/badge/has8b01763050866.png",
        "paris sg" to "https://r2.thesportsdb.com/images/media/team/badge/has8b01763050866.png",
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
        "porto" to "https://r2.thesportsdb.com/images/media/team/badge/w8yspj1705225875.png",
        "porto juniors" to "https://r2.thesportsdb.com/images/media/team/badge/w8yspj1705225875.png",
        "portogallo" to "https://r2.thesportsdb.com/images/media/team/badge/swqvpy1455466083.png",
        "portugal" to "https://r2.thesportsdb.com/images/media/team/badge/swqvpy1455466083.png",
        "psg" to "https://r2.thesportsdb.com/images/media/team/badge/has8b01763050866.png",
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
        "stati uniti" to "https://r2.thesportsdb.com/images/media/team/badge/86mluc1731001482.png",
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
        "torcy" to "https://r2.thesportsdb.com/images/media/team/badge/has8b01763050866.png",
        "torino" to "https://r2.thesportsdb.com/images/media/team/badge/xxprty1448806802.png",
        "toronto raptors" to "https://r2.thesportsdb.com/images/media/team/badge/lct96a1778227205.png",
        "tottenham" to "https://r2.thesportsdb.com/images/media/team/badge/3dhd0j1605371995.png",
        "tottenham hotspur" to "https://r2.thesportsdb.com/images/media/team/badge/3dhd0j1605371995.png",
        "tottenham women" to "https://r2.thesportsdb.com/images/media/team/badge/3dhd0j1605371995.png",
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
        "united states" to "https://r2.thesportsdb.com/images/media/team/badge/86mluc1731001482.png",
        "united states u17" to "https://r2.thesportsdb.com/images/media/team/badge/86mluc1731001482.png",
        "uruguay" to "https://r2.thesportsdb.com/images/media/team/badge/6vjbr11726167756.png",
        "usa" to "https://r2.thesportsdb.com/images/media/team/badge/86mluc1731001482.png",
        "utah jazz" to "https://r2.thesportsdb.com/images/media/team/badge/trct8w1778198868.png",
        "utrecht" to "https://r2.thesportsdb.com/images/media/team/badge/yuhha71625167104.png",
        "valencia" to "https://r2.thesportsdb.com/images/media/team/badge/dm8l6o1655594864.png",
        "venezia" to "https://r2.thesportsdb.com/images/media/team/badge/vbiget1781026964.png",
        "verona" to "https://r2.thesportsdb.com/images/media/team/badge/p6camf1593457737.png",
        "viktoria plzen" to "https://r2.thesportsdb.com/images/media/team/badge/at8i2h1679265942.png",
        "villarreal" to "https://r2.thesportsdb.com/images/media/team/badge/vrypqy1473503073.png",
        "virtus bologna" to "https://r2.thesportsdb.com/images/media/team/badge/kqfwqq1659402893.png",
        "virtus bologna basketball women" to "https://r2.thesportsdb.com/images/media/team/badge/kqfwqq1659402893.png",
        "wales" to "https://r2.thesportsdb.com/images/media/team/badge/pdayn21591983222.png",
        "washington wizards" to "https://r2.thesportsdb.com/images/media/team/badge/dxac7a1778227278.png",
        "watford" to "https://r2.thesportsdb.com/images/media/team/badge/rsuswy1448813519.png",
        "werder" to "https://r2.thesportsdb.com/images/media/team/badge/tkvqan1716960454.png",
        "werder bremen" to "https://r2.thesportsdb.com/images/media/team/badge/tkvqan1716960454.png",
        "west ham" to "https://r2.thesportsdb.com/images/media/team/badge/hfum4l1599931799.png",
        "west ham women" to "https://r2.thesportsdb.com/images/media/team/badge/hfum4l1599931799.png",
        "wolfsburg" to "https://r2.thesportsdb.com/images/media/team/badge/ci9trv1778399557.png",
        "wolverhampton" to "https://r2.thesportsdb.com/images/media/team/badge/16posu1727839976.png",
        "wolves" to "https://r2.thesportsdb.com/images/media/team/badge/16posu1727839976.png",
        "young boys" to "https://r2.thesportsdb.com/images/media/team/badge/9mxdoo1534784569.png",
        "zagabria" to "https://www.thesportsdb.com/images/media/team/badge/zcb6f61784988620.png",
        "zalgiris" to "https://r2.thesportsdb.com/images/media/team/badge/x3v4nc1726761217.png",
        "zalgiris kaunas" to "https://r2.thesportsdb.com/images/media/team/badge/dn7ouv1703960565.png",
        "zalgiris youth" to "https://r2.thesportsdb.com/images/media/team/badge/x3v4nc1726761217.png",
        "zurich" to "https://r2.thesportsdb.com/images/media/team/badge/af50gk1779213314.png",
        "zurigo" to "https://r2.thesportsdb.com/images/media/team/badge/af50gk1779213314.png"
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
