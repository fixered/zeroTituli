package it.zeroTituli.shared

/**
 * Quali partite mostrare: il palinsesto delle fonti contiene centinaia di campionati minori.
 *
 * Passa se il campionato è tra quelli seguiti — con vincolo sul paese, perché "Premier League"
 * da solo pesca anche Armenia, Libano e Nuova Zelanda — oppure se gioca una squadra importante,
 * così amichevoli e coppe delle big entrano comunque. Femminili, giovanili e seconde squadre
 * restano fuori.
 */
internal object MatchFilter {

    /**
     * Massime serie: passano da sole. Le coppe nazionali no, perché nei primi turni ci sono le
     * serie minori (Tranmere - Rochdale in EFL Cup): entrano solo se gioca una big.
     */
    private val topFlightByCountry = mapOf(
        "italy" to listOf("serie a"),
        "england" to listOf("premier league"),
        "spain" to listOf("la liga", "laliga", "primera division"),
        "germany" to listOf("bundesliga"),
        "france" to listOf("ligue 1"),
        "netherlands" to listOf("eredivisie"),
        "portugal" to listOf("primeira liga", "liga portugal"),
        "turkey" to listOf("super lig")
    )

    /** Competizioni internazionali, senza vincolo di paese. */
    private val internationalLeagues = listOf(
        "champions league", "europa league", "conference league", "uefa super cup",
        "nations league", "european championship", "euro qualifiers", "european qualifiers",
        "world cup", "world cup qualifying", "copa america", "copa libertadores",
        "club world cup", "intercontinental"
    )

    /**
     * Le amichevoli non passano per campionato: entrano solo se le gioca una squadra importante,
     * altrimenti arriverebbe ogni amichevole di ogni serie minore.
     */
    private val excludedWords = listOf(
        "women", "womens", "w", "femminile", "feminine", "youth", "junior", "juniors",
        "reserves", "reserve", "primavera", "u17", "u18", "u19", "u20", "u21", "u23", "futsal"
    )

    fun isInteresting(league: String, country: String, home: String, away: String): Boolean {
        if (isExcluded(league) || isExcluded(home) || isExcluded(away)) return false
        if (isFollowedLeague(league, country)) return true
        return Covers.isMajorTeam(home) || Covers.isMajorTeam(away)
    }

    fun isFollowedLeague(league: String, country: String): Boolean {
        val name = Covers.normalizeLoose(league)
        if (internationalLeagues.any { containsWords(name, it) }) return true
        val allowed = topFlightByCountry[Covers.normalizeLoose(country)] ?: return false
        return allowed.any { containsWords(name, it) }
    }

    private fun isExcluded(text: String): Boolean {
        val words = Covers.normalizeLoose(text).split(" ")
        return words.any { it in excludedWords }
    }

    private fun containsWords(haystack: String, needle: String): Boolean =
        needle.isNotBlank() && " $haystack ".contains(" ${Covers.normalizeLoose(needle)} ")
}
