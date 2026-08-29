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
    private val pendingTimeout = Runnable {
        if (pendingTurn != null) {
            pendingTurn = null
            cb.onAgentError("Backend se ne odziva.")
        }
    }

    val isReady: Boolean get() = ready

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
        }
    }

    private fun onDisconnected(message: String) {
        ws = null
        ready = false
        pendingTurn = null
        handler.removeCallbacks(pendingTimeout)
        if (!shuttingDown) cb.onAgentError(message)
        shuttingDown = false
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
