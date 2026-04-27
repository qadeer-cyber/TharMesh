package com.tharmesh.ui.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stage 8.4 \u2014 codec round-trip for [LegalDocBodies.fromKeyOrNull].
 *
 * The legal viewer activity passes the doc identity across an Intent
 * extra as the enum's `name` string. This test pins:
 *  - every enum value round-trips through [name] \u2192 fromKeyOrNull;
 *  - unknown / blank / null keys resolve to null (so the activity
 *    finishes gracefully instead of crashing);
 *  - the enum still has the three documented entries (TERMS,
 *    PRIVACY, DISCLAIMER) so a later refactor can't quietly remove
 *    a doc surface.
 */
class LegalDocBodiesTest {

    @Test
    fun `every enum value round-trips through fromKeyOrNull`() {
        for (doc in LegalDocBodies.values()) {
            val resolved = LegalDocBodies.fromKeyOrNull(doc.name)
            assertNotNull("enum $doc must round-trip", resolved)
            assertEquals(doc, resolved)
        }
    }

    @Test
    fun `unknown key resolves to null`() {
        assertNull(LegalDocBodies.fromKeyOrNull("DOES_NOT_EXIST"))
        assertNull(LegalDocBodies.fromKeyOrNull("terms"))         // case-sensitive
        assertNull(LegalDocBodies.fromKeyOrNull("ABOUT"))         // 8.4 dropped ABOUT
    }

    @Test
    fun `blank and null keys resolve to null`() {
        assertNull(LegalDocBodies.fromKeyOrNull(null))
        assertNull(LegalDocBodies.fromKeyOrNull(""))
        assertNull(LegalDocBodies.fromKeyOrNull("   "))
    }

    @Test
    fun `documented doc set is exactly TERMS PRIVACY DISCLAIMER`() {
        // If a future refactor adds or removes a legal doc, the test
        // fails so the change is visible in code review.
        assertEquals(
            setOf("TERMS", "PRIVACY", "DISCLAIMER"),
            LegalDocBodies.values().map { it.name }.toSet()
        )
    }
}
