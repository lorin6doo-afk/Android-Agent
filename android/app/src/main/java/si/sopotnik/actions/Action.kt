package si.sopotnik.actions

/** Dejanja, ki jih izvaja telefon. Klasificira jih SafetyGate, izvaja Actions. */
sealed class Action {
    data class Call(val query: String) : Action()
    data class OpenApp(val query: String) : Action()
    object MediaPlay : Action()
    data class MediaPlaySearch(val query: String, val app: String? = null) : Action()
    object MediaPause : Action()
    object MediaNext : Action()
    object MediaPrev : Action()
    data class Navigate(val dest: String) : Action()
    object VolumeUp : Action()
    object VolumeDown : Action()
    data class VolumeSet(val percent: Int) : Action()
    data class Torch(val on: Boolean) : Action()
}

data class ContactMatch(val name: String, val number: String)
