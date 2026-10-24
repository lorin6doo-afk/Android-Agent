package si.sopotnik

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import si.sopotnik.assist.SvenVoiceService

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var editUrl: EditText
    private lateinit var editToken: EditText
    private lateinit var editHome: EditText
    private lateinit var editWork: EditText
    private lateinit var swConfirmCalls: Switch
    private lateinit var swFollowUp: Switch
    private lateinit var swRealtime: Switch
    private lateinit var swCallsLocked: Switch
    private lateinit var swNavLocked: Switch
    private lateinit var txtAssistant: TextView
    private lateinit var txtLockWarn: TextView

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
        swCallsLocked = findViewById(R.id.sw_calls_locked)
        swNavLocked = findViewById(R.id.sw_nav_locked)
        txtAssistant = findViewById(R.id.txt_assistant)
        txtLockWarn = findViewById(R.id.txt_lock_warn)

        editUrl.setText(prefs.backendUrl)
        editToken.setText(prefs.token)
        editHome.setText(prefs.homeAddress)
        editWork.setText(prefs.workAddress)
        swConfirmCalls.isChecked = prefs.confirmCalls
        swFollowUp.isChecked = prefs.followUp
        swRealtime.isChecked = prefs.realtime
        swCallsLocked.isChecked = prefs.callsWhenLocked
        swNavLocked.isChecked = prefs.navWhenLocked

        findViewById<Button>(R.id.btn_assistant).setOnClickListener { openAssistantSettings() }
        findViewById<Button>(R.id.btn_miui_perms).setOnClickListener { openMiuiPerms() }

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
        prefs.callsWhenLocked = swCallsLocked.isChecked
        prefs.navWhenLocked = swNavLocked.isChecked
    }

    override fun onResume() {
        super.onResume()
        refreshAssistantStatus()
    }

    /** Vloge asistenta ni mogoče zahtevati z dialogom (ni »requestable«) — odpremo sistemske nastavitve. */
    private fun openAssistantSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            if (runCatching { startActivity(i) }.isSuccess) {
                Toast.makeText(this, "Izberi Sopotnik pod »Aplikacija digitalnega pomočnika«.", Toast.LENGTH_LONG).show()
                return
            }
        }
    }

    /** HyperOS/MIUI »Druga dovoljenja« (prikaz na zaklenjenem zaslonu, odpiranje oken v ozadju). */
    private fun openMiuiPerms() {
        val tries = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in tries) if (runCatching { startActivity(i) }.isSuccess) return
    }

    private fun refreshAssistantStatus() {
        val held = runCatching {
            val rm = getSystemService(RoleManager::class.java)
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false)
        val active = runCatching { SvenVoiceService.isActive(this) }.getOrDefault(false)
        txtAssistant.text = when {
            held && active && SvenVoiceService.instance != null -> "✅ Sven je privzeti digitalni pomočnik."
            held && active -> "Sven je izbran, storitev se še povezuje."
            held -> "Vloga je dodeljena, storitev pa ni aktivna — izberi Svena znova ali znova zaženi telefon."
            else -> "Sven ni privzeti pomočnik (trenutno: ${currentAssistant()})."
        }
        val secure = LockState.secure(this)
        txtLockWarn.visibility = android.view.View.VISIBLE
        txtLockWarn.text = if (!secure)
            "⚠️ Telefon nima zaklepa zaslona (PIN, vzorec): Sven ga bo vedno obravnaval kot odklenjenega — kdorkoli lahko prek njega bere sporočila."
        else
            "Ob zaklenjenem telefonu Sven ne bere sporočil in ne pošilja ničesar. Pozor: Smart Lock (zaupanja vredna naprava ali kraj) šteje kot odklenjeno."
    }

    /** Trenutni asistent (nastavitev »assistant« je berljiva). */
    private fun currentAssistant(): String = runCatching {
        val flat = Settings.Secure.getString(contentResolver, "assistant")
        if (flat.isNullOrEmpty()) return "nihče"
        val pkg = ComponentName.unflattenFromString(flat)?.packageName ?: return flat
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("neznano")

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
