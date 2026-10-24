package si.sopotnik

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lokalni dnevnik dejanj: JSONL v zasebnem pomnilniku aplikacije, izvoz samo ročno.
 * Vsebina sporočil se ne zapisuje (le prejemnik, vrsta in izid); datoteka je omejena
 * na ~256 KB — ob prekoračitvi ostane novejša polovica.
 */
object AuditLog {

    private const val FILE = "audit.jsonl"
    private const val MAX_BYTES = 256 * 1024L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun append(ctx: Context, type: String, detail: String, tier: String? = null, outcome: String? = null) {
        runCatching {
            val o = JSONObject()
                .put("ts", fmt.format(Date()))
                .put("type", type)
                .put("detail", detail.take(300))
            tier?.let { o.put("tier", it) }
            outcome?.let { o.put("outcome", it.take(200)) }
            val f = File(ctx.filesDir, FILE)
            f.appendText(o.toString() + "\n")
            if (f.length() > MAX_BYTES) trim(f)
        }
    }

    private fun trim(f: File) {
        val keep = f.readText().let { it.substring(it.length / 2) }
        f.writeText(keep.substringAfter('\n'))
    }

    /** Zadnjih [n] vnosov — bere le konec datoteke. */
    @Synchronized
    fun tail(ctx: Context, n: Int): List<String> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val text = runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val from = (len - 64 * 1024).coerceAtLeast(0)
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8).let { if (from > 0) it.substringAfter('\n') else it }
            }
        }.getOrDefault("")
        return text.lines().filter { it.isNotBlank() }.takeLast(n).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                buildString {
                    append(o.optString("ts"))
                    append("  [").append(o.optString("type")).append("] ")
                    append(o.optString("detail"))
                    o.optString("tier").takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
                    o.optString("outcome").takeIf { it.isNotEmpty() }?.let { append(" -> ").append(it) }
                }
            }.getOrNull()
        }
    }
}
