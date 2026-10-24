package si.sopotnik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.sopotnik.actions.Actions
import si.sopotnik.actions.ContactMatch

class ContactScoreTest {

    private fun sc(name: String, query: String) = Actions.scoreName(Actions.matchKey(name), Actions.matchKey(query))

    @Test
    fun exactAndSymbols() {
        assertEquals(100, sc("Urša*", "Urša"))
        assertEquals(100, sc("Urša", "urša"))
    }

    @Test
    fun slovenianCases() {
        for ((name, q) in listOf("Urša" to "Uršo", "Urša" to "Urši", "Marko" to "Marka", "Marko" to "Marku", "Peter" to "Petra", "Peter" to "Petru", "Ana" to "Ani"))
            assertTrue("$q -> $name: ${sc(name, q)}", sc(name, q) >= 80)
    }

    @Test
    fun noSubstringMatches() {
        assertEquals(0, sc("Ivana", "Ana"))
        assertEquals(0, sc("Milana", "Ana"))
        assertTrue(sc("Marko Novak", "Novak") < 100)
    }

    @Test
    fun uniqueness() {
        fun c(n: String, s: Int) = Actions.ScoredContact(ContactMatch(n, "1"), s)
        assertNull("dva popolna zadetka nista enolična", Actions.unique(listOf(c("Urša", 100), c("Urša*", 100))))
        assertNotNull("natančno ime z znakom proti enakemu ključu", Actions.unique(listOf(c("Urša*", 100), c("Urša", 97))))
        assertNotNull("»Ana« ob »Ana Novak«", Actions.unique(listOf(c("Ana", 100), c("Ana Novak", 95))))
        assertNull(Actions.unique(listOf(c("Ana Novak", 95), c("Ana Kovač", 90))))
        assertNull(Actions.unique(listOf(c("Ana", 75))))
        assertNotNull(Actions.unique(listOf(c("Ana", 85))))
    }
}
