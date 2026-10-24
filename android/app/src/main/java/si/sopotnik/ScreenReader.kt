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

        /**
         * Nikoli, ne glede na potrditev: brisanje, plačila, nakupi, naročila, klici, blokiranje,
         * prijava zlorabe, odjava, ponastavitev. Primerja se vsako besedo napisa (brez šumnikov):
         * WORDS natančno, PREFIX kot začetek besede (sklanjatve: »Izbriši za vse«, »Plačilo«,
         * »Pokliči«), zloženke so naštete izrecno (»Videoklic«, »PayPal«). Podnizov sredi besede
         * NE iščemo — sicer bi bil blokiran pogovor z »Ana Jaklič« ali zapisek »Nakupovalni seznam«.
         */
        private val HARD_WORDS = setOf(
            "prijavi", "report", "order", "buy", "kupi", "kupite", "nakup", "purchase", "checkout",
            "naroci", "narocite", "subscribe", "unsubscribe", "zavrzi", "discard", "reset", "format",
            "clear", "money", "denar", "pay", "call"
        )
        private val HARD_PREFIX = listOf(
            "izbris", "zbris", "brisi", "brisanj", "pobris", "odstran", "remov", "uninstall", "odinstal", "delet",
            "placaj", "placil", "placat", "placam", "doplac", "payment", "kupit", "kupim", "narocil",
            "poklic", "klic", "calling", "blokir", "odblokir", "block", "unblock",
            "odjav", "logout", "signout", "pocisti", "izprazni", "ponastav", "nakaz", "transfer",
            "videoklic", "glasovniklic", "videocall", "voicecall", "paypal", "gpay", "googlepay", "applepay"
        )

        /** Predmet + dejanje (»Prekliči naročnino«, »Complete payment«); sam navigacijski napis (»Naročnine«) ostane dovoljen. */
        private val HARD_OBJECT = listOf("narocnin", "subscription", "placil", "payment")
        private val HARD_ACTION = listOf(
            "oddaj", "potrd", "zakljuc", "dokonc", "preklic", "zacni", "izvedi", "opravi", "podaljs",
            "complete", "make", "authoriz", "submit", "confirm", "cancel", "start", "place", "proceed", "continue", "renew"
        )

        /** Zavihek »Klici« / »Zgodovina klicev« (s c, ne č) je navigacija, ne klic — preverimo pred odstranitvijo šumnikov. */
        private val CALL_TAB_WORDS = setOf("klici", "klicev", "klice")

        /** Le z izrecno ustno potrditvijo (tap_item potrjeno=true): pošiljanje, potrditev, objava, sprejem. */
        private val CONFIRM_WORDS = setOf("send", "post", "share", "deli", "submit", "ok", "accept", "yes", "da")
        private val CONFIRM_PREFIX = listOf("poslj", "posil", "potrd", "confirm", "objav", "oddaj", "sprejm", "publish", "delit")

        private fun words(text: String): List<String> = Actions.matchKey(text).split(' ').filter { it.isNotEmpty() }

        internal fun isHardForbidden(text: String): Boolean {
            // izvirne besede (s šumniki): »klici« (zavihek) ≠ »kliči« (dejanje)
            val raw = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val w = words(text)
            val joined = w.joinToString("")
            val rule = w.withIndex().any { (i, t) ->
                val tab = raw.getOrNull(i) in CALL_TAB_WORDS && raw.size == w.size
                !tab && (t in HARD_WORDS || HARD_PREFIX.any { t.startsWith(it) })
            }
            val objAction = w.any { t -> HARD_OBJECT.any { t.startsWith(it) } } && w.any { t -> HARD_ACTION.any { t.startsWith(it) } }
            return rule || objAction || joined == "signout" || joined == "logout" || joined == "videoklic" || joined == "glasovniklic"
        }

        /** Paket aktivnega okna (ali je spredaj še Svenovo sejno okno). */
        fun activePackage(): String? = runCatching { instance?.rootInActiveWindow?.packageName?.toString() }.getOrNull()

        /** Napis za tap brez oznak izpisa read_screen (»[L] «, »[D] «, »▸ «, »[vnos] «). */
        fun cleanLabel(raw: String): String =
            raw.replace(Regex("^\\s*(?:(?:\\[(?:L|D|vnos|skrito polje)]|▸)\\s*)+"), "").trim()

        internal fun needsConfirm(text: String): Boolean =
            words(text).any { t -> t in CONFIRM_WORDS || CONFIRM_PREFIX.any { t.startsWith(it) } }

        private fun label(n: AccessibilityNodeInfo): String {
            val t = n.text?.toString()?.trim().orEmpty()
            return if (t.isNotEmpty()) t else n.contentDescription?.toString()?.trim().orEmpty()
        }

        /** Ena vrstica izpisa s položajem na zaslonu (za razvrstitev in stran mehurčka). */
        private class Line(val text: String, val top: Int, val left: Int, val right: Int, val order: Int, val plain: Boolean)

        /**
         * Vidno besedilo aktivnega okna od ZGORAJ NAVZDOL po položaju na zaslonu (ne po vrstnem
         * redu drevesa — ta se v seznamih po pomikanju lahko razlikuje). V pogovorih je
         * najnovejše spodaj. Ozke vrstice brez gumba dobijo oznako strani: [D] desno (praviloma
         * tvoja poslana sporočila), [L] levo (prejeta).
         */
        fun dump(maxChars: Int = 6000): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            val pkg = root.packageName?.toString().orEmpty()
            val app = runCatching {
                val pm = svc.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val raw = ArrayList<Line>()
            collect(root, raw, 0)
            val screen = Rect().also { root.getBoundsInScreen(it) }
            val width = screen.width().coerceAtLeast(1)
            val sorted = raw.withIndex().sortedWith(compareBy({ it.value.top }, { it.value.left }, { it.index })).map { it.value }
            val lines = ArrayList<String>()
            for (l in sorted) {
                val w = l.right - l.left
                val side = if (l.plain && w in 1 until (width * 0.8).toInt() && l.text.length > 1) {
                    val center = (l.left + l.right) / 2 - screen.left
                    if (center > width * 0.55) "[D] " else if (center < width * 0.45) "[L] " else ""
                } else ""
                val line = side + l.text
                if (line.isNotBlank() && lines.lastOrNull() != line) lines.add(line)
            }
            val sb = StringBuilder("Zaslon: $app")
            root.window?.title?.toString()?.takeIf { it.isNotBlank() && it != app }?.let { sb.append(" — ").append(it) }
            sb.append('\n')
            if (lines.isEmpty()) sb.append("(na zaslonu ni berljivega besedila)")
            else lines.forEach { sb.append(it).append('\n') }
            var out = sb.toString().trimEnd()
            if (out.length > maxChars) out = out.take(maxChars).trimEnd() + " … (izpis od vrha navzdol je predolg in je odrezan; za najnovejše se najprej pomakni na dno s scroll_screen 'dno')"
            return out
        }

        private fun add(out: MutableList<Line>, n: AccessibilityNodeInfo, line: String, plain: Boolean) {
            if (line.isBlank()) return
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(Line(line, r.top, r.left, r.right, out.size, plain))
        }

        /**
         * Vrne true, če je iz tega poddrevesa kaj izpisal. Poleg lastnega besedila (text)
         * zajame tudi opis (contentDescription): pri sporočilih v nekaterih klepetih je
         * celotno besedilo mehurčka le opis VRSTICE, katere podpogledi svojega besedila
         * nimajo — prav to je prej manjkalo (pošiljatelj in ura sta se zajela, telo pa ne).
         */
        private fun collect(n: AccessibilityNodeInfo, out: MutableList<Line>, depth: Int): Boolean {
            if (depth > 80 || !n.isVisibleToUser) return false
            if (n.isPassword) {
                add(out, n, "[skrito polje]", false)
                return true
            }
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            var emitted = false
            if (t.isNotEmpty()) {
                val clickable = n.isClickable || n.isCheckable
                add(out, n, if (n.isEditable) "[vnos] $t" else if (clickable) "▸ $t" else t, !n.isEditable && !clickable)
                emitted = true
            }
            // opis vrstice/mehurčka, ki NI enak že zajetemu besedilu (klepetalna sporočila);
            // pri vsebinsko praznih ikonah z opisom (childCount == 0) ravno tako koristi
            if (d.isNotEmpty() && d != t) {
                add(out, n, if (n.isClickable) "▸ $d" else d, !n.isClickable)
                emitted = true
            }
            var childEmitted = false
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (collect(c, out, depth + 1)) childEmitted = true
            }
            return emitted || childEmitted
        }

        private sealed class Find {
            class One(val node: AccessibilityNodeInfo) : Find()
            class Many(val labels: List<String>) : Find()
            object None : Find()
        }

        /**
         * Natančen napis ima prednost. Sicer delno ujemanje le po CELIH besedah (vsaka beseda
         * poizvedbe je beseda napisa ali začetek besede z vsaj 3 črkami) — »Ana« NE zadene
         * »Ivana«. Več različnih delnih zadetkov = dvoumno (nič se ne tapne).
         */
        private fun findByLabel(root: AccessibilityNodeInfo, query: String): Find {
            val q = Actions.matchKey(query)
            if (q.isEmpty()) return Find.None
            val qTok = q.split(' ')
            var exact: AccessibilityNodeInfo? = null
            val partial = LinkedHashMap<String, AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 60 || exact != null) return
                if (n.isVisibleToUser) {
                    val k = Actions.matchKey(label(n))
                    if (k.isNotEmpty()) {
                        if (k == q) {
                            exact = n
                            return
                        }
                        val kTok = k.split(' ')
                        if (qTok.all { qt -> kTok.any { it == qt || (qt.length >= 3 && it.startsWith(qt)) } }) {
                            partial.putIfAbsent(k, n)
                        }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            exact?.let { return Find.One(it) }
            return when (partial.size) {
                0 -> Find.None
                1 -> Find.One(partial.values.first())
                else -> Find.Many(partial.values.map { label(it) }.take(5))
            }
        }

        /** Tapne vidni element z danim napisom (oz. njegov najbližji klikljivi nadrejeni element). */
        fun tap(rawQuery: String, confirmed: Boolean = false): String {
            val svc = instance ?: return "Branje zaslona ni povezano."
            val query = cleanLabel(rawQuery)
            if (isHardForbidden(query))
                return "USTAVLJENO: gumba »$query« ne tapkam (brisanje, plačilo, naročilo, klic, blokiranje ali odjava) — tega Sven prek zaslona ne dela."
            if (needsConfirm(query) && !confirmed)
                return "ČAKAM POTRDITEV: »$query« je gumb za pošiljanje/potrditev. Uporabniku preberi, komu in kaj bo poslano, in šele po njegovem izrecnem 'pošlji' ali 'da' znova pokliči tap_item s potrjeno=true."
            val root = svc.rootInActiveWindow ?: return "Zaslon ni na voljo (zaklenjen ali prazen)."
            var n = when (val f = findByLabel(root, query)) {
                is Find.One -> f.node
                is Find.Many -> return "DVOUMNO: »$query« ustreza več elementom (${f.labels.joinToString("; ")}). Nič ni tapnjeno — uporabi napis natanko tako, kot je v izpisu read_screen."
                Find.None -> return "Na zaslonu ni elementa »$query«. Pokliči read_screen in uporabi napis natanko tako, kot je izpisan."
            }
            val found = label(n)
            var hops = 0
            while (!n.isClickable && hops < 8) {
                n = n.parent ?: break
                hops++
            }
            if (!n.isClickable) return "Element »$found« ni klikljiv."
            val own = label(n) // klikljivi nadrejeni element ima lahko svoj napis (npr. ikona »Izbriši«)
            if (isHardForbidden(found) || isHardForbidden(own))
                return "USTAVLJENO: element »$found« je gumb za brisanje/plačilo/klic/blokiranje — tega ne tapkam."
            val sendLike = needsConfirm(found) || needsConfirm(own)
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
            val finalText = when {
                !append || existing.isBlank() -> text
                existing.last().isWhitespace() -> existing + text
                else -> "$existing $text"
            }
            if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            var ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            var how = "vpisano"
            if (!ok) {
                // rezerva: odložišče + prilepi. Pri zamenjavi najprej označi vse (sicer bi se
                // besedilo podvojilo), po prilepljenju odložišče povrni (besedilo ne sme ostati tam).
                val cm = svc.getSystemService(ClipboardManager::class.java)
                val previous = runCatching { cm.primaryClip }.getOrNull()
                val pasteText = if (append && existing.isNotBlank()) (if (existing.last().isWhitespace()) text else " $text") else text
                if (append) {
                    val end = existing.length
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                    })
                } else if (existing.isNotEmpty()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existing.length)
                    })
                }
                cm.setPrimaryClip(ClipData.newPlainText("Sopotnik", pasteText))
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                runCatching { if (previous != null) cm.setPrimaryClip(previous) else cm.clearPrimaryClip() }
                how = "prilepljeno"
            }
            if (!ok) return "Besedila ni bilo mogoče vpisati v polje »$fieldName« — aplikacija tega ne dovoli."
            // izid pove, kar je v polju ZARES (ne, kar smo želeli vpisati)
            target.refresh()
            val now = target.text?.toString().orEmpty().takeIf { it != hint } ?: ""
            if (now.isNotEmpty() && now.trim() != finalText.trim()) {
                return "POZOR: v polju »$fieldName« je zdaj »$now« (ne natanko »$finalText«). Nič še ni poslano — uporabniku preberi, kaj je dejansko v polju, ali besedilo popravi s type_text (nacin zamenjaj)."
            }
            return "V polje »$fieldName« $how: »$finalText«. Nič še ni poslano — uporabniku preberi, kaj je vpisano in komu; po njegovem 'pošlji' tapni gumb za pošiljanje s tap_item(potrjeno=true)."
        }

        /** Pomik po največjem pomičnem seznamu: 'up' = proti starejšemu/začetku, 'down' = naprej. */
        /** Največji viden pomični element v aktivnem oknu (vsakič sveže — sklic po pomiku zastara). */
        private fun scrollable(): AccessibilityNodeInfo? {
            val root = instance?.rootInActiveWindow ?: return null
            var best: AccessibilityNodeInfo? = null
            var bestArea = 0
            val r = Rect()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 80) return
                if (n.isScrollable && n.isVisibleToUser) {
                    n.getBoundsInScreen(r)
                    val area = r.width() * r.height()
                    if (area > bestArea) { bestArea = area; best = n }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            return best
        }

        fun scroll(direction: String): String {
            if (instance == null) return "Branje zaslona ni povezano."
            val d = direction.lowercase().trim()
            val backward = d == "up" || d == "vrh" || d == "gor" || d == "navzgor"
            val toEnd = d == "dno" || d == "vrh" || d == "konec"
            val act = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (scrollable() == null) return "Na zaslonu ni pomičnega seznama."
            if (toEnd) {
                // sklic na element ob vsakem koraku poiščemo znova, sicer po prvem pomiku zastara.
                // Kliči IZVEN glavne niti (LiveSession) — zanka s premori traja do nekaj sekund.
                var steps = 0
                var stuck = false
                while (steps < 30) {
                    val node = scrollable() ?: break
                    if (!node.performAction(act)) { stuck = true; break }
                    steps++
                    try { Thread.sleep(140) } catch (_: InterruptedException) {}
                }
                val kje = if (backward) "vrha" else "dna"
                return when {
                    steps == 0 -> "Že na ${if (backward) "vrhu" else "dnu"}. Pokliči read_screen."
                    stuck -> "Pomaknjeno do $kje ($steps korakov). Pokliči read_screen — najnovejše je na koncu izpisa."
                    else -> "Pomaknjeno za $steps korakov, a $kje morda še NI — seznam je zelo dolg. Pokliči read_screen; po potrebi znova scroll_screen."
                }
            }
            val ok = scrollable()?.performAction(act) == true
            return if (ok) "Pomaknjeno ${if (backward) "navzgor (starejše)" else "navzdol"}. Pokliči read_screen."
            else "Pomik ni več mogoč — verjetno si na ${if (backward) "začetku" else "koncu"}."
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
