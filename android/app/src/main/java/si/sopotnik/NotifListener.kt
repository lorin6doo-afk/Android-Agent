package si.sopotnik

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class NotifEntry(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val timeMs: Long,
    val canReply: Boolean,
)

/**
 * Faza 2: branje aktivnih obvestil in odgovarjanje prek njihove reply akcije
 * (RemoteInput — isti kanal kot pametne ure). Dostop podeli uporabnik v nastavitvah.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        const val TAG = "Sopotnik"

        @Volatile
        var instance: NotifListener? = null

        fun accessGranted(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true

        /** Po vklopu dostopa HyperOS storitve ne poveže vedno takoj — to jo prisili. */
        fun ensureBound(ctx: Context) {
            if (accessGranted(ctx) && instance == null) {
                runCatching {
                    requestRebind(ComponentName(ctx, NotifListener::class.java))
                    Log.i(TAG, "requestRebind poslan (instance je bil null)")
                }
            }
        }

        fun snapshot(max: Int = 12): List<NotifEntry>? {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "snapshot(): instance == null (poslušalec ni vezan)")
                return null
            }
            return runCatching {
                svc.activeNotifications
                    .filter { sbn ->
                        val n = sbn.notification
                        sbn.packageName != svc.packageName &&
                            !sbn.isOngoing &&
                            n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                            n.extras.getCharSequence(Notification.EXTRA_TITLE) != null
                    }
                    .sortedByDescending { it.postTime }
                    .take(max)
                    .also { Log.i(TAG, "snapshot(): ${svc.activeNotifications.size} skupaj, ${it.size} po filtru") }
                    .map { sbn ->
                        val e = sbn.notification.extras
                        NotifEntry(
                            key = sbn.key,
                            app = svc.appLabel(sbn.packageName),
                            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                            text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT)
                                ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                            timeMs = sbn.postTime,
                            canReply = replyAction(sbn) != null,
                        )
                    }
            }.getOrNull()
        }

        fun reply(key: String, text: String): Boolean {
            val svc = instance ?: return false
            return runCatching {
                val sbn = svc.activeNotifications.firstOrNull { it.key == key } ?: return false
                val action = replyAction(sbn) ?: return false
                val remoteInputs = action.remoteInputs ?: return false
                val intent = Intent()
                val results = Bundle()
                remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(remoteInputs, intent, results)
                action.actionIntent.send(svc, 0, intent)
                true
            }.getOrDefault(false)
        }

        private fun replyAction(sbn: StatusBarNotification): Notification.Action? {
            val n = sbn.notification
            n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }?.let { return it }
            return Notification.WearableExtender(n).actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "poslušalec obvestil POVEZAN")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.w(TAG, "poslušalec obvestil ODKLOPLJEN")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
