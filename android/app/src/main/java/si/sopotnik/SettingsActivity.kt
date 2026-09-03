package si.sopotnik

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var editUrl: EditText
    private lateinit var editToken: EditText
    private lateinit var editHome: EditText
    private lateinit var editWork: EditText
    private lateinit var swConfirmCalls: Switch
    private lateinit var swFollowUp: Switch
    private lateinit var swRealtime: Switch

    private var testClient: AgentClient? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Insets.pad(findViewById(R.id.root_settings))
        prefs = Prefs(this)

        editUrl = findViewById(R.id.edit_url)
        editToken = findViewById(R.id.edit_token)
        editHome = findViewById(R.id.edit_home)
        editWork = findViewById(R.id.edit_work)
        swConfirmCalls = findViewById(R.id.sw_confirm_calls)
        swFollowUp = findViewById(R.id.sw_follow_up)
        swRealtime = findViewById(R.id.sw_realtime)

        editUrl.setText(prefs.backendUrl)
        editToken.setText(prefs.token)
        editHome.setText(prefs.homeAddress)
        editWork.setText(prefs.workAddress)
        swConfirmCalls.isChecked = prefs.confirmCalls
        swFollowUp.isChecked = prefs.followUp
        swRealtime.isChecked = prefs.realtime

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            save()
            Toast.makeText(this, "Shranjeno.", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            save()
            testConnection()
        }

        findViewById<Button>(R.id.btn_perms).setOnClickListener {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.SEND_SMS
                ), 1
            )
        }

        findViewById<Button>(R.id.btn_notif_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            // brez tega Android blokira odpiranje pogovorov/aplikacij, kadar je spredaj druga aplikacija
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.btn_a11y).setOnClickListener {
            // HyperOS / Android 13+: za nameščen APK prej Podatki o aplikaciji → ⋮ → Dovoli omejene nastavitve
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_diag).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🩺 Diagnostika obvestil")
                .setMessage(NotifListener.diagReport(this))
                .setPositiveButton("Zapri", null)
                .show()
        }

        findViewById<Button>(R.id.btn_audit).setOnClickListener {
            val lines = AuditLog.tail(this, 50)
            AlertDialog.Builder(this)
                .setTitle("Dnevnik dejanj (zadnjih 50)")
                .setMessage(if (lines.isEmpty()) "Dnevnik je prazen." else lines.joinToString("\n"))
                .setPositiveButton("Zapri", null)
                .show()
        }
    }

    private fun save() {
        prefs.backendUrl = editUrl.text.toString()
        prefs.token = editToken.text.toString()
        prefs.homeAddress = editHome.text.toString()
        prefs.workAddress = editWork.text.toString()
        prefs.confirmCalls = swConfirmCalls.isChecked
        prefs.followUp = swFollowUp.isChecked
        prefs.realtime = swRealtime.isChecked
    }

    private fun testConnection() {
        testClient?.shutdown()
        var done = false
        val client = AgentClient(prefs, object : AgentClient.Callback {
            override fun onAgentReady() {
                if (done) return
                done = true
                Toast.makeText(this@SettingsActivity, "✅ Povezava deluje.", Toast.LENGTH_LONG).show()
                testClient?.shutdown()
            }

            override fun onSayDelta(text: String) {}
            override fun onTurnDone(say: String, actionsJson: String?) {}

            override fun onAgentError(message: String) {
                if (done) return
                done = true
                Toast.makeText(this@SettingsActivity, "❌ $message", Toast.LENGTH_LONG).show()
            }
        })
        testClient = client
        client.connect()
        handler.postDelayed({
            if (!done) {
                done = true
                Toast.makeText(this, "❌ Ni odgovora (preveri naslov, žeton in Tailscale).", Toast.LENGTH_LONG).show()
                client.shutdown()
            }
        }, 6_000)
    }

    override fun onPause() {
        // Nastavitve se shranijo samodejno — gumb Shrani ni več obvezen.
        save()
        super.onPause()
    }

    override fun onDestroy() {
        testClient?.shutdown()
        super.onDestroy()
    }
}
