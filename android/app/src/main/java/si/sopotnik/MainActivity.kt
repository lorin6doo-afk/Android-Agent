package si.sopotnik

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), SessionService.UiListener {

    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var micButton: Button

    private var service: SessionService? = null
    private var pendingAutostart = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SessionService.LocalBinder).service()
            service?.uiListener = this@MainActivity
            if (pendingAutostart) {
                pendingAutostart = false
                startSessionChecked()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        partialText = findViewById(R.id.partial_text)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        micButton = findViewById(R.id.btn_mic)

        micButton.setOnClickListener {
            val s = service
            if (s == null || s.state == SessionState.IDLE) startSessionChecked() else s.stopSession()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        bindService(Intent(this, SessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (intent?.getBooleanExtra("autostart", false) == true) pendingAutostart = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("autostart", false)) startSessionChecked()
    }

    override fun onDestroy() {
        service?.uiListener = null
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun startSessionChecked() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.POST_NOTIFICATIONS)

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        SessionService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) SessionService.start(this)
        else statusText.text = "Brez mikrofona ne gre — dovoli dostop."
    }

    // ---- SessionService.UiListener ----

    override fun onSessionState(state: SessionState, label: String) {
        statusText.text = label
        micButton.text = if (state == SessionState.IDLE) "🎤  Začni pogovor" else "■  Končaj"
        if (state == SessionState.IDLE) partialText.text = ""
    }

    override fun onPartial(text: String) {
        partialText.text = if (text.isEmpty()) "" else "… $text"
    }

    override fun onLine(who: String, text: String) {
        logText.append("$who: $text\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
