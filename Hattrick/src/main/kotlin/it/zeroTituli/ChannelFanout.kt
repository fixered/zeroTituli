package it.zeroTituli

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Come si distribuiscono i tentativi sui canali di un evento.
 *
 * Ogni canale ha due strade: quella veloce (richieste HTTP) e quella lenta (la WebView, che passa i
 * controlli del browser di `meritend.net` e `*.dynriver.net` ma costa venti secondi). La strada
 * veloce si prova su tutti insieme; quella lenta solo su chi è rimasto fuori, uno alla volta,
 * perché la WebView è una sola.
 *
 * Prima la strada lenta si tentava solo se non era rimasto *nessun* canale suonabile, e si fermava
 * al primo che funzionava: su una partita con cinque canali, se il primo andava a segno subito, gli
 * altri quattro non venivano nemmeno provati e nell'elenco compariva un link solo. Ora la strada
 * lenta si prova sempre su chi è rimasto fuori, e si prendono tutti quelli che rispondono.
 */
internal object ChannelFanout {

    /**
     * @param fast strada veloce; deve emettere il link e restituire `true` se ce l'ha fatta. Emette
     *   da dentro la coroutine, così i link compaiono nell'elenco appena sono pronti invece di
     *   aspettare il canale più lento.
     * @param slow strada con la WebView, uno alla volta.
     * @param slowLimit quanti canali al massimo passano dalla WebView.
     * @param slowBudgetMs quanto tempo in tutto si concede alla WebView: scaduto quello non si
     *   aprono altri tentativi, perché l'utente sta guardando una rotella.
     * @return quanti link sono stati emessi.
     */
    suspend fun <C> emitAll(
        channels: List<C>,
        fast: suspend (C) -> Boolean,
        slow: suspend (C) -> Boolean,
        slowLimit: Int,
        slowBudgetMs: Long,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Int {
        var emitted = 0
        val unresolved = mutableListOf<C>()

        coroutineScope {
            channels
                .map { ch -> ch to async { runCatching { fast(ch) }.getOrDefault(false) } }
                .forEach { (ch, job) -> if (job.await()) emitted++ else unresolved += ch }
        }

        val deadline = clock() + slowBudgetMs
        var attempts = 0
        for (ch in unresolved) {
            if (attempts >= slowLimit || clock() >= deadline) break
            attempts++
            if (runCatching { slow(ch) }.getOrDefault(false)) emitted++
        }
        return emitted
    }
}
