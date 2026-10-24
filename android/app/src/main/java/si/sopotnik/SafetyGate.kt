package si.sopotnik

import si.sopotnik.actions.Action

enum class Tier { GREEN, YELLOW, RED }

/**
 * Deterministična klasifikacija dejanj (PLAN.md, razdelek 6).
 * Odloča izključno ta tabela — nikoli AI. AI lahko stopnjo le zviša (v kasnejši fazi).
 */
object SafetyGate {

    fun classify(action: Action, prefs: Prefs): Tier = when (action) {
        is Action.Call -> if (prefs.confirmCalls) Tier.YELLOW else Tier.GREEN
        is Action.OpenApp,
        is Action.MediaPlay,
        is Action.MediaPlaySearch,
        is Action.MediaPause,
        is Action.MediaNext,
        is Action.MediaPrev,
        is Action.Navigate,
        is Action.VolumeUp,
        is Action.VolumeDown,
        is Action.VolumeSet,
        is Action.Torch,
        is Action.ReadNotifications -> Tier.GREEN
    }

    /** Pritrditev: vsaj ena od teh besed … (»v redu« se pred preverbo združi v »vredu«) */
    private val YES = setOf(
        "ja", "jap", "da", "seveda", "potrdi", "potrjujem", "poslji", "posli", "posljes", "posljite",
        "poklici", "poklices", "poklicite", "klici", "prav", "ok", "okej", "okey", "okay", "lahko",
        "dajmo", "daj", "dej", "velja", "tocno", "vredu", "yes"
    )

    /** … in poleg njih kvečjemu še mašila. Karkoli drugega (drugo ime, nov ukaz) = nejasno. */
    private val FILLER = setOf(
        "kar", "ga", "jo", "mu", "ji", "jih", "mi", "me", "je", "zdaj", "takoj", "res", "prosim", "hvala",
        "tako", "super", "pa", "ze", "sporocilo", "odgovor", "sms", "to"
    )

    /** Zanikanje ali odlog KJERKOLI v izjavi — nikoli ni pritrditev. */
    private val NO = setOf(
        "ne", "nikar", "nisem", "noce", "nocem", "pocakaj", "pocakajmo", "kasneje", "pozneje", "pusti", "raje",
        "preklici", "prekini", "stop", "nehaj", "ustavi", "popravi", "spremeni", "mogoce", "morda",
        "minuto", "minutko", "sekundo", "trenutek"
    )

    /** Glagoli dejanja: »pošlji« ne potrdi klica in »pokliči« ne potrdi pošiljanja. */
    private val SEND_WORDS = setOf("poslji", "posli", "posljes", "posljite", "sporocilo", "sms", "odgovor")
    private val CALL_WORDS = setOf("poklici", "poklices", "poklicite", "klici")

    enum class Kind { CALL, SEND, OTHER }

    /**
     * Lokalna (ne-AI) razpoznava glasovne potrditve iz normaliziranega besedila.
     * true  = kratka, nedvoumna pritrditev (»ja«, »da, pošlji«, »ok, kar pokliči«, »v redu, hvala«);
     * false = zanikanje ali odlog kjerkoli (»ja, ne vem«, »prav, ne kliči«, »pokliči raje …«);
     * null  = vse ostalo (daljša izjava, drugo ime) — klicatelj NE izvede ničesar.
     * [ignoreStems]: korenine imena prejemnika, ki smejo biti v pritrditvi (»ja, Urši«) — a nikoli
     * ne »pojedo« pritrdilnih besed (ime Jan → korenina »ja«).
     */
    fun parseConfirmation(normalizedText: String, ignoreStems: List<String> = emptyList(), kind: Kind = Kind.OTHER): Boolean? {
        val raw = normalizedText.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
        val words = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            if (raw[i] == "v" && i + 1 < raw.size && raw[i + 1] == "redu") { words.add("vredu"); i += 2 } else { words.add(raw[i]); i++ }
        }
        if (words.isEmpty()) return null
        if (words.any { it in NO }) return false // zanikanje velja tudi v vprašanju (»Ne?«)
        if ('?' in normalizedText) return null // vprašanje (»Je to v redu?«) ni pritrditev
        val rest = words.filterNot { w -> w !in YES && w !in FILLER && ignoreStems.any { s -> s.length >= 2 && w.startsWith(s) } }
        if (rest.isEmpty() || rest.size > 5) return null
        if (rest.none { it in YES }) return null
        if (kind == Kind.CALL && rest.any { it in SEND_WORDS }) return null
        if (kind == Kind.SEND && rest.any { it in CALL_WORDS }) return null
        return if (rest.all { it in YES || it in FILLER }) true else null
    }

    // ---- imena v Svenovem govoru (deterministična preverba, da je prejemnika povedal) ----

    /** Slovenske sklonske končnice, ki jih dovolimo za podstavo imena. */
    private val ENDINGS = setOf(
        "", "a", "e", "i", "o", "u", "om", "em", "ju", "ja", "jem", "jo", "ji", "ta", "te", "tu", "tom", "ovi", "evi", "ova", "eva"
    )

    /** Korenina dela imena za izločitev iz pritrditve; 3-črkovna imena na soglasnik (Jan, Dan, Lah) ostanejo cela. */
    fun nameStem(part: String): String = when {
        part.length <= 3 -> if (part.last() in "aeiou") part.take(2) else part
        else -> part.take(maxOf(3, part.length - 2))
    }

    /**
     * Ali je beseda [w] oblika dela imena [part]: podstava + sklonska končnica (Urša → Urši/Uršo,
     * Marko → Marku, Peter → Petra/Petru, Novak → Novaku, Jure → Juretu). Navadne besede s
     * podobno predpono (»novo« za Novak, »to« za Tom, »marca« za Marko) NE štejejo.
     */
    fun isNameForm(w: String, part: String): Boolean {
        if (part.isEmpty() || w.isEmpty()) return false
        val bases = ArrayList<String>(3)
        bases.add(part)
        if (part.last() in "aeo" && part.length >= 3) bases.add(part.dropLast(1))
        if (part.length >= 5 && part[part.length - 2] == 'e' && part.last() !in "aeiou") bases.add(part.dropLast(2) + part.last())
        // okrnjena podstava sama (»kaj« iz Kaja) ni oblika imena — potrebuje končnico
        return bases.any { b -> w.startsWith(b) && w.substring(b.length) in ENDINGS && (b == part || w != b) }
    }
}
