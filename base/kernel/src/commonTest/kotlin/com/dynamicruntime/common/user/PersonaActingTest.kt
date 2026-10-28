package com.dynamicruntime.common.user

import com.dynamicruntime.common.http.request.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A persona beside the roles a user acts with (issue #1450): a persona names which of a person's users this is and
 * is frozen at creation, while roles change -- so where the two part, the persona's label says the access the user
 * has, on JVM and JS alike.
 */
class PersonaActingTest {
    @Test
    fun accessIsTheHighestRungWithAnEditorBesideARungBelowAdmin() {
        assertEquals(ROLE.admin, PERSONA.accessOf(listOf(ROLE.user, ROLE.admin, ROLE.allClients)))
        // An administrator implies editor; an operator holds no part of it, nor an editor of operator.
        assertEquals(ROLE.admin, PERSONA.accessOf(listOf(ROLE.user, ROLE.admin, ROLE.editor)))
        assertEquals(PERSONA.operatorAndEditor, PERSONA.accessOf(listOf(ROLE.user, ROLE.operator, ROLE.editor)))
        assertEquals(ROLE.editor, PERSONA.accessOf(listOf(ROLE.user, ROLE.editor)))
        assertEquals(ROLE.user, PERSONA.accessOf(listOf(ROLE.user)))
        // No rung and no editor: what a disabled or stranded user acts with. A capability alone is no access.
        assertEquals(PERSONA.noAccess, PERSONA.accessOf(emptyList()))
        assertEquals(PERSONA.noAccess, PERSONA.accessOf(listOf(ROLE.allClients)))
        assertEquals("Administrator", PERSONA.accessLabel(ROLE.admin))
        assertEquals("Operator and editor", PERSONA.accessLabel(PERSONA.operatorAndEditor))
        assertEquals("No access", PERSONA.accessPhrase(PERSONA.noAccess))
        assertEquals("User access", PERSONA.accessPhrase(ROLE.user))
    }

    @Test
    fun aUserActsAsSomethingElseOnlyWhereItsRolesLeftItsPersonaBehind() {
        // As made: nothing to say.
        for (def in PERSONA.defs) assertNull(PERSONA.actsAs(def.name, def.defaultRoles))
        // A capability is no access level: an administrator of every client is still an admin.
        assertNull(PERSONA.actsAs(PERSONA.admin, listOf(ROLE.user, ROLE.admin, ROLE.allClients)))
        // Promoted, demoted, or given a level no persona names.
        assertEquals(ROLE.admin, PERSONA.actsAs(PERSONA.member, listOf(ROLE.user, ROLE.admin)))
        assertEquals(ROLE.user, PERSONA.actsAs(PERSONA.admin, listOf(ROLE.user)))
        assertEquals(ROLE.operator, PERSONA.actsAs(PERSONA.member, listOf(ROLE.user, ROLE.operator)))
        assertEquals(ROLE.editor, PERSONA.actsAs(PERSONA.member, listOf(ROLE.user, ROLE.editor)))
        // An editor given the operator rung keeps its editing, and the label says both.
        assertEquals(PERSONA.operatorAndEditor, PERSONA.actsAs(PERSONA.editor, listOf(ROLE.user, ROLE.operator, ROLE.editor)))
        // Acting with nothing is said as such, never as a plain user's access.
        assertEquals(PERSONA.noAccess, PERSONA.actsAs(PERSONA.admin, emptyList()))
        // A persona the registry does not hold has no defaults to part from.
        assertNull(PERSONA.actsAs("advisor", listOf(ROLE.user, ROLE.admin)))
    }

    @Test
    fun aTermSaysWhatItsUserActsAsAndNothingWithoutRoles() {
        assertEquals("Member (Administrator access)", PERSONA.withActing("Member", PERSONA.member, listOf(ROLE.user, ROLE.admin)))
        assertEquals("Admin 2 (User access)", PERSONA.withActing("Admin 2", PERSONA.admin, listOf(ROLE.user)))
        assertEquals("Admin (No access)", PERSONA.withActing("Admin", PERSONA.admin, emptyList()))
        assertEquals("Member", PERSONA.withActing("Member", PERSONA.member, listOf(ROLE.user)))
        assertEquals("Member", PERSONA.withActing("Member", PERSONA.member, null))
    }

    @Test
    fun theQualifierSaysWhatAUserActsAsWhereItShowsThePersona() {
        val member = UserChoice(1L, "acme", PERSONA.member, isCurrent = true)
        val admin = UserChoice(2L, "acme", PERSONA.admin)
        val promoted = listOf(ROLE.user, ROLE.admin)
        assertEquals("Member (Administrator access)", member.qualifierWithin(listOf(member, admin), promoted))
        assertEquals("Member", member.qualifierWithin(listOf(member, admin)))
        // Where the persona is not shown -- two clients, one persona -- nothing is added either.
        val elsewhere = UserChoice(3L, "globex", PERSONA.member)
        assertEquals("acme", member.qualifierWithin(listOf(member, elsewhere), promoted))
        // A batch: a suffix shows the persona, so the access goes beside the whole term.
        val memberB = UserChoice(4L, "acme", PERSONA.member, personaSuffix = "B")
        assertEquals("Member (Administrator access)", member.qualifierWithin(listOf(member, memberB), promoted))
        assertEquals("Member B (Administrator access)", memberB.qualifierWithin(listOf(member, memberB), promoted))
    }
}
