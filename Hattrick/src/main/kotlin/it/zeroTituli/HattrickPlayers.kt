package it.zeroTituli

/**
 * Lettura delle pagine dei player di Hattrick: solo testo, nessuna rete.
 *
 * Le pagine canale incorporano player di terze parti che cambiano dominio ogni poche settimane,
 * ma i modi in cui nascondono l'indirizzo del flusso sono pochi e stabili nel tempo. Qui stanno
 * tutti, riconosciuti sull'HTML già scaricato; la catena degli iframe e le chiamate alle API la
 * segue [Hattrick].
 */
internal object HattrickPlayers {

    /** Chiavi ClearKey, in esadecimale, come le scrive il parametro `ck` dei player TIM. */
    data class ClearKey(val kid: String, val key: String)

    /**
     * @param clearKey presente solo per i flussi cifrati che portano la chiave con sé.
     * @param family da dove è uscito l'indirizzo: serve a distinguere nei log e nei nomi dei link.
     */
    data class Found(val url: String, val clearKey: ClearKey? = null, val family: String = "")

    // ============= FLUSSO NELLA PAGINA =============

    /**
     * L'indirizzo del flusso, cercato nei modi noti e in ordine di specificità.
     *
     * L'estensione per Chrome viene per prima: la sua pagina è un iframe come gli altri, ma con
     * schema `chrome-extension:`, e chi cerca solo `.m3u8` nel testo non la vede nemmeno.
     */
    fun stream(html: String): Found? =
        extensionStream(html)
            ?: charArrayStream(html)
            ?: atobStream(html)
            ?: taggedStream(html)
            ?: looseStream(html)

    /**
     * Player dell'estensione per Chrome:
     * `chrome-extension://<id>/pages/player.html#<indirizzo vero>?ck=<base64 di kid:key>`
     *
     * Sono le voci marcate "EXT CHROME" nel palinsesto: nel browser servono l'estensione e le sue
     * chiavi, qui basta leggere l'indirizzo e passare le chiavi al player.
     */
    fun extensionStream(html: String): Found? {
        val m = Regex("""chrome-extension://[a-z]{16,}/[^"'#\s]*#(https?://[^"'\s<>]+)""")
            .find(html) ?: return null
        val url = unescape(m.groupValues[1])
        return Found(url, clearKeyOf(url), "ext")
    }

    /** `?ck=` base64 di `kid:key` in esadecimale. Se contiene altro (un indirizzo) si ignora. */
    fun clearKeyOf(url: String): ClearKey? {
        val raw = Regex("""[?&]ck=([^&#]+)""").find(url)?.groupValues?.getOrNull(1) ?: return null
        val decoded = decodeBase64(raw)?.trim() ?: return null
        val m = Regex("""^([0-9a-fA-F]{32})\s*:\s*([0-9a-fA-F]{32})$""").find(decoded) ?: return null
        return ClearKey(m.groupValues[1].lowercase(), m.groupValues[2].lowercase())
    }

    /**
     * Indirizzo spezzato in un array di caratteri, con eventuali code:
     *
     *     player.load({source: ["h","t","t","p",…].join("") + coda.join("") + span.innerHTML})
     *
     * Le code sono quasi sempre vuote (servono a spaventare chi legge il sorgente), ma quando ci
     * sono fanno parte dell'indirizzo: senza di loro il CDN risponde 403.
     */
    fun charArrayStream(html: String): Found? {
        val arrays = Regex("""\[\s*((?:"(?:\\.|[^"\\])*"\s*,\s*)+"(?:\\.|[^"\\])*")\s*]\s*\.join\(\s*""\s*\)""")
        val strings = Regex(""""((?:\\.|[^"\\])*)"""")
        arrays.findAll(html).forEach { m ->
            val joined = unescape(strings.findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] })
            if (!joined.contains(".m3u8") && !joined.contains(".mpd")) return@forEach
            // L'espressione finisce dove finisce l'istruzione o l'oggetto che la contiene: oltre
            // quel punto ci sono altri `join("")` che non fanno parte dell'indirizzo.
            val tail = html.substring(m.range.last + 1)
                .takeWhile { it != ';' && it != '}' && it != ',' }
                .take(400)
            return Found(joined + tails(html, tail), null, "chararray")
        }
        return null
    }

    /**
     * Le code che seguono l'array: altri array (`nome.join("")`) e il testo di uno span nascosto
     * (`document.getElementById("id").innerHTML`), concatenati nell'ordine in cui compaiono.
     */
    private fun tails(html: String, expression: String): String = buildString {
        Regex("""(?:([A-Za-z_$][\w$]*)\s*\.join\(\s*""\s*\))|(?:getElementById\(\s*["']?([\w$-]+)["']?\s*\)\s*\.innerHTML)""")
            .findAll(expression).forEach { m ->
                val variable = m.groupValues[1]
                val element = m.groupValues[2]
                if (variable.isNotEmpty()) append(joinedVariable(html, variable))
                if (element.isNotEmpty()) append(elementText(html, element))
            }
    }

    /** `var nome = ["a","b"];` → "ab". Gli array vuoti (`[""]`) non aggiungono niente. */
    private fun joinedVariable(html: String, name: String): String {
        val m = Regex("""\b${Regex.escape(name)}\s*=\s*\[([^\]]*)]""").find(html) ?: return ""
        return unescape(Regex(""""((?:\\.|[^"\\])*)"""").findAll(m.groupValues[1])
            .joinToString("") { it.groupValues[1] })
    }

    /** Testo di un elemento con quell'id, con o senza apici attorno all'attributo. */
    private fun elementText(html: String, id: String): String {
        val m = Regex("""<[^>]*\bid=["']?${Regex.escape(id)}["']?[^>]*>([^<]*)<""").find(html)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    /**
     * Indirizzo passato al player dentro un `atob('…')`: è come lo scrive Clappr sulle pagine
     * della famiglia DaddyLive.
     */
    fun atobStream(html: String): Found? {
        Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{24,})["']\s*\)""").findAll(html).forEach { m ->
            val decoded = decodeBase64(m.groupValues[1])?.trim().orEmpty()
            if (decoded.startsWith("http") && (decoded.contains(".m3u8") || decoded.contains(".mpd"))) {
                return Found(unescape(decoded), null, "atob")
            }
        }
        return null
    }

    /** Indirizzo assegnato a un campo noto del player. */
    fun taggedStream(html: String): Found? {
        Regex("""streamUrl\s*[:=]\s*["']([^"']+)["']""").find(html)
            ?.groupValues?.getOrNull(1)
            ?.let { return Found(unescape(it), null, "streamUrl") }
        Regex(
            """(?:file|source|src|hlsUrl|playlist|loadSource\()\s*[:=(]?\s*["']([^"']+\.(?:m3u8|mpd)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { return Found(unescape(it), null, "field") }
        return null
    }

    /** Ultima possibilità: il primo indirizzo di playlist che compare nel testo. */
    fun looseStream(html: String): Found? =
        Regex("""https?:(?:\\?/){2}[^"'\s\\<>]+\.(?:m3u8|mpd)[^"'\s\\<>]*""")
            .find(html)?.value?.let { Found(unescape(it), null, "loose") }

    // ============= IFRAME =============

    private val adPatterns = listOf(
        "/ads/", "/ad/", "adserver", "doubleclick", "300x250", "300v250", "728x90", "banner"
    )

    /**
     * L'iframe del player fra quelli della pagina. Si preferisce quello con `allowfullscreen`,
     * che è sempre il video; gli altri sono riquadri pubblicitari.
     */
    fun playerIframe(html: String): String? {
        val tags = Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.value }
            .filter { tag ->
                val src = iframeSrc(tag)
                src != null && !src.startsWith("about:") && !src.startsWith("chrome-extension:") &&
                    adPatterns.none { src.contains(it, ignoreCase = true) }
            }
            .toList()
        if (tags.isEmpty()) return null
        val fullscreen = tags.firstOrNull { it.contains("allowfullscreen", ignoreCase = true) }
        return iframeSrc(fullscreen ?: tags.first())
    }

    private fun iframeSrc(tag: String): String? =
        Regex("""\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    // ============= DADDYLIVE =============

    /**
     * Numero del canale DaddyLive nascosto negli indirizzi già visti.
     *
     * Serve in due casi che capitano spesso. Il dominio del player muore (`dami-tv.pro` è stato
     * sequestrato) e il numero è ancora scritto nell'indirizzo dell'iframe. Oppure il player è vivo
     * ma il suo proxy no: le API della famiglia `damitv.st` rispondono con un indirizzo che contiene,
     * in base64, quello vero su DaddyLive (`…/premium878/index.m3u8`), e da quel numero si arriva
     * alla pagina originale, che funziona.
     */
    fun daddyId(urls: List<String>): String? {
        // Solo forme che nominano DaddyLive per esteso: un `?id=` qualsiasi appartiene a un altro
        // player, e sbagliare canale è peggio che non trovarne nessuno.
        val patterns = listOf(
            Regex("""[?&]resolve=dlhd-(\d+)"""),
            Regex("""daddy\d*\.php\?id=(\d+)"""),
            Regex("""stream-(\d+)\.php"""),
            Regex("""/premium(\d+)/"""),
        )
        urls.asReversed().forEach { url ->
            val haystack = url + " " + embeddedUrls(url)
            patterns.forEach { p ->
                p.find(haystack)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return null
    }

    /** Gli indirizzi nascosti in base64 dentro un indirizzo, un pezzo di percorso alla volta. */
    private fun embeddedUrls(url: String): String =
        url.split('/', '?', '&', '=')
            .filter { it.length >= 24 }
            .mapNotNull { part -> decodeBase64(part)?.takeIf { it.startsWith("http") } }
            .joinToString(" ")

    // ============= MANIFEST DASH =============

    /**
     * Percorso relativo del segmento di inizializzazione della prima rappresentazione.
     *
     * È l'unico posto dove leggere l'identificativo della chiave quando il manifest non lo
     * dichiara, e senza quel confronto non si sa se le chiavi pubblicate sul sito sono ancora
     * quelle del flusso.
     */
    fun initPath(manifest: String): String? {
        val template = Regex("""initialization="([^"]+)"""").find(manifest)
            ?.groupValues?.getOrNull(1) ?: return null
        val id = Regex("""<Representation\b[^>]*\bid="([^"]+)"""").find(manifest)
            ?.groupValues?.getOrNull(1) ?: return null
        return template
            .replace("\$RepresentationID\$", id)
            .replace("\$Bandwidth\$", Regex("""bandwidth="(\d+)"""")
                .find(manifest)?.groupValues?.getOrNull(1).orEmpty())
            .takeIf { !it.contains('$') }
    }

    /** `cenc:default_KID` del manifest, senza trattini. */
    fun manifestKid(manifest: String): String? =
        Regex("""default_KID="([0-9a-fA-F-]{32,36})"""").find(manifest)
            ?.groupValues?.getOrNull(1)?.replace("-", "")?.lowercase()

    /**
     * Identificativo della chiave dentro la scatola `tenc` del segmento di inizializzazione:
     * quattro byte di versione e opzioni dopo il nome, poi sedici byte di identificativo.
     */
    fun tencKid(init: ByteArray): String? {
        val name = byteArrayOf('t'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte(), 'c'.code.toByte())
        outer@ for (i in 0..init.size - (4 + 8 + 16)) {
            for (j in name.indices) if (init[i + j] != name[j]) continue@outer
            val start = i + 12
            return (start until start + 16)
                .joinToString("") { "%02x".format(init[it].toInt() and 0xFF) }
        }
        return null
    }

    // ============= CONVERSIONI =============

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Base64 fatto in casa: `java.util.Base64` arriva con Android 8 e il plugin parte da Android
     * 5, e `android.util.Base64` non esiste sulla macchina virtuale dei test.
     */
    fun decodeBase64(value: String): String? {
        val clean = value.trim()
            .replace('-', '+').replace('_', '/')
            .filter { !it.isWhitespace() && it != '=' }
        if (clean.isEmpty() || clean.any { ALPHABET.indexOf(it) < 0 }) return null
        val out = StringBuilder(clean.length * 3 / 4 + 1)
        var buffer = 0
        var bits = 0
        clean.forEach { c ->
            buffer = (buffer shl 6) or ALPHABET.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return out.toString()
    }

    /** Esadecimale → base64 senza riempimento, che è la forma che ClearKey vuole per kid e chiave. */
    fun hexToBase64Url(hex: String): String? {
        val clean = hex.trim().replace("-", "").lowercase()
        if (clean.length % 2 != 0 || clean.isEmpty()) return null
        val bytes = IntArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        }
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i]
            val b1 = bytes.getOrNull(i + 1)
            val b2 = bytes.getOrNull(i + 2)
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or ((b1 ?: 0) shr 4)])
            if (b1 != null) out.append(ALPHABET[((b1 and 0x0F) shl 2) or ((b2 ?: 0) shr 6)])
            if (b2 != null) out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return out.toString().replace('+', '-').replace('/', '_')
    }

    fun unescape(url: String): String =
        url.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&").trim()
}
