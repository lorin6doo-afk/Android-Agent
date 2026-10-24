package si.sopotnik

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import si.sopotnik.actions.Action
import si.sopotnik.actions.Actions
import si.sopotnik.assist.SvenVoiceService

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
        if (phase3(ctx, intent)) return
        val smsTo = intent.getStringExtra("sms_to")
        val smsText = intent.getStringExtra("sms_text")
        if (smsTo != null && smsText != null) {
            AuditLog.append(ctx, "diagnostika", "DEBUG_SMS (adb)", "YELLOW", "pošiljam")
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
        intent.getStringExtra("tapc")?.let {
            AuditLog.append(ctx, "diagnostika", "DEBUG_TAPC (adb)", "YELLOW", "tap s potrditvijo")
            Log.i(NotifListener.TAG, "DEBUG_TAPC '$it' -> ${ScreenReader.tap(it, confirmed = true)}")
            return
        }
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

    /**
     * Faza 3 (sistemski asistent, zaklep) — adb kljuke za avtomatske teste (ops/t12-faza3.sh):
     *   --ez assist true            stanje vloge, storitve, zaklepa in seje (DEBUG_ASSIST)
     *   --es show <src>             odpri sejno okno kot asistent (DEBUG_SHOW)
     *   --ez stop true              končaj sejo (DEBUG_STOP)
     *   --es say "<besedilo>"       Sven izgovori v klasični seji (DEBUG_SAY)
     *   --es mode live|classic      preklop Sven Live (DEBUG_MODE)
     *   --es lock_tool <ime> [--es dest <cilj>]   pravilo zaklepa za orodje (DEBUG_LOCK)
     *   --es rt_tool <ime> [--es rt_args '{"k":"v"}']   umeten klic orodja v živo sejo (izid v logcat »orodje …«)
     *   --es nav_bg <cilj>          zagon navigacije iz ozadja (DEBUG_NAV_BG)
     */
    private fun phase3(ctx: Context, intent: Intent): Boolean {
        val tag = NotifListener.TAG
        if (intent.getBooleanExtra("assist", false)) {
            val held = runCatching {
                ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }.getOrNull()
            val km = ctx.getSystemService(KeyguardManager::class.java)
            Log.i(
                tag, "DEBUG_ASSIST role=$held active=${runCatching { SvenVoiceService.isActive(ctx) }.getOrNull()}" +
                    " ready=${SvenVoiceService.instance != null} deviceLocked=${km.isDeviceLocked} keyguard=${km.isKeyguardLocked}" +
                    " secure=${km.isDeviceSecure} state=${SessionService.current?.state} overlay=${Settings.canDrawOverlays(ctx)}" +
                    " live=${Prefs(ctx).realtime} rec=${Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")}" +
                    " assistant=${Settings.Secure.getString(ctx.contentResolver, "assistant")}"
            )
            return true
        }
        intent.getStringExtra("show")?.let { Log.i(tag, "DEBUG_SHOW $it -> ${SvenVoiceService.show(it)}"); return true }
        if (intent.getBooleanExtra("stop", false)) {
            SessionService.current?.stopSession()
            Log.i(tag, "DEBUG_STOP state=${SessionService.current?.state}")
            return true
        }
        intent.getStringExtra("say")?.let { SessionService.current?.debugSay(it); Log.i(tag, "DEBUG_SAY"); return true }
        intent.getStringExtra("mode")?.let { Prefs(ctx).realtime = it == "live"; Log.i(tag, "DEBUG_MODE $it"); return true }
        intent.getStringExtra("lock_tool")?.let { t ->
            val dest = intent.getStringExtra("dest")
            val locked = LockState.locked(ctx)
            val rule = LockPolicy.forTool(t, dest, Prefs(ctx).lockCfg())
            Log.i(tag, "DEBUG_LOCK tool=$t dest=$dest locked=$locked rule=$rule deny=${LockPolicy.deny(locked, rule) != null}")
            return true
        }
        intent.getStringExtra("rt_tool")?.let { t ->
            val args = runCatching { JSONObject(intent.getStringExtra("rt_args") ?: "{}") }.getOrDefault(JSONObject())
            val ok = SessionService.current?.debugRt(t, args) == true
            Log.i(tag, "DEBUG_RT tool=$t injected=$ok locked=${LockState.locked(ctx)}")
            return true
        }
        intent.getStringExtra("nav_bg")?.let { d ->
            Log.i(tag, "DEBUG_NAV_BG -> " + Actions.execute(ctx, Action.Navigate(d)))
            return true
        }
        return false
    }
}
