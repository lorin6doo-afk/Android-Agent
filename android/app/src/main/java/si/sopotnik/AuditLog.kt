package si.sopotnik

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lokalni dnevnik dejanj: JSONL v zasebnem pomnilniku aplikacije, izvoz samo ročno. */
object AuditLog {

    private const val FILE = "audit.jsonl"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun append(ctx: Context, type: String, detail: String, tier: String? = null, outcome: String? = null) {
        runCatching {
            val o = JSONObject()
                .put("ts", fmt.format(Date()))
                .put("type", type)
                .put("detail", detail)
            tier?.let { o.put("tier", it) }
            outcome?.let { o.put("outcome", it) }
            File(ctx.filesDir, FILE).appendText(o.toString() + "\n")
        }
    }

    fun tail(ctx: Context, n: Int): List<String> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(n).mapNotNull { line ->
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
