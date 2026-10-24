package si.sopotnik

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import si.sopotnik.actions.Action
import si.sopotnik.actions.Actions
import si.sopotnik.actions.ContactMatch
import java.util.concurrent.CopyOnWriteArraySet

enum class SessionState { IDLE, LISTENING, THINKING, SPEAKING, CONFIRMING, FOLLOWUP, LIVE }

/**
 * Glasovna seja: poslušanje -> lokalna gramatika ali AI -> varnostna potrditev -> izvedba -> odgovor.
 * Teče kot foreground service tipa "microphone". Edini lastnik seje: zaženejo jo aplikacija,
 * ploščica, sistemski asistent (sejno okno SvenSession) ali gumb na slušalkah — vsi prek start().
 * UI (MainActivity, sejno okno) je le poslušalec; vsi klici poslušalcev tečejo na glavni niti.
 */
class SessionService : Service(), SpeechIO.Callback, AgentClient.Callback {

    interface UiListener {
        fun onSessionState(state: SessionState, label: String)
        fun onPartial(text: String)
        fun onLine(who: String, text: String)

        /** Dejanje je zavrnjeno, ker je telefon zaklenjen (sejno okno pokaže gumb Odkleni). */
        fun onUnlockNeeded() {}

        /**
         * Sven bo odprl ali bral DRUGO aplikacijo: sejno okno naj se umakne (seja teče naprej),
         * sicer prekrije aplikacijo in branje zaslona bi bralo Svenovo okno. true = okno je bilo vidno.
         */
        fun onExternalUi(): Boolean = false
    }

    inner class LocalBinder : Binder() {
        fun service(): SessionService = this@SessionService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val listeners = CopyOnWriteArraySet<UiListener>()

    /** Zadnje vrstice pogovora — le v RAM-u (sejno okno ob odklenjenem telefonu). */
    private val recent = ArrayDeque<Pair<String, String>>()

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

    private var lastTriggerAt = 0L
    private var lastLocked: Boolean? = null
    private var screenRxRegistered = false

    /**
     * Med sejo na ODKLENJENEM telefonu zaslon ne sme sam ugasniti (ugasnjen zaslon = zaklep = nova
     * seja brez konteksta in omejena orodja) — tudi ko je spredaj druga aplikacija (WhatsApp, ki ga
     * Sven bere). Drži največ KEEP_ON_MS od zadnje aktivnosti; ob zaklepu in koncu seje se sprosti.
     */
    @Suppress("DEPRECATION")
    private val screenLock: PowerManager.WakeLock? by lazy {
        runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "sopotnik:session")
                .apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        prefs = Prefs(this)
        router = IntentRouter(prefs)
        speech = SpeechIO(this, this)
        agent = AgentClient(prefs, this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Glasovna seja", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "F3 stop (obvestilo/ukaz)")
            endSession()
            return START_NOT_STICKY
        }
        // Pogodbo startForegroundService() izpolni VEDNO najprej — tudi ko seje ne bo
        // (sicer sistem čez 10 s sesuje aplikacijo). A14+: mikrofon iz ozadja brez izjeme vrže.
        val fgOk = try {
            startForeground(NOTIF_ID, buildNotification("Sopotnik posluša …"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "F3 fgs refused $e")
            false
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "F3 fgs refused $e")
            false
        }
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val src = intent?.getStringExtra(EXTRA_SOURCE) ?: "app"
        Log.i(TAG, "F3 fgs ok=$fgOk mic=$micOk src=$src state=$state")
        if (!fgOk || !micOk) {
            emitLine(
                "⚙", if (!micOk) "Manjka dovoljenje za mikrofon — odpri Sopotnika in ga dodeli."
                else "Android ni dovolil mikrofona v ozadju. Odpri Sopotnika ali ga nastavi kot privzetega digitalnega pomočnika (Nastavitve → Sistemski asistent)."
            )
            if (state == SessionState.IDLE) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val now = SystemClock.elapsedRealtime()
        when {
            state == SessionState.IDLE -> {
                lastTriggerAt = now
                Log.i(TAG, "F3 start new src=$src")
                startSession()
            }
            now - lastTriggerAt < 1_000 -> Log.i(TAG, "F3 start dup ignored src=$src") // npr. tipka + slušalke hkrati
            else -> {
                lastTriggerAt = now
                bargeIn()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        unregisterScreenRx()
        releaseScreen()
        live?.end(notify = false)
        live = null
        abandonFocus()
        speech.shutdown()
        agent.shutdown()
        super.onDestroy()
    }

    // ---- poslušalci UI (glavna nit) ----

    fun addUiListener(l: UiListener) {
        listeners += l
        l.onSessionState(state, stateLabel())
    }

    fun removeUiListener(l: UiListener) {
        listeners -= l
    }

    fun recentLines(n: Int): List<Pair<String, String>> = recent.toList().takeLast(n)

    fun notifyUnlockNeeded() = listeners.forEach { it.onUnlockNeeded() }

    /** Glej UiListener.onExternalUi. true = sejno okno se je pravkar umaknilo (počakaj, da izgine). */
    fun notifyExternalUi(): Boolean {
        var hid = false
        listeners.forEach { if (it.onExternalUi()) hid = true }
        return hid
    }

    private fun emitLine(who: String, text: String) {
        recent.addLast(who to text)
        while (recent.size > 8) recent.removeFirst()
        listeners.forEach { it.onLine(who, text) }
    }

    private fun emitPartial(text: String) = listeners.forEach { it.onPartial(text) }

    /** Uporabnik je aktiven (govor, orodje): podaljšaj prižgan zaslon — le ob odklenjenem telefonu. */
    fun userActive() {
        if (state == SessionState.IDLE || LockState.locked(this)) return
        runCatching { screenLock?.acquire(KEEP_ON_MS) }
    }

    private fun releaseScreen() {
        runCatching { screenLock?.let { if (it.isHeld) it.release() } }
    }

    // ---- potek seje ----

    private fun startSession() {
        requestFocus()
        agent.connect()
        endAfterSpeech = false
        pendingCall = null
        recent.clear()
        lastLocked = null
        registerScreenRx()
        checkLock()
        handler.post(lockPoll)
        handler.postDelayed(sessionCap, MAX_SESSION_MS)
        if (prefs.realtime) {
            setState(SessionState.LIVE)
            live = LiveSession(
                this, agent, prefs,
                onLine = { who, text -> emitLine(who, text) },
                onEnd = { handler.post { endSession() } },
            )
            live?.start()
            return
        }
        listen(followUp = false)
    }

    /** Ponovni sprožilec med sejo = prekini Svena in poslušaj. THINKING/LISTENING/CONFIRMING: pusti. */
    private fun bargeIn() {
        Log.i(TAG, "F3 bargein state=$state")
        when (state) {
            SessionState.SPEAKING, SessionState.FOLLOWUP -> {
                lastFinalUtterance = null // stop govora ne sme sprožiti afterSpeech
                endAfterSpeech = false
                speech.stopSpeaking()
                speech.cancelListen()
                listen(followUp = true)
            }
            SessionState.LIVE -> live?.interruptPlayback()
            else -> Unit
        }
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
        unregisterScreenRx()
        releaseScreen()
        val l = live
        live = null
        l?.end(notify = false)
        speech.cancelListen()
        speech.stopSpeaking()
        abandonFocus()
        pendingCall = null
        // Zapre povezavo: zapozneli odgovor preklicanega AI obrata tako ne more priti
        // v naslednjo sejo, gateway pa za novo sejo začne s praznim kontekstom.
        agent.shutdown()
        setState(SessionState.IDLE)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private val sessionCap = Runnable {
        emitLine("⚙", "Seja je dosegla časovno omejitev — končujem.")
        endSession()
    }

    private val lockedCap = Runnable {
        if (LockState.locked(this)) {
            emitLine("⚙", "Telefon je zaklenjen že ${LOCKED_CAP_MS / 60_000} minute — končujem sejo.")
            endSession()
        }
    }

    // ---- zaklep ----

    private val lockPoll = object : Runnable {
        override fun run() {
            checkLock()
            if (state != SessionState.IDLE) handler.postDelayed(this, 500)
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = checkLock()
    }

    private fun registerScreenRx() {
        if (screenRxRegistered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // le zaščiteni sistemski broadcasti; izvoz ni potreben
        screenRxRegistered = runCatching { registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED) }.isSuccess
    }

    private fun unregisterScreenRx() {
        if (!screenRxRegistered) return
        runCatching { unregisterReceiver(screenRx) }
        screenRxRegistered = false
    }

    private fun checkLock() {
        val l = LockState.locked(this)
        if (l == lastLocked) return
        val first = lastLocked == null
        lastLocked = l
        Log.i(TAG, "F3 lock locked=$l")
        if (!l) lockedFinds.clear() // omejitev iskanj stikov ob zaklepu velja do odklepa
        if (l) releaseScreen() else userActive()
        // Zaklenjen telefon: trda zgornja meja seje (okoliški govor jo sicer drži pri življenju).
        handler.removeCallbacks(lockedCap)
        if (l) handler.postDelayed(lockedCap, LOCKED_CAP_MS)
        if (!first) live?.onLockChanged(l)
        if (state != SessionState.IDLE) setState(state)
    }

    /** UnlockActivity: odklep uspel — takoj osveži stanje (ne čakaj na poll). */
    fun onUnlocked() = checkLock()

    /** Klasični tok: true = sme naprej; sicer zavrne z glasom in zapisom. [tag] gre v logcat (brez vsebine), [audit] v dnevnik dejanj. */
    private fun gate(rule: LockRule, tag: String, audit: String = tag): Boolean {
        val locked = LockState.locked(this)
        LockPolicy.deny(locked, rule) ?: return true
        Log.i(TAG, "F3 gate tool=$tag rule=$rule locked=$locked -> deny")
        AuditLog.append(this, "zaklep", audit, "LOCKED", "zavrnjeno ($rule)")
        notifyUnlockNeeded()
        speakFinal(
            if (rule == LockRule.FORBID) "Tega ob zaklenjenem telefonu ne smem."
            else "Telefon je zaklenjen. Odkleni ga in ponovi."
        )
        return false
    }

    private fun handleUtterance(text: String) {
        try {
            handleUtteranceInner(text)
        } catch (e: Exception) {
            emitLine("⚙", "notranja napaka: $e")
            speakFinal("Ups, nekaj se je zalomilo.")
        }
    }

    private fun handleUtteranceInner(text: String) {
        emitLine("TI", text)

        if (state == SessionState.CONFIRMING) {
            val decision = SafetyGate.parseConfirmation(IntentRouter.normalize(text).trim(), kind = SafetyGate.Kind.CALL)
            val call = pendingCall
            pendingCall = null
            when {
                decision == true && call != null -> {
                    // zaklep se je med vprašanjem in odgovorom lahko spremenil
                    if (!gate(LockPolicy.forAction(Action.Call(call.name), prefs.lockCfg()), "klic (potrditev)")) return
                    val say = Actions.execute(this, Action.Call(call.name), resolved = call)
                    AuditLog.append(this, "dejanje", "klic -> ${call.name}", "YELLOW", if (say.startsWith("Kličem")) "potrjeno, izvedeno" else "potrjeno, ni uspelo: $say")
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
                if (!gate(LockPolicy.AI_TURN, "ai")) return
                setState(SessionState.THINKING)
                sayBuffer = StringBuilder()
                spokenUpTo = 0
                AuditLog.append(this, "ai", text)
                agent.sendTurn(text)
            }
        }
    }

    private fun runAction(action: Action) {
        if (!gate(LockPolicy.forAction(action, prefs.lockCfg()), action::class.simpleName ?: "action", action.toString())) return
        if (action is Action.Call) {
            missingPermission(Manifest.permission.READ_CONTACTS)?.let { speakFinal(it); return }
            missingPermission(Manifest.permission.CALL_PHONE)?.let { speakFinal(it); return }
            val locked = LockState.locked(this)
            if (locked) {
                // enako kot v Sven Live: le priljubljeni, vsaj 3 črke, omejeno število iskanj, brez naštevanja imenika
                if (Actions.matchKey(action.query).replace(" ", "").length < 3) { speakFinal("Ob zaklenjenem telefonu povej celo ime."); return }
                if (!allowLockedFind()) {
                    notifyUnlockNeeded()
                    speakFinal("Preveč iskanj ob zaklenjenem telefonu. Odkleni ga.")
                    return
                }
            }
            val cands = runCatching { Actions.resolveContacts(this, action.query, starredOnly = locked) }.getOrDefault(emptyList())
            val match = Actions.unique(cands)?.match
            if (match == null) {
                AuditLog.append(this, "dejanje", "klic -> '${action.query}'${if (locked) " (zaklenjeno)" else ""}", "YELLOW", if (cands.isEmpty()) "stik ni najden" else "dvoumno")
                speakFinal(
                    when {
                        locked -> "Ob zaklenjenem telefonu kličem le priljubljene stike z enoličnim imenom."
                        cands.isEmpty() -> "V imeniku ne najdem stika ${action.query}."
                        else -> "Več stikov je podobnih: ${cands.joinToString(", ") { it.match.name }}. Povej celo ime."
                    }
                )
                return
            }
            // ob zaklenjenem telefonu vedno ustna potrditev, ne glede na nastavitev
            val tier = if (LockState.locked(this)) Tier.YELLOW else SafetyGate.classify(action, prefs)
            when (tier) {
                Tier.YELLOW -> {
                    pendingCall = match
                    setState(SessionState.CONFIRMING)
                    speakPrompt("Kličem ${match.name}. Naj pokličem?")
                }

                else -> {
                    val say = Actions.execute(this, action, resolved = match)
                    AuditLog.append(this, "dejanje", "klic -> ${match.name}", "GREEN", if (say.startsWith("Kličem")) "izvedeno" else "ni uspelo: $say")
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
        emitLine("SVEN", text)
        if (!speech.ttsReady) {
            pendingCall = null
            speakFinal("Govor ni na voljo, zato potrditvenih dejanj ne izvajam.")
            return
        }
        speech.speak(text, "confirm-prompt")
    }

    private fun speakFinal(text: String) {
        emitLine("SVEN", text)
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
            emitLine("SVEN", text)
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

    /** Diagnostika (DebugReceiver): Sven izgovori besedilo v tekoči klasični seji. */
    fun debugSay(text: String) {
        if (state == SessionState.IDLE || state == SessionState.LIVE) return
        speech.cancelListen()
        speakFinal(text)
    }

    /** Diagnostika (DebugReceiver, A6): umeten klic orodja v tekočo živo sejo — preveri zaporo. */
    fun debugRt(name: String, args: JSONObject): Boolean {
        val l = live ?: return false
        l.onRt(JSONObject().put("t", "rt_action").put("callId", "dbg-${SystemClock.elapsedRealtime()}").put("name", name).put("args", args))
        return true
    }

    // ---- SpeechIO.Callback ----

    override fun onTtsReady(ok: Boolean) {
        if (!ok) emitLine("⚙", "Slovenski glas TTS ni na voljo — preveri Google TTS.")
    }

    override fun onSttReady() {
        setState(if (state == SessionState.CONFIRMING) SessionState.CONFIRMING else SessionState.LISTENING)
    }

    override fun onSttPartial(text: String) {
        emitPartial(text)
    }

    override fun onSttFinal(text: String) {
        emitPartial("")
        userActive()
        handleUtterance(text)
    }

    override fun onSttError(name: String, transient: Boolean, noSpeech: Boolean) {
        when {
            noSpeech && state == SessionState.CONFIRMING -> {
                pendingCall = null
                speakFinal("Nisem slišal potrditve, zato ne naredim nič.")
            }

            noSpeech -> {
                emitLine("⚙", "nič slišanega ($name) — konec seje")
                endSession()
            }

            transient && !sttRetryUsed -> {
                sttRetryUsed = true
                emitLine("⚙", "STT $name — poskušam znova")
                handler.postDelayed({ if (state != SessionState.IDLE) speech.listen(inFollowUpListen) }, 400)
            }

            name == "PERMISSIONS" -> endSession()

            else -> {
                // trajna napaka: po obvestilu konec (sicer bi follow-up znova poslušal -> zanka)
                pendingCall = null
                endAfterSpeech = true
                speakFinal("Prepoznava govora trenutno ne deluje ($name).")
            }
        }
    }

    override fun onSttDebug(msg: String) {
        emitLine("⚙", msg)
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
            emitLine("⚙", message)
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
        val label = stateLabel()
        listeners.forEach { it.onSessionState(s, label) }
        if (s != SessionState.IDLE) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(label))
            userActive() // vsak korak pogovora podaljša prižgan zaslon (le odklenjeno)
        }
    }

    private fun stateLabel(): String {
        val base = when (state) {
            SessionState.IDLE -> if (prefs.realtime) "Pripravljen — Sven Live je VKLOPLJEN." else "Pripravljen — klasični način."
            SessionState.LISTENING -> "Poslušam …"
            SessionState.THINKING -> "Razmišljam …"
            SessionState.SPEAKING -> "Govorim …"
            SessionState.CONFIRMING -> "Čakam potrditev …"
            SessionState.FOLLOWUP -> "Poslušam nadaljevanje …"
            SessionState.LIVE -> "Sven Live 🔴 — govori kar naravno"
        }
        return if (state != SessionState.IDLE && lastLocked == true) "$base (zaklenjeno 🔒)" else base
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // dohodni klic ali druga aplikacija je prevzela zvok: Sven se umakne
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                handler.removeCallbacks(focusLost)
                handler.postDelayed(focusLost, 800)
            }
            AudioManager.AUDIOFOCUS_GAIN -> handler.removeCallbacks(focusLost)
        }
    }

    private val focusLost = Runnable {
        if (state == SessionState.IDLE) return@Runnable
        Log.i(TAG, "F3 audio focus lost -> konec")
        emitLine("⚙", "Zvok je prevzela druga aplikacija (npr. klic) — končujem sejo.")
        endSession()
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, handler)
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
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        // Obvestilo vsebuje le stanje, nikoli prepisa — zato je lahko vidno tudi na zaklenjenem zaslonu.
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sopotnik")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Končaj", stop
                ).setAuthenticationRequired(false).build()
            )
            .build()
    }

    companion object {
        private const val TAG = "Sopotnik"
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 10
        private const val MAX_SESSION_MS = 30 * 60_000L
        private const val LOCKED_CAP_MS = 4 * 60_000L
        private const val KEEP_ON_MS = 3 * 60_000L
        const val ACTION_STOP = "si.sopotnik.STOP"
        const val EXTRA_SOURCE = "src"

        /**
         * Iskanja stikov ob ZAKLENJENEM telefonu (oba načina, vse seje): največ 3 v 10 minutah,
         * ponastavi le odklep — nova seja (dolg pritisk) tujcu ne da novih poskusov. Glavna nit.
         */
        private val lockedFinds = ArrayDeque<Long>()

        fun allowLockedFind(): Boolean {
            val now = SystemClock.elapsedRealtime()
            while (lockedFinds.isNotEmpty() && now - lockedFinds.first() > 10 * 60_000L) lockedFinds.removeFirst()
            if (lockedFinds.size >= 3) return false
            lockedFinds.addLast(now)
            return true
        }

        /** Tekoča storitev (ali null) — za sejno okno, odklep in diagnostiko. */
        @Volatile
        var current: SessionService? = null
            private set

        /**
         * Zažene sejo ali — če že teče — sproži prekinitev (barge-in). Vrne false, če Android
         * zagona storitve v ozadju ni dovolil (klicatelj lahko poskusi znova, ko je okno vidno).
         */
        fun start(ctx: Context, source: String = "app"): Boolean = try {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java).putExtra(EXTRA_SOURCE, source))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "F3 fgs start refused $e")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "F3 fgs start refused $e")
            false
        }
    }
}
