package it.zeroTituli.shared

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Server HTTP locale condiviso dai plugin.
 *
 * Serve a due cose diverse:
 *
 *  - riscrivere al volo le playlist HLS che il CDN non serve così come sono (FCTV33);
 *  - far arrivare il flusso al Chromecast. Cloudstream manda al televisore solo l'indirizzo
 *    (`CastHelper`: `MediaInfo.Builder(link.url)`) e usa il receiver predefinito di Google:
 *    `User-Agent`, `Referer` e `Origin` dell'ExtractorLink vengono persi e il CDN rifiuta.
 *    Passando dal proxy è il telefono a scaricare, con gli header al posto giusto, e il
 *    televisore parla solo con il telefono.
 *
 * In riproduzione locale l'indirizzo resta su `127.0.0.1`; per il Chromecast serve l'indirizzo del
 * telefono sulla rete, perché sul televisore `127.0.0.1` è il televisore stesso.
 */
object LocalProxy {

    /** Corpo di una playlist ricalcolato a ogni richiesta: le dirette scadono, gli indirizzi no. */
    fun interface PlaylistSource {
        suspend fun body(params: Map<String, String>): String?
    }

    private const val MIME_HLS = "application/vnd.apple.mpegurl"
    private const val PATH_DYNAMIC = "/d"   // playlist prodotta dal plugin
    private const val PATH_HLS = "/m"       // playlist remota inoltrata e riscritta
    private const val PATH_RAW = "/r"       // segmento o chiave inoltrati così come sono

    @Volatile private var port = 0
    @Volatile private var source: PlaylistSource? = null

    private val headerSets = ConcurrentHashMap<String, Map<String, String>>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "zt-proxy").apply { isDaemon = true }
    }

    // ============= INDIRIZZI =============

    /** @param forCast true quando l'indirizzo deve essere raggiungibile dal Chromecast. */
    fun playlist(source: PlaylistSource, params: Map<String, String>, forCast: Boolean): String {
        this.source = source
        return base(forCast) + PATH_DYNAMIC + "?" + query(params)
    }

    /** Playlist remota: il proxy la scarica con gli header e ne riscrive le righe. */
    fun hls(url: String, headers: Map<String, String>, forCast: Boolean): String =
        base(forCast) + PATH_HLS + "?" + query(mapOf("u" to url, "h" to register(headers)))

    /** Segmento (o chiave, o traccia audio non HLS): inoltrato senza toccare il corpo. */
    fun raw(url: String, headers: Map<String, String>, forCast: Boolean): String =
        base(forCast) + PATH_RAW + "?" + query(mapOf("u" to url, "h" to register(headers)))

    private fun base(forCast: Boolean): String {
        val host = if (forCast) lanIp() ?: "127.0.0.1" else "127.0.0.1"
        return "http://$host:${start()}"
    }

    /**
     * Indirizzo del telefono sulla rete locale. Le interfacce Wi-Fi vengono prima: sono le uniche
     * che il Chromecast può raggiungere, mentre i tunnel (VPN) e la rete dati non servono.
     */
    private fun lanIp(): String? = runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
        interfaces.forEach { nif ->
            Collections.list(nif.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.let { return@runCatching it.hostAddress }
        }
        null
    }.getOrNull()

    private fun register(headers: Map<String, String>): String {
        val clean = headers.filterValues { it.isNotBlank() }
        val key = clean.entries.sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value}" }
            .hashCode().toString(16)
        headerSets[key] = clean
        return key
    }

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    // ============= SERVER =============

    private fun start(): Int {
        port.takeIf { it != 0 }?.let { return it }
        synchronized(this) {
            if (port != 0) return port
            // Senza indirizzo esplicito il socket ascolta su tutte le interfacce, non solo loopback:
            // è quello che serve perché il Chromecast possa collegarsi.
            val server = ServerSocket(0, 64)
            port = server.localPort
            pool.execute { accept(server) }
            return port
        }
    }

    private fun accept(server: ServerSocket) {
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            pool.execute { serve(socket) }
        }
    }

    private data class Request(val method: String, val target: String, val range: String?)

    private fun serve(socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val request = readRequest(input) ?: return
                val path = request.target.substringBefore('?')
                val params = parseQuery(request.target.substringAfter('?', ""))
                val out = socket.getOutputStream()

                // Il receiver del Chromecast è una pagina web, quindi valgono le regole del
                // browser. I segmenti dei VOD si scaricano a pezzi, con l'header `Range`, che non
                // è fra quelli ammessi d'ufficio: prima parte una preflight OPTIONS, e se non le
                // si risponde come si deve le richieste a pezzi non partono mai. Le dirette non
                // usano i Range, ecco perché passano anche senza tutto questo.
                if (request.method == "OPTIONS") {
                    writeHead(out, 204, emptyList())
                    return
                }

                when (path) {
                    PATH_DYNAMIC -> serveDynamic(out, params)
                    PATH_HLS -> serveHls(out, params)
                    PATH_RAW -> serveRaw(out, params, request.range)
                    else -> writeText(out, 404, "text/plain", "not found")
                }
            }
        }
    }

    private fun serveDynamic(out: OutputStream, params: Map<String, String>) {
        val body = runBlocking { source?.body(params) }
        if (body == null) writeText(out, 502, "text/plain", "no playlist")
        else writeText(out, 200, MIME_HLS, body)
    }

    private fun serveHls(out: OutputStream, params: Map<String, String>) {
        val url = params["u"].orEmpty()
        val headers = headerSets[params["h"]].orEmpty()
        val body = fetchText(url, headers)
        if (body == null || !body.contains("#EXTM3U")) {
            writeText(out, 502, "text/plain", "no playlist")
            return
        }
        val rewritten = M3u8.rewrite(body, url) { child ->
            if (child.substringBefore('?').endsWith(".m3u8")) hls(child, headers, forCast = true)
            else raw(child, headers, forCast = true)
        }
        writeText(out, 200, MIME_HLS, rewritten)
    }

    private fun serveRaw(out: OutputStream, params: Map<String, String>, range: String?) {
        val url = params["u"].orEmpty()
        val headers = headerSets[params["h"]].orEmpty()
        val conn = open(url, headers, range)
        if (conn == null) {
            writeText(out, 502, "text/plain", "upstream error")
            return
        }
        runCatching {
            val status = conn.responseCode
            val stream: InputStream = if (status >= 400) conn.errorStream ?: return@runCatching
            else conn.inputStream

            val extra = mutableListOf<String>()
            conn.getHeaderField("Content-Type")?.let { extra += "Content-Type: $it" }
            conn.getHeaderField("Content-Length")?.let { extra += "Content-Length: $it" }
            conn.getHeaderField("Content-Range")?.let { extra += "Content-Range: $it" }
            extra += "Accept-Ranges: bytes"

            writeHead(out, status, extra)
            stream.copyTo(out, 64 * 1024)
            out.flush()
        }
        conn.disconnect()
    }

    // ============= HTTP IN USCITA =============
    //
    // HttpURLConnection e non il client di Cloudstream: qui servono header esatti (compreso
    // User-Agent, che il client sovrascrive), le richieste Range e il corpo a flusso.

    private fun open(url: String, headers: Map<String, String>, range: String?): HttpURLConnection? =
        runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                range?.let { setRequestProperty("Range", it) }
                // HttpURLConnection chiede gzip da solo e poi decomprime senza dirlo: il
                // `Content-Length` che rigiriamo sarebbe quello compresso, più corto del corpo che
                // scriviamo, e il televisore resterebbe ad aspettare byte che non arrivano.
                setRequestProperty("Accept-Encoding", "identity")
            }
        }.getOrNull()

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        val conn = open(url, headers, null) ?: return null
        return runCatching {
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull().also { conn.disconnect() }
    }

    // ============= PROTOCOLLO =============

    private fun readRequest(input: BufferedInputStream): Request? {
        val first = readLine(input)?.takeIf { it.isNotBlank() } ?: return null
        val parts = first.split(' ')
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val target = parts.getOrNull(1).orEmpty()
        var range: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                range = line.substringAfter(':').trim()
            }
        }
        return Request(method, target, range)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(b.toChar())
            if (out.length > 8192) return out.toString()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val name = part.substringBefore('=', "")
            if (name.isEmpty()) return@mapNotNull null
            name to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.toMap()
    }

    private fun writeText(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, status, listOf("Content-Type: $type", "Content-Length: ${bytes.size}"))
        out.write(bytes)
        out.flush()
    }

    /**
     * Intestazioni comuni a ogni risposta. `Allow-Headers` è quello che fa passare la preflight
     * delle richieste con `Range`; `Expose-Headers` è quello che permette al player di leggere il
     * `Content-Range` della risposta, senza il quale non sa che pezzo ha ricevuto. I CDN veri
     * mandano entrambi, il proxy deve fare lo stesso.
     */
    private fun writeHead(out: OutputStream, status: Int, extra: List<String>) {
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("Cache-Control: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Range, Content-Type, Accept, Origin\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Content-Type, Accept-Ranges\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        404 -> "Not Found"
        else -> if (status < 400) "OK" else "Error"
    }
}

/** Riscrittura degli indirizzi dentro una playlist HLS. */
object M3u8 {

    private val tagUri = Regex("""URI="([^"]*)"""")

    fun absolute(line: String, base: String): String = when {
        line.startsWith("http://") || line.startsWith("https://") -> line
        line.startsWith("//") -> base.substringBefore("://") + ":" + line
        line.startsWith("/") -> base.substringBefore("://") + "://" +
            base.substringAfter("://").substringBefore('/') + line
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + line
    }

    /**
     * Applica [map] a ogni indirizzo della playlist: le righe dei segmenti e gli `URI="..."` dei
     * tag (tracce audio, chiavi di cifratura, `EXT-X-MAP`), che altrimenti resterebbero relativi
     * all'indirizzo del proxy invece che a quello del CDN.
     */
    fun rewrite(playlist: String, base: String, map: (String) -> String): String =
        playlist.lineSequence().map { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> tagUri.replace(raw) { m ->
                    "URI=\"" + map(absolute(m.groupValues[1], base)) + "\""
                }
                else -> map(absolute(line, base))
            }
        }.joinToString("\n")
}
