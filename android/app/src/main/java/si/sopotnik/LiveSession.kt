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
    private var lastFoundAt = 0L
    private var lastNotifs: List<NotifEntry> = emptyList()
    private var lastNotifsAt = 0L
    /** SMS osnutek iz compose_message, ki čaka na uporabnikov »pošlji« (send_message). */
    private data class Draft(val match: ContactMatch, val text: String, val at: Long)
    private var pendingDraft: Draft? = null
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

    /**
     * Ime, ki ga trdi model, se mora ujemati z dejanskim imenom (naslov obvestila
     * oz. stik). Primerjava prek matchKey (brez šumnikov in simbolov — »Urša*« ==
     * »Urša«), ujemanje pa zahteva, da so VSE besede ene strani prisotne kot cele
     * besede druge — ena skupna beseda (skupen priimek) ali podniz sredi besede
     * (»Ana« ⊂ »Milana«) NI dovolj.
     */
    private fun namesMatch(claimed: String, actual: String): Boolean {
        val c = Actions.matchKey(claimed)
        val a = Actions.matchKey(actual)
        if (c.isEmpty() || a.isEmpty()) {
            val cn = IntentRouter.normalize(claimed).trim()
            val an = IntentRouter.normalize(actual).trim()
            return cn.isNotEmpty() && cn == an
        }
        if (c == a) return true
        val cTok = c.split(' ')
        val aTok = a.split(' ')
        val aSet = aTok.toSet()
        val cSet = cTok.toSet()
        return cTok.all { it in aSet } || aTok.all { it in cSet }
    }

    private fun recipientMatches(claimed: String, entry: NotifEntry): Boolean =
        namesMatch(claimed, entry.title)

    /**
     * Odpre pogovor s stikom iz imenika — prek SMS aplikacije (smsto:, z ali brez
     * pripravljenega besedila) ali WhatsAppa (wa.me). Pošteno javi tudi Androidovo
     * omejitev: odpiranje zaslona iz ozadja je blokirano, če Sopotnik nima
     * dovoljenja »Prikaz nad drugimi aplikacijami« in je spredaj druga aplikacija.
     */
    private fun openOrCompose(name: String, via: String, text: String?): String {
        if (via != "sms" && via != "whatsapp")
            return "Povej kanal: 'sms' ali 'whatsapp'. (»Aplikacija sporočila« pomeni sms.)"
        val (picked, err) = pickContact(name)
        val m = picked ?: return err!!
        val intent: android.content.Intent
        val kje: String
        if (via == "sms") {
            intent = android.content.Intent(
                android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("smsto:" + android.net.Uri.encode(m.number))
            )
            if (!text.isNullOrBlank()) intent.putExtra("sms_body", text)
            kje = "SMS aplikaciji"
        } else {
            val digits = m.number.filter { it.isDigit() }
            val intl = when {
                m.number.trimStart().startsWith("+") -> digits
                digits.startsWith("00") -> digits.drop(2)
                digits.startsWith("0") -> "386" + digits.drop(1)
                else -> digits
            }
            if (intl.length < 8)
                return "Številka stika ${m.name} (${m.number}) ni videti veljavna mobilna številka."
            val url = "https://wa.me/$intl" + if (!text.isNullOrBlank()) "?text=" + android.net.Uri.encode(text) else ""
            intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .setPackage("com.whatsapp")
            kje = "WhatsAppu"
        }

        val opened = runCatching {
            service.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) return "Aplikacije ni mogoče odpreti (${if (via == "sms") "SMS" else "WhatsApp"})."

        val overlayWarn = if (android.provider.Settings.canDrawOverlays(service)) ""
        else " POZOR: če uporabnik zaslona NE vidi, ga je Android blokiral, ker je spredaj druga aplikacija — povej mu, naj v nastavitvah Sopotnika vklopi »Prikaz nad drugimi aplikacijami«, in poskusi znova."

        val dejanje = if (text.isNullOrBlank()) "odprt pogovor" else "osnutek (pošlje uporabnik sam)"
        AuditLog.append(service, "dejanje", "$dejanje v $kje -> ${m.name}${if (text.isNullOrBlank()) "" else ": $text"}", "GREEN", "ok")
        return if (text.isNullOrBlank())
            "V $kje je odprt pogovor z ${m.name}.$overlayWarn"
        else
            "V $kje je odprt pogovor z ${m.name} s pripravljenim besedilom — sporočilo NI poslano, uporabnik pritisne Pošlji sam; to mu povej.$overlayWarn"
    }

    /** Enolični stik iz imenika za sporočilo — ali razlog (besedilo za model), zakaj ga ni. */
    private fun pickContact(name: String): Pair<ContactMatch?, String?> {
        val cands = try {
            Actions.resolveContacts(service, name)
        } catch (e: SecurityException) {
            return null to "Dovoljenje za branje stikov ni podeljeno — povej uporabniku, naj ga dodeli v nastavitvah Sopotnika."
        }
        val top = cands.firstOrNull()
            ?: return null to "Stika '$name' ni v imeniku. Poskusi znova SAMO z osnovnim imenom."
        if (top.score < 80 || (cands.size > 1 && top.score < cands[1].score + 15 && top.score < 100))
            return null to "Nisem prepričan, koga misliš: ${cands.joinToString("; ") { it.match.name }}. Ustno preveri in znova pokliči orodje z natančnim imenom."
        return top.match to null
    }

    /**
     * compose_message via 'sms': osnutek ostane na telefonu — nič se ne odpre in nič
     * ne pošlje. Pošlje ga šele send_message po uporabnikovem ustnem »pošlji«, in to
     * natanko to besedilo (model ga vmes ne more zamenjati).
     */
    private fun prepareSms(name: String, text: String): String {
        val (picked, err) = pickContact(name)
        val m = picked ?: return err!!
        pendingDraft = Draft(m, text, System.currentTimeMillis())
        AuditLog.append(service, "dejanje", "SMS osnutek -> ${m.name}: $text", "GREEN", "pripravljen, čaka na 'pošlji'")
        return "SMS osnutek za »${m.name}« je pripravljen: »$text«. Sporočilo še NI poslano — uporabniku naglas preberi prejemnika in celotno besedilo ter vprašaj, ali naj pošlješ. Šele ko reče 'pošlji', pokliči send_message z recipient »${m.name}«; če želi popravek, znova pokliči compose_message."
    }

    /** send_message: pošlje pripravljeni SMS osnutek; izid orodja pove, kaj se je zares zgodilo. */
    private fun sendMessageAsync(callId: String, claimed: String) {
        val draft = pendingDraft
        if (draft == null || System.currentTimeMillis() - draft.at > 5 * 60_000) {
            result(callId, "Ni pripravljenega SMS osnutka (ali je potekel) — najprej pokliči compose_message z via 'sms', preberi prejemnika in besedilo ter počakaj na 'pošlji'.")
            return
        }
        val early: String? = when {
            claimed.isBlank() -> "Manjka prejemnik (recipient) — navedi ime NATANKO tako, kot ga je vrnil compose_message."
            !namesMatch(claimed, draft.match.name) -> {
                AuditLog.append(service, "dejanje", "SMS -> ${draft.match.name} (zahtevan: $claimed)", "RED", "USTAVLJENO: neujemanje prejemnika")
                "USTAVLJENO: pripravljeni osnutek je za »${draft.match.name}«, NE za »$claimed«. Nič NI poslano — znova pokliči compose_message s pravim stikom."
            }
            !SmsSender.hasPermission(service) ->
                "Dovoljenje za pošiljanje SMS ni podeljeno — SMS NI poslan. Povej uporabniku, naj v nastavitvah Sopotnika pritisne gumb za dovoljenja in dovoli SMS, nato naj znova reče 'pošlji'."
            else -> null
        }
        if (early != null) {
            Log.i("Sopotnik", "orodje send_message($claimed) -> $early")
            result(callId, early)
            return
        }
        SmsSender.send(service, draft.match.number, draft.text) { r ->
            handler.post {
                val out = when (r) {
                    is SmsSender.Result.Sent -> {
                        pendingDraft = null
                        AuditLog.append(service, "dejanje", "SMS -> ${draft.match.name}: ${draft.text}", "YELLOW", "ustno potrjeno, poslano (live)")
                        "SMS poslan prejemniku »${draft.match.name}«."
                    }
                    is SmsSender.Result.Failed -> {
                        AuditLog.append(service, "dejanje", "SMS -> ${draft.match.name}: ${draft.text}", "YELLOW", "pošiljanje ni uspelo: ${r.reason}")
                        "SMS NI poslan — ${r.reason}. Povej uporabniku točno to; osnutek ostaja, na ponovni 'pošlji' poskusim znova."
                    }
                    is SmsSender.Result.Timeout -> {
                        pendingDraft = null
                        AuditLog.append(service, "dejanje", "SMS -> ${draft.match.name}: ${draft.text}", "YELLOW", "brez potrditve omrežja v 20 s")
                        "Omrežje v 20 sekundah NI potrdilo pošiljanja — SMS za »${draft.match.name}« je morda šel, morda ne. Povej uporabniku, naj preveri v Sporočilih; osnutek sem zavrgel, da ne pride do dvojnega pošiljanja."
                    }
                }
                Log.i("Sopotnik", "orodje send_message($claimed) -> $out")
                result(callId, out)
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
        if (name == "send_message") {
            touchActivity()
            sendMessageAsync(callId, args.optString("recipient"))
            return
        }
        val out: String = when (name) {
            "get_time" -> {
                val c = Calendar.getInstance()
                "Ura je ${c.get(Calendar.HOUR_OF_DAY)} in ${c.get(Calendar.MINUTE)} minut."
            }

            "find_contact" -> {
                val q = args.optString("query")
                val cands = try {
                    Actions.resolveContacts(service, q)
                } catch (e: SecurityException) {
                    null
                }
                when {
                    cands == null -> {
                        lastFound = null
                        "Dovoljenje za branje stikov ni podeljeno — povej uporabniku, naj ga dodeli v nastavitvah Sopotnika."
                    }
                    cands.isEmpty() -> {
                        lastFound = null
                        "Stika '$q' ni v imeniku. Poskusi znova SAMO z osnovnim imenom (brez priimka, opisov ali izgovorjenih simbolov)."
                    }
                    cands[0].score == 100 ||
                        (cands.size == 1 && cands[0].score >= 80) ||
                        (cands.size > 1 && cands[0].score >= 80 && cands[0].score >= cands[1].score + 15) -> {
                        lastFound = cands[0].match
                        lastFoundAt = System.currentTimeMillis()
                        "Najden stik: ${cands[0].match.name}. Ustno vprašaj uporabnika za potrditev; za klic uporabi call_contact, za novo sporočilo compose_message."
                    }
                    cands.size == 1 -> {
                        lastFound = cands[0].match
                        lastFoundAt = System.currentTimeMillis()
                        "Najbolj podoben stik: ${cands[0].match.name} (ujemanje ni popolno). OBVEZNO ustno preveri, ali je to prava oseba, preden narediš karkoli."
                    }
                    else -> {
                        lastFound = null
                        "Več podobnih stikov: ${cands.joinToString("; ") { it.match.name }}. Ustno vprašaj uporabnika, katerega misli, in znova pokliči find_contact z izbranim natančnim imenom."
                    }
                }
            }

            "call_contact" -> {
                val c = lastFound
                val claimed = args.optString("name")
                val fresh = System.currentTimeMillis() - lastFoundAt < 2 * 60_000
                when {
                    c == null || !fresh -> "Najprej uporabi find_contact (izbire ni ali je potekla)."
                    claimed.isBlank() -> "Navedi ime stika (name), da ga lahko preverim."
                    !namesMatch(claimed, c.name) -> {
                        AuditLog.append(service, "dejanje", "klic -> ${c.name} (zahtevan: $claimed)", "RED", "USTAVLJENO: neujemanje imena")
                        "USTAVLJENO: izbran stik je »${c.name}«, ne »$claimed«. Klica NISEM izvedel — znova pokliči find_contact."
                    }
                    else -> {
                        lastFound = null
                        val say = Actions.execute(service, Action.Call(c.name), resolved = c)
                        AuditLog.append(service, "dejanje", "klic -> ${c.name} (live)", "YELLOW", "ustno potrjeno, izvedeno")
                        say
                    }
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

            "read_screen" -> when {
                !ScreenReader.enabled(service) ->
                    "Branje zaslona ni vklopljeno — povej uporabniku: Nastavitve Sopotnika → gumb »Branje zaslona (dostopnost)« → vklopi Sopotnik (na HyperOS prej Podatki o aplikaciji → ⋮ → Dovoli omejene nastavitve)."
                ScreenReader.instance == null ->
                    "Storitev za branje zaslona je vklopljena, a ni povezana — povej uporabniku, naj jo v nastavitvah dostopnosti izklopi in znova vklopi."
                else -> {
                    val d = ScreenReader.dump()
                    AuditLog.append(service, "zaslon", "read_screen (live)", "GREEN", if (d == null) "ni zaslona" else "prebranih ${d.length} znakov")
                    d ?: "Zaslona ni mogoče prebrati — telefon je zaklenjen ali pa odprta aplikacija vsebine ne razkriva."
                }
            }

            "tap_item" -> {
                val label = args.optString("label")
                when {
                    label.isBlank() -> "Manjka napis elementa (label)."
                    !ScreenReader.enabled(service) -> "Branje zaslona ni vklopljeno (glej read_screen)."
                    else -> {
                        val confirmed = args.optBoolean("potrjeno", false)
                        val r = ScreenReader.tap(label, confirmed)
                        val tier = when {
                            r.startsWith("USTAVLJENO") -> "RED"
                            r.startsWith("ČAKAM") || confirmed -> "YELLOW"
                            else -> "GREEN"
                        }
                        AuditLog.append(service, "zaslon", "tap_item »$label«${if (confirmed) " (potrjeno)" else ""} (live)", tier, r.take(80))
                        r
                    }
                }
            }

            "type_text" -> {
                val text = args.optString("besedilo").ifBlank { args.optString("text") }
                val append = args.optString("nacin") == "dodaj"
                when {
                    text.isBlank() -> "Manjka besedilo (besedilo)."
                    !ScreenReader.enabled(service) -> "Branje zaslona ni vklopljeno (glej read_screen)."
                    else -> {
                        val r = ScreenReader.typeText(text, append)
                        AuditLog.append(service, "zaslon", "type_text (${text.length} zn.) (live)", "GREEN", r.take(80))
                        r
                    }
                }
            }

            "scroll_screen" ->
                if (!ScreenReader.enabled(service)) "Branje zaslona ni vklopljeno (glej read_screen)."
                else ScreenReader.scroll(args.optString("direction").ifBlank { "up" })

            "go_back" -> if (ScreenReader.back()) "Nazaj." else "Nazaj ni mogoče (branje zaslona ni povezano)."

            "read_notification" -> {
                val idx = args.optInt("number") - 1
                val claimed = args.optString("recipient")
                val fresh = System.currentTimeMillis() - lastNotifsAt < 5 * 60_000
                val entry = lastNotifs.getOrNull(idx)
                when {
                    entry == null || !fresh -> "Najprej znova preberi seznam z read_notifications."
                    claimed.isNotBlank() && !namesMatch(claimed, entry.title) ->
                        "POZOR: obvestilo številka ${idx + 1} pripada »${entry.title}« (${entry.app}), NE »$claimed«. Preveri seznam in izberi pravo številko."
                    else -> {
                        AuditLog.append(service, "obvestila", "prebrana vsebina -> ${entry.app}/${entry.title} (live)", "GREEN", "ok")
                        NotifListener.detail(entry.key)
                            ?: "Tega obvestila ni več — morda je medtem izginilo. Znova preberi seznam."
                    }
                }
            }

            "open_notification" -> {
                val idx = args.optInt("number") - 1
                val claimed = args.optString("recipient")
                val fresh = System.currentTimeMillis() - lastNotifsAt < 5 * 60_000
                val entry = lastNotifs.getOrNull(idx)
                when {
                    entry == null || !fresh -> "Najprej znova preberi obvestila z read_notifications."
                    claimed.isBlank() -> "Manjka prejemnik (recipient) — navedi ime NATANKO tako, kot je v zadnjem seznamu obvestil. Nič ni odprto."
                    !namesMatch(claimed, entry.title) ->
                        "USTAVLJENO: obvestilo številka ${idx + 1} pripada »${entry.title}« (${entry.app}), NE »$claimed«. Nič ni odprto — preveri seznam in izberi pravo številko."
                    NotifListener.open(entry.key) -> "Odprto na zaslonu telefona: ${entry.app} — ${entry.title}."
                    else -> "Tega obvestila ni mogoče odpreti — morda je medtem izginilo."
                }
            }

            "open_conversation" -> {
                val name = args.optString("contact")
                val via = args.optString("via")
                openOrCompose(name, via, text = null)
            }

            "send_reply" -> {
                val idx = args.optInt("number") - 1
                val text = args.optString("text")
                val claimed = args.optString("recipient")
                val fresh = System.currentTimeMillis() - lastNotifsAt < 5 * 60_000
                val entry = lastNotifs.getOrNull(idx)
                when {
                    text.isBlank() -> "Manjka besedilo odgovora."
                    entry == null || !fresh -> "Najprej znova preberi obvestila z read_notifications."
                    claimed.isBlank() -> "Manjka prejemnik (recipient) — navedi ime NATANKO tako, kot je v zadnjem seznamu obvestil."
                    !recipientMatches(claimed, entry) -> {
                        AuditLog.append(service, "dejanje", "odgovor -> ${entry.app}/${entry.title} (zahtevan: $claimed)", "RED", "USTAVLJENO: neujemanje prejemnika")
                        "USTAVLJENO: obvestilo številka ${idx + 1} pripada »${entry.title}« (${entry.app}), NE »$claimed«. Odgovor NI bil poslan. Znova preberi obvestila; če osebe ni v seznamu, ji s send_reply ni mogoče pisati — uporabi compose_message."
                    }
                    !entry.canReply -> "Na to obvestilo ni mogoče odgovoriti."
                    NotifListener.reply(entry.key, text) -> {
                        AuditLog.append(service, "dejanje", "odgovor -> ${entry.app}/${entry.title}: $text", "YELLOW", "ustno potrjeno, poslano (live)")
                        "Odgovor poslan: »${entry.title}« (${entry.app})."
                    }
                    else -> {
                        AuditLog.append(service, "dejanje", "odgovor -> ${entry.app}/${entry.title}", "YELLOW", "pošiljanje ni uspelo")
                        "Pošiljanje ni uspelo — obvestilo je morda izginilo."
                    }
                }
            }

            "compose_message" -> {
                val name = args.optString("contact")
                val text = args.optString("text")
                val via = args.optString("via")
                when {
                    text.isBlank() -> "Manjka besedilo sporočila."
                    via == "sms" -> prepareSms(name, text)
                    else -> openOrCompose(name, via, text)
                }
            }

            "end_conversation" -> {
                handler.postDelayed({ end(notify = true) }, 5_000)
                "Seja se bo končala. Poslovi se."
            }

            else -> "Orodja '$name' ne poznam."
        }

        Log.i("Sopotnik", "orodje $name($args) -> $out")
        val selfAudited = setOf("find_contact", "call_contact", "get_time", "end_conversation", "send_reply", "compose_message", "open_conversation", "send_message", "read_notification", "read_screen", "tap_item", "type_text")
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
