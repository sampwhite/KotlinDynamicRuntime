package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Profile page's layout (issue #1479): its structure line, its account panel, and what the caller can do. */
class ProfileLayoutTest {
    private fun profile(
        id: Long = 1L, roles: Set<String> = setOf(ROLE.user), persona: String? = PERSONA.member, org: String? = null,
        isEntity: Boolean = false, name: String? = null,
    ) = UserProfile(
        authId = "a1", userId = id, client = "acme", org = org, roles = roles, publicName = "ada@acme.test",
        isEntity = isEntity, name = name, persona = persona,
    )

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
        val promoted = profileStructure("ada@acme.test", listOf(member, memberB), profile(roles = setOf(ROLE.user, ROLE.admin)))
        assertEquals("Member (Administrator access)", promoted.rows.first().label)
        assertEquals("Administrator access", promoted.access)
        // Before the users load, or a list without the one acted as (a sandbox's): the signed-in user alone.
        assertEquals(listOf("acme / Member"), profileStructure("ada@acme.test", emptyList(), profile()).rows.map { it.label })
        assertEquals(listOf("acme / Member"), profileStructure("ada@acme.test", listOf(memberB), profile()).rows.map { it.label })
    }

    @Test
    fun theAccountPanelSaysWhatThePageAndTheShellAlreadyKnow() {
        val facts = ShellFacts(timeZone = "America/Chicago", timeZoneLabel = "CT")
        val plain = profileAccountFacts(profile(), "ada@acme.test", listOf(member), facts).associate { it.key to it.value }
        assertEquals(
            mapOf(
                "signedInAs" to "ada@acme.test", "email" to "ada@acme.test", "client" to "Acme", "persona" to "Member",
                "access" to "User", "accountType" to "Personal", "timeZone" to "America/Chicago (CT)",
            ),
            plain,
        )
        // A named business account in an organization, promoted past its persona, in a sandbox.
        val inSandbox = ShellFacts(sandboxOfName = "Acme")
        val business = profileAccountFacts(
            profile(id = 2, roles = setOf(ROLE.user, ROLE.admin), org = "east", isEntity = true, name = "Acme Labs"),
            "ada@acme.test", listOf(member, memberB.copy(isCurrent = true)), inSandbox,
        ).associate { it.key to it.value }
        assertEquals("Acme Labs", business["signedInAs"])
        assertEquals("Acme's sandbox", business["sandbox"])
        assertEquals("Member B (Administrator access)", business["persona"])
        assertEquals("Administrator", business["access"])
        assertEquals("east", business["org"])
        assertEquals("Business", business["accountType"])
        // The order the panel reads in.
        assertEquals(
            listOf("signedInAs", "email", "client", "sandbox", "persona", "access", "org", "accountType", "timeZone"),
            profileAccountFacts(profile(org = "east"), "a@b.test", listOf(member), inSandbox).map { it.key },
        )
    }

    @Test
    fun accessAndTheTimeZoneReadAsWords() {
        assertEquals("Administrator, across every client", profileAccessText(setOf(ROLE.user, ROLE.admin, ROLE.allClients)))
        assertEquals("Editor", profileAccessText(setOf(ROLE.user, ROLE.editor)))
        assertEquals("None", profileAccessText(emptySet()))
        assertEquals("Europe/Paris", profileTimeZoneText("Europe/Paris", null))
        assertTrue(profileTimeZoneText(null, null).startsWith("This site's default (UTC"))
    }

    @Test
    fun whatTheCallerCanDoIsTheWidestReachFirst() {
        assertEquals(listOf(PRFA.ownForms), profileAbilities(CallerReach()))
        assertEquals(
            listOf(PRFA.allClients),
            profileAbilities(CallerReach(managesUsers = true, allClients = true, administersClient = true)),
        )
        assertEquals(
            listOf(PRFA.administersClient, PRFA.opensSandbox),
            profileAbilities(CallerReach(managesUsers = true, administersClient = true, opensSandbox = true)),
        )
        // An administrator in `public` manages only their own address's users.
        assertEquals(listOf(PRFA.ownUsers), profileAbilities(CallerReach(managesUsers = true)))
        assertEquals(listOf(PRFA.editsClient), profileAbilities(CallerReach(editsClient = true)))
        // Every key has its words for when the copy has not arrived.
        val all = listOf(PRFA.allClients, PRFA.administersClient, PRFA.ownUsers, PRFA.editsClient, PRFA.opensSandbox, PRFA.ownForms)
        assertTrue(all.all { profileAbilityDefaults[it]?.isNotBlank() == true })
    }

    @Test
    fun theShellTellsThePageTheCallersReachZoneAndSandbox() {
        val config = homeConfigFrom(
            UiConfig(
                FragmentRef("home", "b1"),
                mapOf(HFEAT.canManageUsers to true, HFEAT.administersClient to true, HFEAT.canOpenSandbox to true),
                emptyMap(),
                mapOf(HFLD.timeZone to "America/Chicago", HFLD.timeZoneLabel to "CT", HFLD.sandboxOf to "acme", HFLD.sandboxOfName to "Acme"),
            ),
        )
        val facts = shellFactsOf(config)
        assertEquals(CallerReach(managesUsers = true, administersClient = true, opensSandbox = true), facts.reach)
        assertEquals("America/Chicago" to "CT", facts.timeZone to facts.timeZoneLabel)
        assertEquals("Acme", facts.sandboxOfName)
    }
}
