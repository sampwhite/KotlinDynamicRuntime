package com.dynamicruntime.common.user

import com.dynamicruntime.common.http.request.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A persona beside the roles a user acts with (issue #1450): a persona names which of a person's users this is and
 * is frozen at creation, while roles change -- so where the two part, the persona's label says what the user acts
 * as, on JVM and JS alike.
 */
class PersonaActingTest {
    @Test
    fun accessIsTheHighestHeldAndAnEditorWhoOperatesReadsAsTheOperator() {
        assertEquals(ROLE.admin, PERSONA.accessOf(listOf(ROLE.user, ROLE.admin, ROLE.allClients)))
        assertEquals(ROLE.operator, PERSONA.accessOf(listOf(ROLE.user, ROLE.operator, ROLE.editor)))
        assertEquals(ROLE.editor, PERSONA.accessOf(listOf(ROLE.user, ROLE.editor)))
        assertEquals(ROLE.user, PERSONA.accessOf(listOf(ROLE.user)))
        assertEquals("Administrator", PERSONA.accessLabel(ROLE.admin))
        assertEquals("User", PERSONA.accessLabel(ROLE.user))
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
        // A persona the registry does not hold has no defaults to part from.
        assertNull(PERSONA.actsAs("advisor", listOf(ROLE.user, ROLE.admin)))
    }

    @Test
    fun aTermSaysWhatItsUserActsAsAndNothingWithoutRoles() {
        assertEquals("Member (acts as administrator)", PERSONA.withActing("Member", PERSONA.member, listOf(ROLE.user, ROLE.admin)))
        assertEquals("Admin 2 (acts as user)", PERSONA.withActing("Admin 2", PERSONA.admin, listOf(ROLE.user)))
        assertEquals("Member", PERSONA.withActing("Member", PERSONA.member, listOf(ROLE.user)))
        assertEquals("Member", PERSONA.withActing("Member", PERSONA.member, null))
    }

    @Test
    fun theQualifierSaysWhatAUserActsAsWhereItShowsThePersona() {
        val member = UserChoice(1L, "acme", PERSONA.member, isCurrent = true)
        val admin = UserChoice(2L, "acme", PERSONA.admin)
        val promoted = listOf(ROLE.user, ROLE.admin)
        assertEquals("Member (acts as administrator)", member.qualifierWithin(listOf(member, admin), promoted))
        assertEquals("Member", member.qualifierWithin(listOf(member, admin)))
        // Where the persona is not shown -- two clients, one persona -- nothing is added either.
        val elsewhere = UserChoice(3L, "globex", PERSONA.member)
        assertEquals("acme", member.qualifierWithin(listOf(member, elsewhere), promoted))
    }
}
