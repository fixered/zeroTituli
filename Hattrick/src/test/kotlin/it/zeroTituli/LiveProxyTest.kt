package it.zeroTituli

import it.zeroTituli.shared.LocalProxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Il proxy locale messo alla prova con un CDN finto che si comporta come quelli veri: firma gli
 * indirizzi con un gettone, lo scrive dentro la playlist e risponde 403 quando è scaduto.
 *
 * È la prova del difetto che si vedeva come "si vede il primo minuto e poi si blocca": alla
 * scadenza il player chiedeva la playlist con il gettone vecchio e riceveva un rifiuto.
 */
class LiveProxyTest {

    private lateinit var cdn: FakeCdn

    @After
    fun tearDown() {
        cdn.close()
    }

    @Test
    fun `alla scadenza del gettone il flusso continua invece di fermarsi`() {
        cdn = FakeCdn(token = "AAA")
        var offerto = "AAA"
        val proxy = LocalProxy.live(
            key = "prova",
            master = cdn.master("AAA"),
            headers = mapOf("User-Agent" to "zt-test", "Referer" to "https://player.example/"),
            source = LocalProxy.LiveSource { cdn.master(offerto) },
            forCast = false,
            maxAgeMs = Long.MAX_VALUE,
        )

        // 1. La playlist principale arriva riscritta: ogni riga torna al proxy, così i figli
        //    possono essere serviti con la firma del momento invece di quella scritta nel corpo.
        val master = get(proxy)
        assertEquals(200, master.code)
        assertTrue(master.body.contains("#EXTM3U"))
        assertFalse("le righe non devono puntare al CDN", master.body.contains(cdn.host))
        assertTrue(master.body.lineSequence().any { it.startsWith("http://127.0.0.1") })
        assertEquals("zt-test", cdn.lastUserAgent)
        assertEquals("https://player.example/", cdn.lastReferer)

        // 2. La playlist figlia e il suo segmento passano dal proxy e arrivano interi.
        val childLink = master.body.lineSequence().first { it.startsWith("http://127.0.0.1") }
        val child = get(childLink)
        assertEquals(200, child.code)
        assertTrue(child.body.contains("#EXTINF"))
        val segmentLink = child.body.lineSequence().first { it.startsWith("http://127.0.0.1") }
        assertEquals("SEGMENTO-AAA", get(segmentLink).body.trim())

        // 3. Il gettone scade: da qui in poi il CDN rifiuta le richieste firmate "AAA", che è
        //    esattamente quello che il player si ritrovava a chiedere.
        cdn.token = "BBB"
        offerto = "BBB"
        assertEquals(403, cdn.status(cdn.master("AAA")))

        // 4. Le stesse richieste di prima, quelle che il player ha in mano, continuano a funzionare:
        //    il proxy si accorge del rifiuto, ripercorre la catena e riprova con la firma nuova.
        val childDopo = get(childLink)
        assertEquals(200, childDopo.code)
        assertTrue(childDopo.body.contains("#EXTINF"))
        val segmentoDopo = childDopo.body.lineSequence().first { it.startsWith("http://127.0.0.1") }
        assertEquals("SEGMENTO-BBB", get(segmentoDopo).body.trim())
        assertEquals(200, get(proxy).code)
    }

    @Test
    fun `un rinnovo che non produce niente non fa insistere il proxy`() {
        cdn = FakeCdn(token = "AAA")
        var tentativi = 0
        val proxy = LocalProxy.live(
            key = "prova-vuota",
            master = cdn.master("AAA"),
            headers = emptyMap(),
            source = LocalProxy.LiveSource { tentativi++; null },
            forCast = false,
            maxAgeMs = Long.MAX_VALUE,
        )
        cdn.token = "BBB"

        assertEquals(403, get(proxy).code)
        assertEquals(403, get(proxy).code)
        assertEquals("un solo tentativo di rinnovo entro la finestra di guardia", 1, tentativi)
    }

    // ============= STRUMENTI =============

    private data class Reply(val code: Int, val body: String)

    private fun get(url: String): Reply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }.orEmpty()
        conn.disconnect()
        return Reply(code, body)
    }

    /**
     * CDN finto: `/live/index.m3u8?token=X` con dentro la playlist figlia, e il gettone ripetuto in
     * ogni riga come fanno i CDN veri. Con il gettone sbagliato risponde 403.
     */
    private class FakeCdn(@Volatile var token: String) {
        private val server = ServerSocket(0, 32, java.net.InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        private val seen = ConcurrentHashMap<String, String>()

        val host: String = "127.0.0.1:${server.localPort}"
        val lastUserAgent: String? get() = seen["user-agent"]
        val lastReferer: String? get() = seen["referer"]

        fun master(token: String) = "http://$host/live/index.m3u8?token=$token"

        fun status(url: String): Int {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            val code = conn.responseCode
            conn.disconnect()
            return code
        }

        init {
            pool.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: return@execute
                    pool.execute { serve(socket) }
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            socket.use {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val request = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val name = line.substringBefore(':').lowercase()
                    if (name == "user-agent" || name == "referer") {
                        seen[name] = line.substringAfter(':').trim()
                    }
                }
                val target = request.split(' ').getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val given = Regex("""token=([^&]*)""").find(query)?.groupValues?.getOrNull(1)

                val (code, body) = when {
                    given != token -> 403 to "forbidden"
                    path == "/live/index.m3u8" -> 200 to """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=1000000
                        tracks/mono.m3u8?token=$given
                    """.trimIndent()
                    path == "/live/tracks/mono.m3u8" -> 200 to """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:4
                        #EXTINF:4.000,
                        seg1.ts?token=$given
                    """.trimIndent()
                    path == "/live/tracks/seg1.ts" -> 200 to "SEGMENTO-$given"
                    else -> 404 to "not found"
                }
                val type = if (path.endsWith(".m3u8")) LocalProxy.MIME_HLS else "video/mp2t"
                val bytes = body.toByteArray()
                val head = "HTTP/1.1 $code X\r\nContent-Type: $type\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }

        fun close() {
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }
}
