package si.sopotnik

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import si.sopotnik.actions.Actions

/**
 * Branje zaslona in preprosto upravljanje prek storitve dostopnosti — za sporočila,
 * ki jih med obvestili ni več (prebrana, izbrisana, starejša). Dela izključno na
 * zahtevo orodij (read_screen, tap_item, scroll_screen, go_back): dogodkov ne
 * spremlja in ničesar ne shranjuje. Telefon mora biti odklenjen in aplikacija na
 * zaslonu. Skrita polja (gesla) se nikoli ne berejo; gumbov za pošiljanje, brisanje,
 * plačilo, klic ali potrditev tap_item ne pritisne — take stvari gredo prek namenskih
 * orodij z ustno potrditvijo.
 */
class ScreenReader : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenReader? = null

        fun enabled(ctx: Context): Boolean {
            val full = "${ctx.packageName}/${ScreenReader::class.java.name}"
            val short = "${ctx.packageName}/.${ScreenReader::class.java.simpleName}"
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return s.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }

        /** Napisi gumbov, ki jih tap_item NIKOLI ne pritisne (primerjava prek matchKey, brez šumnikov). */
        private val FORBIDDEN = setOf(
            "poslji", "send", "izbrisi", "delete", "odstrani", "remove", "placaj", "pay", "kupi", "buy",
            "poklici", "call", "klic", "blokiraj", "block", "potrdi", "confirm", "objavi", "post", "share",
            "deli", "prijavi", "report", "odjava", "logout", "zavrzi", "discard", "naroci", "order"
        )

        private fun forbidden(text: String): Boolean {
            val k = Actions.matchKey(text)
            return k in FORBIDDEN || k.split(' ').any { it in FORBIDDEN }
        }

        private fun label(n: AccessibilityNodeInfo): String {
            val t = n.text?.toString()?.trim().orEmpty()
            return if (t.isNotEmpty()) t else n.contentDescription?.toString()?.trim().orEmpty()
        }

        /** Vidno besedilo aktivnega okna od zgoraj navzdol (v pogovorih je najnovejše spodaj). */
        fun dump(maxChars: Int = 2500): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            val pkg = root.packageName?.toString().orEmpty()
            val app = runCatching {
                val pm = svc.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val lines = ArrayList<String>()
            collect(root, lines, 0)
            val sb = StringBuilder("Zaslon: $app")
            root.window?.title?.toString()?.takeIf { it.isNotBlank() && it != app }?.let { sb.append(" — ").append(it) }
            sb.append('\n')
            if (lines.isEmpty()) sb.append("(na zaslonu ni berljivega besedila)")
            else lines.forEach { sb.append(it).append('\n') }
            var out = sb.toString().trimEnd()
            if (out.length > maxChars) out = out.take(maxChars).trimEnd() + " … (zaslon ima še več besedila — skrajšano)"
            return out
        }

        private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
            if (depth > 60 || !n.isVisibleToUser) return
            if (n.isPassword) {
                out.add("[skrito polje]")
                return
            }
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val childCount = n.childCount
            val shown = when {
                t.isNotEmpty() -> t
                d.isNotEmpty() && childCount == 0 -> d
                else -> ""
            }
            if (shown.isNotEmpty()) {
                val line = when {
                    n.isEditable -> "[vnos] $shown"
                    n.isClickable || n.isCheckable -> "▸ $shown"
                    else -> shown
                }
                if (out.lastOrNull() != line) out.add(line)
            }
            for (i in 0 until childCount) {
                val c = n.getChild(i) ?: continue
                collect(c, out, depth + 1)
            }
        }

        private fun findByLabel(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
            val q = Actions.matchKey(query)
            if (q.isEmpty()) return null
            val qTok = q.split(' ')
            var exact: AccessibilityNodeInfo? = null
            var partial: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 60 || exact != null) return
                if (n.isVisibleToUser) {
                    val k = Actions.matchKey(label(n))
                    if (k.isNotEmpty()) {
                        if (k == q) {
                            exact = n
                            return
                        }
                        if (partial == null && qTok.all { k.contains(it) }) partial = n
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            return exact ?: partial
        }

        /** Tapne vidni element z danim napisom (oz. njegov najbližji klikljivi nadrejeni element). */
        fun tap(query: String): String {
            val svc = instance ?: return "Branje zaslona ni povezano."
            if (forbidden(query))
                return "USTAVLJENO: gumba »$query« ne tapkam (pošiljanje, brisanje, plačilo, klic ali potrditev) — take stvari gredo prek namenskih orodij z ustno potrditvijo."
            val root = svc.rootInActiveWindow ?: return "Zaslon ni na voljo (zaklenjen ali prazen)."
            var n = findByLabel(root, query)
                ?: return "Na zaslonu ni elementa »$query«. Pokliči read_screen in uporabi napis natanko tako, kot je izpisan."
            val found = label(n)
            var hops = 0
            while (!n.isClickable && hops < 8) {
                n = n.parent ?: break
                hops++
            }
            if (!n.isClickable) return "Element »$found« ni klikljiv."
            if (forbidden(found))
                return "USTAVLJENO: element »$found« je gumb za pošiljanje/brisanje/plačilo/klic/potrditev — tega ne tapkam."
            val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return if (ok) "Tapnjeno: »$found«. Pokliči read_screen, da vidiš, kaj se je odprlo."
            else "Tapa na »$found« ni bilo mogoče izvesti."
        }

        /** Pomik po največjem pomičnem seznamu: 'up' = proti starejšemu/začetku, 'down' = naprej. */
        fun scroll(direction: String): String {
            val svc = instance ?: return "Branje zaslona ni povezano."
            val root = svc.rootInActiveWindow ?: return "Zaslon ni na voljo."
            var best: AccessibilityNodeInfo? = null
            var bestArea = 0
            val r = Rect()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 60) return
                if (n.isScrollable && n.isVisibleToUser) {
                    n.getBoundsInScreen(r)
                    val area = r.width() * r.height()
                    if (area > bestArea) {
                        bestArea = area
                        best = n
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            val node = best ?: return "Na zaslonu ni pomičnega seznama."
            val up = direction == "up"
            val ok = node.performAction(
                if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            )
            return if (ok) "Pomaknjeno ${if (up) "navzgor (starejše)" else "navzdol"}. Pokliči read_screen."
            else "Pomik ni več mogoč — verjetno si na ${if (up) "začetku" else "koncu"}."
        }

        fun back(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(NotifListener.TAG, "branje zaslona POVEZANO")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* beremo le na zahtevo */ }

    override fun onInterrupt() { /* nič */ }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(NotifListener.TAG, "branje zaslona ODKLOPLJENO")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
