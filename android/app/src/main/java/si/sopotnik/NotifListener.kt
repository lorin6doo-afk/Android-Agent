package si.sopotnik

import android.app.ActivityOptions
import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

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

        private val rescueScheduled = AtomicBoolean(false)

        /**
         * Po vklopu dostopa HyperOS storitve ne poveže vedno takoj — to jo prisili.
         * Če requestRebind ne zaleže, čez 2 s še znani obhod za zataknjene poslušalce:
         * cikel onemogoči/omogoči komponente (grant pri tem ostane) + nov requestRebind.
         */
        fun ensureBound(ctx: Context) {
            if (!accessGranted(ctx) || instance != null) return
            val cn = ComponentName(ctx, NotifListener::class.java)
            runCatching {
                requestRebind(cn)
                Log.i(TAG, "requestRebind poslan (instance je bil null)")
            }
            if (!rescueScheduled.compareAndSet(false, true)) return
            val app = ctx.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                rescueScheduled.set(false)
                if (accessGranted(app) && instance == null) {
                    runCatching {
                        val pm = app.packageManager
                        pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                        pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                        requestRebind(cn)
                        Log.i(TAG, "obhod: cikel onemogoči/omogoči komponente + requestRebind")
                    }
                }
            }, 2_000)
        }

        /** Poročilo za gumb 🩺 v nastavitvah in za DebugReceiver (adb). */
        fun diagReport(ctx: Context): String {
            val granted = accessGranted(ctx)
            val svc = instance
            val sb = StringBuilder()
            sb.appendLine("dostop podeljen: ${if (granted) "DA" else "NE"}")
            sb.appendLine("poslušalec vezan: ${if (svc != null) "DA" else "NE"}")
            when {
                !granted -> {
                    sb.appendLine()
                    sb.appendLine("Podeli dostop z gumbom »Dostop do obvestil«. Na Androidu 16 prej: Podatki o aplikaciji → ⋮ → Dovoli omejene nastavitve.")
                }
                svc == null -> {
                    ensureBound(ctx)
                    sb.appendLine()
                    sb.appendLine("Vezavo sem pravkar znova zahteval (requestRebind + obhod). Počakaj 3 sekunde in znova pritisni 🩺. Če ostane NE: izklopi in znova vklopi dostop do obvestil ali ponovno zaženi telefon.")
                }
                else -> {
                    val all = runCatching { svc.activeNotifications.toList() }.getOrNull()
                    if (all == null) sb.appendLine("branje activeNotifications NI uspelo — poslušalec je vezan le navidezno; izklopi in znova vklopi dostop.")
                    else {
                        val filtered = snapshot() ?: emptyList()
                        sb.appendLine("aktivnih obvestil v sistemu: ${all.size}")
                        sb.appendLine("po filtru (brez trajnih in povzetkov skupin): ${filtered.size}")
                        filtered.take(4).forEachIndexed { i, n ->
                            sb.appendLine("${i + 1}) [${n.app}] ${n.title}${if (n.canReply) " ↩" else ""}")
                        }
                        // forenzika: surov seznam — pove, ali pogovorna obvestila (WhatsApp …)
                        // do poslušalca sploh pridejo ali jih izloči filter
                        sb.appendLine()
                        sb.appendLine("Surovo (paket · profil · zastavice):")
                        all.sortedByDescending { it.postTime }.take(25).forEach { sbn ->
                            val n = sbn.notification
                            val flags = mutableListOf<String>()
                            if (sbn.isOngoing) flags.add("trajno")
                            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) flags.add("povzetek")
                            if (n.extras.getCharSequence(Notification.EXTRA_TITLE) == null) flags.add("brez-naslova")
                            if (replyAction(sbn) != null) flags.add("odgovor")
                            val user = sbn.user.toString().filter { it.isDigit() }.ifEmpty { "?" }
                            sb.appendLine("· ${sbn.packageName} u$user${if (flags.isEmpty()) "" else " [${flags.joinToString(",")}]"}")
                        }
                    }
                }
            }
            return sb.toString().trimEnd()
        }

        fun snapshot(max: Int = 15): List<NotifEntry>? {
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
                    // pogovori z možnostjo odgovora najprej — da jih kup vremenskih
                    // napovedi in reklam nikoli ne izrine iz omejenega seznama
                    .sortedWith(
                        compareByDescending<StatusBarNotification> { replyAction(it) != null }
                            .thenByDescending { it.postTime }
                    )
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

        /** Poštni odjemalci v obvestilo dajo le izsek sporočila — model naj to pove, ne pa da »prebere« izsek kot celoto. */
        private val MAIL_PKGS = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
            "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "me.bluemail.mail",
            "com.fsck.k9", "eu.faircode.email"
        )

        /**
         * Celotna vsebina enega obvestila za glasno branje: pri pogovorih (MessagingStyle —
         * WhatsApp, SMS, Telegram, Signal …) zadnja sporočila s pošiljatelji (in morebitna
         * starejša), sicer razširjeno besedilo (BigText / vrstice Inbox sloga). Ničesar ne
         * odpre. Omejeno na [maxChars], da TTS ne bere v nedogled.
         */
        @Suppress("DEPRECATION")
        fun detail(key: String, maxChars: Int = 1500): String? {
            val svc = instance ?: return null
            val sbn = runCatching { svc.activeNotifications.firstOrNull { it.key == key } }.getOrNull() ?: return null
            val e = sbn.notification.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val convTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            val isGroup = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val me = (e.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person)?.name?.toString()

            val sb = StringBuilder()
            sb.append(svc.appLabel(sbn.packageName)).append(" — ").append(convTitle ?: title)
            if (isGroup) sb.append(" (skupina)")
            sb.append(", ").append(ago(sbn.postTime))
            if (replyAction(sbn) != null) sb.append(" [odgovor možen]")
            sb.append('\n')

            val msgs = parseMessages(e.getParcelableArray(Notification.EXTRA_MESSAGES))
            if (msgs.isNotEmpty()) {
                val hist = parseMessages(e.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES))
                if (hist.isNotEmpty()) {
                    sb.append("Prej:\n")
                    hist.takeLast(5).forEach { sb.append("- ").append(fmtMsg(it, me)).append('\n') }
                    sb.append("Novo:\n")
                }
                msgs.forEach { sb.append("- ").append(fmtMsg(it, me)).append('\n') }
            } else {
                val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                sb.append(
                    when {
                        !lines.isNullOrEmpty() -> lines.joinToString("\n") { "- $it" }
                        !big.isNullOrBlank() -> big
                        !text.isNullOrBlank() -> text
                        else -> "(obvestilo nima besedila)"
                    }
                ).append('\n')
                e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                    ?.takeIf { it.isNotBlank() && it != title }
                    ?.let { sb.append('(').append(it).append(")\n") }
            }
            if (sbn.packageName in MAIL_PKGS)
                sb.append("OPOMBA: e-poštno obvestilo vsebuje le izsek — celotnega sporočila prek obvestila ni mogoče prebrati.\n")

            var out = sb.toString().trimEnd()
            if (out.length > maxChars) out = out.take(maxChars).trimEnd() + " … (besedilo je daljše — skrajšano)"
            return out
        }

        private data class Msg(val sender: String?, val text: String, val time: Long)

        /** Bundli sporočil MessagingStyle (ključi kot v Notification.MessagingStyle.Message). */
        @Suppress("DEPRECATION")
        private fun parseMessages(arr: Array<Parcelable>?): List<Msg> {
            if (arr == null) return emptyList()
            return arr.mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = (b.getParcelable("sender_person") as? Person)?.name?.toString()
                    ?: b.getCharSequence("sender")?.toString()
                Msg(sender, text, b.getLong("time", 0L))
            }.sortedBy { it.time }
        }

        /** Pošiljatelj brez imena (ali enak uporabniku) je po pogodbi MessagingStyle uporabnik sam. */
        private fun fmtMsg(m: Msg, me: String?): String {
            val who = if (m.sender.isNullOrBlank() || (me != null && m.sender == me)) "Ti" else m.sender
            return "$who: ${m.text}"
        }

        private fun ago(t: Long): String {
            val min = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
            return when {
                min < 1 -> "pravkar"
                min < 60 -> "pred $min min"
                min < 24 * 60 -> "pred ${min / 60} h"
                else -> "pred ${min / (24 * 60)} dnevi"
            }
        }

        /** Odpre obvestilo na zaslonu — enako kot dotik obvestila (npr. pogovor v WhatsAppu). */
        fun open(key: String): Boolean {
            val svc = instance ?: return false
            return runCatching {
                val sbn = svc.activeNotifications.firstOrNull { it.key == key } ?: return false
                val pi = sbn.notification.contentIntent ?: return false
                val opts = ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pi.send(svc, 0, null, null, null, null, opts.toBundle())
                true
            }.getOrDefault(false)
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
