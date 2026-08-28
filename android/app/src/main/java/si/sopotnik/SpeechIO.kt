package si.sopotnik

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * STT + TTS z lekcijami iz faze 0: trda časovna omejitev seje poslušanja,
 * zavračanje zapoznelih rezultatov (generacija seje) in označene prehodne napake za retry.
 */
class SpeechIO(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onTtsReady(ok: Boolean)
        fun onSttReady()
        fun onSttPartial(text: String)
        fun onSttFinal(text: String)
        fun onSttError(name: String, transient: Boolean, noSpeech: Boolean)
        fun onUtteranceDone(id: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sl: Locale = Locale.forLanguageTag("sl-SI")

    private var recognizer: SpeechRecognizer? = null
    private var gen = 0
    private val capRunnable = Runnable { recognizer?.stopListening() }

    private var tts: TextToSpeech? = null
    var ttsReady = false
        private set

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS &&
                (tts?.isLanguageAvailable(sl) ?: -2) >= TextToSpeech.LANG_AVAILABLE
            if (ttsReady) tts?.language = sl
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id -> handler.post { cb.onUtteranceDone(id) } }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id -> handler.post { cb.onUtteranceDone(id) } }
                }
            })
            handler.post { cb.onTtsReady(ttsReady) }
        }
    }

    // ---- STT ----

    fun listen(followUp: Boolean) {
        cancelListen()
        gen++
        val myGen = gen
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (myGen == gen) cb.onSttReady()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (myGen != gen) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { cb.onSttPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                if (myGen != gen) return
                handler.removeCallbacks(capRunnable)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) cb.onSttError("NO_MATCH", transient = false, noSpeech = true)
                else cb.onSttFinal(text)
            }

            override fun onError(error: Int) {
                if (myGen != gen) return
                handler.removeCallbacks(capRunnable)
                val (name, transient, noSpeech) = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Triple("NETWORK_TIMEOUT", true, false)
                    SpeechRecognizer.ERROR_NETWORK -> Triple("NETWORK", true, false)
                    SpeechRecognizer.ERROR_SERVER -> Triple("SERVER", true, false)
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Triple("SERVER_DISCONNECTED", true, false)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Triple("BUSY", true, false)
                    SpeechRecognizer.ERROR_CLIENT -> Triple("CLIENT", true, false)
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Triple("SPEECH_TIMEOUT", false, true)
                    SpeechRecognizer.ERROR_NO_MATCH -> Triple("NO_MATCH", false, true)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Triple("PERMISSIONS", false, false)
                    else -> Triple("KODA_$error", false, false)
                }
                cb.onSttError(name, transient, noSpeech)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sl-SI")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300)
        }
        r.startListening(intent)
        handler.postDelayed(capRunnable, if (followUp) 7_000L else 12_000L)
    }

    fun cancelListen() {
        handler.removeCallbacks(capRunnable)
        gen++
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    // ---- TTS ----

    fun speak(text: String, id: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        cancelListen()
        tts?.shutdown()
        tts = null
    }
}
