package si.sopotnik.faza0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preizkusna foreground storitev tipa "microphone" (nič ne snema).
 * Vsakih 15 s zapiše "tick" v datoteko — vrzeli ali ponovni zagoni
 * razkrijejo, ali HyperOS storitev ubija ali zamrzuje ob zaklenjenem zaslonu.
 */
class TickService : Service() {

    companion object {
        const val LOG_NAME = "ticks.log"
        private const val CHANNEL = "faza0"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private var ticks = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            ticks++
            append("tick")
            updateNotification("Tick #$ticks ob ${timeFmt.format(Date())} — zakleni telefon za 5–10 min")
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Faza 0 — test ozadja", NotificationManager.IMPORTANCE_LOW)
        )
        append("start")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sopotnik:faza0").also {
            it.acquire(30 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIF_ID,
            buildNotification("Test ozadja teče …"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        append("stop")
        handler.removeCallbacksAndMessages(null)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun append(type: String) {
        runCatching {
            File(filesDir, LOG_NAME).appendText("$type|${System.currentTimeMillis()}\n")
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sopotnik — faza 0")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
