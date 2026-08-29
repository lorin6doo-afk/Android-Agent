package si.sopotnik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Diagnostika stanja poslušalca obvestil prek adb — izpis gre izključno v logcat,
 * ki je berljiv samo prek adb, zato izvožen sprejemnik ne razkriva ničesar:
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP si.sopotnik
 *   adb logcat -s Sopotnik:* -d
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        NotifListener.diagReport(ctx).lines().forEach { Log.i(NotifListener.TAG, "DEBUG_DUMP: $it") }
    }
}
