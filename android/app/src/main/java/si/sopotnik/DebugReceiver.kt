package si.sopotnik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Diagnostika prek adb — izpis gre izključno v logcat, ki je berljiv samo prek
 * adb, zato izvožen sprejemnik ne razkriva ničesar:
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP si.sopotnik                    → stanje obvestil
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP --es q "Urša Zvezdica" si.sopotnik → test iskanja stikov
 *   adb logcat -s Sopotnik:* -d
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val q = intent.getStringExtra("q")
        if (q != null) {
            val res = si.sopotnik.actions.Actions.resolveContacts(ctx, q)
            Log.i(
                NotifListener.TAG,
                "DEBUG_FIND '$q' -> " +
                    if (res.isEmpty()) "NI ZADETKA"
                    else res.joinToString("; ") { "${it.match.name} (${it.score})" }
            )
            return
        }
        NotifListener.diagReport(ctx).lines().forEach { Log.i(NotifListener.TAG, "DEBUG_DUMP: $it") }
    }
}
