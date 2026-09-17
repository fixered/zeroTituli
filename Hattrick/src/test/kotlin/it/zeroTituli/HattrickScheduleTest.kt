package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Le intestazioni e gli orari sono copiati dalla home di htsport.org, nelle due forme che il sito
 * ha usato finora: è l'unico modo di accorgersi che la pagina ha cambiato vestito un'altra volta.
 */
class HattrickScheduleTest {

    private val rome: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(rome).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ============= GIORNO =============

    @Test
    fun `intestazione con il mese per esteso`() {
        assertEquals("16/9", HattrickSchedule.dayMonth("MERCOLEDI 16 SETTEMBRE"))
        assertEquals("1/1", HattrickSchedule.dayMonth("  giovedì 1   gennaio  "))
    }

    @Test
    fun `intestazione con il mese in cifre`() {
        assertEquals("29/07", HattrickSchedule.dayMonth("MERCOLEDI 29/07"))
    }

    @Test
    fun `un titolo senza data non e un'intestazione di giornata`() {
        assertEquals("", HattrickSchedule.dayMonth("Canali on Line"))
        assertEquals("", HattrickSchedule.dayMonth("MERCOLEDI 16 QUALCOSA"))
    }

    // ============= ORARIO =============

    @Test
    fun `orario letto dal testo libero della casella`() {
        assertEquals("18:30", HattrickSchedule.timeOf("18:30"))
        assertEquals("21:00", HattrickSchedule.timeOf("  Live  21:00  "))
    }

    @Test
    fun `la casella dei canali sempre attivi non ha orario`() {
        assertEquals("", HattrickSchedule.timeOf("Live Elenco Canali"))
    }

    // ============= DATA COMPLETA =============

    @Test
    fun `giorno e orario diventano un istante nell'ora di Roma`() {
        val now = at(2026, 9, 16, 12, 0)
        assertEquals(at(2026, 9, 16, 18, 30) / 1000L, HattrickSchedule.timestamp("16/9", "18:30", rome, now))
    }

    @Test
    fun `a fine anno si sceglie l'anno che avvicina la data a oggi`() {
        val silvestro = at(2026, 12, 31, 23, 0)
        assertEquals(at(2027, 1, 1, 15, 0) / 1000L, HattrickSchedule.timestamp("1/1", "15:00", rome, silvestro))

        val capodanno = at(2027, 1, 1, 1, 0)
        assertEquals(at(2026, 12, 31, 23, 0) / 1000L, HattrickSchedule.timestamp("31/12", "23:00", rome, capodanno))
    }

    @Test
    fun `senza giorno o senza orario non si inventa niente`() {
        assertEquals(0L, HattrickSchedule.timestamp("", "18:30", rome, at(2026, 9, 16, 12, 0)))
        assertEquals(0L, HattrickSchedule.timestamp("16/9", "", rome, at(2026, 9, 16, 12, 0)))
    }
}
