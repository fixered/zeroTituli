package it.zeroTituli

import it.zeroTituli.shared.Disguise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * I segmenti dei player exmxbxe arrivano da un CDN di immagini: 42 byte di intestazione WEBP
 * finta, poi il TS. Il proxy deve trovare dove comincia il TS, e non toccare il resto.
 */
class DisguiseTest {

    private fun ts(packets: Int): ByteArray = ByteArray(packets * 188) { i ->
        if (i % 188 == 0) 0x47 else 0x11
    }

    @Test
    fun `un TS vero resta com'è`() {
        assertEquals(0, Disguise.tsOffset(ts(6)))
    }

    @Test
    fun `l'intestazione WEBP finta viene saltata`() {
        // Come arriva davvero: "RIFF", dimensione, "WEBPVP8L", un blocco EXIF, poi il TS.
        val fake = "RIFF\"_&\u0000WEBPVP8L".toByteArray(Charsets.ISO_8859_1) + ByteArray(26) { 0x10 }
        assertEquals(42, fake.size)
        assertEquals(42, Disguise.tsOffset(fake + ts(6)))
    }

    @Test
    fun `un'immagine vera non viene scambiata per un TS`() {
        // Un 0x47 isolato non basta: servono tre pacchetti di fila.
        val png = ByteArray(2000) { i -> if (i == 100) 0x47 else (i % 7).toByte() }
        assertNull(Disguise.tsOffset(png))
    }
}
