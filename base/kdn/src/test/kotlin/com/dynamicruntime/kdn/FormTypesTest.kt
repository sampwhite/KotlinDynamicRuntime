package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.FTYPE
import com.dynamicruntime.common.gedra.FormTypeService
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.globalTraitBases
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Form types (issue #1902): a client's kinds of form, what a form's type may be, and who may set it. Over the shared
 * instance, a client per scenario.
 */
class FormTypesTest : StringSpec({
    val cxt = TestInstances.default("formTypes1902")
    fun asClient(client: String, on: KdrCxt = cxt): KdrCxt = on.mkSubContext("formTypes", client).also { it.userId = 19020L }

    fun clientDef(client: String) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
    )

    /** A site's client: leads anyone may make, videos only an administrator may, and notes whose type may be changed. */
    fun siteConfig(client: String, on: KdrCxt = cxt, types: GedraConfigBuilder.() -> Unit = {
        formType("lead", "Lead") { trait("ask") }
        formType("video", "Video", setWhen = CFACTS.hasAdminLevel) { trait("clip"); trait(GT.name, required = false) }
        formType("note", "Note", description = "Only a category.", mayLeave = true)
        formType("memo", "Memo", mayLeave = true)
    }): GedraConfig = gedraConfig(on, "main", clientNamespace(client), client) {
        defineClient(clientDef(client))
        trait("AskEntry", "ask", setOf(GedraDataType.formDoc), "What they ask.") { property("text", "The ask.") }
        trait("ClipEntry", "clip", setOf(GedraDataType.formDoc), "A clip.") { property("title", "Its title.") }
        workflow("make", WfEntry.creation) {
            task("start", "Start") {
                trait(GT.formType)
                save("create", "Create", WfSaveKind.create)
            }
        }
        types()
    }

    fun write(config: GedraConfig, client: String, on: KdrCxt = cxt) {
        GedraConfigService.get(on).writeConfig(asClient(client, on), config)
        GedraConfigReload.reloadClient(on, client)
    }

    class Site(val client: String, val lead: TestUser, val admin: TestUser)

    fun createSite(client: String): Site {
        write(siteConfig(client), client)
        return Site(
            client, TestUser.create(cxt, "lead@$client.test", userClient = client),
            TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client),
        )
    }

    fun typed(type: String) = mapOf(GE.traitId to GT.formType, GE.data to mapOf(GT.formTypeField to type))
    fun ask(text: String) = mapOf(GE.traitId to "ask", GE.data to mapOf("text" to text))
    fun body(vararg entries: Map<String, Any?>) = mapOf(GDF.entries to entries.toList())

    fun TestUser.make(vararg entries: Map<String, Any?>): String = postItem(GEP.formDocCreate, body(*entries))[GDF.gedraId].toOptStr()!!

    fun edit(gedraId: String, action: GedraEditAction, traitId: String, data: Map<String, Any?>? = null) = mapOf(
        GPF.targets to mapOf(
            GedraDataType.formDoc.name to listOf(
                mapOf(
                    GDF.gedraId to gedraId,
                    GPF.edits to listOf(
                        buildMap {
                            put(GED.action, action.name)
                            put(GE.traitId, traitId)
                            data?.let { put(GE.data, it) }
                        },
                    ),
                ),
            ),
        ),
    )

    fun setType(gedraId: String, type: String) =
        edit(gedraId, GedraEditAction.addOrReplace, GT.formType, mapOf(GT.formTypeField to type))

    fun importOf(vararg entries: Map<String, Any?>) = mapOf(GIF.data to body(*entries))

    fun TestUser.refused(code: Int, path: String, data: Map<String, Any?>): String =
        expectError(code, path, data)[EP.errorMessage].toString()

    fun TestUser.typeOf(gedraId: String): String? = getItem(GEP.formDoc, mapOf(GDF.gedraId to gedraId))[GDF.entries]
        .toJsonListOfMaps().firstOrNull { it[GE.traitId] == GT.formType }?.get(GE.data).toJsonMapOrEmpty()[GT.formTypeField].toOptStr()

    "a form is of one of its client's types, and of no other" {
        val site = createSite("site1902a")
        val form = site.lead.make(typed("lead"), ask("A demo?"))
        site.lead.typeOf(form) shouldBe "lead"
        // A type that lists no traits is a classification.
        site.lead.typeOf(site.lead.make(typed("note"))) shouldBe "note"
        // No type at all is fine too, and a type selects traits without refusing others.
        site.lead.make(ask("No type."))
        site.lead.make(typed("note"), ask("A trait the type does not list."))
        val untyped = site.lead.make(ask("Not yet."))
        site.lead.refused(EXC.badInput, GEP.formDocCreate, body(typed("nosuch"))) shouldContain "has no form type 'nosuch'"
        site.lead.refused(EXC.badInput, GEP.patch, setType(untyped, "nosuch")) shouldContain "has no form type 'nosuch'"
        site.lead.refused(EXC.badInput, GEP.formDocImport, importOf(typed("nosuch"))) shouldContain "has no form type 'nosuch'"
        site.lead.typeOf(untyped) shouldBe null
    }

    "a type with a rule is set only by whoever meets it, on every write" {
        val site = createSite("site1902b")
        val message = "You may not make this form's type 'Video'."
        site.lead.refused(EXC.notAuthorized, GEP.formDocCreate, body(typed("video"))) shouldContain message
        site.lead.refused(EXC.notAuthorized, GEP.formDocImport, importOf(typed("video"))) shouldContain message
        val mine = site.lead.make(typed("note"))
        site.lead.refused(EXC.notAuthorized, GEP.patch, setType(mine, "video")) shouldContain message
        site.lead.typeOf(mine) shouldBe "note"
        site.admin.typeOf(site.admin.make(typed("video"))) shouldBe "video"
        // An administrator makes the lead's form a video, and the lead still edits what else it holds.
        site.admin.postItems(GEP.patch, setType(mine, "video"))
        site.lead.postItems(GEP.patch, edit(mine, GedraEditAction.addOrReplace, "ask", mapOf("text" to "Still mine.")))
        site.lead.typeOf(mine) shouldBe "video"
    }

    "an import stores none of its forms when the type of one is refused" {
        val site = createSite("site1902j")
        fun imports(vararg types: String) = mapOf(GIF.data to mapOf(EP.items to types.map { body(typed(it)) }))
        fun held() = site.lead.getItems(GEP.formDocs).size
        site.lead.refused(EXC.notAuthorized, GEP.formDocImport, imports("lead", "video")) shouldContain "You may not make"
        site.lead.refused(EXC.badInput, GEP.formDocImport, imports("lead", "nosuch")) shouldContain "has no form type"
        held() shouldBe 0
        site.lead.postData(GEP.formDocImport, imports("lead", "note"))
        held() shouldBe 2
    }

    "a type is fixed once stored, unless it may be left" {
        val site = createSite("site1902c")
        val fixed = site.lead.make(typed("lead"))
        val message = "This form's type, 'Lead', was settled when it was stored"
        site.lead.refused(EXC.badInput, GEP.patch, setType(fixed, "note")) shouldContain message
        site.lead.refused(EXC.badInput, GEP.patch, edit(fixed, GedraEditAction.deleteOrNoOp, GT.formType)) shouldContain message
        // Not an administrator's to change either.
        site.admin.refused(EXC.badInput, GEP.patch, setType(fixed, "video")) shouldContain message
        // Resending the type it has is no change.
        site.lead.postItems(GEP.patch, setType(fixed, "lead"))
        // One that may be left is changed and removed, and the traits the form holds stay.
        val loose = site.lead.make(typed("note"), ask("Kept."))
        site.lead.postItems(GEP.patch, setType(loose, "memo"))
        site.lead.typeOf(loose) shouldBe "memo"
        site.lead.postItems(GEP.patch, edit(loose, GedraEditAction.deleteOrNoOp, GT.formType))
        site.lead.typeOf(loose) shouldBe null
        site.lead.getItem(GEP.formDoc, mapOf(GDF.gedraId to loose))[GDF.entries].toJsonListOfMaps()
            .map { it[GE.traitId] } shouldContainExactly listOf("ask")
    }

    "a form of a type no longer defined is still written, and may leave it" {
        val site = createSite("site1902d")
        val form = site.lead.make(typed("lead"), ask("Before."))
        write(siteConfig(site.client) { formType("note", "Note", mayLeave = true) }, site.client)
        site.lead.postItems(GEP.patch, edit(form, GedraEditAction.addOrReplace, "ask", mapOf("text" to "After.")))
        site.lead.typeOf(form) shouldBe "lead"
        site.lead.refused(EXC.badInput, GEP.formDocCreate, body(typed("lead"))) shouldContain "has no form type 'lead'"
        site.lead.postItems(GEP.patch, setType(form, "note"))
        site.lead.typeOf(form) shouldBe "note"
    }

    /** The choices of a form's type among [defs], as a page reads them. */
    fun choicesIn(defs: Any?): List<String> = defs.toJsonMapOrEmpty().values.map { it.toJsonMapOrEmpty() }
        .mapNotNull { it[SCH.properties].toJsonMapOrEmpty()[GT.formTypeField]?.toJsonMapOrEmpty() }
        .first { SCH.options in it }[SCH.options].toJsonListOfMaps().map { it[SCH.value].toString() }

    "a form's page offers the types the caller may set and the form's own, and the catalog lists them all" {
        val site = createSite("site1902e")
        val view = clientPath(GEP.workflowView, site.client)
        fun offered(user: TestUser, args: Map<String, Any?> = emptyMap()) = choicesIn(user.getData(view, args)[SCH.dDefs])
        offered(site.lead) shouldContainExactly listOf("lead", "note", "memo")
        offered(site.admin) shouldContainExactly listOf("lead", "video", "note", "memo")
        // Its labels, wherever a form is shown, are the catalog's.
        val create = site.lead.catalogLookup(clientPath(GEP.formDocCreate, site.client), HttpMethod.POST)
        choicesIn(create[SCH.dDefs]) shouldContainExactly listOf("lead", "video", "note", "memo")
        // Another client's, for an administrator of every client looking at it.
        val chief = TestUser.createFullAdmin(cxt, "chief@formtypes1902.test")
        choicesIn(chief.catalog(mapOf(EI.client to site.client))[SCH.dDefs]) shouldContainExactly listOf("lead", "video", "note", "memo")
        val service = FormTypeService.get(cxt)
        val caller = cxt.mkSubContext("choices", site.client)
        // The form's own though the caller may not set it, and, when the form may not leave it, no other.
        service.choicesFor(caller, site.client, "video").map { it.value } shouldContainExactly listOf("video")
        service.choicesFor(caller, site.client, "note").map { it.value } shouldContainExactly listOf("lead", "note", "memo")
    }

    "a client has the trait by defining types, and by no group" {
        val site = createSite("site1902f")
        val schema = SchemaService.get(cxt)
        fun traitsOf(client: String) =
            schema.supportedGedraTraitsFor(client, ClientService.get(cxt).present(client).shouldNotBeNull()).map { it.traitId }
        traitsOf(site.client) shouldContain GT.formType
        // The public client includes every global trait, and runs the global schema itself.
        traitsOf(CL.public) shouldNotContain GT.formType
        ClientService.get(cxt).known(CL.public).shouldNotBeNull().includedTraits shouldContain CLD.allGlobal
        val visitor = TestUser.create(cxt, "visitor@public1902.test", userClient = CL.public)
        visitor.refused(EXC.badInput, GEP.formDocCreate, body(typed("lead"))) shouldContain "does not support '${GT.formType}'"
        // Nor by asking to store a trait the client does not support.
        visitor.refused(EXC.badInput, GEP.formDocCreate, body(typed("lead")) + (GDF.allowAdditionalTraits to true)) shouldContain
            "has no form type 'lead'"
        // And it is none of the traits a client may narrow.
        globalTraitBases(cxt).keys.let {
            it shouldContain GT.name
            it shouldNotContain GT.formType
        }
    }

    "types declared in code are a client's, its sandbox's, and its template's children's, and a marked entry stands over one" {
        val on = Startup.mkTestBootCxt(
            "formTypesCode", "formTypesCode", additionalComponents = listOf(FormTypesComponent()),
        )
        val code = FormTypesComponent.client
        fun labels(client: String) = FormTypeService.get(on).forClient(client).mapValues { it.value.label }
        labels(code) shouldBe mapOf("lead" to "Lead", "note" to "Note")
        labels(sandboxOf(code)) shouldBe labels(code)
        val child = "child1902"
        fun writeChild(build: GedraConfigBuilder.() -> Unit = {}) {
            val config = gedraConfig(on, "main", clientNamespace(child), child) {
                defineClient(
                    ClientDef(
                        clientId = child, name = child, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = code,
                    ),
                )
                build()
            }
            GedraConfigService.get(on).writeConfig(asClient(child, on), config, trial = true)
            GedraConfigReload.reloadClient(on, child)
        }
        writeChild()
        labels(child) shouldBe labels(code)
        // Unmarked, one of the template's ids is refused; marked, the child's stands over it, whole.
        shouldThrow<KdrException> { writeChild { formType("lead", "Prospect") } }.message.orEmpty() shouldContain "form type 'lead'"
        writeChild {
            formType("lead", "Prospect", setWhen = CFACTS.hasAdminLevel)
            overridesCode("lead")
        }
        labels(child) shouldBe mapOf("lead" to "Prospect", "note" to "Note")
        FormTypeService.get(on).forClient(child).getValue("lead").traits.size shouldBe 0
        // The template is as it was.
        labels(code) shouldBe mapOf("lead" to "Lead", "note" to "Note")
    }

    "a type that is no good is refused at the write" {
        val site = createSite("site1902g")
        fun bundle(more: GedraConfigBuilder.() -> Unit): Map<String, Any?> = mapOf(
            CFEP.name to "extra", CFEP.namespaceField to clientNamespace(site.client),
            CFEP.slots to gedraConfigToEntries(gedraConfig(cxt, "extra", clientNamespace(site.client), site.client, build = more)),
        )
        fun refusal(more: GedraConfigBuilder.() -> Unit) = site.admin.expectError(EXC.badInput, CFEP.bundleWrite, bundle(more)).toString()
        refusal { formType("bad id", "Bad") } shouldContain FTYPE.formTypeId
        refusal { formType("blank", "  ") } shouldContain "'label' is missing"
        refusal { formType("lead", "Again") } shouldContain "declared more than once"
        refusal { formType("a", "A") { trait("nosuch") } } shouldContain "which the client's forms cannot hold"
        refusal { formType("a", "A") { trait("ask"); trait("ask") } } shouldContain "lists the trait 'ask' twice"
        refusal { formType("a", "A") { trait(GT.formType) } } shouldContain "where a form says its type"
        refusal { formType("a", "A", setWhen = "noSuchFact") } shouldContain "has a setWhen that does not parse"
        refusal { formType("a", "A", setWhen = "kdr:isOwner") } shouldContain "relation to the form"
        // A sound one is taken, and is the client's at once.
        site.admin.postData(CFEP.bundleWrite, bundle { formType("a", "A") { trait("ask") } })
        GedraConfigReload.reloadClient(cxt, site.client)
        FormTypeService.get(cxt).forClient(site.client).keys shouldBe setOf("lead", "video", "note", "memo", "a")
    }

    "a load keeps what it can: a trait left out, and a rule that does not parse met by nobody" {
        // Its own instance: stored configuration is judged forgivingly, as outside the unit tests.
        val forgiving = Startup.mkTestBootCxt(
            "formTypesWarn", "formTypesForgiving", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
        )
        val client = "site1902h"
        write(
            siteConfig(client, forgiving) {
                formType("lead", "Lead", setWhen = "noSuchFact") { trait("ask"); trait("nosuch") }
            },
            client, forgiving,
        )
        FormTypeService.get(forgiving).forClient(client).getValue("lead").traits.map { it.traitId } shouldBe listOf("ask")
        val admin = TestUser.create(forgiving, "admin@$client.test", level = ROLE.admin, userClient = client)
        admin.refused(EXC.notAuthorized, GEP.formDocCreate, body(typed("lead"))) shouldContain "You may not make"
    }

    "a form type's entry round-trips through its stored slot" {
        val entries = gedraConfigToEntries(siteConfig("site1902i"))[CCT.formTypeDef].orEmpty()
        entries.map { it[FTYPE.formTypeId] } shouldBe listOf("lead", "video", "note", "memo")
        entries[1] shouldBe mapOf(
            FTYPE.formTypeId to "video", FTYPE.label to "Video",
            FTYPE.traits to listOf(
                mapOf(WFD.traitId to "clip", WFD.required to true), mapOf(WFD.traitId to GT.name, WFD.required to false),
            ),
            FTYPE.setWhen to CFACTS.hasAdminLevel,
        )
        entries[2] shouldBe mapOf(
            FTYPE.formTypeId to "note", FTYPE.label to "Note", FTYPE.description to "Only a category.", FTYPE.mayLeave to true,
        )
    }
})

/** A component declaring one client in code, with form types and a sandbox. */
class FormTypesComponent : ComponentDefinition {
    override val providerName: String = "formTypesFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "base", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Code", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true, includedTraits = listOf(GT.name),
                ),
            )
            formType("lead", "Lead") { trait(GT.name) }
            formType("note", "Note", mayLeave = true)
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val client = "code1902"
    }
}
