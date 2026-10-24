package si.sopotnik.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import si.sopotnik.MainActivity
import si.sopotnik.SessionService

/**
 * Zahteva odklep (PIN, prstni odtis). requestDismissKeyguard potrebuje aktivnost s
 * showWhenLocked; zaženemo jo prek startAssistantActivity iz sejnega okna.
 * Ne izvožena — zažene jo lahko le Sopotnik sam.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            done(true)
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
    }

    private fun done(ok: Boolean) {
        if (ok) {
            SessionService.current?.onUnlocked()
            if (intent.getBooleanExtra(EXTRA_THEN_MAIN, false)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_THEN_MAIN = "then_main"
    }
}
