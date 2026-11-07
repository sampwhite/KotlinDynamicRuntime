package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignSharedEdit
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.reassembleGedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.workflowDefStamp
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's stored overlay of its code (issue #1657): an entry marked `"overrides": "codeOverlay"` holds only what it
 * changes of a code workflow or type, is stored as written, and is merged over the code as it is now on every read -- so
 * a later change to the code reaches the parts the client left alone. One that does not merge is refused at write and by
 * a patch's dry run; one the code has since moved out from under is forgiven at load, part by part, and reported.
 */
class CodeOverlaysTest : StringSpec({
    val tpl = ExtensionTemplateComponent.template
    val own = ExtensionTemplateComponent.ownCode
    val cxt = TestInstances.extensionTemplate("codeOverlays1657")

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("overlayWrite", client).also { it.userId = 16570L }

    /** An overlay of the code's workflow [id]: only [definition], which it changes. */
    fun flowOverlay(id: String, definition: Map<String, Any?>): Map<String, Any?> = mapOf(
        CCT.workflowId to id, CCT.overrides to CCT.overridesCodeOverlayValue, CCT.definition to mapOf(WFD.workflowId to id) + definition,
    )

    /** What changes task [task]'s label to [label], and nothing else. */
    fun relabel(task: String, label: String): Map<String, Any?> = mapOf(WFD.tasks to listOf(mapOf(WFD.id to task, WFD.label to label)))

    /**
     * Writes [client]'s stored config [name] holding [entries] beside what [build] declares, defined on [template] when
     * given. The overlays pass the write's reassembly as written, as they do through the bundle endpoints.
     */
    fun store(
        on: KdrCxt,
        client: String,
        entries: Map<String, List<Map<String, Any?>>>,
        name: String = "main",
        template: String? = null,
        trial: Boolean = true,
    ) {
        val namespace = "${clientNamespace(client)}.$name"
        val declared = gedraConfig(on, name, namespace, client) {
            template?.let {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = it,
                    ),
                )
            }
        }
        val slots = gedraConfigToEntries(declared).toMutableMap()
        entries.forEach { (slot, added) -> slots[slot] = slots[slot].orEmpty() + added }
        GedraConfigService.get(on).writeConfig(writer(on, client), reassembleGedraConfig(on, name, namespace, client, slots), trial = trial)
    }

    /** Publishes [client]'s config [name]: Design View saves only into a configuration with nothing unpublished. */
    fun publish(on: KdrCxt, client: String, name: String = "main") =
        GedraConfigService.get(on).publish(writer(on, client), listOf(GedraId.of(GedraConfigType.configDoc, client, name)))

    fun flow(on: KdrCxt, client: String, id: String) = WorkflowService.get(on).forClient(client).workflow(id).shouldNotBeNull().def

    fun storedEntries(on: KdrCxt, client: String, slot: String, name: String = "main") =
        GedraConfigService.get(on).currentConfigs(writer(on, client), client).single { it.configId.baseId == name }
            .entriesBySlot()[slot].orEmpty()

    "an overlay of a template's workflow changes one task's label, keeps the rest, and is stored as written" {
        val client = "ovltpl1657"
        val overlay = flowOverlay("tplFlow", relabel("a", "The client's A"))
        store(cxt, client, mapOf(CCT.workflowDef to listOf(overlay)), template = tpl)
        GedraConfigReload.reloadClient(cxt, client).issues shouldBe emptyList()

        val task = flow(cxt, client, "tplFlow").tasks.single()
        task.label shouldBe "The client's A"
        // What the overlay does not mention is the template's.
        task.traits.map { it.traitId } shouldContainExactly listOf("tplNote")
        task.saves.map { it.id } shouldContainExactly listOf("s")
        // Stored as written, never as the definition it merges into.
        storedEntries(cxt, client, CCT.workflowDef).single() shouldBe overlay
    }

    "an overlay of the client's own code's workflow and type runs in the client and its sandbox" {
        val typeName = "client.$own.OwnShared"
        // A property changed and one added; the code's other one stays.
        val typeOverlay = mapOf(
            CCT.typeName to typeName, CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.schema to mapOf(
                SCH.properties to mapOf(
                    "size" to mapOf(SCH.description to "A size, in meters.", SCH.type to "number"),
                    "color" to mapOf(SCH.description to "A color."),
                ),
            ),
        )
        val relabeled = flowOverlay("ownFlow", relabel("a", "The stored task"))
        store(cxt, own, mapOf(CCT.workflowDef to listOf(relabeled), CCT.schemaDef to listOf(typeOverlay)), name = "own")
        GedraConfigService.get(cxt).publish(writer(cxt, own), listOf(GedraId.of(GedraConfigType.configDoc, own, "own")))
        GedraConfigReload.reloadClient(cxt, own)

        for (client in listOf(own, sandboxOf(own))) {
            flow(cxt, client, "ownFlow").tasks.single().label shouldBe "The stored task"
            val properties = SchemaService.get(cxt).storeFor(client).types.getValue(typeName).properties
            properties.keys.toList() shouldContainExactly listOf("code", "size", "color")
            properties.getValue("size").description shouldBe "A size, in meters."
        }

        // Without the overlays, the code's are back.
        store(cxt, own, emptyMap(), name = "own")
        GedraConfigService.get(cxt).publish(writer(cxt, own), listOf(GedraId.of(GedraConfigType.configDoc, own, "own")))
        GedraConfigReload.reloadClient(cxt, own)
        flow(cxt, own, "ownFlow").tasks.single().label shouldBe "Code's task"
        SchemaService.get(cxt).storeFor(own).types.getValue(typeName).properties.keys.toList() shouldContainExactly listOf("code", "size")
    }

    "an overlay that does not merge over the code is refused at write, and by a patch's dry run" {
        val client = "ovlbad1657"
        store(cxt, client, emptyMap(), template = tpl)
        GedraConfigReload.reloadClient(cxt, client)
        val stray = flowOverlay("tplFlow", relabel("zz", "Nothing"))
        shouldThrow<KdrException> {
            store(cxt, client, mapOf(CCT.workflowDef to listOf(stray)), template = tpl)
        }.message.shouldNotBeNull() shouldContain "'zz' matches no entry here"
        // An overlay of a workflow the code does not declare is refused too.
        shouldThrow<KdrException> {
            store(cxt, client, mapOf(CCT.workflowDef to listOf(flowOverlay("noSuchFlow", relabel("a", "A")))), template = tpl)
        }.message.shouldNotBeNull() shouldContain "declares no workflow 'noSuchFlow'"

        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val refused = chief.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "main",
                        CFEP.edits to listOf(
                            mapOf(CFEP.slot to CCT.workflowDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to stray),
                        ),
                    ),
                ),
                CFEP.dryRun to true,
            ),
        ).toString()
        refused shouldContain "'zz' matches no entry here"
    }

    "the mark's overlay value is refused on a slot that only takes a whole override" {
        val client = "ovlslot1657"
        store(cxt, client, emptyMap(), template = tpl)
        GedraConfigReload.reloadClient(cxt, client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val refused = chief.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "main",
                        CFEP.edits to listOf(
                            mapOf(
                                CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.addOrReplace.name,
                                GE.data to mapOf(
                                    CCT.name to "tplFlag", CCT.group to "tpl", CCT.description to "Changed.",
                                    CCT.overrides to CCT.overridesCodeOverlayValue,
                                ),
                            ),
                        ),
                    ),
                ),
                CFEP.dryRun to true,
            ),
        ).toString()
        refused shouldContain "kdr:cfactDef[tplFlag].overrides"
    }

    "an overlay of a template's type changes it for the client, and Design View keeps only the change" {
        val client = "ovltype1657"
        val typeName = "client.$tpl.Shared"
        val overlay = mapOf(
            CCT.typeName to typeName, CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.schema to mapOf(SCH.properties to mapOf("extra" to mapOf(SCH.description to "Something extra."))),
        )
        // In the stored bundle named like the template's copy, as the bundle editor files it.
        store(cxt, client, emptyMap(), template = tpl)
        store(cxt, client, mapOf(CCT.schemaDef to listOf(overlay)), name = "from_${tpl}_base")
        publish(cxt, client)
        publish(cxt, client, "from_${tpl}_base")
        GedraConfigReload.reloadClient(cxt, client).issues shouldBe emptyList()
        SchemaService.get(cxt).storeFor(client).types.getValue(typeName).properties.keys.toList() shouldContainExactly
            listOf("code", "extra")
        // The template keeps its own.
        SchemaService.get(cxt).storeFor(tpl).types.getValue(typeName).properties.keys.toList() shouldContainExactly listOf("code")

        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        // The listing says which bundle holds changes to which code, so the Publishing table can.
        val listed = chief.getItems(CFEP.bundles).associateBy { it[CFEP.name] }
        listed.getValue("main")[CFEP.changesCode] shouldBe null
        val copy = listed.getValue("from_${tpl}_base")
        copy[CFEP.changesCode] shouldBe true
        copy[CFEP.template] shouldBe tpl

        // A heading set in Design View is edited on the whole type and stored as part of the change.
        chief.postData(
            DSV.sharedHeadingEdit,
            mapOf(DSV.typeName to typeName, DSV.label to "Shared things", DSV.sharedBasedOn to DesignSharedEdit.stampOf(overlay)),
        )
        storedEntries(cxt, client, CCT.schemaDef, "from_${tpl}_base").single()[CCT.schema] shouldBe mapOf(
            SCH.properties to mapOf("extra" to mapOf(SCH.description to "Something extra.")),
            SCH.layout to mapOf(SL.label to "Shared things"),
        )
    }

    "Design View edits an overlay of a code workflow as it runs, and keeps only the change" {
        val client = "ovldesign1657"
        store(cxt, client, mapOf(CCT.workflowDef to listOf(flowOverlay("tplFlow", relabel("a", "The client's A")))), template = tpl)
        publish(cxt, client)
        GedraConfigReload.reloadClient(cxt, client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.postData(
            DSV.labelEdit,
            mapOf(
                DSV.workflowId to "tplFlow", DSV.taskId to "a", DSV.saveId to "s", DSV.label to "Keep it",
                DSV.basedOn to workflowDefStamp(flow(cxt, client, "tplFlow")),
            ),
        )
        val task = flow(cxt, client, "tplFlow").tasks.single()
        task.label shouldBe "The client's A"
        task.saves.single().label shouldBe "Keep it"
        storedEntries(cxt, client, CCT.workflowDef).single()[CCT.definition] shouldBe mapOf(
            WFD.workflowId to "tplFlow",
            WFD.tasks to listOf(
                mapOf(WFD.id to "a", WFD.label to "The client's A", WFD.saves to listOf(mapOf(WFD.id to "s", WFD.label to "Keep it"))),
            ),
        )
    }

    // Its own instance, since it changes the template's code as a deploy would.
    "a change to the code reaches an overlay of it; a part the code no longer has is dropped and reported" {
        val changing = Startup.mkTestBootCxt(
            "codeOverlayChange", "kdnCodeOverlayChange1657", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
            listOf(ExtensionTemplateComponent()),
        )
        val client = "ovlchange1657"
        store(changing, client, mapOf(CCT.workflowDef to listOf(flowOverlay("tplFlow", relabel("a", "The client's A")))), template = tpl)
        // Beside it, a client that detached the workflow from the code: stored whole, as it read when it detached.
        val detached = "ovldetach1657"
        store(
            changing, detached,
            mapOf(
                CCT.workflowDef to listOf(
                    gedraConfigToEntries(
                        gedraConfig(changing, "main", "${clientNamespace(detached)}.main", detached) {
                            workflow("tplFlow", WfEntry.normal) {
                                task("a", "Detached A") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) }
                            }
                            overridesCode("tplFlow")
                        },
                    ).getValue(CCT.workflowDef).single(),
                ),
            ),
            template = tpl,
        )

        val collector = SchemaCollector.get(changing).shouldNotBeNull()
        /** Deploys template code whose `tplFlow` is what [build] declares. */
        fun deploy(build: WfDefBuilder.() -> Unit) {
            val held = collector.gedraConfigs.configs.single { it.gedraId.client == tpl && it.name == "base" }
            val changed = gedraConfig(changing, "base", "client.$tpl", tpl) { workflow("tplFlow", WfEntry.normal, build) }
                .workflows.getValue("tplFlow")
            collector.replaceGedraConfig(held, held.copy(workflows = held.workflows + ("tplFlow" to changed))) shouldBe true
            GedraConfigReload.reloadClient(changing, client)
            GedraConfigReload.reloadClient(changing, detached)
        }

        // The code relabels the workflow and gives the task a second save: both reach the overlay.
        deploy {
            label = "Template flow, revised"
            task("a", "A") { trait("tplNote"); save("s", "Save", WfSaveKind.edit); save("t", "Save and close", WfSaveKind.edit) }
        }
        val revised = flow(changing, client, "tplFlow")
        revised.label shouldBe "Template flow, revised"
        revised.tasks.single().label shouldBe "The client's A"
        revised.tasks.single().saves.map { it.id } shouldContainExactly listOf("s", "t")
        ClientConfigIssues.get(changing).issuesFor(client) shouldBe emptyList()
        // The detached client stopped following the code, by choice.
        flow(changing, detached, "tplFlow").tasks.single().saves.map { it.id } shouldContainExactly listOf("s")

        // The code renames the task: the overlay's change of it has nothing to target, and is dropped and reported.
        deploy { task("b", "B") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) } }
        flow(changing, client, "tplFlow").tasks.map { it.label } shouldContainExactly listOf("B")
        val said = ClientConfigIssues.get(changing).issuesFor(client).map { it.message }
        said.single() shouldContain "changes the code's workflow 'tplFlow' with parts the code no longer has a place for"
        said.single() shouldContain "'a' matches no entry here"
        BootCheckRegistry.get(changing).results().single { it.name == BCHK.storedConfig }.findings
            .any { "'a' matches no entry here" in it } shouldBe true
        // Kept as stored, so the client's change is there to fix or remove.
        storedEntries(changing, client, CCT.workflowDef).single()[CCT.definition].toJsonMapOrEmpty()[WFD.tasks] shouldBe
            listOf(mapOf(WFD.id to "a", WFD.label to "The client's A"))
        // Design View, which edits it whole, refuses rather than drop that part unseen, and points to the bundle editor.
        publish(changing, client)
        val chief = TestUser.create(changing, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.expectError(
            EXC.badInput, DSV.labelEdit,
            mapOf(
                DSV.workflowId to "tplFlow", DSV.taskId to "b", DSV.label to "B, mine",
                DSV.basedOn to workflowDefStamp(flow(changing, client, "tplFlow")),
            ),
        ).toString() shouldContain "Fix or remove them in the bundle editor first"
    }
})
