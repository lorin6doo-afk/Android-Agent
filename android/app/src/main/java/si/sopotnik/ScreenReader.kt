package si.sopotnik

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

        /** Nikoli, ne glede na potrditev: brisanje, plačila, naročila, klici, blokiranje, odjava. */
        private val HARD_FORBIDDEN = setOf(
            "izbrisi", "delete", "odstrani", "remove", "placaj", "pay", "kupi", "buy", "naroci", "order",
            "poklici", "call", "klic", "blokiraj", "block", "prijavi", "report", "odjava", "logout",
            "zavrzi", "discard"
        )

        /** Le z izrecno ustno potrditvijo (tap_item potrjeno=true): pošiljanje, potrditev, objava. */
        private val CONFIRM_REQUIRED = setOf("poslji", "send", "potrdi", "confirm", "objavi", "post", "share", "deli", "submit")

        private fun inSet(text: String, set: Set<String>): Boolean {
            val k = Actions.matchKey(text)
            return k in set || k.split(' ').any { it in set }
        }

        private fun label(n: AccessibilityNodeInfo): String {
            val t = n.text?.toString()?.trim().orEmpty()
            return if (t.isNotEmpty()) t else n.contentDescription?.toString()?.trim().orEmpty()
        }

        /** Vidno besedilo aktivnega okna od zgoraj navzdol (v pogovorih je najnovejše spodaj). */
        fun dump(maxChars: Int = 3000): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            val pkg = root.packageName?.toString().orEmpty()
            val app = runCatching {
                val pm = svc.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val lines = ArrayList<String>()
            collect(root, lines, 0)  // vrne Boolean, tu ga ne rabimo
            val sb = StringBuilder("Zaslon: $app")
            root.window?.title?.toString()?.takeIf { it.isNotBlank() && it != app }?.let { sb.append(" — ").append(it) }
            sb.append('\n')
            if (lines.isEmpty()) sb.append("(na zaslonu ni berljivega besedila)")
            else lines.forEach { sb.append(it).append('\n') }
            var out = sb.toString().trimEnd()
            if (out.length > maxChars) out = out.take(maxChars).trimEnd() + " … (zaslon ima še več besedila — skrajšano)"
            return out
        }

        private fun add(out: MutableList<String>, line: String) {
            if (line.isNotBlank() && out.lastOrNull() != line) out.add(line)
        }

        /**
         * Vrne true, če je iz tega poddrevesa kaj izpisal. Poleg lastnega besedila (text)
         * zajame tudi opis (contentDescription): pri sporočilih v nekaterih klepetih je
         * celotno besedilo mehurčka le opis VRSTICE, katere podpogledi svojega besedila
         * nimajo — prav to je prej manjkalo (pošiljatelj in ura sta se zajela, telo pa ne).
         */
        private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int): Boolean {
            if (depth > 80 || !n.isVisibleToUser) return false
            if (n.isPassword) {
                add(out, "[skrito polje]")
                return true
            }
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            var emitted = false
            if (t.isNotEmpty()) {
                add(out, if (n.isEditable) "[vnos] $t" else if (n.isClickable || n.isCheckable) "▸ $t" else t)
                emitted = true
            }
            // opis vrstice/mehurčka, ki NI enak že zajetemu besedilu (klepetalna sporočila);
            // pri vsebinsko praznih ikonah z opisom (childCount == 0) ravno tako koristi
            if (d.isNotEmpty() && d != t) {
                add(out, if (n.isClickable) "▸ $d" else d)
                emitted = true
            }
            var childEmitted = false
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (collect(c, out, depth + 1)) childEmitted = true
            }
            return emitted || childEmitted
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
        fun tap(query: String, confirmed: Boolean = false): String {
            val svc = instance ?: return "Branje zaslona ni povezano."
            if (inSet(query, HARD_FORBIDDEN))
                return "USTAVLJENO: gumba »$query« ne tapkam (brisanje, plačilo, naročilo, klic, blokiranje ali odjava) — tega Sven prek zaslona ne dela."
            if (inSet(query, CONFIRM_REQUIRED) && !confirmed)
                return "ČAKAM POTRDITEV: »$query« je gumb za pošiljanje/potrditev. Uporabniku preberi, kaj bo poslano, in šele po njegovem izrecnem 'pošlji' ali 'da' znova pokliči tap_item s potrjeno=true."
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
            if (inSet(found, HARD_FORBIDDEN))
                return "USTAVLJENO: element »$found« je gumb za brisanje/plačilo/klic/blokiranje — tega ne tapkam."
            val sendLike = inSet(found, CONFIRM_REQUIRED)
            if (sendLike && !confirmed)
                return "ČAKAM POTRDITEV: element »$found« pošilja ali potrjuje — najprej ustna potrditev uporabnika, nato tap_item s potrjeno=true."
            val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return when {
                !ok -> "Tapa na »$found« ni bilo mogoče izvesti."
                sendLike -> "Tapnjeno (potrjeno): »$found«. Pokliči read_screen in potrdi uporabniku le, če je res poslano."
                else -> "Tapnjeno: »$found«. Pokliči read_screen, da vidiš, kaj se je odprlo."
            }
        }

        /** Polje za vnos: fokusirano, sicer prvo vidno; gesla nikoli. */
        private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var focused: AccessibilityNodeInfo? = null
            var first: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 80 || focused != null) return
                if (n.isVisibleToUser && n.isEditable && !n.isPassword) {
                    if (n.isFocused) { focused = n; return }
                    if (first == null) first = n
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            return focused ?: first
        }

        /**
         * Vpiše besedilo v polje za vnos — za pisanje v katerokoli aplikacijo. NIČ ne pošlje;
         * pošiljanje je ločen, potrjen tap. Če polje ne podpira ACTION_SET_TEXT, rezerva prek
         * odložišča + ACTION_PASTE.
         */
        fun typeText(text: String, append: Boolean): String {
            val svc = instance ?: return "Branje zaslona ni povezano."
            if (text.isBlank()) return "Manjka besedilo."
            val root = svc.rootInActiveWindow ?: return "Zaslon ni na voljo (zaklenjen ali prazen)."
            val target = findEditable(root)
                ?: return "Na zaslonu ni polja za vnos — najprej odpri pogovor ali tapni polje/gumb za nov pogovor (tap_item), nato znova type_text."
            val hint = target.hintText?.toString().orEmpty()
            val raw = target.text?.toString().orEmpty()
            val existing = if (raw == hint) "" else raw
            val fieldName = hint.ifBlank { raw }.ifBlank { "za vnos" }
            val finalText = if (append && existing.isNotBlank()) existing + text else text
            if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            var ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            var how = "vpisano"
            if (!ok) {
                svc.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Sopotnik", finalText))
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                how = "prilepljeno"
            }
            if (!ok) return "Besedila ni bilo mogoče vpisati v polje »$fieldName« — aplikacija tega ne dovoli."
            return "V polje »$fieldName« $how: »$finalText«. Nič še ni poslano — uporabniku preberi, kaj je vpisano; po njegovem 'pošlji' tapni gumb za pošiljanje s tap_item(potrjeno=true)."
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
