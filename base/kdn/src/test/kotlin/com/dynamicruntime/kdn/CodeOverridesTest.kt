package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.reassembleGedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's stored configuration standing over its code (issue #1634): an entry marked `"overrides": "code"` stands over
 * the code layer's entry of the same key -- its template's, its own code's, and in its sandbox -- which is cut out of the
 * code; an unmarked match is refused at write, saying what it would replace, and loads as before with an issue; a marked
 * entry with nothing under it is refused. The patch's dry run is `ConfigCodeLayerTest`'s, and Acme's own code the
 * sample's `AcmeCodeOverrideTest`. A client per case, on the shared extension-template instance.
 */
class CodeOverridesTest : StringSpec({
    val tpl = ExtensionTemplateComponent.template
    val own = ExtensionTemplateComponent.ownCode
    val cxt = TestInstances.extensionTemplate("codeOverrides1634")

    fun typeOf(on: KdrCxt, client: String, traitId: String) =
        SchemaService.get(on).gedraTraitsFor(client).single { it.traitId == traitId }.typeName
    fun firstTask(on: KdrCxt, client: String, workflowId: String) =
        WorkflowService.get(on).forClient(client).workflow(workflowId).shouldNotBeNull().def.tasks.first().label
    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("overrideWrite", client).also { it.userId = 16340L }

    /** Writes [client]'s stored config [name], defining it on [template] when given -- a code client defines itself. */
    fun store(
        on: KdrCxt,
        client: String,
        name: String = "main",
        template: String? = null,
        trial: Boolean = true,
        build: GedraConfigBuilder.() -> Unit = {},
    ) {
        val config = gedraConfig(on, name, "${clientNamespace(client)}.$name", client) {
            template?.let {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = it,
                    ),
                )
            }
            build()
        }
        GedraConfigService.get(on).writeConfig(writer(on, client), config, trial = trial)
    }

    /** Publishes [client]'s config [name]: a client with a sandbox runs only what it has published. */
    fun publish(client: String, name: String) =
        GedraConfigService.get(cxt).publish(writer(cxt, client), listOf(GedraId.of(GedraConfigType.configDoc, client, name)))

    fun GedraConfigBuilder.note(entry: String, traitId: String) =
        trait(entry, traitId, setOf(GedraDataType.formDoc), "The stored note.") { property("memo", "A memo.") }

    "on a template, a marked entry stands over the template's; an unmarked one, or one over nothing, is refused at write" {
        val client = "ovrtpl1634"
        store(cxt, client, template = tpl)
        GedraConfigReload.reloadClient(cxt, client)

        // Unmarked, the write says what it would replace, and where.
        shouldThrow<KdrException> {
            store(cxt, client, template = tpl) { note("MineNoteEntry", "tplNote") }
        }.message.shouldNotBeNull() shouldContain "holds trait 'tplNote', which template '$tpl''s 'base' also declares, without " +
            "marking it \"${CCT.overrides}\": \"${CCT.overridesCodeValue}\""
        // Marked over nothing, it says so.
        shouldThrow<KdrException> {
            store(cxt, client, template = tpl) {
                note("LoneEntry", "lone")
                overridesCode("lone")
            }
        }.message.shouldNotBeNull() shouldContain "marks trait 'lone' to stand over code"

        // Marked, the trait and the workflow stand over the template's, whole.
        store(cxt, client, template = tpl) {
            note("MineNoteEntry", "tplNote")
            workflow("tplFlow", WfEntry.normal) {
                task("mine", "The client's task") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) }
            }
            // The template's `notes` report reads the field this takes away, so it stands over that too.
            report("notes", "Memos") { column("memo", "Memo", "form.tplNote.memo") }
            overridesCode("tplNote", "tplFlow", "notes")
        }
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(cxt, client, "tplNote") shouldBe "${clientNamespace(client)}.main.MineNoteEntry"
        firstTask(cxt, client, "tplFlow") shouldBe "The client's task"
        // The template's other definitions are still the client's.
        typeOf(cxt, client, "tplScore") shouldBe "client.$tpl.TplScoreEntry"
    }

    "a client's own code is stood over by a marked entry, in its sandbox too, and comes back when the entry goes" {
        // Unmarked, the write is refused, naming the code's configuration.
        shouldThrow<KdrException> {
            store(cxt, own, name = "bad") { note("BadNoteEntry", "ownNote") }
        }.message.shouldNotBeNull() shouldContain "holds trait 'ownNote', which its code's 'own' also declares"

        store(cxt, own, name = "over") {
            note("StoredNoteEntry", "ownNote")
            workflow("ownFlow", WfEntry.normal) { task("b", "The stored task") { trait("ownNote"); save("s", "Save", WfSaveKind.edit) } }
            overridesCode("ownNote", "ownFlow")
        }
        publish(own, "over")
        GedraConfigReload.reloadClient(cxt, own)
        typeOf(cxt, own, "ownNote") shouldBe "${clientNamespace(own)}.over.StoredNoteEntry"
        firstTask(cxt, own, "ownFlow") shouldBe "The stored task"
        // The sandbox runs the latest revisions over the same code.
        typeOf(cxt, sandboxOf(own), "ownNote") shouldBe "${clientNamespace(own)}.over.StoredNoteEntry"
        firstTask(cxt, sandboxOf(own), "ownFlow") shouldBe "The stored task"

        // A revision without the entries: the code's are back, as declared, in both.
        store(cxt, own, name = "over")
        publish(own, "over")
        GedraConfigReload.reloadClient(cxt, own)
        typeOf(cxt, own, "ownNote") shouldBe "client.$own.OwnNoteEntry"
        firstTask(cxt, own, "ownFlow") shouldBe "Code's task"
        typeOf(cxt, sandboxOf(own), "ownNote") shouldBe "client.$own.OwnNoteEntry"
    }

    "a mark is stored as the entry's own field, and read back" {
        val built = gedraConfig(cxt, "over", "${clientNamespace(own)}.over", own) {
            note("StoredNoteEntry", "ownNote")
            overridesCode("ownNote")
        }
        val entries = gedraConfigToEntries(built)
        entries.getValue(CCT.traitDef).single()[CCT.overrides] shouldBe CCT.overridesCodeValue
        reassembleGedraConfig(cxt, "over", built.namespace, own, entries).overridesCode shouldBe built.overridesCode
    }

    "configuration stored before the mark still loads as it did, with an issue saying to mark it" {
        val warn = TestInstances.extensionTemplateWarn("codeOverridesWarn1634")
        val client = "ovrold1634"
        // Stored as an older release would have: past the write's trial.
        store(warn, client, template = tpl, trial = false) {
            note("OldNoteEntry", "tplNote")
            report("notes", "Memos") { column("memo", "Memo", "form.tplNote.memo") }
        }
        GedraConfigReload.reloadClient(warn, client)
        typeOf(warn, client, "tplNote") shouldBe "${clientNamespace(client)}.main.OldNoteEntry"
        val said = ClientConfigIssues.get(warn).issuesFor(client).map { it.message }
        said.any { "holds trait 'tplNote'" in it && "without marking it" in it } shouldBe true
    }
})
