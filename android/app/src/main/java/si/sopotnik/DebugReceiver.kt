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
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP --es sms_to "+386..." --es sms_text "Test" si.sopotnik → pošlje pravi SMS (izid DEBUG_SMS)
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP --ei notif 1 si.sopotnik → celotna vsebina 1. obvestila (DEBUG_NOTIF)
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP --ez screen true si.sopotnik → besedilo zaslona (DEBUG_SCREEN)
 *   adb shell am broadcast -a si.sopotnik.DEBUG_DUMP --es tap "Napis" si.sopotnik / --es scroll up → tap / pomik (DEBUG_TAP / DEBUG_SCROLL)
 *   adb logcat -s Sopotnik:* -d
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val smsTo = intent.getStringExtra("sms_to")
        val smsText = intent.getStringExtra("sms_text")
        if (smsTo != null && smsText != null) {
            SmsSender.send(ctx, smsTo, smsText) { r -> Log.i(NotifListener.TAG, "DEBUG_SMS -> $r") }
            return
        }
        if (intent.getBooleanExtra("screen", false)) {
            Log.i(NotifListener.TAG, "DEBUG_SCREEN enabled=${ScreenReader.enabled(ctx)} bound=${ScreenReader.instance != null}\n" + (ScreenReader.dump() ?: "(ni zaslona)"))
            return
        }
        intent.getStringExtra("tap")?.let { Log.i(NotifListener.TAG, "DEBUG_TAP '$it' -> ${ScreenReader.tap(it)}"); return }
        intent.getStringExtra("scroll")?.let { Log.i(NotifListener.TAG, "DEBUG_SCROLL $it -> ${ScreenReader.scroll(it)}"); return }
        intent.getStringExtra("type")?.let { Log.i(NotifListener.TAG, "DEBUG_TYPE -> ${ScreenReader.typeText(it, false)}"); return }
        intent.getStringExtra("tapc")?.let { Log.i(NotifListener.TAG, "DEBUG_TAPC '$it' -> ${ScreenReader.tap(it, confirmed = true)}"); return }
        val notifNo = intent.getIntExtra("notif", 0)
        if (notifNo > 0) {
            val list = NotifListener.snapshot()
            val d = list?.getOrNull(notifNo - 1)?.let { NotifListener.detail(it.key) }
            Log.i(NotifListener.TAG, "DEBUG_NOTIF $notifNo -> " + (d ?: "NI VNOSA (seznam: ${list?.size ?: "null"})"))
            return
        }
        val q = intent.getStringExtra("q")
        if (q != null) {
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(NotifListener.TAG, "DEBUG_FIND '$q' -> NI DOVOLJENJA READ_CONTACTS")
                return
            }
            val res = runCatching { si.sopotnik.actions.Actions.resolveContacts(ctx, q) }.getOrDefault(emptyList())
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
