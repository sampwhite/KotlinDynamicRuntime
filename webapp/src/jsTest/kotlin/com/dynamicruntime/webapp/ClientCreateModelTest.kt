package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The New client form's pure half (issue #1820): its choices, its request, what it checks, and what it says after. */
class ClientCreateModelTest {
    private fun choice(value: String, label: String) = mapOf(SCH.value to value, SCH.label to label)

    private val platform = parseClientCreateChoices(
        mapOf(
            CLD.asPlatform to true,
            CLD.templateChoices to listOf(choice("starterkit", "Starter kit (starterkit)"), choice("acme", "Acme (acme)")),
            CLD.customerChoices to listOf(choice("acme", "Acme Corporation (acme)")),
        ),
    )
    private val acme = parseClientCreateChoices(
        mapOf(CLD.asPlatform to false, CLD.customerId to "acme", CLD.templateChoices to listOf(choice("starterkit", "Starter kit"))),
    )

    @Test
    fun choicesReadEachIdToItsLabel() {
        assertTrue(platform.asPlatform)
        assertNull(platform.customerId)
        assertEquals(listOf("starterkit" to "Starter kit (starterkit)", "acme" to "Acme (acme)"), platform.templates)
        assertEquals(listOf("acme" to "Acme Corporation (acme)"), platform.customers)
        assertFalse(acme.asPlatform)
        assertEquals("acme", acme.customerId)
        assertEquals(emptyList(), acme.customers)
        // A choice with no label is shown by its id; one with no id is none.
        val bare = parseClientCreateChoices(mapOf(CLD.templateChoices to listOf(mapOf(SCH.value to "kit"), mapOf(SCH.label to "No id"))))
        assertEquals(listOf("kit" to "kit"), bare.templates)
    }

    @Test
    fun aCustomersClientIdIsItsPrefixAndThePartTyped() {
        assertEquals("acme_east", customerClientId("acme", " east "))
        assertEquals("acme_east", draftClientId(acme, ClientCreateDraft(clientId = "east")))
        // The platform types the whole id.
        assertEquals("acme_east", draftClientId(platform, ClientCreateDraft(clientId = " acme_east ")))
    }

    @Test
    fun aCustomersRequestCarriesTheCreatorsChoicesOnly() {
        val body = clientCreateRequest(
            acme,
            ClientCreateDraft(clientId = "east", name = " East ", description = "", template = "starterkit", sandbox = true, customerId = "x"),
        )
        assertEquals(
            mapOf(CLD.clientId to "acme_east", CLD.name to "East", CLD.extendsFromClientId to "starterkit", CLD.sandbox to true),
            body,
        )
        assertEquals(UADEP.clientCreate, clientCreatePath(acme))
    }

    @Test
    fun theStarterWorkflowsAreSentOnlyWhenABlankClientGoesWithout() {
        val blank = ClientCreateDraft(clientId = "east", name = "East")
        assertNull(clientCreateRequest(acme, blank)[CLD.starterWorkflows])
        assertEquals(false, clientCreateRequest(acme, blank.copy(starterWorkflows = false))[CLD.starterWorkflows])
        // A template's client takes the template's, whatever the box said.
        assertNull(clientCreateRequest(acme, blank.copy(starterWorkflows = false, template = "starterkit"))[CLD.starterWorkflows])
    }

    @Test
    fun thePlatformsRequestCarriesTheCustomerUsageAudienceAndEnvironments() {
        val draft = ClientCreateDraft(clientId = "acme_west", name = "West", description = " Ours ", customerId = "acme")
        val body = clientCreateRequest(platform, draft)
        assertEquals("acme_west", body[CLD.clientId])
        assertEquals("Ours", body[CLD.description])
        assertEquals("acme", body[CLD.customerId])
        assertEquals(ClientUsageType.production.name, body[CLD.usageType])
        // A customer's client is the customer's, unless chosen otherwise; one under none is ours.
        assertEquals(ClientAudience.customer.name, body[CLD.audience])
        assertEquals(ClientAudience.internal.name, clientCreateRequest(platform, draft.copy(customerId = ""))[CLD.audience])
        assertEquals(ClientAudience.internal.name, clientCreateRequest(platform, draft.copy(audience = "internal"))[CLD.audience])
        assertEquals(ENV.names, body[CLD.enabledEnvironments])
        assertNull(clientCreateRequest(platform, draft.copy(customerId = ""))[CLD.customerId])
        assertEquals(ADEP.clientCreate, clientCreatePath(platform))
    }

    @Test
    fun onlyWhatIsPlainlyMissingKeepsADraftFromBeingSent() {
        assertEquals(listOf("Give the client an id.", "Give the client a name."), clientCreateProblems(acme, ClientCreateDraft()))
        assertEquals(emptyList(), clientCreateProblems(acme, ClientCreateDraft(clientId = "east", name = "East", environments = emptyList())))
        assertEquals(
            listOf("Choose at least one environment the client runs in."),
            clientCreateProblems(platform, ClientCreateDraft(clientId = "west", name = "West", environments = emptyList())),
        )
    }

    @Test
    fun moreOptionsNamesWhatIsSetThere() {
        assertEquals(emptyList(), clientCreateOptionsInForce(true, ClientCreateDraft()))
        assertEquals(listOf("Sandbox"), clientCreateOptionsInForce(false, ClientCreateDraft(sandbox = true, usageType = "dev")))
        assertEquals(
            listOf("Usage: dev", "Audience: customer", "Environments: dev, prod"),
            clientCreateOptionsInForce(true, ClientCreateDraft(usageType = "dev", audience = "customer", environments = listOf("dev", "prod"))),
        )
    }

    @Test
    fun whatTheFormSaysOnceTheClientIsMade() {
        val made = parseClientCreateOutcome(mapOf(CLD.client to "acme_east", CLD.present to true, CLD.userId to 42))
        assertEquals("Created acme_east.", clientCreatedText(made))
        assertEquals(42L, made.userId)
        assertTrue(clientCreatedSwitchable(made))
        val absent = parseClientCreateOutcome(
            mapOf(CLD.client to "acme_east", CLD.present to false, CLD.loadFailure to "Not enabled in dev.", CLD.userFailure to "No identity."),
        )
        assertEquals(
            "Created acme_east. This node does not carry it: Not enabled in dev. Your administrator user there could not be made: No identity.",
            clientCreatedText(absent),
        )
        assertFalse(clientCreatedSwitchable(absent))
        // The platform's create makes no user, so offers no switch.
        assertFalse(clientCreatedSwitchable(parseClientCreateOutcome(mapOf(CLD.client to "west", CLD.present to true))))
    }

    @Test
    fun aCustomerOnTheFreePlanIsOfferedNoNewClientOfItsOwn() {
        val free = CustomerSummary("cu", "Cu", null, PLAN.free, "cu", 1, false)
        assertFalse(customerOffersNewClient(free, acrossClients = false))
        assertTrue(customerOffersNewClient(free, acrossClients = true))
        assertTrue(customerOffersNewClient(CustomerSummary("cu", "Cu", null, "unlimited", "cu", 1, false), acrossClients = false))
    }
}
