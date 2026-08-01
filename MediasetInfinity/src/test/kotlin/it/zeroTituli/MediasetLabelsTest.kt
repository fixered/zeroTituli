package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetLabelsTest {

    private fun entry(
        plot: String? = "Trama",
        genres: List<String> = listOf("Commedia", "Fiction"),
        free: Boolean = true,
    ) = FeedEntry(
        guid = "g1",
        title = "Titolo",
        longDescription = plot,
        // `isFree` guarda `rights`: senza "AVOD" il contenuto è a pagamento.
        rights = if (free) listOf("AVOD") else listOf("SVOD"),
        genreList = genres,
    )

    @Test
    fun `voce gratuita, i tag sono esattamente i generi, nell ordine, senza Abbonamento`() {
        val out = entry(free = true, genres = listOf("Commedia", "Fiction"))
        assertEquals(listOf("Commedia", "Fiction"), MediasetLabels.tags(out))
        assertFalse(MediasetLabels.tags(out).contains("Abbonamento"))
    }

    @Test
    fun `voce gratuita, la descrizione e la trama invariata`() {
        val out = entry(free = true, plot = "Una trama qualsiasi")
        assertEquals("Una trama qualsiasi", MediasetLabels.description(out))
    }

    @Test
    fun `voce a pagamento, Abbonamento e il primo tag e i generi seguono nell ordine`() {
        val out = entry(free = false, genres = listOf("Commedia", "Fiction"))
        assertEquals(listOf("Abbonamento", "Commedia", "Fiction"), MediasetLabels.tags(out))
    }

    @Test
    fun `voce a pagamento, la descrizione inizia con l avviso e contiene ancora la trama`() {
        val out = entry(free = false, plot = "Una trama qualsiasi")
        val description = MediasetLabels.description(out)!!
        assertTrue(description.startsWith("Serve un abbonamento o un noleggio Mediaset Infinity"))
        assertTrue(description.contains("Una trama qualsiasi"))
    }

    @Test
    fun `voce a pagamento senza trama, la descrizione e solo l avviso e non contiene null`() {
        val out = entry(free = false, plot = null)
        val description = MediasetLabels.description(out)!!
        assertTrue(description.startsWith("Serve un abbonamento o un noleggio Mediaset Infinity"))
        assertFalse(description.contains("null"))
    }

    @Test
    fun `voce gratuita senza generi, i tag sono una lista vuota`() {
        val out = entry(free = true, genres = emptyList())
        assertTrue(MediasetLabels.tags(out).isEmpty())
    }
}
