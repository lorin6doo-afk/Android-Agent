package si.sopotnik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRulesTest {

    @Test
    fun forbidden() {
        for (s in listOf("Izbriši", "Izbriši za vse", "Delete chat", "Plačaj", "Plačilo", "Kupi", "Buy now", "Pokliči",
                "Videoklic", "Glasovni klic", "Voice call", "PayPal", "Blokiraj", "Odjava", "Sign out", "Log out", "Prijavi",
                "Oddaj naročilo", "Zaključi naročilo", "Potrdi naročilo", "Naročilo", "Pobriši vse", "Continue to payment",
                "Payment", "Plačati", "Plačam", "Kupiti", "Prekliči naročnino", "Cancel subscription", "Make payment",
                "Complete payment", "Doplačaj", "Podaljšaj naročnino", "Kliči"))
            assertTrue(s, ScreenReader.isHardForbidden(s))
    }

    @Test
    fun allowedChatsAndTabs() {
        for (s in listOf("Ana Jaklič", "Miklič Janez", "Nakupovalni seznam", "Naročnine", "Subscriptions", "Marketplace",
                "Skupina", "Urša*", "Klepeti", "Replay", "Display", "Prekliči", "Klici", "Zgodovina klicev", "Calls"))
            assertFalse(s, ScreenReader.isHardForbidden(s))
    }

    @Test
    fun confirm() {
        for (s in listOf("Pošlji", "Pošlji SMS", "Send", "Send message", "Potrdi", "Objavi", "OK"))
            assertTrue(s, ScreenReader.needsConfirm(s))
        for (s in listOf("Urša*", "Klepeti", "Nov klepet"))
            assertFalse(s, ScreenReader.needsConfirm(s))
    }

    @Test
    fun cleanLabel() {
        assertEquals("Urša Novak", ScreenReader.cleanLabel("[L] Urša Novak"))
        assertEquals("Pošlji SMS", ScreenReader.cleanLabel("▸ Pošlji SMS"))
        assertEquals("Napiši sporočilo", ScreenReader.cleanLabel("[vnos] Napiši sporočilo"))
        assertEquals("Ana", ScreenReader.cleanLabel("Ana"))
    }
}
