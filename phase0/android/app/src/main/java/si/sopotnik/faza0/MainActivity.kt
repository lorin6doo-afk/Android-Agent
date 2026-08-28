package si.sopotnik.faza0

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val sl: Locale = Locale.forLanguageTag("sl-SI")
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private lateinit var diagText: TextView
    private lateinit var sttStatus: TextView
    private lateinit var sttPartial: TextView
    private lateinit var sttResult: TextView
    private lateinit var btnStt: Button
    private lateinit var chkEcho: CheckBox
    private lateinit var chkOffline: CheckBox
    private lateinit var editTts: EditText
    private lateinit var ttsStatus: TextView
    private lateinit var btnInstallVoice: Button
    private lateinit var fgsReport: TextView

    private val report = StringBuilder()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val supportProbes = mutableListOf<SpeechRecognizer>()

    private var tStartListen = 0L
    private var tEndOfSpeech = 0L
    private var tSpeakRequest = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        diagText = findViewById(R.id.diag_text)
        sttStatus = findViewById(R.id.stt_status)
        sttPartial = findViewById(R.id.stt_partial)
        sttResult = findViewById(R.id.stt_result)
        btnStt = findViewById(R.id.btn_stt)
        chkEcho = findViewById(R.id.chk_echo)
        chkOffline = findViewById(R.id.chk_offline)
        editTts = findViewById(R.id.edit_tts)
        ttsStatus = findViewById(R.id.tts_status)
        btnInstallVoice = findViewById(R.id.btn_install_voice)
        fgsReport = findViewById(R.id.fgs_report)

        btnStt.setOnClickListener { toggleListening() }
        findViewById<Button>(R.id.btn_tts).setOnClickListener { speak(editTts.text.toString()) }
        btnInstallVoice.setOnClickListener {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: Exception) {
                toast("Namestitev glasu ni na voljo: ${e.message}")
            }
        }
        findViewById<Button>(R.id.btn_fgs_start).setOnClickListener { startTickService() }
        findViewById<Button>(R.id.btn_fgs_stop).setOnClickListener {
            stopService(Intent(this, TickService::class.java))
            log("FGS", "zahtevana ustavitev storitve")
            refreshTickReport()
        }
        findViewById<Button>(R.id.btn_fgs_refresh).setOnClickListener { refreshTickReport() }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.btn_share).setOnClickListener { shareReport() }

        requestMissingPermissions()
        runDiagnostics()
        initTts()
        refreshTickReport()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        supportProbes.forEach { runCatching { it.destroy() } }
        tts?.shutdown()
    }

    // ---------- pomožno ----------

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun log(tag: String, msg: String) {
        val line = "[${timeFmt.format(Date())}] $tag: $msg"
        report.append(line).append('\n')
        runOnUiThread {
            diagText.append(line + "\n")
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun requestMissingPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            log("DOVOLJENJE", "$p -> ${if (ok) "odobreno" else "ZAVRNJENO"}")
        }
    }

    private fun readProp(name: String): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", name))
            .inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")

    // ---------- diagnostika ----------

    private fun runDiagnostics() {
        log("NAPRAVA", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        log("NAPRAVA", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        val hyper = readProp("ro.mi.os.version.name").ifEmpty { readProp("ro.miui.ui.version.name") }
        if (hyper.isNotEmpty()) log("NAPRAVA", "HyperOS/MIUI: $hyper")

        log("STT", "isRecognitionAvailable = ${SpeechRecognizer.isRecognitionAvailable(this)}")
        val defaultService = runCatching {
            Settings.Secure.getString(contentResolver, "voice_recognition_service")
        }.getOrNull()
        log("STT", "privzeta storitev prepoznave: ${defaultService ?: "neznana"}")

        if (Build.VERSION.SDK_INT >= 33) {
            val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            log("STT", "isOnDeviceRecognitionAvailable = $onDevice")
            probeSupport("omrežni", SpeechRecognizer.createSpeechRecognizer(this))
            if (onDevice) {
                probeSupport("on-device", SpeechRecognizer.createOnDeviceSpeechRecognizer(this))
            }
        }
    }

    private fun probeSupport(label: String, probe: SpeechRecognizer) {
        if (Build.VERSION.SDK_INT < 33) return
        supportProbes.add(probe)
        try {
            probe.checkRecognitionSupport(makeSttIntent(false), mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        log("STT[$label]", "nameščeni on-device jeziki: ${recognitionSupport.installedOnDeviceLanguages}")
                        log("STT[$label]", "podprti on-device jeziki: ${recognitionSupport.supportedOnDeviceLanguages}")
                        log("STT[$label]", "online jeziki: ${recognitionSupport.onlineLanguages}")
                        val slOnDevice = recognitionSupport.installedOnDeviceLanguages.any { it.startsWith("sl") }
                        val slOnline = recognitionSupport.onlineLanguages.any { it.startsWith("sl") }
                        log("STT[$label]", "slovenščina: on-device=${if (slOnDevice) "DA" else "NE"}, online=${if (slOnline) "DA" else "NE"}")
                    }

                    override fun onError(error: Int) {
                        log("STT[$label]", "checkRecognitionSupport napaka: $error")
                    }
                })
        } catch (e: Exception) {
            log("STT[$label]", "checkRecognitionSupport izjema: ${e.message}")
        }
    }

    // ---------- STT ----------

    private fun makeSttIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sl-SI")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun toggleListening() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Najprej dovoli dostop do mikrofona.")
            requestMissingPermissions()
            return
        }

        recognizer?.destroy()
        val offline = chkOffline.isChecked
        recognizer = if (offline && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            log("STT", "uporabljam on-device prepoznavalnik")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(recognitionListener)

        sttPartial.text = ""
        sttResult.text = ""
        tEndOfSpeech = 0L
        tStartListen = now()
        recognizer?.startListening(makeSttIntent(offline))
        listening = true
        btnStt.text = "■  Ustavi"
        sttStatus.text = "Poslušam …"
        log("STT", "startListening (offline=${offline})")
    }

    private fun listeningDone() {
        listening = false
        btnStt.text = "🎤  Začni poslušanje"
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            log("STT", "pripravljen po ${now() - tStartListen} ms")
            sttStatus.text = "Poslušam — govori!"
        }

        override fun onBeginningOfSpeech() {
            sttStatus.text = "Slišim govor …"
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            tEndOfSpeech = now()
            sttStatus.text = "Obdelujem …"
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val txt = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            sttPartial.text = "… $txt"
        }

        override fun onResults(results: Bundle?) {
            val tFinal = now()
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val best = alternatives.firstOrNull() ?: ""
            val reference = if (tEndOfSpeech > 0) tEndOfSpeech else tStartListen
            val latency = tFinal - reference

            sttPartial.text = ""
            sttResult.text = "»$best«\n(zakasnitev ${latency} ms)"
            sttStatus.text = "Končano."
            log("STT", "rezultat po ${latency} ms od konca govora: \"$best\"")
            alternatives.drop(1).forEachIndexed { i, alt ->
                val score = scores?.getOrNull(i + 1)?.let { " (%.2f)".format(it) } ?: ""
                log("STT", "  alternativa ${i + 2}: \"$alt\"$score")
            }
            listeningDone()

            if (chkEcho.isChecked && best.isNotBlank()) speak(best)
        }

        override fun onError(error: Int) {
            val name = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                SpeechRecognizer.ERROR_SERVER -> "SERVER"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT (nič slišanega)"
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH (ni prepoznano)"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE (offline paket ni nameščen?)"
                else -> "koda $error"
            }
            sttStatus.text = "Napaka: $name"
            log("STT", "NAPAKA: $name")
            listeningDone()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- TTS ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                log("TTS", "inicializacija NEUSPEŠNA (status $status)")
                runOnUiThread { ttsStatus.text = "TTS ni na voljo." }
                return@TextToSpeech
            }
            val engine = tts?.defaultEngine ?: "?"
            log("TTS", "motor: $engine")

            val avail = tts?.isLanguageAvailable(sl) ?: TextToSpeech.LANG_NOT_SUPPORTED
            val availName = when (avail) {
                TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE"
                TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE"
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE"
                TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA (glas ni nameščen)"
                TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"
                else -> "koda $avail"
            }
            log("TTS", "sl-SI: $availName")

            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = sl
                ttsReady = true
                runOnUiThread { ttsStatus.text = "TTS pripravljen (sl-SI, motor: $engine)." }
            } else {
                runOnUiThread {
                    ttsStatus.text = "Slovenski glas manjka ($availName)."
                    btnInstallVoice.visibility = android.view.View.VISIBLE
                }
            }

            runCatching {
                val slVoices = tts?.voices?.filter { it.locale.language == "sl" }.orEmpty()
                if (slVoices.isEmpty()) {
                    log("TTS", "ni najdenih sl glasov")
                } else {
                    slVoices.forEach {
                        log("TTS", "glas: ${it.name}, kakovost=${it.quality}, omrežni=${it.isNetworkConnectionRequired}")
                    }
                }
            }.onFailure { log("TTS", "branje glasov ni uspelo: ${it.message}") }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    log("TTS", "govor se je začel po ${now() - tSpeakRequest} ms")
                }

                override fun onDone(utteranceId: String?) {
                    log("TTS", "govor končan")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    log("TTS", "NAPAKA med govorom")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    log("TTS", "NAPAKA med govorom (koda $errorCode)")
                }
            })
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            toast("TTS ni pripravljen — preveri razdelek 2.")
            return
        }
        tSpeakRequest = now()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sopotnik-utt")
    }

    // ---------- test ozadja ----------

    private fun startTickService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Storitev tipa mikrofon potrebuje dovoljenje za mikrofon.")
            requestMissingPermissions()
            return
        }
        startForegroundService(Intent(this, TickService::class.java))
        log("FGS", "storitev zagnana — zakleni telefon za 5–10 min")
        toast("Storitev teče. Zakleni telefon za 5–10 minut.")
    }

    private fun refreshTickReport() {
        val f = File(filesDir, TickService.LOG_NAME)
        if (!f.exists()) {
            fgsReport.text = "Ni še podatkov."
            return
        }
        data class Entry(val type: String, val ts: Long)

        val entries = f.readLines().mapNotNull { line ->
            val parts = line.split('|')
            val ts = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size >= 2 && ts != null) Entry(parts[0], ts) else null
        }
        val starts = entries.count { it.type == "start" }
        val stops = entries.count { it.type == "stop" }
        val ticks = entries.filter { it.type == "tick" }

        val sb = StringBuilder()
        sb.append("zagonov storitve: $starts, ustavitev: $stops, tickov: ${ticks.size}\n")
        if (ticks.isNotEmpty()) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
            sb.append("prvi: ${fmt.format(Date(ticks.first().ts))}, zadnji: ${fmt.format(Date(ticks.last().ts))}\n")
            var maxGap = 0L
            var maxGapAt = 0L
            ticks.zipWithNext().forEach { (a, b) ->
                val gap = b.ts - a.ts
                if (gap > maxGap) {
                    maxGap = gap
                    maxGapAt = a.ts
                }
            }
            sb.append("največja vrzel: ${maxGap / 1000} s")
            if (maxGap > 0) sb.append(" (ob ${fmt.format(Date(maxGapAt))})")
            sb.append('\n')

            val restartsAfterFirst = starts - 1
            when {
                restartsAfterFirst > 0 ->
                    sb.append("⚠️ HyperOS je storitev ubil in znova zagnal ${restartsAfterFirst}×\n")
                maxGap > 40_000 ->
                    sb.append("⚠️ vrzel > 40 s — sistem je storitev zamrznil ali upočasnil\n")
                else ->
                    sb.append("✅ brez vrzeli — storitev je preživela\n")
            }
        }
        fgsReport.text = sb.toString()
    }

    // ---------- poročilo ----------

    private fun buildReport(): String {
        refreshTickReport()
        return buildString {
            append("SOPOTNIK FAZA 0 — POROČILO\n")
            append("čas: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}\n")
            append("\n--- test ozadja ---\n")
            append(fgsReport.text)
            append("\n--- dnevnik ---\n")
            append(report)
        }
    }

    private fun copyReport() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Sopotnik poročilo", buildReport()))
        toast("Poročilo je v odložišču — prilepi ga v pogovor s Claudom.")
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildReport())
        }
        startActivity(Intent.createChooser(send, "Deli poročilo"))
    }
}
