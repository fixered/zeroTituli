package it.zeroTituli

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ChannelFanoutTest {

    private val webViewFamilies = setOf("meritend", "dynriver")

    /**
     * Il difetto per cui l'elenco mostrava un link solo: un canale risolto in fretta bastava a
     * far saltare la WebView per tutti gli altri.
     */
    @Test
    fun `la webview si prova anche quando un altro canale ha gia funzionato`() = runBlocking {
        val channels = listOf("nexa", "meritend", "dynriver")
        val slowTried = Collections.synchronizedList(mutableListOf<String>())

        val emitted = ChannelFanout.emitAll(
            channels = channels,
            fast = { ch -> ch !in webViewFamilies },
            slow = { ch -> slowTried += ch; true },
            slowLimit = 3,
            slowBudgetMs = 60_000L,
        )

        assertEquals(listOf("meritend", "dynriver"), slowTried)
        assertEquals(3, emitted)
    }

    @Test
    fun `la webview non si ferma al primo successo`() = runBlocking {
        val tried = mutableListOf<String>()
        val emitted = ChannelFanout.emitAll(
            channels = listOf("a", "b", "c"),
            fast = { false },
            slow = { ch -> tried += ch; true },
            slowLimit = 3,
            slowBudgetMs = 60_000L,
        )
        assertEquals(listOf("a", "b", "c"), tried)
        assertEquals(3, emitted)
    }

    @Test
    fun `i canali che vanno subito non passano dalla webview`() = runBlocking {
        val slowCalls = AtomicInteger()
        val emitted = ChannelFanout.emitAll(
            channels = listOf("a", "b"),
            fast = { true },
            slow = { slowCalls.incrementAndGet(); true },
            slowLimit = 3,
            slowBudgetMs = 60_000L,
        )
        assertEquals(0, slowCalls.get())
        assertEquals(2, emitted)
    }

    @Test
    fun `il limite dei tentativi lenti viene rispettato`() = runBlocking {
        val tried = mutableListOf<String>()
        ChannelFanout.emitAll(
            channels = listOf("a", "b", "c", "d", "e"),
            fast = { false },
            slow = { ch -> tried += ch; false },
            slowLimit = 2,
            slowBudgetMs = 60_000L,
        )
        assertEquals(listOf("a", "b"), tried)
    }

    /** Scaduto il tempo concesso alla WebView non si aprono altri tentativi. */
    @Test
    fun `il tempo concesso alla webview ferma i tentativi`() = runBlocking {
        val tried = mutableListOf<String>()
        var fakeNow = 0L
        ChannelFanout.emitAll(
            channels = listOf("a", "b", "c"),
            fast = { false },
            slow = { ch -> tried += ch; fakeNow += 20_000L; false },
            slowLimit = 5,
            slowBudgetMs = 30_000L,
            clock = { fakeNow },
        )
        assertEquals(listOf("a", "b"), tried)
    }

    @Test
    fun `un canale che lancia non porta via gli altri`() = runBlocking {
        val emitted = ChannelFanout.emitAll(
            channels = listOf("boom", "ok"),
            fast = { ch -> if (ch == "boom") error("porta del proxy occupata") else true },
            slow = { false },
            slowLimit = 1,
            slowBudgetMs = 60_000L,
        )
        assertEquals(1, emitted)
    }

    @Test
    fun `senza niente di suonabile non si emette nulla`() = runBlocking {
        val emitted = ChannelFanout.emitAll(
            channels = listOf("a", "b"),
            fast = { false },
            slow = { false },
            slowLimit = 2,
            slowBudgetMs = 60_000L,
        )
        assertEquals(0, emitted)
    }

    /** La strada veloce parte su tutti i canali insieme: nessuno aspetta il più lento. */
    @Test
    fun `la strada veloce e in parallelo`() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        ChannelFanout.emitAll(
            channels = (1..4).map { it.toString() },
            fast = {
                val now = running.incrementAndGet()
                peak.updateAndGet { max -> if (now > max) now else max }
                delay(50)
                running.decrementAndGet()
                true
            },
            slow = { false },
            slowLimit = 0,
            slowBudgetMs = 0L,
        )
        assertTrue("attesi tentativi in parallelo, visti ${peak.get()}", peak.get() >= 2)
    }
}
