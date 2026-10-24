package si.sopotnik.assist

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * Vloga privzetega digitalnega pomočnika (faza 3). Dokler je Sopotnik izbran asistent, ga
 * sistem drži vezanega od zagona telefona naprej — zato tu ni NIČ težkega (brez TTS, STT,
 * WS) in NIČ poslušanja: mikrofon se odpre šele, ko uporabnik izrecno sproži sejo
 * (dolg pritisk gumba za vklop, poteza, ploščica, slušalke).
 */
class SvenVoiceService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
        // brez strukture in posnetka zaslona tuje aplikacije (zasebnost, hitrost)
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
        Log.i(TAG, "F3 vis ready")
    }

    override fun onShutdown() {
        Log.i(TAG, "F3 vis shutdown")
        if (instance === this) instance = null
        super.onShutdown()
    }

    companion object {
        private const val TAG = "Sopotnik"

        @Volatile
        var instance: SvenVoiceService? = null
            private set

        /** Odpre sejno okno (tudi čez zaklenjen zaslon). false = Sopotnik ni aktivni/pripravljen asistent. */
        fun show(src: String): Boolean {
            val s = instance ?: return false
            return try {
                s.showSession(
                    Bundle().apply { putString(SvenSession.ARG_SRC, src) },
                    VoiceInteractionSession.SHOW_SOURCE_APPLICATION
                )
                true
            } catch (e: RuntimeException) { // pred onReady vrže IllegalStateException
                Log.w(TAG, "F3 show fail src=$src $e")
                false
            }
        }

        fun isActive(ctx: Context): Boolean =
            VoiceInteractionService.isActiveService(ctx, ComponentName(ctx, SvenVoiceService::class.java))
    }
}
