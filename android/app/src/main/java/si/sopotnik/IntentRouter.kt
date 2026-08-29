package si.sopotnik

import si.sopotnik.actions.Action
import java.util.Calendar

sealed class Route {
    data class Do(val action: Action) : Route()
    data class Answer(val say: String) : Route()
    data class Ai(val text: String) : Route()
    object End : Route()
}

/**
 * Lokalna slovenska gramatika za hitre ukaze; vse neprepoznano gre na AI.
 * Normalizacija ohranja dolžino niza (č->c, š->s, ž->z), zato lahko imena in cilje
 * izrežemo iz izvirnega besedila z ohranjenimi šumniki.
 */
class IntentRouter(private val prefs: Prefs) {

    fun parse(original: String): Route {
        val fullNorm = normalize(original)
        var offset = Regex("""^\s*sven[,!]?\s+""").find(fullNorm)?.value?.length ?: 0
        val sub = fullNorm.substring(offset)
        val lead = sub.indexOfFirst { !it.isWhitespace() }
        if (lead > 0) offset += lead
        val n = sub.trim().trimEnd('.', '!', '?')
        if (n.isEmpty()) return Route.End

        fun orig(range: IntRange): String =
            original.substring(offset + range.first, offset + range.last + 1)
                .trim().trimEnd('.', '!', '?')
                .removeSuffix(" prosim").trim()

        if (Regex("""^(stop|prekini|konec|dovolj|hvala to je vse|hvala)$""").matches(n)) {
            return Route.End
        }

        if (n.contains("koliko je ura") || n.contains("kolko je ura")) {
            val c = Calendar.getInstance()
            return Route.Answer("Ura je ${c.get(Calendar.HOUR_OF_DAY)} in ${c.get(Calendar.MINUTE)} minut.")
        }

        Regex("""^(poklici|klici)\s+(.+)$""").find(n)?.let { m ->
            return Route.Do(Action.Call(orig(m.groups[2]!!.range)))
        }

        if (Regex("""^(preberi\s+)?(obvestila|sporocila)$""").matches(n) || n == "kaj je novega") {
            return Route.Do(Action.ReadNotifications)
        }

        if (Regex("""^(pavza|pavziraj|ustavi glasbo|ustavi predvajanje)$""").matches(n)) return Route.Do(Action.MediaPause)
        if (Regex("""^(naprej|naslednja( pesem| skladba)?|preskoci)$""").matches(n)) return Route.Do(Action.MediaNext)
        if (Regex("""^(nazaj|prejsnja( pesem| skladba)?)$""").matches(n)) return Route.Do(Action.MediaPrev)
        if (Regex("""^(predvajaj|nadaljuj|glasba)( glasbo| predvajanje)?$""").matches(n)) return Route.Do(Action.MediaPlay)

        Regex("""^(predvajaj|zavrti)(\s+mi)?\s+(.+)$""").find(n)?.let { m ->
            val g = m.groups[3]!!
            var qn = m.groupValues[3]
            var qo = original.substring(offset + g.range.first, offset + g.range.last + 1)
            var app: String? = null

            Regex("""\s+na\s+(youtube\w*|jutjub\w*|jutub\w*)$""").find(qn)?.let { t ->
                app = "youtube"
                qo = qo.substring(0, t.range.first)
                qn = qn.substring(0, t.range.first)
            }
            if (app == null) Regex("""\s+na\s+spotify\w*$""").find(qn)?.let { t ->
                app = "spotify"
                qo = qo.substring(0, t.range.first)
                qn = qn.substring(0, t.range.first)
            }

            val filler = Regex("""^(glasbo|glas bo|glasba|muziko|pesem|pesmico|skladbo|skupino|skupine|skupina|izvajalca)\s+""")
            while (true) {
                val f = filler.find(qn) ?: break
                qn = qn.substring(f.range.last + 1)
                qo = qo.substring(f.range.last + 1)
            }
            qn = qn.trim().removeSuffix(" prosim").trim()
            qo = qo.trim().trimEnd('.', '!', '?').removeSuffix(" prosim").trim()

            return if (qn.isEmpty() || qn in setOf("glasbo", "glas bo", "glasba", "muziko")) {
                if (app == "youtube") Route.Do(Action.MediaPlaySearch("glasba", "youtube"))
                else Route.Do(Action.MediaPlay)
            } else {
                Route.Do(Action.MediaPlaySearch(qo, app))
            }
        }

        Regex("""^glasnost na (\d{1,3})( odstotkov| procentov)?$""").find(n)?.let { m ->
            val pct = m.groupValues[1].toInt().coerceIn(0, 100)
            return Route.Do(Action.VolumeSet(pct))
        }
        if (Regex("""^(glasneje|povecaj glasnost|bolj na ?glas)$""").matches(n)) return Route.Do(Action.VolumeUp)
        if (Regex("""^(tise|tisje|zmanjsaj glasnost|bolj potiho)$""").matches(n)) return Route.Do(Action.VolumeDown)

        Regex("""^(prizgi|vklopi)\s+(svetilko|luc|lucko)$""").find(n)?.let {
            return Route.Do(Action.Torch(true))
        }
        Regex("""^(ugasni|izklopi)\s+(svetilko|luc|lucko)$""").find(n)?.let {
            return Route.Do(Action.Torch(false))
        }

        Regex("""^(navigiraj|pelji me|vodi me|zeni navigacijo|zazeni navigacijo)( (do|v|na|proti))?\s+(.+)$""").find(n)?.let { m ->
            val destNorm = m.groupValues[4].trim()
            val dest = when {
                destNorm in setOf("domov", "domu", "dom") ->
                    prefs.homeAddress.ifEmpty { return Route.Answer("Najprej nastavi domači naslov v nastavitvah.") }
                destNorm in setOf("sluzbo", "sluzba", "delo", "v sluzbo", "na delo") ->
                    prefs.workAddress.ifEmpty { return Route.Answer("Najprej nastavi naslov službe v nastavitvah.") }
                else -> orig(m.groups[4]!!.range)
            }
            return Route.Do(Action.Navigate(dest))
        }

        Regex("""^(odpri|zazeni)\s+(.+)$""").find(n)?.let { m ->
            return Route.Do(Action.OpenApp(orig(m.groups[2]!!.range)))
        }

        return Route.Ai(original)
    }

    companion object {
        /** Mala pisava + odstranjeni šumniki, 1:1 po znakih (dolžina se ohrani). */
        fun normalize(s: String): String {
            val lower = s.lowercase()
            val out = CharArray(lower.length)
            for (i in lower.indices) {
                out[i] = when (lower[i]) {
                    'č' -> 'c'; 'š' -> 's'; 'ž' -> 'z'; 'ć' -> 'c'; 'đ' -> 'd'
                    else -> lower[i]
                }
            }
            return String(out)
        }
    }
}
