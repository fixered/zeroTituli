package it.zeroTituli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasetLiveTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val payload = fixture("nownext-c5.json")

    @Test
    fun `sceglie la variante in chiaro`() {
        val url = MediasetLive.clearMediaUrl(payload)
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://link.api.eu.theplatform.com/s/PR1GhC/"))
    }

    @Test
    fun `scarta le varianti protette`() {
        val json = """
        {"response":{"tuningInstruction":{"urn:theplatform:tv:location:any":[
          {"format":"application/dash+xml","protectionScheme":"commonEncryption","publicUrls":["https://x/protetto"]},
          {"format":"application/x-mpegURL","protectionScheme":"fairplay","publicUrls":["https://x/fairplay"]}
        ]}}}
        """.trimIndent()
        assertNull(MediasetLive.clearMediaUrl(json))
    }

    @Test
    fun `preferisce il DASH in chiaro all HLS in chiaro`() {
        // Il ricevitore predefinito del Chromecast legge il DASH; l'HLS in chiaro
        // sulle dirette Mediaset non esiste, ma se comparisse resta la seconda scelta.
        val json = """
        {"response":{"tuningInstruction":{"urn:theplatform:tv:location:any":[
          {"format":"application/x-mpegURL","protectionScheme":"","publicUrls":["https://x/hls"]},
          {"format":"application/dash+xml","protectionScheme":"","publicUrls":["https://x/dash"]}
        ]}}}
        """.trimIndent()
        assertEquals("https://x/dash", MediasetLive.clearMediaUrl(json))
    }

    @Test
    fun `legge il programma in onda`() {
        val info = MediasetLive.info(payload, fallbackLabel = "Canale 5")
        assertNotNull(info)
        assertEquals("Canale 5", info!!.title)
        assertNotNull(info.nowPlaying)
    }

    @Test
    fun `senza programma in onda resta il nome del canale`() {
        val json = """{"response":{"tuningInstruction":{"urn:theplatform:tv:location:any":[]}}}"""
        val info = MediasetLive.info(json, fallbackLabel = "Italia 1")
        assertEquals("Italia 1", info!!.title)
        assertNull(info.nowPlaying)
    }

    @Test
    fun `una risposta di errore non da nessun canale`() {
        val json = """{"error":{"code":"AG015","message":"Channel id not found"},"isOk":false}"""
        assertNull(MediasetLive.info(json, fallbackLabel = "X"))
    }

    @Test
    fun `la lista dei canali non ha doppioni`() {
        val signs = MediasetLive.CHANNELS.map { it.callSign }
        assertEquals(signs.size, signs.distinct().size)
        assertTrue(MediasetLive.CHANNELS.any { it.callSign == "C5" })
    }
}
