package si.sopotnik.assist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import si.sopotnik.LockState
import si.sopotnik.SessionService

/**
 * Gumb za glasovni ukaz na Bluetooth slušalkah ali volanu (HFP → ACTION_VOICE_COMMAND) in
 * žične slušalke ob zaklepu (VOICE_SEARCH_HANDS_FREE). Izvožena aktivnost, zato extras NE
 * zaupamo — izvor določa le action. Brez UI, takoj finish().
 */
class VoiceCommandActivity : Activity() {

    private var handled = false

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 1_500) { // omeji pogostost (druga aplikacija bi nas lahko zasipala)
            finish()
            return
        }
        lastAt = now
        val src = when (intent?.action) {
            Intent.ACTION_VOICE_COMMAND -> "bt"
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE -> "headset"
            else -> "voice_cmd"
        }
        Log.i("Sopotnik", "F3 voicecmd src=$src")
        // Prednostno enotno sejno okno (vidno, z gumbom Končaj). Brez vloge asistenta: seja le,
        // če telefon NI zaklenjen — sicer bi tuja aplikacija lahko odprla mikrofon brez okna.
        if (!SvenVoiceService.show(src) &&
            !LockState.locked(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            SessionService.start(this, src)
        }
        finish()
    }

    private companion object {
        var lastAt = 0L
    }
}
