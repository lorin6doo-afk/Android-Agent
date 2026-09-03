package si.sopotnik

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Neposredno pošiljanje SMS (brez SMS aplikacije in brez uporabnikovega tapa).
 * Izid je pošten: »poslano« šele, ko radio potrdi oddajo (sentIntent RESULT_OK);
 * sicer razlog napake ali odsotnost potrditve v roku.
 */
object SmsSender {

    sealed class Result {
        object Sent : Result() { override fun toString() = "POSLANO" }
        data class Failed(val reason: String) : Result() { override fun toString() = "NEUSPEH: $reason" }
        object Timeout : Result() { override fun toString() = "BREZ POTRDITVE" }
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun send(ctx: Context, rawNumber: String, text: String, timeoutMs: Long = 20_000, cb: (Result) -> Unit) {
        if (!hasPermission(ctx)) { cb(Result.Failed("ni dovoljenja za pošiljanje SMS")); return }
        val number = rawNumber.filter { it.isDigit() || it == '+' }
        if (number.length < 3) { cb(Result.Failed("neveljavna številka")); return }
        if (text.isBlank()) { cb(Result.Failed("prazno besedilo")); return }

        val app = ctx.applicationContext
        val sm = runCatching {
            val base = app.getSystemService(SmsManager::class.java)
            val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
        }.getOrNull() ?: run { cb(Result.Failed("SMS storitev ni na voljo")); return }

        val parts: ArrayList<String> =
            runCatching { sm.divideMessage(text) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: arrayListOf(text)

        val action = "si.sopotnik.SMS_SENT." + System.nanoTime()
        val handler = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        var remaining = parts.size
        var receiver: BroadcastReceiver? = null
        var timeout: Runnable? = null

        fun finish(r: Result) {
            if (!done.compareAndSet(false, true)) return
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            timeout?.let { handler.removeCallbacks(it) }
            cb(r)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val code = resultCode
                if (code != Activity.RESULT_OK) finish(Result.Failed(describe(code)))
                else if (--remaining <= 0) finish(Result.Sent)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)

        val sentIntents = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sentIntents.add(
                PendingIntent.getBroadcast(
                    app, i, Intent(action).setPackage(app.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            )
        }
        timeout = Runnable { finish(Result.Timeout) }.also { handler.postDelayed(it, timeoutMs) }

        try {
            if (parts.size == 1) sm.sendTextMessage(number, null, parts[0], sentIntents[0], null)
            else sm.sendMultipartTextMessage(number, null, parts, sentIntents, null)
        } catch (e: Exception) {
            finish(Result.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "splošna napaka omrežja ali operaterja"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "mobilni radio je izklopljen (letalski način?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "napaka pri sestavi sporočila"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "ni mobilnega omrežja (brez signala ali brez SIM)"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "omejitev števila SMS je presežena"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "pošiljanje na to številko ni dovoljeno"
        SmsManager.RESULT_NETWORK_REJECT -> "omrežje je sporočilo zavrnilo"
        SmsManager.RESULT_INVALID_SMS_FORMAT -> "neveljavna oblika sporočila"
        else -> "koda napake $code"
    }
}
