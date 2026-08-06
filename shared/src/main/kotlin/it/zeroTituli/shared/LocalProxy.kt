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

    /**
     * Ricalcola da zero l'indirizzo della playlist principale.
     *
     * I player di Hattrick firmano gli indirizzi con un gettone che vive pochi minuti: quando
     * scade il CDN risponde 403 e il flusso si ferma dopo il primo pezzo. Il proxy, invece di
     * arrendersi, ripercorre la catena del player e riparte con l'indirizzo nuovo.
     */
    fun interface LiveSource {
        suspend fun master(): String?
    }

    const val MIME_HLS = "application/vnd.apple.mpegurl"

    /**
     * Un manifest DASH servito come HLS non parte: ExoPlayer sceglie l'estrattore dal tipo
     * dichiarato, e l'indirizzo del proxy non ha estensione da cui indovinare.
     */
    const val MIME_DASH = "application/dash+xml"

    private const val PATH_DYNAMIC = "/d"   // playlist prodotta dal plugin
    private const val PATH_HLS = "/m"       // playlist remota inoltrata e riscritta
    private const val PATH_RAW = "/r"       // segmento o chiave inoltrati così come sono
    private const val PATH_LIVE = "/l"      // diretta con gettone che scade: si rinnova da sola

    /** Sotto questa distanza dall'ultimo rinnovo non se ne fa un altro: le richieste sono a raffica. */
    private const val REFRESH_GUARD_MS = 5_000L

    @Volatile private var port = 0
    @Volatile private var source: PlaylistSource? = null

    /** Sta accanto a [source] e si scrive insieme a lui: il corpo e il suo tipo sono una cosa sola. */
    @Volatile private var sourceMime: String = MIME_HLS

    private val headerSets = ConcurrentHashMap<String, Map<String, String>>()
    private val liveStreams = ConcurrentHashMap<String, Live>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "zt-proxy").apply { isDaemon = true }
    }

    // ============= INDIRIZZI =============

    /**
     * @param forCast true quando l'indirizzo deve essere raggiungibile dal Chromecast.
     * @param mime il tipo con cui servire il corpo. Predefinito HLS, che è quello che i tre
     *   plugin storici producono; le dirette Mediaset passano di qui con un manifest DASH.
     */
    fun playlist(
        source: PlaylistSource,
        params: Map<String, String>,
        forCast: Boolean,
        mime: String = MIME_HLS,
    ): String {
        this.source = source
        this.sourceMime = mime
        return base(forCast) + PATH_DYNAMIC + "?" + query(params)
    }

    /** Playlist remota: il proxy la scarica con gli header e ne riscrive le righe. */
    fun hls(url: String, headers: Map<String, String>, forCast: Boolean): String =
        base(forCast) + PATH_HLS + "?" + query(mapOf("u" to url, "h" to register(headers)))

    /** Segmento (o chiave, o traccia audio non HLS): inoltrato senza toccare il corpo. */
    fun raw(url: String, headers: Map<String, String>, forCast: Boolean): String =
        base(forCast) + PATH_RAW + "?" + query(mapOf("u" to url, "h" to register(headers)))

    /**
     * Diretta con indirizzo a scadenza: il proxy la tiene in vita.
     *
     * Playlist e segmenti passano tutti da qui, così due cose diventano possibili. La prima è il
     * rinnovo: quando il CDN rifiuta una richiesta, o quando l'indirizzo in uso è più vecchio di
     * [maxAgeMs], [source] ripercorre la catena del player e restituisce un indirizzo firmato di
     * fresco. La seconda è la sostituzione della firma: i figli della playlist vengono richiesti
     * con la parte di query dell'indirizzo principale del momento, non con quella scritta nel
     * corpo, che invecchia insieme a lui.
     *
     * @param key nome del flusso: due chiamate con la stessa chiave rimpiazzano la voce, e gli
     *   indirizzi già consegnati al player continuano a funzionare.
     * @param kid identificativo della chiave ClearKey, in esadecimale. Solo per il DASH: i
     *   manifest che arrivano da qui non lo dichiarano, e senza `default_KID` ExoPlayer non apre
     *   la sessione.
     */
    fun live(
        key: String,
        master: String,
        headers: Map<String, String>,
        source: LiveSource,
        forCast: Boolean,
        maxAgeMs: Long = 150_000L,
        kid: String? = null,
    ): String {
        liveStreams[key] = Live(
            key = key,
            source = source,
            headers = headers.filterValues { it.isNotBlank() },
            maxAgeMs = maxAgeMs,
            kid = kid,
            forCast = forCast,
            master = master,
        )
        return base(forCast) + PATH_LIVE + "?" + query(mapOf("k" to key))
    }

    private class Live(
        val key: String,
        val source: LiveSource,
        val headers: Map<String, String>,
        val maxAgeMs: Long,
        val kid: String?,
        val forCast: Boolean,
        @Volatile var master: String,
    ) {
        /** Quando l'indirizzo in uso è stato ottenuto: da qui si misura la sua età. */
        @Volatile var stamp: Long = System.currentTimeMillis()

        /** Quando si è provato a rinnovare l'ultima volta, riuscendo o no: serve a non insistere. */
        @Volatile var attempt: Long = 0L
    }

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
                    PATH_LIVE -> serveLive(out, params, request.range)
                    else -> writeText(out, 404, "text/plain", "not found")
                }
            }
        }
    }

    private fun serveDynamic(out: OutputStream, params: Map<String, String>) {
        val body = runBlocking { source?.body(params) }
        if (body == null) writeText(out, 502, "text/plain", "no playlist")
        else writeText(out, 200, sourceMime, body)
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

    // ============= DIRETTE CON INDIRIZZO A SCADENZA =============

    /**
     * Una richiesta della diretta: playlist principale, playlist annidata o segmento.
     *
     * Se il CDN rifiuta si rinnova l'indirizzo principale e si riprova una volta: dal punto di
     * vista del player è una richiesta un po' più lenta, non un errore, e il flusso non si
     * interrompe. Il rinnovo vale anche per il primo tentativo quando l'indirizzo in uso ha
     * passato la sua età massima, perché arrivare al rifiuto costa un giro di richieste in più.
     */
    private fun serveLive(out: OutputStream, params: Map<String, String>, range: String?) {
        val live = liveStreams[params["k"]]
        if (live == null) {
            writeText(out, 404, "text/plain", "unknown stream")
            return
        }
        val foreign = params["u"]
        val relative = params["p"]
        val childQuery = params["q"].orEmpty()

        // Gli indirizzi di altri host (i segmenti su storage firmato) non c'entrano con la firma
        // della playlist: rinnovarla non li aggiusta, e la playlist li riporta già aggiornati.
        if (foreign == null) refresh(live, force = false)

        var target = liveTarget(live, foreign, relative, childQuery)
        var conn = open(target, live.headers, range)
        var status = conn?.let { runCatching { it.responseCode }.getOrNull() } ?: 599
        if (status >= 400 && foreign == null && refresh(live, force = true)) {
            conn?.disconnect()
            target = liveTarget(live, foreign, relative, childQuery)
            conn = open(target, live.headers, range)
            status = conn?.let { runCatching { it.responseCode }.getOrNull() } ?: 599
        }
        if (conn == null) {
            writeText(out, 502, "text/plain", "upstream error")
            return
        }

        runCatching {
            val contentType = conn.getHeaderField("Content-Type").orEmpty()
            if (status < 400 && isManifest(target, contentType)) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                writeManifest(out, live, target, body)
            } else {
                val stream: InputStream =
                    if (status >= 400) conn.errorStream ?: return@runCatching else conn.inputStream
                val extra = mutableListOf<String>()
                contentType.takeIf { it.isNotBlank() }?.let { extra += "Content-Type: $it" }
                conn.getHeaderField("Content-Length")?.let { extra += "Content-Length: $it" }
                conn.getHeaderField("Content-Range")?.let { extra += "Content-Range: $it" }
                extra += "Accept-Ranges: bytes"
                writeHead(out, status, extra)
                stream.copyTo(out, 64 * 1024)
                out.flush()
            }
        }
        conn.disconnect()
    }

    /** Playlist HLS e manifest DASH: gli indirizzi dentro il corpo tornano tutti al proxy. */
    private fun writeManifest(out: OutputStream, live: Live, target: String, body: String) {
        when {
            body.contains("#EXTM3U") -> {
                val rewritten = M3u8.rewrite(body, target) { child -> liveChild(live, child) }
                writeText(out, 200, MIME_HLS, rewritten)
            }
            body.contains("<MPD") -> {
                // Il DASH non si riscrive riga per riga: basta l'indirizzo di partenza, che i
                // segmenti li nomina in modo relativo, e la chiave che ExoPlayer pretende.
                writeText(out, 200, MIME_DASH, Mpd.patch(body, target, live.kid))
            }
            else -> writeText(out, 502, "text/plain", "no playlist")
        }
    }

    private fun liveTarget(live: Live, foreign: String?, relative: String?, childQuery: String): String =
        foreign ?: LiveUrls.target(live.master, relative, childQuery)

    private fun liveChild(live: Live, child: String): String =
        base(live.forCast) + PATH_LIVE + "?" + query(LiveUrls.childParams(live.key, live.master, child))

    private fun refresh(live: Live, force: Boolean): Boolean {
        if (!force && System.currentTimeMillis() - live.stamp < live.maxAgeMs) return false
        synchronized(live) {
            // Le richieste arrivano a raffica e scadono tutte insieme: il primo che entra rinnova,
            // gli altri trovano l'indirizzo già nuovo e non ripercorrono la catena del player.
            if (System.currentTimeMillis() - live.attempt < REFRESH_GUARD_MS) return false
            live.attempt = System.currentTimeMillis()
            val fresh = runCatching { runBlocking { live.source.master() } }.getOrNull()
            if (fresh.isNullOrBlank()) return false
            live.master = fresh
            live.stamp = System.currentTimeMillis()
            return true
        }
    }

    private fun isManifest(url: String, contentType: String): Boolean {
        val path = url.substringBefore('?')
        return path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            contentType.contains("mpegurl", ignoreCase = true) ||
            contentType.contains("dash+xml", ignoreCase = true) ||
            contentType.contains("x-mpegURL", ignoreCase = true)
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

/**
 * Indirizzi delle dirette che passano dal proxy locale.
 *
 * Sta a parte da [LocalProxy] perché è la parte che decide *cosa* si chiede al CDN dopo un
 * rinnovo, e si può leggere e provare senza aprire una porta.
 */
object LiveUrls {

    /**
     * Indirizzo vero di una richiesta, ricalcolato sull'indirizzo principale del momento.
     *
     * I figli che stanno nella cartella dell'indirizzo principale sono stati salvati come percorso
     * relativo: si riattaccano alla cartella corrente, che dopo un rinnovo può essere cambiata
     * (host diverso, o percorso con dentro la firma). La query segue la stessa logica: quella
     * scritta nel corpo della playlist porta il gettone di allora, e se ha le stesse chiavi di
     * quella principale viene sostituita in blocco con la versione fresca. Se le chiavi sono altre
     * è una firma per conto suo (i segmenti su storage) e si lascia com'è.
     */
    fun target(master: String, relative: String?, childQuery: String): String {
        if (relative.isNullOrEmpty()) return master
        val dir = master.substringBefore('?').substringBeforeLast('/')
        val masterQuery = master.substringAfter('?', "")
        val q = when {
            childQuery.isEmpty() -> masterQuery
            queryKeys(childQuery) == queryKeys(masterQuery) -> masterQuery
            else -> childQuery
        }
        return "$dir/$relative" + if (q.isEmpty()) "" else "?$q"
    }

    /**
     * Come si nomina un figlio della playlist nel percorso del proxy: relativo (`p`) se sta nella
     * cartella dell'indirizzo principale, assoluto (`u`) se è altrove — i segmenti su storage
     * firmato non c'entrano con la firma della playlist e non vanno riscritti.
     */
    fun childParams(key: String, master: String, child: String): Map<String, String> {
        val dir = master.substringBefore('?').substringBeforeLast('/') + "/"
        val path = child.substringBefore('?')
        return if (path.startsWith(dir)) {
            mapOf("k" to key, "p" to path.removePrefix(dir), "q" to child.substringAfter('?', ""))
        } else {
            mapOf("k" to key, "u" to child)
        }
    }

    private fun queryKeys(query: String): Set<String> =
        if (query.isEmpty()) emptySet()
        else query.split('&').mapTo(mutableSetOf()) { it.substringBefore('=') }
}

/** Ritocchi a un manifest DASH servito dal proxy locale. */
object Mpd {

    private val mp4Protection =
        Regex("""<ContentProtection([^>]*?)schemeIdUri="urn:mpeg:dash:mp4protection:2011"([^>]*?)/>""")

    /**
     * Rende il manifest utilizzabile da un indirizzo diverso dal suo.
     *
     * Due aggiunte, entrambe necessarie:
     *
     *  - `<BaseURL>` con la cartella d'origine, perché i segmenti dentro il manifest hanno nomi
     *    relativi e il player li cercherebbe sul proxy, che non li ha;
     *  - `cenc:default_KID` sulla protezione `cenc`, quando [kid] c'è. I manifest di questi CDN
     *    dichiarano solo Widevine e PlayReady: senza un identificativo di chiave in chiaro
     *    ExoPlayer non trova niente che corrisponda a ClearKey e non apre nemmeno la sessione,
     *    anche avendo la chiave in mano.
     */
    fun patch(manifest: String, url: String, kid: String?): String {
        var out = manifest
        if (!out.contains("<BaseURL>")) {
            val dir = url.substringBefore('?').substringBeforeLast('/') + "/"
            val open = Regex("""<MPD\b[^>]*>""").find(out)
            if (open != null) {
                out = out.substring(0, open.range.last + 1) +
                    "\n<BaseURL>$dir</BaseURL>" +
                    out.substring(open.range.last + 1)
            }
        }
        val uuid = kid?.let { dashedUuid(it) }
        if (uuid != null && !out.contains("default_KID")) {
            out = mp4Protection.replace(out) { m ->
                "<ContentProtection${m.groupValues[1]}schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"" +
                    "${m.groupValues[2]}cenc:default_KID=\"$uuid\"/>"
            }
        }
        return out
    }

    /** "610bcda111c74c97b0792b059630a10b" → "610bcda1-11c7-4c97-b079-2b059630a10b" */
    fun dashedUuid(hex: String): String? {
        val clean = hex.trim().replace("-", "").lowercase()
        if (clean.length != 32 || clean.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return listOf(
            clean.substring(0, 8),
            clean.substring(8, 12),
            clean.substring(12, 16),
            clean.substring(16, 20),
            clean.substring(20),
        ).joinToString("-")
    }
}
