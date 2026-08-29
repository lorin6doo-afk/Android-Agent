package si.sopotnik

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

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
        @Volatile
        var instance: NotifListener? = null

        fun snapshot(max: Int = 12): List<NotifEntry>? {
            val svc = instance ?: return null
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
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
