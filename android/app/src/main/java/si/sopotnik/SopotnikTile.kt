package si.sopotnik

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import si.sopotnik.assist.SvenVoiceService
import si.sopotnik.assist.TileLaunchActivity

class SopotnikTile : TileService() {

    override fun onClick() {
        // Sven je privzeti asistent: sejno okno (deluje tudi na zaklenjenem zaslonu, brez odklepa)
        if (SvenVoiceService.show("tile")) return
        // sicer: neizvožen trampolin zažene sejo in odpre aplikacijo
        val intent = Intent(this, TileLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
