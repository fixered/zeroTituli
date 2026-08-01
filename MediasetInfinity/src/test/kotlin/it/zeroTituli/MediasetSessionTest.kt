package it.zeroTituli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetSessionTest {

    @Test
    fun `senza sessione va rifatto il login`() {
        assertTrue(MediasetSession.isStale(null, now = 1_000))
    }

    @Test
    fun `una sessione appena nata va bene`() {
        val s = Session(beToken = "t", sid = "s", bornAt = 1_000)
        assertFalse(MediasetSession.isStale(s, now = 1_000))
    }

    @Test
    fun `una sessione vecchia va rifatta`() {
        val s = Session(beToken = "t", sid = "s", bornAt = 0)
        assertTrue(MediasetSession.isStale(s, now = MediasetSession.LIFETIME_MS + 1))
    }

    @Test
    fun `poco prima della scadenza va ancora bene`() {
        val s = Session(beToken = "t", sid = "s", bornAt = 0)
        assertFalse(MediasetSession.isStale(s, now = MediasetSession.LIFETIME_MS - 1))
    }

    @Test
    fun `un orologio che va indietro non blocca tutto`() {
        // Se l'ora del dispositivo cambia, una sessione "nata nel futuro" non deve
        // restare valida per sempre né essere buttata a ogni chiamata.
        val s = Session(beToken = "t", sid = "s", bornAt = 10_000)
        assertTrue(MediasetSession.isStale(s, now = 0))
    }

    @Test
    fun `una sessione senza token non vale`() {
        val s = Session(beToken = "", sid = "s", bornAt = 1_000)
        assertTrue(MediasetSession.isStale(s, now = 1_000))
    }
}
