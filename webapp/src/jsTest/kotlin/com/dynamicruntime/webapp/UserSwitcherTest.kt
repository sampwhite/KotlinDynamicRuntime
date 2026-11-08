package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.user.personaTag
import com.dynamicruntime.common.user.personaTerm
import com.dynamicruntime.common.user.userKeyLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for the account menu's user switcher (issue #749): the users-list parse,
 * the menu labels, and the two rules that decide whether the switcher and the persona chip show at all.
 */
class UserSwitcherTest {

    private val ada = UserChoice(7L, "acme", "admin", name = "Ada Lovelace", isCurrent = true)
    private val batch = UserChoice(8L, "acme", "member", personaSuffix = "2")

    @Test
    fun labelsClientPersonaPersonaSuffixAndName() {
        assertEquals("acme / Admin -- Ada Lovelace", ada.label())
        assertEquals("acme / Member 2", batch.label())
        // A blank name is no name; a personaSuffix is shown only when there is one; the persona is capitalized.
        assertEquals("public / Member", UserChoice(9L, "public", "member", name = "  ").label())
    }

    // The listing's persona tag (issue #1097): the persona and suffix as one term, and none for a plain member.
    @Test
    fun aPersonaTagMarksEveryUserButAPlainMember() {
        assertEquals(null, personaTag(PERSONA.member, ""))
        assertEquals("Member B", personaTag(PERSONA.member, "B"))
        assertEquals("Admin", personaTag(PERSONA.admin, ""))
        assertEquals("Admin 2", personaTag(PERSONA.admin, "2"))
        // An unknown persona is shown by its own name, capitalized, as PERSONA.label does.
        assertEquals("Reviewer", personaTag("reviewer", ""))
        // The full label, as a hover shows it: the client and the term, then the name when there is one.
        assertEquals("acme / Admin 2 -- Ada Lovelace", userKeyLabel("acme", PERSONA.admin, "2", "Ada Lovelace"))
        assertEquals("acme / Member", userKeyLabel("acme", PERSONA.member, "", null))
        assertEquals("Member B", personaTerm(PERSONA.member, "B"))
    }

    @Test
    fun theQualifierSaysOnlyWhatTellsAUserFromThePersonsOthers() {
        val member = UserChoice(8L, "acme", "member")
        val hubMember = UserChoice(9L, "hub", "member")
        // One user: nothing to tell apart.
        assertEquals("", ada.qualifierWithin(listOf(ada)))
        // Same client, personas differ: the persona alone.
        assertEquals("Admin", ada.qualifierWithin(listOf(ada, member)))
        assertEquals("Member", member.qualifierWithin(listOf(ada, member)))
        // Clients differ, same persona: the client alone.
        assertEquals("hub", hubMember.qualifierWithin(listOf(member, hubMember)))
        // A batch: the persona is shown for every user once any carries a personaSuffix, so the plain one is not
        // left with an empty bracket -- `Member` beside `Member 2`, or `hub · Member B`.
        assertEquals("Member", member.qualifierWithin(listOf(member, batch)))
        assertEquals("Member 2", batch.qualifierWithin(listOf(member, batch)))
        val hubBatch = UserChoice(10L, "hub", "member", personaSuffix = "B")
        assertEquals("hub · Member B", hubBatch.qualifierWithin(listOf(member, hubBatch)))
    }

    @Test
    fun aClientIsShownByItsNameWhenItHasOne() {
        // Issue #1110: the bracket says what a person calls the client; the tooltip keeps its id beside the name.
        val east = UserChoice(11L, "acme_east", "member", clientName = "Acme East")
        val hub = UserChoice(12L, "hub", "member")
        assertEquals("Acme East", east.qualifierWithin(listOf(east, hub)))
        assertEquals("hub", hub.qualifierWithin(listOf(east, hub)))
        assertEquals("Acme East (acme_east) / Member", east.label())
        assertEquals(east, UserChoice.fromInfo(east.toInfo()))
    }

    @Test
    fun aJoinedUserIsToldApartByItsRole() {
        // Issue #1689: a user joined as a demo's role says the role, in place of its persona, even where the persona
        // would not be shown -- the speaker and the public user are both members.
        val public = UserChoice(7L, "public", "member", name = "Pat", clientName = "Public")
        val speaker = UserChoice(8L, "confdemo", "member", name = "Pat", clientName = "Conference demo", roleLabel = "Speaker")
        val committee = UserChoice(9L, "confdemo", "editor", name = "Pat", clientName = "Conference demo", roleLabel = "Committee member")
        val all = listOf(public, speaker, committee)
        assertEquals("Conference demo · Speaker", speaker.qualifierWithin(all))
        assertEquals("Conference demo · Committee member", committee.qualifierWithin(all))
        assertEquals("Public · Member", public.qualifierWithin(all))
        assertEquals("Conference demo · Speaker", speaker.qualifierWithin(listOf(public, speaker)))
        // A role's user acting beyond its persona says so, as a persona does.
        assertEquals("Conference demo · Speaker (Administrator access)", speaker.qualifierWithin(all, listOf(ROLE.user, ROLE.admin)))
        // The tooltip's full label keeps the persona, and the role rides the wire.
        assertEquals("Conference demo (confdemo) / Member -- Pat", speaker.label())
        assertEquals(speaker, UserChoice.fromInfo(speaker.toInfo()))
        assertEquals("Speaker", speaker.toInfo()[UCF.roleLabel])
    }

    @Test
    fun theBadgeAppendsTheQualifierInBrackets() {
        val member = UserChoice(8L, "acme", "member")
        assertEquals("Ada [Admin]", identityBadgeText("Ada", listOf(ada, member)))
        // No current user in the list, or a single user: the label alone.
        assertEquals("Ada", identityBadgeText("Ada", listOf(ada.copy(isCurrent = false), member)))
        assertEquals("Ada", identityBadgeText("Ada", listOf(ada)))
    }

    // Issue #1450: the session's roles say the current user's access when it differs from its persona's -- in the
    // badge and on its own menu row, never on another's, whose roles the session does not know.
    @Test
    fun theBadgeAndTheCurrentRowSayWhatTheUserActsAs() {
        val member = UserChoice(8L, "acme", "member")
        val demoted = listOf(ROLE.user)
        assertEquals("Ada [Admin (User access)]", identityBadgeText("Ada", listOf(ada, member), demoted))
        assertEquals("Ada [Admin]", identityBadgeText("Ada", listOf(ada, member), listOf(ROLE.user, ROLE.admin)))
        assertEquals("[Admin (User access)]", userMenuEntryText(ada, listOf(ada, member), demoted))
        assertEquals("[Member]", userMenuEntryText(member, listOf(ada, member), demoted))
        // The badge's tooltip says it in words, and says nothing more where the two agree.
        assertEquals("You are signed in as acme / Admin -- Ada Lovelace, with User access.", identityBadgeTitle(ada, demoted))
        assertEquals("You are signed in as acme / Admin -- Ada Lovelace.", identityBadgeTitle(ada, listOf(ROLE.user, ROLE.admin)))
        // The Persona column and the editors read the user's own roles.
        assertEquals("Member B (Administrator access)", personaCell("member", "B", listOf(ROLE.user, ROLE.admin)))
        assertEquals("Member B", personaCell("member", "B"))
    }

    @Test
    fun parsesTheListTheBackendServesAndDropsAnEntryWithoutAnId() {
        val parsed = userChoicesFrom(
            listOf(
                mapOf(UCF.userId to 7L, UCF.client to "acme", UCF.persona to "admin", UCF.name to "Ada Lovelace", UCF.isCurrent to true),
                mapOf(UCF.userId to 8L, UCF.client to "acme", UCF.persona to "member", UCF.personaSuffix to "2", UCF.isDefault to true),
                mapOf(UCF.client to "acme"),
            ),
        )
        assertEquals(listOf(ada, batch.copy(isDefault = true)), parsed)
        // Absent or malformed: nothing, never a crash.
        assertEquals(emptyList(), userChoicesFrom(null))
        assertEquals(emptyList(), userChoicesFrom("not a list"))
    }

    @Test
    fun theBadgeBecomesAMenuOnlyWhenThereIsSomeoneElseToBe() {
        assertEquals(emptyList(), switchableUsers(emptyList()))
        assertEquals(emptyList(), switchableUsers(listOf(ada)))
        assertEquals(listOf(ada, batch), switchableUsers(listOf(ada, batch)))
        // One that is not the acting user -- a stranded sandbox session's only way out (issue #1197) -- is offered.
        assertEquals(listOf(batch), switchableUsers(listOf(batch)))
    }

    @Test
    fun aMenuRowSaysWhatTellsItFromTheOthersOrWhoItIsWhenAlone() {
        assertEquals("[Member 2]", userMenuEntryText(batch, listOf(ada, batch)))
        // Alone, nothing tells it apart: the row names it in full rather than showing empty brackets.
        assertEquals(batch.label(), userMenuEntryText(batch, listOf(batch)))
    }
}

/** The invitation page's pure parts (issue #751): the preview parse and what it says the account is. */
class InvitationPageTest {
    @Test
    fun parsesThePreviewAndSaysWhatTheAccountIs() {
        val info = invitationInfoFrom(
            mapOf("email" to "ada@acme.com", "client" to "hub", "clientName" to "Hub", "persona" to "admin", "personaSuffix" to ""),
        )
        assertEquals("ada@acme.com", info.email)
        assertEquals("hub", info.client)
        // The client by its name, as the mail names it (issue #1438).
        assertEquals("Hub as Admin", invitationWhat(info))
        val batch = mapOf("email" to "b@acme.com", "client" to "acme", "clientName" to "Acme", "persona" to "member", "personaSuffix" to "B")
        assertEquals("Acme as Member B", invitationWhat(invitationInfoFrom(batch)))
        // Absent persona reads as the default; absent personaSuffix as the ordinary user; an absent name as the id.
        assertEquals("public as Member", invitationWhat(invitationInfoFrom(mapOf("email" to "c@acme.com", "client" to "public"))))
    }
}

/** The claim page's persona field (issue #751): one typed value, split by the kernel's rule. */
class PersonaTypedTest {
    @Test
    fun splitsThePersonaAndItsSuffix() {
        assertEquals("admin" to "", PERSONA.splitTyped("admin"))
        assertEquals("member" to "B", PERSONA.splitTyped("member B"))
        assertEquals("member" to "B", PERSONA.splitTyped("  Member   B "))
        assertEquals("member" to "", PERSONA.splitTyped("   "))
        // The mails give a persona by its label (issue #1438), so each label, typed back, names its persona.
        for (def in PERSONA.defs) {
            assertEquals(def.name to "", PERSONA.splitTyped(def.label))
            assertEquals(def.name to "B", PERSONA.splitTyped(PERSONA.typed(def.label, "B")))
        }
        assertEquals("member B", PERSONA.typed("member", "B"))
        assertEquals("admin", PERSONA.typed("admin", ""))
    }
}
