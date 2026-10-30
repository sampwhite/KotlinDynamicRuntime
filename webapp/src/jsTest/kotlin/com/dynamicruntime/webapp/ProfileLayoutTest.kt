package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Profile page's layout (issue #1479): its structure line, its account panel, and what the caller can do. */
class ProfileLayoutTest {
    private fun profile(
        id: Long = 1L, client: String = "acme", roles: Set<String> = setOf(ROLE.user), persona: String? = PERSONA.member,
        org: String? = null, isEntity: Boolean = false, name: String? = null,
    ) = UserProfile(
        authId = "a1", userId = id, client = client, org = org, roles = roles, publicName = "ada@acme.test",
        isEntity = isEntity, name = name, persona = persona,
    )

    private val admin = setOf(ROLE.user, ROLE.admin)
    private val member = UserChoice(1, "acme", PERSONA.member, clientName = "Acme", isCurrent = true)
    private val memberB = UserChoice(2, "acme", PERSONA.member, personaSuffix = "B", clientName = "Acme")
    private val elsewhere = UserChoice(3, "globex", PERSONA.admin, clientName = "Globex")

    @Test
    fun theStructureLineIsTheAddressTheUsersAndTheAccessOfTheOneActedAs() {
        // One user: by its client and persona, there being nothing to tell it apart from.
        val alone = profileStructure("ada@acme.test", listOf(member), profile())
        assertEquals("ada@acme.test", alone.person)
        assertEquals(listOf("Acme / Member"), alone.rows.map { it.label })
        assertTrue(alone.rows.single().current)
        assertEquals("User access", alone.access)
        // Several: each by what tells it apart, the one acted as marked.
        val several = profileStructure("ada@acme.test", listOf(member, memberB, elsewhere), profile())
        assertEquals(listOf("Acme · Member", "Acme · Member B", "Globex · Admin"), several.rows.map { it.label })
        assertEquals(listOf(true, false, false), several.rows.map { it.current })
        // The one acted as says the access it has beside a persona it no longer matches (issue #1450).
        val promoted = profileStructure("ada@acme.test", listOf(member, memberB), profile(roles = admin))
        assertEquals("Member (Administrator access)", promoted.rows.first().label)
        assertEquals("Administrator access", promoted.access)
        // A list without the one acted as (it failed to load): the signed-in user alone.
        assertEquals(listOf("acme / Member"), profileStructure("ada@acme.test", emptyList(), profile()).rows.map { it.label })
    }

    @Test
    fun aPublicUserIsNoAdministratorOnTheirProfile() {
        // Every `public` registrant is given `admin`, which there reaches only their own users: they read as a member.
        val registrant = profile(client = CL.public, roles = admin)
        assertEquals(setOf(ROLE.user), profileShownRoles(registrant))
        val publicUser = UserChoice(1, CL.public, PERSONA.member, isCurrent = true)
        assertEquals("User access", profileStructure("pat@pub.test", listOf(publicUser), registrant).access)
        val facts = profileAccountFacts(registrant, "pat@pub.test", listOf(publicUser), ShellFacts()).associate { it.key to it.value }
        assertEquals("Member" to "User", facts[PRFA.persona] to facts[PRFA.access])
        // Their own users, and their own forms, as a member's.
        assertEquals(
            listOf(PRFA.canOwnUsers, PRFA.canOwnForms),
            profileAbilities(CallerReach(managesUsers = true), profileShownRoles(registrant), null),
        )
        // An administrator across every client is one wherever they are.
        val allClients = admin + ROLE.allClients
        assertEquals(allClients, profileShownRoles(profile(client = CL.public, roles = allClients)))
    }

    @Test
    fun theAccountPanelSaysWhatThePageAndTheShellAlreadyKnow() {
        val facts = ShellFacts(timeZone = "America/Chicago", timeZoneLabel = "CT")
        // Signed in as is left out when it is only the address the next line gives.
        val plain = profileAccountFacts(profile(), "ada@acme.test", listOf(member), facts).associate { it.key to it.value }
        assertEquals(
            mapOf(
                PRFA.email to "ada@acme.test", PRFA.client to "Acme", PRFA.persona to "Member", PRFA.access to "User",
                PRFA.accountType to "Personal", PRFA.timeZone to "America/Chicago (CT)",
            ),
            plain,
        )
        // A named business account in an organization, promoted past its persona: the persona alone, the access after.
        val business = profileAccountFacts(
            profile(id = 2, roles = admin, org = "east", isEntity = true, name = "Acme Labs"),
            "ada@acme.test", listOf(member, memberB.copy(isCurrent = true)), facts,
        ).associate { it.key to it.value }
        assertEquals("Acme Labs", business[PRFA.signedInAs])
        assertEquals("Member B", business[PRFA.persona])
        assertEquals("Administrator", business[PRFA.access])
        assertEquals("east", business[PRFA.org])
        assertEquals("Business", business[PRFA.accountType])
        // The order the panel reads in.
        assertEquals(
            listOf(PRFA.signedInAs, PRFA.email, PRFA.client, PRFA.persona, PRFA.access, PRFA.org, PRFA.accountType, PRFA.timeZone),
            profileAccountFacts(profile(org = "east", name = "Ada"), "a@b.test", listOf(member), facts).map { it.key },
        )
        // A value's words are the copy's when it has them.
        val reworded = profileAccountFacts(profile(), "a@b.test", listOf(member), facts) { key, shipped ->
            if (key == PRFA.personal) "Individual" else shipped
        }
        assertEquals("Individual", reworded.first { it.key == PRFA.accountType }.value)
        // Every label and word has its shipped words.
        assertTrue(profileAccountDefaults.values.all { it.isNotBlank() })
    }

    @Test
    fun accessAndTheTimeZoneReadAsWords() {
        assertEquals("Administrator, across every client", profileAccessText(admin + ROLE.allClients))
        assertEquals("Operator, across every client", profileAccessText(setOf(ROLE.user, ROLE.operator, ROLE.allClients)))
        // The capability confers no level, so beside a lower one it says nothing (issue #237).
        assertEquals("User", profileAccessText(setOf(ROLE.user, ROLE.allClients)))
        assertEquals("Editor", profileAccessText(setOf(ROLE.user, ROLE.editor, ROLE.allClients)))
        assertEquals("None", profileAccessText(emptySet()))
        assertEquals("Europe/Paris", profileTimeZoneText("Europe/Paris", null))
        // The site's own zone, labeled as every moment on the page is.
        assertEquals("This site's default (GMT-8)", profileTimeZoneText(null, null))
    }

    @Test
    fun whatTheCallerCanDoIsTheWidestReachFirst() {
        val user = setOf(ROLE.user)
        assertEquals(listOf(PRFA.canOwnForms), profileAbilities(CallerReach(), user, null))
        assertEquals(
            listOf(PRFA.canAllClients, PRFA.canOperateSite),
            profileAbilities(CallerReach(managesUsers = true, allClients = true, administersClient = true), admin + ROLE.allClients, null),
        )
        assertEquals(
            listOf(PRFA.canAdministerClient, PRFA.canOpenSandbox),
            profileAbilities(CallerReach(managesUsers = true, administersClient = true, opensSandbox = true), admin, null),
        )
        // Confined to an organization, a client administrator and an editor read only as far as it.
        assertEquals(listOf(PRFA.canAdministerOrg), profileAbilities(CallerReach(managesUsers = true, administersClient = true), admin, "east"))
        assertEquals(listOf(PRFA.canEditOrg), profileAbilities(CallerReach(editsClient = true), user + ROLE.editor, "east"))
        assertEquals(listOf(PRFA.canEditClient), profileAbilities(CallerReach(editsClient = true), user + ROLE.editor, null))
        // An operator: the site's own pages with the capability, their client's facts without it.
        assertEquals(
            listOf(PRFA.canOperateSite, PRFA.canOwnForms),
            profileAbilities(CallerReach(), setOf(ROLE.user, ROLE.operator, ROLE.allClients), null),
        )
        assertEquals(listOf(PRFA.canReadFacts, PRFA.canOwnForms), profileAbilities(CallerReach(), setOf(ROLE.user, ROLE.operator), null))
        // The capability alone reaches no operator page.
        assertEquals(listOf(PRFA.canOwnForms), profileAbilities(CallerReach(), user + ROLE.allClients, null))
    }

    @Test
    fun theShellTellsThePageWhoseFactsTheyAre() {
        val config = homeConfigFrom(
            UiConfig(
                FragmentRef("home", "b1"),
                mapOf(HFEAT.canManageUsers to true, HFEAT.administersClient to true, HFEAT.canOpenSandbox to true),
                emptyMap(),
                mapOf(
                    HFLD.timeZone to "America/Chicago", HFLD.timeZoneLabel to "CT",
                    HFLD.userInfo to mapOf(UPF.authId to "a7", UPF.userId to 7L, UPF.client to "acme", UPF.roles to listOf(ROLE.user)),
                ),
            ),
        )
        val facts = shellFactsOf(config)
        assertEquals(CallerReach(managesUsers = true, administersClient = true, opensSandbox = true), facts.reach)
        assertEquals("America/Chicago" to "CT", facts.timeZone to facts.timeZoneLabel)
        // About user 7: not about another user, as for a moment after a switch, and about nobody before being told.
        assertTrue(facts.describes(7L))
        assertFalse(facts.describes(8L))
        assertFalse(ShellFacts().describes(7L))
        assertNull(shellFactsOf(homeConfigFrom(UiConfig(FragmentRef("home", "b1"), emptyMap(), emptyMap(), emptyMap()))).userId)
    }
}
