package si.sopotnik

import android.view.View
import android.view.WindowInsets

/**
 * Android 15+/targetSdk 35 vsili edge-to-edge risanje; brez tega bi vsebina
 * lezla pod statusno vrstico, navigacijske gumbe in tipkovnico. Sistemski robovi
 * se PRIŠTEJEJO lastnemu odmiku postavitve (npr. 16dp), ne zamenjajo ga.
 */
object Insets {
    fun pad(root: View) {
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val s = insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime()
            )
            v.setPadding(l + s.left, t + s.top, r + s.right, b + s.bottom)
            WindowInsets.CONSUMED
        }
    }
}
