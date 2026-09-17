package it.zeroTituli

import java.util.Calendar
import java.util.TimeZone

/**
 * Lettura del palinsesto di Hattrick: solo testo, nessuna rete.
 *
 * Il sito riscrive la home ogni tanto e le classi su cui ci si appoggiava spariscono. Nella prima
 * versione della pagina il giorno stava in `.date-header` come "MERCOLEDI 29/07" e l'orario in
 * `.ora-txt`; nella pagina di oggi il giorno è un `<h2>` con il mese per esteso ("MERCOLEDI 16
 * SETTEMBRE") e l'orario è testo libero dentro `.time-box`. Qui si accettano tutte e due le forme,
 * perché il sito ha già dimostrato di tornare sui suoi passi e una sola riga di HTML non vale la
 * perdita dell'intero palinsesto.
 */
internal object HattrickSchedule {

    private val hhmmRegex = Regex("""\b(\d{1,2}):(\d{2})\b""")

    private val monthNames = listOf(
        "GENNAIO", "FEBBRAIO", "MARZO", "APRILE", "MAGGIO", "GIUGNO",
        "LUGLIO", "AGOSTO", "SETTEMBRE", "OTTOBRE", "NOVEMBRE", "DICEMBRE"
    )

    /**
     * Giorno e mese di un'intestazione, nella forma "giorno/mese".
     *
     * Riconosce sia "MERCOLEDI 29/07" sia "MERCOLEDI 16 SETTEMBRE". Stringa vuota se non c'è
     * nessuna data: è così che chi chiama distingue un `<h2>` di palinsesto da un `<h2>` qualsiasi.
     */
    fun dayMonth(text: String): String {
        val clean = text.replace(Regex("""\s+"""), " ").trim()
        Regex("""(\d{1,2})/(\d{1,2})""").find(clean)?.let { m ->
            return "${m.groupValues[1]}/${m.groupValues[2]}"
        }
        val m = Regex("""(\d{1,2})\s+([A-Za-zÀ-ÿ]+)""").find(clean) ?: return ""
        val month = monthNames.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }
        if (month < 0) return ""
        return "${m.groupValues[1]}/${month + 1}"
    }

    /** Il primo orario "hh:mm" nel testo, vuoto se non ce n'è (le card dei canali 24/7). */
    fun timeOf(text: String): String = hhmmRegex.find(text)?.value.orEmpty()

    /**
     * "29/07" + "20:30" (ora di Roma) → epoch secondi.
     *
     * L'anno non è scritto da nessuna parte: si sceglie quello che avvicina di più la data a oggi,
     * così il palinsesto di fine dicembre non finisce undici mesi indietro.
     */
    fun timestamp(dayMonth: String, timeHHmm: String, tz: TimeZone, now: Long = System.currentTimeMillis()): Long {
        if (dayMonth.isBlank()) return 0L
        val dm = Regex("""(\d{1,2})/(\d{1,2})""").find(dayMonth) ?: return 0L
        val hm = hhmmRegex.find(timeHHmm) ?: return 0L
        val day = dm.groupValues[1].toIntOrNull() ?: return 0L
        val month = dm.groupValues[2].toIntOrNull() ?: return 0L
        val hour = hm.groupValues[1].toIntOrNull() ?: return 0L
        val minute = hm.groupValues[2].toIntOrNull() ?: return 0L

        val nowCal = Calendar.getInstance(tz).apply { timeInMillis = now }
        val nowSec = now / 1000L
        var best = 0L
        listOf(0, -1, 1).forEach { yearShift ->
            val c = Calendar.getInstance(tz).apply {
                timeInMillis = now
                set(Calendar.YEAR, nowCal.get(Calendar.YEAR) + yearShift)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val ts = c.timeInMillis / 1000L
            if (best == 0L || kotlin.math.abs(ts - nowSec) < kotlin.math.abs(best - nowSec)) best = ts
        }
        return best
    }
}
