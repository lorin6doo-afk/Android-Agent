package si.sopotnik

import android.app.KeyguardManager
import android.content.Context
import si.sopotnik.actions.Action

enum class LockRule { ALLOW, UNLOCK, FORBID }

/**
 * @param callsWhenLocked iskanje stika + klic ob zaklenjenem telefonu (privzeto izklopljeno)
 * @param navWhenLocked navigacija do izgovorjenega javnega cilja ob zaklepu (privzeto izklopljeno)
 */
data class LockCfg(
    val callsWhenLocked: Boolean,
    val navWhenLocked: Boolean,
    val home: String,
    val work: String,
)

/**
 * Kaj sme Sven, ko je telefon ZAKLENJEN (faza 3: seja se lahko sproži čez zaklenjen zaslon —
 * tudi ko telefon drži nekdo drug). Deterministična tabela, neodvisna od modela in od
 * zelene/rumene/rdeče stopnje (SafetyGate) — veljata obe. Vse, česar tabela ne pozna, zahteva
 * odklep. Ista tabela velja na telefonu (LiveSession, SessionService); gateway ima še
 * lasten, strožji seznam dovoljenih (realtime.mjs OK_WHEN_LOCKED).
 */
object LockPolicy {

    /** Orodja, ki tečejo na gatewayu (telefon jih ne vidi); zaporo zanje izvaja gateway. */
    val SERVER_TOOLS = setOf("get_weather", "ask_brain", "poisci_zapiske", "preberi_zapisek", "shrani_zapisek")

    /** Ob zaklepu dovoljeno brez pogojev: brez osebnih podatkov in brez komunikacije navzven. */
    val ALLOW_TOOLS = setOf("get_time", "get_weather", "media_control", "play_music", "set_volume", "torch", "end_conversation")

    /** Ob zaklepu nikoli: dostopnost bi upravljala zaklenjen zaslon ali naše okno. */
    val FORBID_TOOLS = setOf("read_screen", "tap_item", "type_text", "scroll_screen", "go_back")

    /** Ob zaklepu dovoljeno le pod pogojem (stikali v nastavitvah, javni cilj). */
    val CONDITIONAL_TOOLS = setOf("navigate", "find_contact", "call_contact")

    /**
     * Ob zaklepu zahtevajo odklep (osebni podatki ali komunikacija navzven). Seznam je izrečen
     * zaradi testa (vsako orodje mora imeti NAMERNO pravilo); neznano orodje je prav tako UNLOCK.
     */
    val UNLOCK_TOOLS = setOf(
        "read_notifications", "read_notification", "open_notification", "send_reply",
        "compose_message", "send_message", "open_conversation", "open_app",
        "ask_brain", "poisci_zapiske", "preberi_zapisek", "shrani_zapisek"
    )

    /** Klasični AI obrat (Codex) ob zaklepu. */
    val AI_TURN = LockRule.UNLOCK

    private fun key(s: String): List<String> =
        IntentRouter.normalize(s)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

    /** Cele besede (»Domžale« NI »dom«). */
    private val PRIVATE_WORDS = setOf(
        "dom", "doma", "domov", "domu", "domaci", "domace", "domacega", "home", "hisa", "hiso",
        "delo", "work", "firma", "firmo", "firme",
        "mami", "mama", "mamo", "ati", "atiju", "oce", "ocetu", "babi", "dedi", "dedku",
        "sestri", "sestro", "bratu", "brata", "punci", "fantu", "zeni", "mozu", "starsem", "starsi"
    )

    /** Predpone (sklanjatve): služba/službo/v službi, pisarna, prijatelj(ica). */
    private val PRIVATE_STEMS = listOf("sluzb", "pisarn", "prijatelj")

    /**
     * Cilj, ki bi tujcu razkril, kje živi ali dela lastnik (ali do koga): domov/služba,
     * shranjena naslova, sorodniki, »k/h <osebi>«. Preverjamo po besedah, brez ločil.
     */
    fun isPrivateDest(raw: String, cfg: LockCfg): Boolean {
        val t = key(raw)
        if (t.isEmpty()) return true
        if (t.first() == "k" || t.first() == "h") return true
        if (t.any { w -> w in PRIVATE_WORDS || PRIVATE_STEMS.any { w.startsWith(it) } }) return true
        fun covers(addr: String): Boolean {
            val a = key(addr).filter { it.length > 2 }
            return a.isNotEmpty() && a.any { it in t }
        }
        return covers(cfg.home) || covers(cfg.work)
    }

    fun forTool(name: String, destination: String?, cfg: LockCfg): LockRule = when {
        name in ALLOW_TOOLS -> LockRule.ALLOW
        name in FORBID_TOOLS -> LockRule.FORBID
        name == "navigate" ->
            if (cfg.navWhenLocked && !isPrivateDest(destination.orEmpty(), cfg)) LockRule.ALLOW else LockRule.UNLOCK
        name == "find_contact" || name == "call_contact" ->
            if (cfg.callsWhenLocked) LockRule.ALLOW else LockRule.UNLOCK
        else -> LockRule.UNLOCK // obvestila, sporočila, SMS, aplikacije, zapiski, možgani, NOVA orodja
    }

    fun forAction(a: Action, cfg: LockCfg): LockRule = when (a) {
        is Action.MediaPlay, is Action.MediaPause, is Action.MediaNext, is Action.MediaPrev,
        is Action.MediaPlaySearch, is Action.VolumeUp, is Action.VolumeDown,
        is Action.VolumeSet, is Action.Torch -> LockRule.ALLOW
        is Action.Navigate ->
            if (cfg.navWhenLocked && !isPrivateDest(a.dest, cfg)) LockRule.ALLOW else LockRule.UNLOCK
        is Action.Call -> if (cfg.callsWhenLocked) LockRule.ALLOW else LockRule.UNLOCK
        is Action.OpenApp, is Action.ReadNotifications -> LockRule.UNLOCK
    }

    /** null = dovoljeno; sicer izid za model (Live) — začne se z ZAKLENJENO oz. PREPOVEDANO. */
    fun deny(locked: Boolean, rule: LockRule): String? = when {
        !locked || rule == LockRule.ALLOW -> null
        rule == LockRule.FORBID -> "PREPOVEDANO: ob zaklenjenem telefonu tega ne smem. Povej to uporabniku."
        else -> "ZAKLENJENO: telefon je zaklenjen, zato tega ne izvedem. Povej uporabniku, naj ga odklene " +
            "(gumb Odkleni na zaslonu ali prstni odtis) in zahtevo ponovi. Ne poskušaj z drugim orodjem."
    }
}

object LockState {
    /**
     * isDeviceLocked: potrebna je avtentikacija (PIN/vzorec/biometrija). Zaupanja vredna BT
     * naprava ali kraj (Smart Lock) šteje kot odklenjeno; brez zaklepa zaslona nikoli zaklenjeno.
     */
    fun locked(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked }.getOrDefault(true)

    fun secure(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure }.getOrDefault(false)
}
