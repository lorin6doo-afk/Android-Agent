package si.sopotnik

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyGateTest {

    private fun p(s: String, stems: List<String> = emptyList()) =
        SafetyGate.parseConfirmation(IntentRouter.normalize(s).trim(), stems)

    @Test
    fun yes() {
        for (s in listOf("Ja.", "da", "Da, pošlji.", "Pošlji!", "Seveda", "OK, kar pokliči", "Ja, pokliči jo.", "pošlji ga", "Velja", "okej"))
            assertEquals(s, true, p(s))
        assertEquals(true, p("Ja, Urši pošlji", listOf("urs")))
        for (s in listOf("Pošlji, ja, hvala", "Ja, hvala", "V redu", "Ja, v redu", "Ja, lahko pošlješ", "Lahko ga pokličeš", "Ja, pokliči mi jo", "Dej", "Jap", "Super, pošlji", "Ja, tako je"))
            assertEquals(s, true, p(s))
        // ime Jan (korenina »ja«) ne sme pojesti pritrditve
        assertEquals(true, p("Ja.", listOf("ja", "nov")))
        assertEquals(true, p("Ja, Janu pošlji", listOf("ja", "nov")))
        assertEquals(true, p("Da", listOf("da")))
    }

    @Test
    fun no() {
        for (s in listOf("Ne?", "Ne, zakaj bi to poslal?", "Ne.", "ne pošlji", "Ja, ne vem", "Prav, ne kliči", "Pokliči raje Marka", "Da ne", "Počakaj", "stop", "popravi besedilo", "nikar"))
            assertEquals(s, false, p(s))
    }

    @Test
    fun unclear() {
        for (s in listOf("", "Pošlji Marku", "Kaj si rekel?", "Ja, in še to dodaj, da zamujam", "mhm", "Urša", "Ja, vem, pošlji", "aha"))
            assertEquals(s, null, p(s))
        assertEquals(null, p("Ja, Marku pošlji", listOf("urs")))
        // vprašanje ni pritrditev
        for (s in listOf("Je to v redu?", "Je to ok?", "Ja?", "Pošljem?"))
            assertEquals(s, null, p(s))
        // drugo ime s podobno korenino (prejemnik Jan; korenina ostane cela »jan«)
        assertEquals(null, p("Ja, Jakobu pošlji", listOf(SafetyGate.nameStem("jan"))))
        assertEquals(true, p("Lahko", listOf(SafetyGate.nameStem("lah"))))
    }

    @Test
    fun kindMustMatch() {
        fun k(s: String, kind: SafetyGate.Kind) = SafetyGate.parseConfirmation(IntentRouter.normalize(s).trim(), emptyList(), kind)
        assertEquals(null, k("Lahko mu pošlješ SMS", SafetyGate.Kind.CALL))
        assertEquals(true, k("Ja, pokliči ga", SafetyGate.Kind.CALL))
        assertEquals(null, k("Ja, pokliči ga", SafetyGate.Kind.SEND))
        assertEquals(true, k("Ja, pošlji", SafetyGate.Kind.SEND))
    }

    @Test
    fun nameForms() {
        fun f(w: String, part: String) = SafetyGate.isNameForm(w, part)
        for ((w, part) in listOf("ursi" to "ursa", "urso" to "ursa", "marku" to "marko", "petra" to "peter", "petru" to "peter",
                "novaku" to "novak", "juretu" to "jure", "ani" to "ana", "tomu" to "tom", "jana" to "jan", "mami" to "mama"))
            assertEquals("$w ~ $part", true, f(w, part))
        for ((w, part) in listOf("novo" to "novak", "to" to "tom", "ga" to "gal", "marca" to "marko", "petih" to "peter",
                "kaj" to "kaja", "evrov" to "eva", "and" to "ana", "ja" to "jan", "doma" to "domen"))
            assertEquals("$w ~ $part", false, f(w, part))
    }
}
