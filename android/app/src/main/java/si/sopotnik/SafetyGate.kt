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
        is Action.Torch -> Tier.GREEN
    }

    private val yes = Regex("^(ja|da|seveda|potrdi|poslji|poklici|prav|ok|okej|okey|lahko|dajmo|daj)\\b.*")
    private val no = Regex("^(ne|nikar|prekini|preklici|stop|pocakaj|raje ne)\\b.*")

    /** Lokalna (ne-AI) razpoznava glasovne potrditve. */
    fun parseConfirmation(normalizedText: String): Boolean? = when {
        yes.matches(normalizedText) -> true
        no.matches(normalizedText) -> false
        else -> null
    }
}
