package si.sopotnik

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket povezava na backend »gateway« (protokol v1, gl. backend/README.md).
 * Vsa stanja tečejo na glavni niti; OkHttp povratni klici se preusmerijo nanjo.
 */
class AgentClient(private val prefs: Prefs, private val cb: Callback) {

    interface Callback {
        fun onAgentReady()
        fun onSayDelta(text: String)
        fun onTurnDone(say: String, actionsJson: String?)
        fun onAgentError(message: String)
        fun onRtMessage(msg: JSONObject) {}

        /** Povezava je padla (ne ob shutdown). Vrni true, če boš sprožil ponovno povezavo
         *  in naj se onAgentError NE kliče. */
        fun onAgentDropped(message: String): Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var ready = false
    private var shuttingDown = false
    private var pendingTurn: String? = null
    private val rawQueue = mutableListOf<String>()
    private val pendingTimeout = Runnable {
        if (pendingTurn != null) {
            pendingTurn = null
            cb.onAgentError("Backend se ne odziva.")
        }
    }

    val isReady: Boolean get() = ready

    /** Surovo sporočilo (Sven Live rt_* protokol); pred pripravljenostjo se uvrsti v čakalno
     *  vrsto. Zvočni paketki (rt_audio) se med izpadom zavržejo — zastarel zvok je neuporaben,
     *  kopičenje pa bi ob obnovi poplavilo strežnik. */
    fun sendJson(obj: JSONObject) {
        handler.post {
            val s = obj.toString()
            when {
                ready -> ws?.send(s)
                obj.optString("t") == "rt_audio" -> Unit
                else -> {
                    rawQueue.add(s)
                    connect()
                }
            }
        }
    }

    fun sendTurn(text: String) {
        if (ready) {
            ws?.send(JSONObject().put("t", "user_turn").put("text", text).toString())
            return
        }
        pendingTurn = text
        handler.removeCallbacks(pendingTimeout)
        handler.postDelayed(pendingTimeout, 8_000)
        connect()
    }

    fun connect() {
        if (ws != null) return
        val url = prefs.backendUrl
        if (url.isEmpty() || !(url.startsWith("ws://") || url.startsWith("wss://"))) {
            handler.post { cb.onAgentError("Naslov backenda ni nastavljen (nastavitve).") }
            return
        }
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject().put("t", "hello").put("token", prefs.token).put("lang", "sl").toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = t.message?.take(160)?.let { " ($it)" } ?: ""
                handler.post { onDisconnected("Povezava z backendom ni uspela$detail") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { onDisconnected("Backend je prekinil povezavo.") }
            }
        })
    }

    private fun handleMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "ready" -> {
                ready = true
                cb.onAgentReady()
                rawQueue.forEach { ws?.send(it) }
                rawQueue.clear()
                pendingTurn?.let {
                    pendingTurn = null
                    handler.removeCallbacks(pendingTimeout)
                    sendTurn(it)
                }
            }

            "say_delta" -> cb.onSayDelta(msg.optString("text"))

            "turn_done" -> cb.onTurnDone(
                msg.optString("say"),
                msg.optJSONArray("actions")?.toString()
            )

            "error" -> cb.onAgentError(msg.optString("message", "Napaka na backendu."))

            else -> if (msg.optString("t").startsWith("rt_")) cb.onRtMessage(msg)
        }
    }

    private fun onDisconnected(message: String) {
        ws = null
        ready = false
        pendingTurn = null
        rawQueue.clear()
        handler.removeCallbacks(pendingTimeout)
        val wasShutdown = shuttingDown
        shuttingDown = false
        if (wasShutdown) return
        if (!cb.onAgentDropped(message)) cb.onAgentError(message)
    }

    fun resetConversation() {
        if (ready) ws?.send(JSONObject().put("t", "reset").toString())
    }

    fun shutdown() {
        shuttingDown = true
        handler.removeCallbacks(pendingTimeout)
        ws?.close(1000, "konec")
        ws = null
        ready = false
        pendingTurn = null
    }
}
