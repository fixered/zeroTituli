package it.zeroTituli

/**
 * Lettore minimale del formato wire dei Protocol Buffers.
 *
 * L'API di FCTV33 risponde in protobuf e lo schema non è pubblico: i numeri di campo sono stati
 * ricavati leggendo i dati (vedi docs/superpowers/specs/2026-07-31-plugin-fctv33-design.md).
 * Serve solo leggere, quindi bastano i tipi varint e length-delimited; i campi sconosciuti
 * vengono saltati.
 */
internal class Pb private constructor(private val entries: List<Pair<Int, Any>>) {

    companion object {
        fun parse(bytes: ByteArray): Pb {
            val out = ArrayList<Pair<Int, Any>>()
            var i = 0
            while (i < bytes.size) {
                val (key, afterKey) = varint(bytes, i) ?: break
                i = afterKey
                val field = (key ushr 3).toInt()
                when ((key and 7L).toInt()) {
                    0 -> {
                        val (value, next) = varint(bytes, i) ?: return Pb(out)
                        out += field to (value as Any)
                        i = next
                    }
                    1 -> {
                        if (i + 8 > bytes.size) return Pb(out)
                        i += 8
                    }
                    2 -> {
                        val (len, afterLen) = varint(bytes, i) ?: return Pb(out)
                        val start = afterLen
                        val end = start + len.toInt()
                        if (len < 0 || end > bytes.size) return Pb(out)
                        out += field to bytes.copyOfRange(start, end)
                        i = end
                    }
                    5 -> {
                        if (i + 4 > bytes.size) return Pb(out)
                        i += 4
                    }
                    else -> return Pb(out) // gruppi deprecati: si smette
                }
            }
            return Pb(out)
        }

        /** @return valore e indice successivo, oppure null se i byte finiscono a metà. */
        private fun varint(bytes: ByteArray, from: Int): Pair<Long, Int>? {
            var result = 0L
            var shift = 0
            var i = from
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                i++
                if (b and 0x80 == 0) return result to i
                shift += 7
                if (shift > 63) return null
            }
            return null
        }
    }

    fun longs(field: Int): List<Long> = entries.filter { it.first == field }.mapNotNull { it.second as? Long }

    fun long(field: Int): Long? = longs(field).firstOrNull()

    private fun blobs(field: Int): List<ByteArray> =
        entries.filter { it.first == field }.mapNotNull { it.second as? ByteArray }

    fun strings(field: Int): List<String> = blobs(field).map { it.toString(Charsets.UTF_8) }

    fun string(field: Int): String? = strings(field).firstOrNull()

    fun messages(field: Int): List<Pb> = blobs(field).map { parse(it) }

    fun message(field: Int): Pb? = messages(field).firstOrNull()

    /** Scorciatoia per i percorsi annidati: `path(10, 3, 2)` legge `10.3.2` come stringa. */
    fun stringAt(vararg path: Int): String? {
        var node: Pb? = this
        path.dropLast(1).forEach { step -> node = node?.message(step) ?: return null }
        return node?.string(path.last())
    }
}
