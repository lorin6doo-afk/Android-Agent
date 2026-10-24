package si.sopotnik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.sopotnik.actions.Action

class LockPolicyTest {

    private val off = LockCfg(callsWhenLocked = false, navWhenLocked = false, home = "Glavna cesta 5, Mozirje", work = "Tovarniška 2, Celje")
    private val on = off.copy(callsWhenLocked = true, navWhenLocked = true)

    @Test
    fun setsAreDisjoint() {
        val all = listOf(LockPolicy.ALLOW_TOOLS, LockPolicy.FORBID_TOOLS, LockPolicy.CONDITIONAL_TOOLS, LockPolicy.UNLOCK_TOOLS)
        for (i in all.indices) for (j in i + 1 until all.size) {
            assertTrue("prekrivanje: ${all[i] intersect all[j]}", (all[i] intersect all[j]).isEmpty())
        }
    }

    @Test
    fun unlockedAllowsEverything() {
        for (t in LockPolicy.ALLOW_TOOLS + LockPolicy.FORBID_TOOLS + LockPolicy.UNLOCK_TOOLS + LockPolicy.CONDITIONAL_TOOLS) {
            assertNull(t, LockPolicy.deny(false, LockPolicy.forTool(t, "domov", off)))
        }
    }

    @Test
    fun lockedRules() {
        for (t in LockPolicy.ALLOW_TOOLS) assertEquals(t, LockRule.ALLOW, LockPolicy.forTool(t, null, off))
        for (t in LockPolicy.FORBID_TOOLS) assertEquals(t, LockRule.FORBID, LockPolicy.forTool(t, null, on))
        for (t in LockPolicy.UNLOCK_TOOLS) assertEquals(t, LockRule.UNLOCK, LockPolicy.forTool(t, null, on))
        assertEquals(LockRule.UNLOCK, LockPolicy.forTool("novo_neznano_orodje", null, on))
        assertTrue(LockPolicy.deny(true, LockRule.UNLOCK)!!.startsWith("ZAKLENJENO"))
        assertTrue(LockPolicy.deny(true, LockRule.FORBID)!!.startsWith("PREPOVEDANO"))
    }

    @Test
    fun callsFollowSwitch() {
        assertEquals(LockRule.UNLOCK, LockPolicy.forTool("call_contact", null, off))
        assertEquals(LockRule.UNLOCK, LockPolicy.forTool("find_contact", null, off))
        assertEquals(LockRule.ALLOW, LockPolicy.forTool("call_contact", null, on))
        assertEquals(LockRule.ALLOW, LockPolicy.forAction(Action.Call("Ana"), on))
        assertEquals(LockRule.UNLOCK, LockPolicy.forAction(Action.Call("Ana"), off))
    }

    @Test
    fun navigationPrivateDestinations() {
        for (d in listOf("domov", "Domov.", "domov prosim", "home", "moj dom", "k mami", "h Marku", "v službo",
            "na delo", "službi", "do pisarne", "Glavna cesta", "Mozirje", "Celje center", "", "  ")) {
            assertTrue("zaseben: '$d'", LockPolicy.isPrivateDest(d, on))
            assertEquals("'$d'", LockRule.UNLOCK, LockPolicy.forTool("navigate", d, on))
        }
        for (d in listOf("Ljubljana", "Domžale", "letališče Brnik", "Koper, Pristaniška ulica")) {
            assertFalse("javen: '$d'", LockPolicy.isPrivateDest(d, on))
            assertEquals("'$d'", LockRule.ALLOW, LockPolicy.forTool("navigate", d, on))
            assertEquals("'$d' brez stikala", LockRule.UNLOCK, LockPolicy.forTool("navigate", d, off))
        }
    }

    @Test
    fun actionsClassic() {
        assertEquals(LockRule.ALLOW, LockPolicy.forAction(Action.MediaPause, off))
        assertEquals(LockRule.ALLOW, LockPolicy.forAction(Action.Torch(true), off))
        assertEquals(LockRule.UNLOCK, LockPolicy.forAction(Action.OpenApp("WhatsApp"), on))
        assertEquals(LockRule.UNLOCK, LockPolicy.forAction(Action.ReadNotifications, on))
        assertEquals(LockRule.UNLOCK, LockPolicy.forAction(Action.Navigate("domov"), on))
        assertEquals(LockRule.ALLOW, LockPolicy.forAction(Action.Navigate("Ljubljana"), on))
    }
}
