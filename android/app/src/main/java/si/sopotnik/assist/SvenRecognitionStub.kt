package si.sopotnik.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Obstaja samo zato, ker <voice-interaction-service> zahteva atribut recognitionService
 * (od Androida 12 ga sistem ne uporablja). Mikrofona se ne dotakne; brez intent-filtra, zato
 * ga sistem nikoli ne izbere za privzeti prepoznavalnik govora.
 */
class SvenRecognitionStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
