package it.zeroTituli

import it.zeroTituli.shared.Covers

/**
 * A quale sezione della home appartiene una partita di basket.
 *
 * L'API risponde in inglese e i nomi dei campionati cambiano forma da una stagione all'altra
 * ("Italy Lega A Basketball", "Lega Basket Serie A", "Italy Serie A"), per cui il confronto è su
 * parole intere con più alias. Quello che non rientra in nessuna categoria finisce in [OTHER],
 * che sulla home è "Altri eventi": se un alias non basta la partita si vede comunque, solo in
 * un'altra sezione.
 */
internal object BasketLeagues {

    enum class Category { SERIE_A1, SERIE_A2, NBA, OTHER }

    /** Femminili e giovanili restano fuori dalle categorie: la Serie A1 femminile esiste. */
    private val excludedWords = listOf(
        "women", "womens", "w", "femminile", "feminine", "girls", "youth", "junior", "juniors",
        "u14", "u15", "u16", "u17", "u18", "u19", "u20", "u21", "u22", "u23"
    )

    private val serieA1Aliases = listOf(
        "serie a", "serie a1", "a1", "lega a", "lba", "lega basket serie a", "legabasket"
    )

    /** Non basta "lnp": la Lega Nazionale Pallacanestro organizza anche la Serie B. */
    private val serieA2Aliases = listOf("serie a2", "a2")

    /** L'NBA vera: la G League, la WNBA e la lega di 2K sono altri campionati. */
    private val nbaExcluded = listOf("g league", "2k", "esports", "efootball")

    fun of(league: String, country: String): Category {
        val name = Covers.normalizeLoose(league)
        val place = Covers.normalizeLoose(country)
        if (name.isBlank()) return Category.OTHER
        if (excludedWords.any { hasWord(name, it) }) return Category.OTHER

        if (isItaly(place, name)) {
            // Prima la A2: "serie a2" non passa per l'alias "serie a" (il confronto è su parole
            // intere), ma "LNP Serie A2 Old Wild West" sì.
            if (serieA2Aliases.any { hasWord(name, it) }) return Category.SERIE_A2
            if (serieA1Aliases.any { hasWord(name, it) }) return Category.SERIE_A1
        }

        if (hasWord(name, "nba") && nbaExcluded.none { hasWord(name, it) }) return Category.NBA

        return Category.OTHER
    }

    /** Il paese a volte arriva vuoto: allora vale il nome del campionato. */
    private fun isItaly(country: String, league: String): Boolean =
        country == "italy" || country == "italia" ||
            hasWord(league, "italy") || hasWord(league, "italia") || hasWord(league, "italian")

    /** Confronto su parole intere: "a2" non deve pescare "a20", "nba" non deve pescare "wnba". */
    private fun hasWord(haystack: String, needle: String): Boolean =
        needle.isNotBlank() && " $haystack ".contains(" $needle ")
}
