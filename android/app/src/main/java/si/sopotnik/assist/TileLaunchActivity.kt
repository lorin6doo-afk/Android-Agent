package si.sopotnik.assist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import si.sopotnik.MainActivity
import si.sopotnik.SessionService

/**
 * Trampolin za ploščico hitrih nastavitev, kadar Sven NI privzeti asistent. Ne izvožena:
 * zažene jo lahko le naša ploščica. (Prej je to delal extra »autostart« na izvoženi
 * MainActivity — katerakoli aplikacija je z njim lahko odprla mikrofon, ob ponovni
 * izdelavi aktivnosti pa se je seja zagnala znova brez dotika.)
 */
class TileLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            SessionService.start(this, "tile")
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
