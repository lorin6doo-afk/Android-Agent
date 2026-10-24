package si.sopotnik.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Tovarna sejnih oken; veže jo izključno system_server (neizvožena, BIND_VOICE_INTERACTION). */
class SvenSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SvenSession(this)
}
