package si.sopotnik

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import si.sopotnik.actions.Action
import si.sopotnik.actions.Actions
import si.sopotnik.actions.ContactMatch
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * »Sven Live« — dvosmerni realtime govor: mikrofon teče na backend (in naprej na
 * OpenAI Realtime), nazaj prihajajo zvok, prepisi in klici orodij (dejanja telefona).
 * Trdno pravilo za klice: call_contact pokliče IZKLJUČNO številko, ki jo je pred tem
 * vrnil find_contact — klasifikacija ostaja lokalna, model doda le ustno potrditev.
 */
class LiveSession(
    private val service: SessionService,
    private val agent: AgentClient,
    private val prefs: Prefs,
    private val onLine: (String, String) -> Unit,
    private val onEnd: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val playExec = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)

    private var recorder: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var recordThread: Thread? = null
    private var track: AudioTrack? = null
    private var lastFound: ContactMatch? = null
    private var lastNotifs: List<NotifEntry> = emptyList()
    private var lastNotifsAt = 0L
    private var currentRate = 24_000
    private var reconnects = 0

    private val idleTimeout = Runnable {
        onLine("⚙", "2 minuti tišine — končujem živo sejo.")
        end(notify = true)
    }

    private fun touchActivity() {
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, 120_000)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return

        var rate = 24_000
        var minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minIn <= 0) {
            rate = 16_000
            minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }

        val outMin = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(outMin * 2, 19_200))
            .build()
            .also { it.play() }

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minIn * 2, 4_800)
        )
        recorder = rec
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
        }

        // vezava poslušalca obvestil naj steče že zdaj (obhod traja do ~2,5 s),
        // da je ob prvem »kaj je novega« seznam že na voljo
        NotifListener.ensureBound(service)

        currentRate = rate
        agent.sendJson(JSONObject().put("t", "rt_start").put("rate", rate))

        rec.startRecording()
        val chunk = ByteArray(rate / 25 * 2) // ~40 ms
        recordThread = Thread {
            while (running.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val b64 = Base64.encodeToString(chunk.copyOf(n), Base64.NO_WRAP)
                    agent.sendJson(JSONObject().put("t", "rt_audio").put("data", b64))
                }
            }
        }.also { it.start() }

        touchActivity()
        onLine("⚙", "Sven Live: povezujem …")
    }

    /**
     * WS do backenda je padel: mikrofon in seja ostaneta živa, povezavo pa poskusimo
     * obnoviti do 4× (1/2/4/8 s). Ob uspehu backend odpre svežo Realtime sejo — Sven
     * si prejšnjih stavkov ne zapomni, pogovor pa se nadaljuje brez ponovnega zagona.
     * Vrne false, ko odnehamo (klicatelj naj gre po običajni poti napake).
     */
    fun scheduleReconnect(): Boolean {
        if (ended.get() || reconnects >= 4) return false
        reconnects++
        val delayMs = 500L shl reconnects // 1 s, 2 s, 4 s, 8 s
        onLine("⚙", "Povezava z backendom prekinjena — znova povezujem (poskus $reconnects/4) …")
        handler.postDelayed({
            if (!ended.get()) agent.sendJson(JSONObject().put("t", "rt_start").put("rate", currentRate))
        }, delayMs)
        return true
    }

    fun onRt(msg: JSONObject) {
        when (msg.optString("t")) {
            "rt_ready" -> {
                touchActivity()
                if (reconnects > 0) {
                    reconnects = 0
                    onLine("⚙", "Povezava obnovljena — kar nadaljuj.")
                } else onLine("⚙", "Sven Live pripravljen — kar govori.")
            }

            "rt_audio" -> {
                val bytes = runCatching { Base64.decode(msg.optString("data"), Base64.NO_WRAP) }.getOrNull() ?: return
                playExec.execute { runCatching { track?.write(bytes, 0, bytes.size) } }
            }

            "rt_cut" -> {
                touchActivity()
                playExec.execute {
                    runCatching {
                        track?.pause()
                        track?.flush()
                        track?.play()
                    }
                }
            }

            "rt_user_text" -> {
                touchActivity()
                onLine("TI", msg.optString("text"))
            }

            "rt_sven_text" -> onLine("SVEN", msg.optString("text"))

            "rt_action" -> {
                touchActivity()
                handleTool(msg.optString("callId"), msg.optString("name"), msg.optJSONObject("args") ?: JSONObject())
            }

            "rt_error" -> {
                onLine("⚙", msg.optString("message", "Napaka žive seje."))
                end(notify = true)
            }

            "rt_closed" -> {
                onLine("⚙", "Živa seja zaprta.")
                end(notify = true)
            }
        }
    }

    private fun result(callId: String, output: String) {
        agent.sendJson(JSONObject().put("t", "rt_action_result").put("callId", callId).put("output", output))
    }

    /**
     * Poslušalec obvestil je lahko tik po zagonu še nevezan (obhod v ensureBound
     * traja do ~2,5 s), zato na vezavo počakamo asinhrono in orodju odgovorimo
     * šele nato — Realtime API na izid orodja mirno počaka.
     */
    private fun readNotificationsAsync(callId: String, attempt: Int = 0) {
        NotifListener.ensureBound(service)
        val list = NotifListener.snapshot()
        if (list == null && attempt < 10 && running.get()) {
            handler.postDelayed({ readNotificationsAsync(callId, attempt + 1) }, 400)
            return
        }
        val out = when {
            list == null -> "Dostopa do obvestil ni uspelo vzpostaviti. Povej uporabniku, naj v nastavitvah Sopotnika pritisne 🩺 Diagnostika obvestil."
            list.isEmpty() -> "Ni aktivnih obvestil."
            else -> {
                lastNotifs = list
                lastNotifsAt = System.currentTimeMillis()
                AuditLog.append(service, "obvestila", "prebranih ${list.size} (live)", "GREEN", "ok")
                list.mapIndexed { i, n ->
                    "${i + 1}) [${n.app}] ${n.title}: ${n.text.take(160)}${if (n.canReply) " [odgovor možen]" else ""}"
                }.joinToString("\n")
            }
        }
        Log.i("Sopotnik", "orodje read_notifications (poskus $attempt) -> ${out.take(120)}")
        result(callId, out)
    }

    private fun handleTool(callId: String, name: String, args: JSONObject) {
        if (name == "read_notifications") {
            touchActivity()
            readNotificationsAsync(callId)
            return
        }
        val out: String = when (name) {
            "get_time" -> {
                val c = Calendar.getInstance()
                "Ura je ${c.get(Calendar.HOUR_OF_DAY)} in ${c.get(Calendar.MINUTE)} minut."
            }

            "find_contact" -> {
                val q = args.optString("query")
                val m = Actions.resolveContact(service, q)
                lastFound = m
                if (m == null) "Stika '$q' ni v imeniku."
                else "Najden stik: ${m.name}. Ustno vprašaj uporabnika za potrditev, nato uporabi call_contact."
            }

            "call_contact" -> {
                val c = lastFound
                if (c == null) "Najprej uporabi find_contact."
                else {
                    lastFound = null
                    val say = Actions.execute(service, Action.Call(c.name), resolved = c)
                    AuditLog.append(service, "dejanje", "klic -> ${c.name} (live)", "YELLOW", "ustno potrjeno, izvedeno")
                    say
                }
            }

            "media_control" -> when (args.optString("op")) {
                "play" -> Actions.execute(service, Action.MediaPlay)
                "pause" -> Actions.execute(service, Action.MediaPause)
                "next" -> Actions.execute(service, Action.MediaNext)
                "prev" -> Actions.execute(service, Action.MediaPrev)
                else -> "Neznana operacija."
            }

            "play_music" -> {
                val app = args.optString("app").takeIf { it == "youtube" || it == "spotify" }
                Actions.execute(service, Action.MediaPlaySearch(args.optString("query"), app))
            }

            "navigate" -> {
                val destRaw = args.optString("destination")
                val dn = IntentRouter.normalize(destRaw).trim()
                val dest = when {
                    dn in setOf("domov", "dom", "domu") ->
                        prefs.homeAddress.ifEmpty { null }
                    dn.contains("sluzb") || dn == "delo" || dn == "na delo" ->
                        prefs.workAddress.ifEmpty { null }
                    else -> destRaw
                }
                if (dest == null) "Naslov za ta cilj ni nastavljen v aplikaciji."
                else Actions.execute(service, Action.Navigate(dest))
            }

            "set_volume" -> when {
                args.has("percent") -> Actions.execute(service, Action.VolumeSet(args.optInt("percent").coerceIn(0, 100)))
                args.optString("direction") == "up" -> Actions.execute(service, Action.VolumeUp)
                args.optString("direction") == "down" -> Actions.execute(service, Action.VolumeDown)
                else -> "Povej odstotek ali smer."
            }

            "torch" -> Actions.execute(service, Action.Torch(args.optBoolean("on")))

            "open_app" -> Actions.execute(service, Action.OpenApp(args.optString("name")))

            "open_notification" -> {
                val idx = args.optInt("number") - 1
                val fresh = System.currentTimeMillis() - lastNotifsAt < 5 * 60_000
                val entry = lastNotifs.getOrNull(idx)
                when {
                    entry == null || !fresh -> "Najprej znova preberi obvestila z read_notifications."
                    NotifListener.open(entry.key) -> "Odprto na zaslonu telefona: ${entry.app} — ${entry.title}."
                    else -> "Tega obvestila ni mogoče odpreti — morda je medtem izginilo."
                }
            }

            "send_reply" -> {
                val idx = args.optInt("number") - 1
                val text = args.optString("text")
                val fresh = System.currentTimeMillis() - lastNotifsAt < 5 * 60_000
                val entry = lastNotifs.getOrNull(idx)
                when {
                    text.isBlank() -> "Manjka besedilo odgovora."
                    entry == null || !fresh -> "Najprej znova preberi obvestila z read_notifications."
                    !entry.canReply -> "Na to obvestilo ni mogoče odgovoriti."
                    NotifListener.reply(entry.key, text) -> {
                        AuditLog.append(service, "dejanje", "odgovor -> ${entry.app}/${entry.title}: $text", "YELLOW", "ustno potrjeno, poslano (live)")
                        "Odgovor poslan."
                    }
                    else -> {
                        AuditLog.append(service, "dejanje", "odgovor -> ${entry.app}/${entry.title}", "YELLOW", "pošiljanje ni uspelo")
                        "Pošiljanje ni uspelo — obvestilo je morda izginilo."
                    }
                }
            }

            "end_conversation" -> {
                handler.postDelayed({ end(notify = true) }, 5_000)
                "Seja se bo končala. Poslovi se."
            }

            else -> "Orodja '$name' ne poznam."
        }

        Log.i("Sopotnik", "orodje $name($args) -> $out")
        val selfAudited = setOf("find_contact", "call_contact", "get_time", "end_conversation", "send_reply")
        if (name !in selfAudited) {
            AuditLog.append(service, "dejanje", "$name ${args} (live)", "GREEN", out)
        }
        result(callId, out)
    }

    fun end(notify: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        running.set(false)
        handler.removeCallbacksAndMessages(null)
        runCatching { recordThread?.join(500) }
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { aec?.release() }
        recorder = null
        playExec.execute {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            track = null
        }
        playExec.shutdown()
        // rt_stop le, če povezava stoji — sicer bi zgolj zaradi slovesa znova vzpostavljali WS
        if (agent.isReady) agent.sendJson(JSONObject().put("t", "rt_stop"))
        if (notify) onEnd()
    }
}
