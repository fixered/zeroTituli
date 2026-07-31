package it.zeroTituli

import android.util.Base64
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Riscrittura delle playlist HLS di FCTV33.
 *
 * Il CDN non serve i segmenti all'indirizzo scritto nella playlist: quell'indirizzo risponde 302
 * verso se stesso, in ciclo. Ogni riga porta però i parametri `_ctump` (elenco dei mirror, uno per
 * area) e `_ctuph` (percorso firmato), codificati in ROT13 + base64 dopo otto caratteri di
 * riempimento; l'indirizzo vero è mirror + percorso. Sul sito la sostituzione la fa un service
 * worker, qui la fa [HlsProxy].
 */
internal object Csl {

    private const val PAD = 8

    /** ROT13 sulle lettere, cifre e simboli invariati. */
    private fun rot13(s: String): String = buildString(s.length) {
        s.forEach { c ->
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            )
        }
    }

    private fun decode(value: String): String? {
        if (value.length <= PAD) return null
        val body = rot13(value.substring(PAD)).let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching {
            Base64.decode(body, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Voci del tipo `EU-IT:cf@host,EU:cf@host,AS:cf@host`: si prende quella dell'area esatta, poi
     * quella del continente, altrimenti la prima.
     */
    private fun mirrorOf(ctump: String, continent: String, country: String): String? {
        val items = decode(ctump)?.split(',')?.mapNotNull { entry ->
            val code = entry.substringBefore(':', "")
            val host = entry.substringAfter('@', "")
            if (code.isBlank() || host.isBlank()) null else code to host
        }.orEmpty()
        if (items.isEmpty()) return null
        val wanted = listOf("$continent-$country", continent)
        wanted.forEach { code -> items.firstOrNull { it.first == code }?.let { return it.second } }
        return items.first().second
    }

    /**
     * I valori sono base64 con ROT13, quindi possono contenere `+`: si sciolgono solo le sequenze
     * `%XX`, perché il decodificatore standard trasformerebbe il `+` in uno spazio.
     */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.append(hex.toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun queryOf(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val name = part.substringBefore('=', "")
            if (name.isEmpty()) return@mapNotNull null
            name to percentDecode(part.substringAfter('=', ""))
        }.toMap()
    }

    private fun absolute(line: String, base: String): String = when {
        line.startsWith("http") -> line
        line.startsWith("/") -> base.substringBefore("://") + "://" +
            base.substringAfter("://").substringBefore('/') + line
        else -> base.substringBeforeLast('/') + "/" + line
    }

    /**
     * @param base indirizzo della playlist, per risolvere le righe relative.
     * @return la playlist con i segmenti sostituiti dagli indirizzi dei mirror; le playlist
     *   annidate tornano al proxy, così anche le loro righe vengono riscritte.
     */
    fun rewrite(
        playlist: String,
        base: String,
        continent: String,
        country: String,
        nested: (String) -> String
    ): String = playlist.lineSequence().map { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@map raw
        val url = absolute(line, base)
        val query = queryOf(url)
        val ctump = query["_ctump"]
        val ctuph = query["_ctuph"]
        if (ctump != null && ctuph != null) {
            val host = mirrorOf(ctump, continent, country)
            val path = decode(ctuph)
            if (host != null && path != null) return@map "https://$host$path"
        }
        if (url.substringBefore('?').endsWith(".m3u8")) nested(url) else url
    }.joinToString("\n")
}

/**
 * Server HTTP su loopback che serve le playlist riscritte da [Csl].
 *
 * Il player deve poter ricaricare la playlist (è una diretta), quindi non basta riscriverla una
 * volta: serve un indirizzo che risponda a ogni ricarica. Il proxy tiene solo la porta e delega la
 * risposta a [Resolver], così l'indirizzo del flusso viene ricalcolato a ogni giro e non scade.
 */
internal object HlsProxy {

    /** @return corpo della playlist già riscritta, oppure null se non si riesce a comporla. */
    fun interface Resolver {
        suspend fun resolve(params: Map<String, String>): String?
    }

    private const val PATH = "/hls"
    private const val CONTENT_TYPE = "application/vnd.apple.mpegurl"

    @Volatile private var port = 0
    @Volatile private var resolver: Resolver? = null

    /** Registra il risolutore e restituisce l'indirizzo locale con i parametri dati. */
    fun link(resolver: Resolver, params: Map<String, String>): String {
        this.resolver = resolver
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "http://127.0.0.1:${start()}$PATH?$query"
    }

    private fun start(): Int {
        port.takeIf { it != 0 }?.let { return it }
        synchronized(this) {
            if (port != 0) return port
            val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            port = server.localPort
            Thread({ accept(server) }, "fctv33-hls").apply { isDaemon = true }.start()
            return port
        }
    }

    private fun accept(server: ServerSocket) {
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            Thread({ serve(socket) }, "fctv33-hls-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = 20_000
                val input = BufferedInputStream(socket.getInputStream())
                val request = readRequestLine(input) ?: return
                drainHeaders(input)
                val target = request.split(' ').getOrNull(1).orEmpty()
                if (!target.startsWith("$PATH?")) {
                    write(socket.getOutputStream(), 404, "text/plain", "not found")
                    return
                }
                val params = target.substringAfter('?').split('&').mapNotNull { part ->
                    val name = part.substringBefore('=', "")
                    if (name.isEmpty()) return@mapNotNull null
                    name to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                }.toMap()
                val body = runBlocking { resolver?.resolve(params) }
                if (body == null) {
                    write(socket.getOutputStream(), 502, "text/plain", "no playlist")
                } else {
                    write(socket.getOutputStream(), 200, CONTENT_TYPE, body)
                }
            }
        }
    }

    private fun readRequestLine(input: BufferedInputStream): String? {
        val line = readLine(input)
        return line?.takeIf { it.isNotBlank() }
    }

    private fun drainHeaders(input: BufferedInputStream) {
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) return
        }
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

    private fun write(output: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
