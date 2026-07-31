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
        if (cleanMeta.isNotBlank()) lines += wrap(cleanMeta, LINE_CHARS, 1)
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
        kept[maxLines - 1] = kept[maxLines - 1].dropLast(1) + "…"
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
        return leagueLogos.firstOrNull { (kw, _) -> key.contains(kw) }?.second
    }

    private fun channelBadge(channelName: String): String? {
        val key = normalizeLoose(channelName)
        if (key.isBlank()) return null
        return channelLogos.firstOrNull { (kw, _) -> key.contains(kw) }?.second
    }

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

    // <TEAM_DATA>

    // <LEAGUE_DATA>

    // <CHANNEL_DATA>
}
