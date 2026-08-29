package si.sopotnik

import android.view.View
import android.view.WindowInsets

/**
 * Android 15+/targetSdk 35 vsili edge-to-edge risanje; brez tega bi vsebina
 * lezla pod statusno vrstico, navigacijske gumbe in tipkovnico.
 */
object Insets {
    fun pad(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
    }
}
