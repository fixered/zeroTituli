package it.zeroTituli.shared

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Quando comincia un evento, scritto come si legge sulle card.
 *
 * "Ieri", "Oggi" e "Domani" portano sempre anche giorno e mese: il solo "Domani 21:00" costringeva
 * a fare il conto di che giorno fosse, e in una lista dove le sezioni sono divise per fascia oraria
 * era la cosa più facile da leggere male. Oltre quella finestra il giorno della settimana basta a
 * collocare l'evento.
 *
 * Le tre fonti (Hattrick, FCTV33 calcio, FCTV33 basket) usano tutte questa, così le etichette non
 * divergono da un plugin all'altro.
 */
internal object EventTime {

    val rome: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    private const val DAY = 86_400L

    /**
     * @param ts inizio dell'evento, in secondi. Zero o negativo (i canali sempre attivi) → "".
     * @param nowMs adesso, in millisecondi: è un parametro perché "oggi" dipende da quando si
     *   guarda, e i test hanno bisogno di fissarlo.
     */
    fun label(ts: Long, nowMs: Long = System.currentTimeMillis(), tz: TimeZone = rome): String {
        if (ts <= 0L) return ""
        val todayStart = startOfDay(nowMs, tz)
        val date = Date(ts * 1000L)
        val dayMonth = format("d/M", tz, date)
        val time = format("HH:mm", tz, date)
        return when {
            ts < todayStart - DAY || ts >= todayStart + 2 * DAY -> format("E d/M HH:mm", tz, date)
            ts < todayStart -> "Ieri $dayMonth $time"
            ts < todayStart + DAY -> "Oggi $dayMonth $time"
            else -> "Domani $dayMonth $time"
        }
    }

    /** Solo l'ora: per le sezioni dove il giorno è già scritto nel titolo. */
    fun timeOnly(ts: Long, tz: TimeZone = rome): String =
        if (ts <= 0L) "" else format("HH:mm", tz, Date(ts * 1000L))

    /** Mezzanotte del giorno di [nowMs], in secondi. */
    fun startOfDay(nowMs: Long, tz: TimeZone = rome): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L

    /**
     * SimpleDateFormat non si può condividere fra thread e queste etichette si compongono da più
     * coroutine insieme: se ne fa una nuova ogni volta, che costa meno di un errore di formato.
     */
    private fun format(pattern: String, tz: TimeZone, date: Date): String =
        SimpleDateFormat(pattern, Locale.ITALY).apply { timeZone = tz }.format(date)
}
