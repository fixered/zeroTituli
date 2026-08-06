package it.zeroTituli

import it.zeroTituli.BasketLeagues.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'API scrive i nomi dei campionati in inglese e la forma cambia da una stagione all'altra: i
 * casi qui sotto sono le varianti plausibili viste sulle fonti di questo provider.
 */
class BasketLeaguesTest {

    @Test
    fun `serie a1 nelle sue varianti`() {
        listOf(
            "Italy Lega A Basketball" to "Italy",
            "Lega Basket Serie A" to "Italy",
            "Italy Serie A" to "Italy",
            "Italy Serie A1" to "Italy",
            "Italian Serie A Basketball" to ""
        ).forEach { (league, country) ->
            assertEquals(league, Category.SERIE_A1, BasketLeagues.of(league, country))
        }
    }

    @Test
    fun `serie a2 non finisce in a1`() {
        listOf(
            "Italy Serie A2" to "Italy",
            "Italy Serie A2 Basketball" to "Italy",
            "LNP Serie A2 Old Wild West" to "Italy"
        ).forEach { (league, country) ->
            assertEquals(league, Category.SERIE_A2, BasketLeagues.of(league, country))
        }
    }

    @Test
    fun `nba senza le leghe collegate`() {
        assertEquals(Category.NBA, BasketLeagues.of("NBA", "USA"))
        assertEquals(Category.NBA, BasketLeagues.of("NBA Preseason", "USA"))
        assertEquals(Category.NBA, BasketLeagues.of("NBA Summer League", "USA"))
        listOf("WNBA", "NBA G League", "NBA 2K League").forEach {
            assertEquals(it, Category.OTHER, BasketLeagues.of(it, "USA"))
        }
    }

    /** La Serie A1 femminile ha lo stesso nome della maschile: deve restare fuori. */
    @Test
    fun `femminili e giovanili restano negli altri eventi`() {
        listOf(
            "Italy Serie A1 Women",
            "Italy Serie A2 Women",
            "Italy Serie A U19",
            "NBA Women"
        ).forEach { assertEquals(it, Category.OTHER, BasketLeagues.of(it, "Italy")) }
    }

    /** Serie A di un altro paese, o italiane che non sono A1/A2, non entrano nelle categorie. */
    @Test
    fun `altri campionati restano negli altri eventi`() {
        listOf(
            "Chile Liga Nacional Basketball" to "Chile",
            "BIG3 Basketball" to "USA",
            "Spain ACB" to "Spain",
            "Euroleague" to "Europe",
            "Italy Serie B" to "Italy",
            "Italy Coppa Italia" to "Italy",
            "Brazil NBB" to "Brazil"
        ).forEach { (league, country) ->
            assertEquals(league, Category.OTHER, BasketLeagues.of(league, country))
        }
    }

    @Test
    fun `campionato vuoto non rompe niente`() {
        assertEquals(Category.OTHER, BasketLeagues.of("", ""))
        assertEquals(Category.OTHER, BasketLeagues.of("   ", "Italy"))
    }
}
