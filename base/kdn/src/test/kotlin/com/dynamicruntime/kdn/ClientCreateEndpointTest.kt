package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.ClientCreateFields
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Creating a client over one typed endpoint (issue #1054): its definition written as its first stored
 * configuration, published, and the client loaded -- where it used to take a hand-built bundle write and a reload.
 * A create is held to what any written definition is, and refused for the same reasons in the same words.
 *
 * Env-authed, since the input schema is read from the endpoint catalog; the fixture component gives the node a
 * source-defined template to extend.
 */
class ClientCreateEndpointTest : StringSpec({
    val cxt: KdrCxt = Startup.mkTestBootCxt(
        "clientCreate", "clientCreateEndpointTest", mapOf(ACFG.assumeEnvAuth to true), listOf(ExtensionTemplateComponent()),
    )
    val admin = TestUser.createFullAdmin(cxt, "chief@create1054.test")

    fun body(clientId: String, vararg more: Pair<String, Any?>): Map<String, Any?> = mapOf(
        CLD.clientId to clientId, CLD.name to "Created $clientId", CLD.usageType to ClientUsageType.dev.name,
        CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
    ) + more

    fun refusal(status: Int, request: Map<String, Any?>): String =
        admin.expectError(status, ADEP.clientCreate, request)[EP.errorMessage].toString()

    /** The create endpoint's input schema, as the catalog serves it to this caller. */
    fun inputProperties(): Map<String, Any?> =
        admin.getData("/schema/endpoints", mapOf(SS.pathRegex to ADEP.clientCreate))[EI.endpoints]
            .toJsonListOfMaps().single { it[EI.path].toOptStr() == ADEP.clientCreate }[EI.inputSchema]
            .toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()

    "a created client is stored, published, present at once, and listed" {
        val client = "made1054"
        ClientService.get(cxt).known(client) shouldBe null
        val result = admin.postData(
            ADEP.clientCreate,
            body(
                client, CLD.description to "  Made by the endpoint.  ", CLD.domainPrefix to "made1054", CLD.customDomain to " ",
                CLD.userLabels to listOf("reviewer", " reviewer ", "approver"),
            ),
        )
        result[CLD.client] shouldBe client
        result[COV.configName] shouldBe CLD.definitionConfigName
        result[CLD.present] shouldBe true
        result[CLD.issues].toJsonListOfMaps().shouldBeEmpty()
        // The definition as stored: text trimmed and a blank optional field left out (reading the request does
        // both), the labels each once.
        val stored = result[CLD.definition].toJsonMapOrEmpty()
        stored[CLD.description] shouldBe "Made by the endpoint."
        stored.containsKey(CLD.customDomain) shouldBe false
        stored[CLD.userLabels] shouldBe listOf("reviewer", "approver")
        stored[CLD.enabledEnvironments] shouldBe listOf(ENV.unit, ENV.local)

        // The node carries it, the listing shows it, and its definition reads back.
        ClientService.get(cxt).present(client).shouldNotBeNull().domainPrefix shouldBe "made1054"
        admin.getItems(ADEP.clients).mapNotNull { it[CLD.clientId].toOptStr() } shouldContain client
        admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to client))[CLD.present] shouldBe true
        // Its definition is its first configuration, and published: there is no draft left to remember.
        val bundle = admin.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to CLD.definitionConfigName))
        bundle[CFEP.published] shouldBe true
        bundle[CFEP.slots].toJsonMapOrEmpty().keys shouldBe setOf(CCT.clientDef)
    }

    "an id that is taken is a 409, whoever holds it" {
        val client = "taken1054"
        admin.postData(ADEP.clientCreate, body(client))[CLD.present] shouldBe true
        refusal(EXC.conflict, body(client)) shouldContain "already exists: it is defined in stored configuration"
        refusal(EXC.conflict, body(CL.hub)) shouldContain "defined in source code"
        // Nothing of the refused create was written over what is there.
        ClientService.get(cxt).present(client).shouldNotBeNull().name shouldBe "Created $client"

        // An id whose configuration was written and never loaded is taken too: the node does not carry the client,
        // and a create would otherwise write a second definition over the one waiting there.
        val waiting = "waiting1054"
        admin.postData(
            ACEP.bundleWrite,
            mapOf(
                CFEP.client to waiting, CFEP.name to "other", CFEP.namespaceField to clientNamespace(waiting),
                CFEP.slots to mapOf(CCT.cfactDef to listOf(mapOf(CCT.name to "ready", CCT.group to "g", CCT.description to "Ready."))),
            ),
        )[CFEP.version] shouldBe 1
        ClientService.get(cxt).known(waiting) shouldBe null
        refusal(EXC.conflict, body(waiting)) shouldContain "already has stored configuration"
    }

    "a create is refused for what any written definition is, in the same words" {
        // By the input's own schema: a missing field, an environment that does not exist, a flag that is not one.
        refusal(EXC.badInput, body("bad1054") - CLD.name) shouldContain CLD.name
        refusal(EXC.badInput, body("bad1054", CLD.enabledEnvironments to listOf(ENV.unit, "staging"))) shouldContain
            "Validation failed for 1 field: ${CLD.enabledEnvironments}[1]"
        refusal(EXC.badInput, body("bad1054", CLD.sandbox to "yes")) shouldContain CLD.sandbox
        // A field a create does not take is not one of its inputs.
        refusal(EXC.badInput, body("bad1054", CLD.staticConfig to true)) shouldContain CLD.staticConfig
        // By the trial load: an id that is not a legal one, and an environment set that is not a legal set.
        refusal(EXC.badInput, body("bad-1054")) shouldContain "may hold only letters, digits and underscores"
        refusal(EXC.badInput, body("bad1054", CLD.enabledEnvironments to listOf(ENV.dev))) shouldContain
            "is enabled in dev but not in unit or local"
        // The client it extends must be a template defined in source code.
        refusal(EXC.badInput, body("bad1054", CLD.extendsFromClientId to "nosuch1054")) shouldContain
            "extends 'nosuch1054', which is not defined in source"
        refusal(EXC.badInput, body("bad1054", CLD.extendsFromClientId to CL.hub)) shouldContain "which is not a template"
        // By the write's own guard: a domain another client holds.
        admin.postData(ADEP.clientCreate, body("holder1054", CLD.customDomain to "forms.holder1054.example"))[CLD.present] shouldBe true
        refusal(EXC.badInput, body("bad1054", CLD.customDomain to "forms.holder1054.example")) shouldContain
            "is client 'holder1054''s; a domain routes to one client"
        // A sandbox is made by the system from its parent, never created.
        refusal(EXC.badInput, body("holder1054:sandbox")) shouldContain "is a sandbox's id"
        // None of the refusals left anything behind: the id is still free, and a sound create takes it.
        ClientService.get(cxt).known("bad1054") shouldBe null
        admin.expectError(EXC.notFound, ACEP.bundle, args = mapOf(CFEP.client to "bad1054", CFEP.name to CLD.definitionConfigName))
        admin.postData(ADEP.clientCreate, body("bad1054"))[CLD.present] shouldBe true
    }

    "a client may be created on a template, and with a sandbox" {
        val created = admin.postData(
            ADEP.clientCreate,
            body("kid1054", CLD.extendsFromClientId to ExtensionTemplateComponent.template, CLD.sandbox to true),
        )
        created[CLD.present] shouldBe true
        // Built on the template: it presents with the template's web resources, which its own definition did not name.
        val def = ClientService.get(cxt).present("kid1054").shouldNotBeNull()
        def.extendsFromClientId shouldBe ExtensionTemplateComponent.template
        def.webResourcesId shouldBe "tplres"
        // A client asking for a sandbox runs only what is published; the create published it, so it is present.
        def.sandbox shouldBe true
        // Its sandbox is live now, and so an id this node knows -- which is still a sandbox's id, said as that
        // rather than as a client that already exists.
        ClientService.get(cxt).known("kid1054:sandbox").shouldNotBeNull()
        refusal(EXC.badInput, body("kid1054:sandbox")) shouldContain "made by the system from its parent, 'kid1054'"
    }

    "a create-only write is refused once the configuration exists, judged under the client's lock" {
        // What stops two creates of one id from both succeeding: the check a create makes first cannot, since both
        // may pass it before either writes. The write itself refuses the second, with the first as it was.
        val client = "race1054"
        val writer = cxt.mkSubContext("raceWrite", client).also { it.userId = 10541L }
        val svc = GedraConfigService.get(cxt)
        fun definition(name: String) = gedraConfig(cxt, CLD.definitionConfigName, clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = name, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        }
        svc.writeConfig(writer, definition("First"), mustBeNew = true).version shouldBe 1
        shouldThrow<KdrException> { svc.writeConfig(writer, definition("Second"), mustBeNew = true) }.code shouldBe EXC.conflict
        val stored = svc.readLatest(writer, GedraId.of(GedraConfigType.configDoc, client, CLD.definitionConfigName)).shouldNotBeNull()
        stored.version shouldBe 1
        stored.entriesBySlot().getValue(CCT.clientDef).single()[CLD.name] shouldBe "First"
        // An ordinary write of the same configuration is the edit it always was.
        svc.writeConfig(writer, definition("Edited")).entriesBySlot().getValue(CCT.clientDef).single()[CLD.name] shouldBe "Edited"
    }

    "a client created for environments this node is not in is stored, not present, and still taken" {
        // A development node, where a client enabled only for local work and the unit tests is not carried.
        val dev = Startup.mkBootCxt(
            "clientCreateDev", "clientCreateDevTest",
            mapOf(ACFG.env to ENV.dev, ACFG.isTestInstance to false, ACFG.inMemoryOnly to true, "KDR_DB_NAME" to "clientCreateDev1054"),
        )
        val actor = dev.mkSubContext("createActor", CL.hub).also { it.userId = 10542L }
        val result = ClientCreate.create(actor, body("elsewhere1054"))
        result.present shouldBe false
        result.loadFailure shouldBe null
        result.info[CLD.enabledEnvironments] shouldBe listOf(ENV.unit, ENV.local)
        ClientService.get(dev).present("elsewhere1054") shouldBe null
        // Stored and published all the same, so the id is taken: a second create is refused, not written over it.
        shouldThrow<KdrException> { ClientCreate.create(actor, body("elsewhere1054")) }.code shouldBe EXC.conflict
    }

    "the input is a schema a form can be drawn from: the create fields, with their choices" {
        val properties = inputProperties()
        properties.keys shouldBe ClientCreateFields.names.toSet()
        fun choices(field: Map<String, Any?>) = field[SCH.options].toJsonListOfMaps().mapNotNull { it[SCH.value].toOptStr() }
        choices(properties[CLD.usageType].toJsonMapOrEmpty()) shouldBe ClientUsageType.entries.map { it.name }
        val environments = properties[CLD.enabledEnvironments].toJsonMapOrEmpty()[SCH.items].toJsonMapOrEmpty()
        choices(environments) shouldBe ENV.names
        // Closed, as a written definition's are: a form offers the environments there are and no box to type another.
        environments.containsKey(SCH.openOptions) shouldBe false
        // The templates there are, offered by name -- and offered only: the rule is the load's.
        val templates = properties[CLD.extendsFromClientId].toJsonMapOrEmpty()
        choices(templates) shouldBe listOf(ExtensionTemplateComponent.template)
        templates.containsKey(SCH.optionsSource) shouldBe false
        admin.getData("/schema/endpoints", mapOf(SS.pathRegex to ADEP.clientCreate))[EI.endpoints]
            .toJsonListOfMaps().single()[EI.inputSchema].toJsonMapOrEmpty()[SCH.required].toJsonListOfStrings().toSet() shouldBe
            setOf(CLD.clientId, CLD.name, CLD.usageType, CLD.audience, CLD.enabledEnvironments)
    }

    "creating a client is the platform operator's: a client's own administrator is refused by the section" {
        val clientAdmin = TestUser.create(cxt, "admin@made1054.test", level = ROLE.admin, userClient = CL.hub)
        clientAdmin.expectError(EXC.notAuthorized, ADEP.clientCreate, body("notmine1054"))
        ClientService.get(cxt).known("notmine1054") shouldBe null
    }
})
