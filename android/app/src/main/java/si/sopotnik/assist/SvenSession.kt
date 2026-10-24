package si.sopotnik.assist

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import si.sopotnik.LockState
import si.sopotnik.R
import si.sopotnik.SessionService
import si.sopotnik.SessionState

/**
 * Sejno okno asistenta (tip TYPE_VOICE_INTERACTION — sistem ga riše NAD zaklenjenim zaslonom,
 * brez showWhenLocked). Je le tanek UI: sejo vodi SessionService (isti kot iz aplikacije).
 * Ob zaklenjenem telefonu okno ne kaže prepisa pogovora — le stanje in gumbe.
 * Skritje okna (Nazaj, Domov, zaklep) seje NE konča: med vožnjo z ugasnjenim zaslonom teče
 * naprej; konča jo gumb Končaj, akcija v obvestilu, glas ali časovna omejitev.
 */
class SvenSession(ctx: Context) : VoiceInteractionSession(ctx), SessionService.UiListener {

    init {
        setTheme(R.style.Theme_Sopotnik_Session) // mora biti pred onCreate()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var svc: SessionService? = null
    private var bound = false
    private var shownAt = 0L
    private var ui = false
    /** Okno je prikazano (po onShow, do hide/onHide). */
    private var visible = false
    /** Napaka, ki mora ostati vidna (IDLE je ne sme prepisati ali okna samodejno skriti). */
    private var errorText: String? = null
    private lateinit var root: View
    private lateinit var status: TextView
    private lateinit var partial: TextView
    private lateinit var lines: TextView
    private lateinit var lockHint: TextView
    private lateinit var btnUnlock: Button

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = (b as SessionService.LocalBinder).service().also { it.addUiListener(this@SvenSession) }
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            svc = null
        }
    }

    override fun onCreateContentView(): View {
        root = layoutInflater.inflate(R.layout.session_sven, null)
        val card = root.findViewById<View>(R.id.s_card)
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { _, ins -> // okno seje je edge-to-edge
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            card.setPadding(pad + b.left, pad, pad + b.right, pad + b.bottom)
            WindowInsets.CONSUMED
        }
        status = root.findViewById(R.id.s_status)
        partial = root.findViewById(R.id.s_partial)
        lines = root.findViewById(R.id.s_lines)
        lockHint = root.findViewById(R.id.s_lock)
        btnUnlock = root.findViewById(R.id.s_unlock)
        root.setOnClickListener { hide() } // dotik ob kartici: skrij okno, seja teče naprej
        card.setOnClickListener { }
        root.findViewById<Button>(R.id.s_end).setOnClickListener {
            SessionService.current?.stopSession()
            hide()
        }
        // Najprej zaženi aktivnost, šele nato skrij okno: na skriti seji startAssistantActivity
        // vrže IllegalStateException, okno (plast nad keyguardom) pa bi sicer prekrilo vnos PIN-a.
        btnUnlock.setOnClickListener { launchUnlock(thenMain = false) }
        root.findViewById<Button>(R.id.s_open).setOnClickListener { launchUnlock(thenMain = true) }
        ui = true
        return root
    }

    private fun launchUnlock(thenMain: Boolean) {
        runCatching {
            startAssistantActivity(
                Intent(context, UnlockActivity::class.java).putExtra(UnlockActivity.EXTRA_THEN_MAIN, thenMain)
            )
        }.onFailure { Log.w(TAG, "F3 unlock launch fail $it") }
        hide()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shownAt = SystemClock.elapsedRealtime()
        val itype = args?.getInt("invocation_type", -1) ?: -1
        val src = args?.getString(ARG_SRC) ?: when (itype) {
            6 -> "power"
            7 -> "assist_key"
            5 -> "home"
            1, 2, 8 -> "gesture"
            9 -> "launcher"
            else -> "assist"
        }
        val locked = LockState.locked(context)
        Log.i(TAG, "F3 show src=$src itype=$itype flags=$showFlags locked=$locked")
        visible = true
        errorText = null
        if (src == "tile") closeSystemDialogs() // zapre hitre nastavitve (keyguarda ne)
        refreshLockUi()
        // brez mikrofona se NE veže (sicer bi »Pripravljen« prepisal nasvet in okno skril)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Sven nima dovoljenja za mikrofon — odkleni, pritisni Odpri in ga dodeli.")
            return
        }
        if (!bound) {
            bound = context.bindService(Intent(context, SessionService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
        // idempotentno: SessionService sam odloči — nova seja, prezrt dvojni sprožilec ali prekinitev
        if (!SessionService.start(context, src)) {
            // Ob prvem prikazu okno še ni izrisano; ko je, velja tudi izjema »vidno okno«.
            if (ui) root.post {
                if (!SessionService.start(context, src)) {
                    showError("Android zagona seje ni dovolil — glej Nastavitve → Sistemski asistent.")
                }
            }
        }
    }

    private fun showError(text: String) {
        errorText = text
        handler.removeCallbacksAndMessages(null)
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // seja ne teče
        if (ui) status.text = text
    }

    override fun onCloseSystemDialogs() {
        // Ponovni sprožilec (dolg pritisk) najprej razpošlje »zapri sistemska okna«; na Androidu 13
        // bi to skrilo pravkar prikazano okno. Takoj po prikazu ga zato prezremo.
        if (SystemClock.elapsedRealtime() - shownAt < 1_000) return
        super.onCloseSystemDialogs()
    }

    override fun onHide() {
        Log.i(TAG, "F3 hide")
        visible = false
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacksAndMessages(null)
        svc?.removeUiListener(this)
        svc = null
        if (bound) {
            runCatching { context.unbindService(conn) }
            bound = false
        }
        super.onHide() // NAMERNO brez stopSession(): seja teče naprej
    }

    // ---- SessionService.UiListener (vedno na glavni niti) ----

    override fun onSessionState(state: SessionState, label: String) {
        if (!ui) return
        if (state != SessionState.IDLE) errorText = null
        if (errorText != null) return // napaka ostane vidna, okno se ne skrije samo
        status.text = label
        refreshLockUi()
        // zaslon naj ne ugasne, dokler Sven dela
        window?.window?.let { w ->
            if (state != SessionState.IDLE) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        handler.removeCallbacksAndMessages(null)
        if (state == SessionState.IDLE) {
            handler.postDelayed({ if (svc?.state == SessionState.IDLE) hide() }, 1_500)
        }
    }

    override fun onPartial(text: String) {
        if (ui) partial.text = text
    }

    override fun onLine(who: String, text: String) {
        if (!ui) return
        if (LockState.locked(context)) { // prepis pogovora ne sodi na zaklenjen zaslon
            lines.text = ""
            return
        }
        lines.text = svc?.recentLines(4)?.joinToString("\n") { "${it.first}: ${it.second}" }.orEmpty()
    }

    override fun onUnlockNeeded() {
        if (ui) btnUnlock.visibility = View.VISIBLE
    }

    /** Sven odpira ali bere drugo aplikacijo: okno se umakne, seja teče naprej. */
    override fun onExternalUi(): Boolean {
        if (!visible) return false
        visible = false
        hide()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        svc?.removeUiListener(this)
        svc = null
        if (bound) {
            runCatching { context.unbindService(conn) }
            bound = false
        }
        super.onDestroy()
    }

    private fun refreshLockUi() {
        if (!ui) return
        val locked = LockState.locked(context)
        lockHint.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) {
            lines.text = ""
        } else {
            btnUnlock.visibility = View.GONE
        }
    }

    companion object {
        const val ARG_SRC = "src"
        private const val TAG = "Sopotnik"
    }
}
