package it.zeroTituli

import it.zeroTituli.shared.EventTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Le etichette si provano con un "adesso" fissato: giovedì 6 agosto 2026, 16:00 di Roma.
 */
class EventTimeTest {

    private val rome: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    private fun ts(day: Int, month: Int, hour: Int, minute: Int, year: Int = 2026): Long =
        Calendar.getInstance(rome).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L

    private val now = ts(6, 8, 16, 0) * 1000L

    @Test
    fun `oggi porta giorno e mese`() {
        assertEquals("Oggi 6/8 21:00", EventTime.label(ts(6, 8, 21, 0), now, rome))
    }

    @Test
    fun `domani porta giorno e mese`() {
        assertEquals("Domani 7/8 21:00", EventTime.label(ts(7, 8, 21, 0), now, rome))
    }

    @Test
    fun `ieri porta giorno e mese`() {
        assertEquals("Ieri 5/8 20:45", EventTime.label(ts(5, 8, 20, 45), now, rome))
    }

    /** Fuori dalla finestra ieri-domani comanda il giorno della settimana. */
    @Test
    fun `oltre domani si scrive il giorno della settimana`() {
        assertEquals("sab 8/8 18:30", EventTime.label(ts(8, 8, 18, 30), now, rome).lowercase())
    }

    @Test
    fun `prima di ieri si scrive il giorno della settimana`() {
        assertEquals("mar 4/8 20:00", EventTime.label(ts(4, 8, 20, 0), now, rome).lowercase())
    }

    /** Mezzanotte e un minuto è già domani, non stasera tardi. */
    @Test
    fun `il confine di mezzanotte sta con il giorno giusto`() {
        assertEquals("Oggi 6/8 00:01", EventTime.label(ts(6, 8, 0, 1), now, rome))
        assertEquals("Domani 7/8 00:30", EventTime.label(ts(7, 8, 0, 30), now, rome))
        assertEquals("Oggi 6/8 23:59", EventTime.label(ts(6, 8, 23, 59), now, rome))
    }

    /** I canali sempre attivi non hanno orario. */
    @Test
    fun `senza orario non si scrive niente`() {
        assertEquals("", EventTime.label(0L, now, rome))
        assertEquals("", EventTime.label(-1L, now, rome))
        assertEquals("", EventTime.timeOnly(0L, rome))
    }

    @Test
    fun `l'ora da sola resta l'ora`() {
        assertEquals("21:00", EventTime.timeOnly(ts(9, 12, 21, 0), rome))
    }

    /** Un anno diverso non deve leggersi come "oggi": il confronto è sul tempo, non sul giorno. */
    @Test
    fun `stessa data di un altro anno non e oggi`() {
        val label = EventTime.label(ts(6, 8, 21, 0, year = 2027), now, rome)
        assertEquals("ven 6/8 21:00", label.lowercase())
    }
}
