package si.sopotnik

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import si.sopotnik.actions.Action
import si.sopotnik.actions.Actions
import si.sopotnik.actions.ContactMatch

enum class SessionState { IDLE, LISTENING, THINKING, SPEAKING, CONFIRMING, FOLLOWUP, LIVE }

/**
 * Glasovna seja: poslušanje -> lokalna gramatika ali AI -> varnostna potrditev -> izvedba -> odgovor.
 * Teče kot foreground service tipa "microphone", zagnan iz vidne aktivnosti/ploščice.
 */
class SessionService : Service(), SpeechIO.Callback, AgentClient.Callback {

    interface UiListener {
        fun onSessionState(state: SessionState, label: String)
        fun onPartial(text: String)
        fun onLine(who: String, text: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): SessionService = this@SessionService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    var uiListener: UiListener? = null
        set(value) {
            field = value
            value?.onSessionState(state, stateLabel())
        }

    private lateinit var prefs: Prefs
    private lateinit var speech: SpeechIO
    private lateinit var agent: AgentClient
    private lateinit var router: IntentRouter

    var state: SessionState = SessionState.IDLE
        private set

    private var pendingCall: ContactMatch? = null
    private var sttRetryUsed = false
    private var inFollowUpListen = false
    private var endAfterSpeech = false

    private var sayBuffer = StringBuilder()
    private var spokenUpTo = 0
    private var uttSeq = 0
    private var lastFinalUtterance: String? = null

    private var focusRequest: AudioFocusRequest? = null
    private var live: LiveSession? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        router = IntentRouter(prefs)
        speech = SpeechIO(this, this)
        agent = AgentClient(prefs, this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Glasovna seja", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> endSession()
            else -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    NOTIF_ID, buildNotification("Sopotnik posluša …"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                if (state == SessionState.IDLE) startSession()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        abandonFocus()
        speech.shutdown()
        agent.shutdown()
        super.onDestroy()
    }

    // ---- potek seje ----

    private fun startSession() {
        requestFocus()
        agent.connect()
        endAfterSpeech = false
        pendingCall = null
        if (prefs.realtime) {
            setState(SessionState.LIVE)
            live = LiveSession(
                this, agent, prefs,
                onLine = { who, text -> uiListener?.onLine(who, text) },
                onEnd = { handler.post { endSession() } },
            )
            live?.start()
            return
        }
        listen(followUp = false)
    }

    private fun listen(followUp: Boolean) {
        inFollowUpListen = followUp
        sttRetryUsed = false
        if (state != SessionState.CONFIRMING) setState(SessionState.LISTENING)
        speech.listen(followUp)
    }

    fun stopSession() = endSession()

    private fun endSession() {
        handler.removeCallbacksAndMessages(null)
        val l = live
        live = null
        l?.end(notify = false)
        speech.cancelListen()
        speech.stopSpeaking()
        abandonFocus()
        pendingCall = null
        setState(SessionState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleUtterance(text: String) {
        try {
            handleUtteranceInner(text)
        } catch (e: Exception) {
            uiListener?.onLine("⚙", "notranja napaka: $e")
            speakFinal("Ups, nekaj se je zalomilo.")
        }
    }

    private fun handleUtteranceInner(text: String) {
        uiListener?.onLine("TI", text)

        if (state == SessionState.CONFIRMING) {
            val decision = SafetyGate.parseConfirmation(IntentRouter.normalize(text).trim())
            val call = pendingCall
            pendingCall = null
            when {
                decision == true && call != null -> {
                    val say = Actions.execute(this, Action.Call(call.name), resolved = call)
                    AuditLog.append(this, "dejanje", "klic -> ${call.name}", "YELLOW", "potrjeno, izvedeno")
                    endAfterSpeech = true
                    speakFinal(say)
                }

                decision == false -> {
                    AuditLog.append(this, "dejanje", "klic -> ${call?.name}", "YELLOW", "preklicano z glasom")
                    speakFinal("Prav, ne kličem.")
                }

                else -> {
                    AuditLog.append(this, "dejanje", "klic -> ${call?.name}", "YELLOW", "nerazumljiva potrditev")
                    speakFinal("Nisem razumel kot da ali ne, zato ne naredim nič.")
                }
            }
            return
        }

        when (val route = router.parse(text)) {
            is Route.End -> {
                endAfterSpeech = true
                speakFinal("Adijo.")
            }

            is Route.Answer -> {
                AuditLog.append(this, "odgovor", route.say)
                speakFinal(route.say)
            }

            is Route.Do -> runAction(route.action)

            is Route.Ai -> {
                if (missingPermission(Manifest.permission.INTERNET) != null) return
                setState(SessionState.THINKING)
                sayBuffer = StringBuilder()
                spokenUpTo = 0
                AuditLog.append(this, "ai", text)
                agent.sendTurn(text)
            }
        }
    }

    private fun runAction(action: Action) {
        if (action is Action.Call) {
            missingPermission(Manifest.permission.READ_CONTACTS)?.let { speakFinal(it); return }
            missingPermission(Manifest.permission.CALL_PHONE)?.let { speakFinal(it); return }
            val match = Actions.resolveContact(this, action.query)
            if (match == null) {
                AuditLog.append(this, "dejanje", "klic -> '${action.query}'", "YELLOW", "stik ni najden")
                speakFinal("V imeniku ne najdem stika ${action.query}.")
                return
            }
            when (SafetyGate.classify(action, prefs)) {
                Tier.YELLOW -> {
                    pendingCall = match
                    setState(SessionState.CONFIRMING)
                    speakPrompt("Kličem ${match.name}. Naj pokličem?")
                }

                else -> {
                    val say = Actions.execute(this, action, resolved = match)
                    AuditLog.append(this, "dejanje", "klic -> ${match.name}", "GREEN", "izvedeno")
                    endAfterSpeech = true
                    speakFinal(say)
                }
            }
            return
        }

        val say = Actions.execute(this, action)
        AuditLog.append(this, "dejanje", action.toString(), SafetyGate.classify(action, prefs).name, say)
        speakFinal(say)
    }

    private fun missingPermission(permission: String): String? =
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            "Manjka dovoljenje — odpri Sopotnika in ga dodeli v nastavitvah." else null

    // ---- govor ----

    private fun speakPrompt(text: String) {
        uiListener?.onLine("SVEN", text)
        if (!speech.ttsReady) {
            pendingCall = null
            speakFinal("Govor ni na voljo, zato potrditvenih dejanj ne izvajam.")
            return
        }
        speech.speak(text, "confirm-prompt")
    }

    private fun speakFinal(text: String) {
        uiListener?.onLine("SVEN", text)
        setState(SessionState.SPEAKING)
        if (!speech.ttsReady) {
            afterSpeech()
            return
        }
        val id = "final-${++uttSeq}"
        lastFinalUtterance = id
        speech.speak(text, id)
    }

    private fun speakSegment(text: String) {
        if (text.isBlank()) return
        setState(SessionState.SPEAKING)
        speech.speak(text, "seg-${++uttSeq}")
    }

    private fun flushSentences(force: Boolean) {
        val text = sayBuffer.toString()
        if (force) {
            val rest = text.substring(spokenUpTo.coerceAtMost(text.length)).trim()
            spokenUpTo = text.length
            uiListener?.onLine("SVEN", text)
            setState(SessionState.SPEAKING)
            val id = "final-${++uttSeq}"
            lastFinalUtterance = id
            if (speech.ttsReady && rest.isNotEmpty()) speech.speak(rest, id) else afterSpeech()
            return
        }
        var boundary = -1
        for (i in text.length - 1 downTo spokenUpTo) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if ((c == '.' || c == '!' || c == '?') && (next == ' ' || next == '\n')) {
                boundary = i
                break
            }
        }
        if (boundary >= spokenUpTo) {
            speakSegment(text.substring(spokenUpTo, boundary + 1).trim())
            spokenUpTo = boundary + 1
        }
    }

    private fun afterSpeech() {
        if (endAfterSpeech || !prefs.followUp) {
            endSession()
            return
        }
        setState(SessionState.FOLLOWUP)
        // Kratek premor: takojšen zagon prepoznave po TTS na tej napravi
        // konča s SERVER_DISCONNECTED (zvočni cevovod še ni sproščen).
        handler.postDelayed({ if (state == SessionState.FOLLOWUP) listen(followUp = true) }, 350)
    }

    // ---- SpeechIO.Callback ----

    override fun onTtsReady(ok: Boolean) {
        if (!ok) uiListener?.onLine("⚙", "Slovenski glas TTS ni na voljo — preveri Google TTS.")
    }

    override fun onSttReady() {
        setState(if (state == SessionState.CONFIRMING) SessionState.CONFIRMING else SessionState.LISTENING)
    }

    override fun onSttPartial(text: String) {
        uiListener?.onPartial(text)
    }

    override fun onSttFinal(text: String) {
        uiListener?.onPartial("")
        handleUtterance(text)
    }

    override fun onSttError(name: String, transient: Boolean, noSpeech: Boolean) {
        when {
            noSpeech && state == SessionState.CONFIRMING -> {
                pendingCall = null
                speakFinal("Nisem slišal potrditve, zato ne naredim nič.")
            }

            noSpeech -> {
                uiListener?.onLine("⚙", "nič slišanega ($name) — konec seje")
                endSession()
            }

            transient && !sttRetryUsed -> {
                sttRetryUsed = true
                uiListener?.onLine("⚙", "STT $name — poskušam znova")
                handler.postDelayed({ if (state != SessionState.IDLE) speech.listen(inFollowUpListen) }, 400)
            }

            name == "PERMISSIONS" -> endSession()

            else -> speakFinal("Prepoznava govora trenutno ne deluje ($name).")
        }
    }

    override fun onSttDebug(msg: String) {
        uiListener?.onLine("⚙", msg)
    }

    override fun onUtteranceDone(id: String) {
        when {
            id == "confirm-prompt" && state == SessionState.CONFIRMING ->
                handler.postDelayed({ if (state == SessionState.CONFIRMING) speech.listen(true) }, 300)

            id == lastFinalUtterance -> afterSpeech()
        }
    }

    // ---- AgentClient.Callback ----

    override fun onAgentReady() {}

    override fun onSayDelta(text: String) {
        if (state != SessionState.THINKING && state != SessionState.SPEAKING) return
        sayBuffer.append(text)
        flushSentences(force = false)
    }

    override fun onTurnDone(say: String, actionsJson: String?) {
        if (state != SessionState.THINKING && state != SessionState.SPEAKING) return
        if (sayBuffer.isEmpty() && say.isNotEmpty()) sayBuffer.append(say)
        if (sayBuffer.isEmpty()) sayBuffer.append("Ni odgovora.")
        actionsJson?.let { json ->
            runCatching { JSONArray(json) }.getOrNull()?.takeIf { it.length() > 0 }?.let {
                AuditLog.append(this, "ai-akcije", it.toString(), null, "v fazi 1 se ne izvajajo")
            }
        }
        flushSentences(force = true)
    }

    override fun onAgentDropped(message: String): Boolean {
        // v živem načinu padec povezave najprej rešuje LiveSession s ponovnim povezovanjem
        if (state != SessionState.LIVE) return false
        return live?.scheduleReconnect() == true
    }

    override fun onAgentError(message: String) {
        if (state == SessionState.LIVE) {
            uiListener?.onLine("⚙", message)
            endSession()
            return
        }
        if (state != SessionState.THINKING && state != SessionState.SPEAKING) return
        speakFinal(message)
    }

    override fun onRtMessage(msg: JSONObject) {
        live?.onRt(msg)
    }

    // ---- infrastruktura ----

    private fun setState(s: SessionState) {
        state = s
        uiListener?.onSessionState(s, stateLabel())
        if (s != SessionState.IDLE) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(stateLabel()))
        }
    }

    private fun stateLabel(): String = when (state) {
        SessionState.IDLE -> if (prefs.realtime) "Pripravljen — Sven Live je VKLOPLJEN." else "Pripravljen — klasični način."
        SessionState.LISTENING -> "Poslušam …"
        SessionState.THINKING -> "Razmišljam …"
        SessionState.SPEAKING -> "Govorim …"
        SessionState.CONFIRMING -> "Čakam potrditev …"
        SessionState.FOLLOWUP -> "Poslušam nadaljevanje …"
        SessionState.LIVE -> "Sven Live 🔴 — govori kar naravno"
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .build()
            .also { am.requestAudioFocus(it) }
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sopotnik")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 10
        const val ACTION_STOP = "si.sopotnik.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        }
    }
}
