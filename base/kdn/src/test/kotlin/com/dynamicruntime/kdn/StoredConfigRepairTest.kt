package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigReloadResult
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A fault in a client's configuration costs only itself (issue #1739 gathered these; issue #841): a fault in its stored
 * schema -- a keyword, message, layout, type, or cfact -- and in the rest of its stored configuration -- a UiBlock or
 * fragment overlay, the extends rule, a layout pull -- is dropped and reported rather than refusing the reload or the
 * next boot; and a fault in a component's own source schema is judged by the source check mode. Each part below is one
 * of the specs that used to be a file of its own, kept whole.
 */
class StoredConfigRepairTest : StringSpec({
    include(storedSchemaRepairTests)
    include(storedOverlayRepairTests)
    include(sourceSchemaForgivenessTests)
})

/**
 * A fault in a client's stored schema costs only itself (issue #841): the offending keyword, message, layout, type
 * change, or cfact is dropped as the client's variant is built -- at boot and on a reload alike -- rather than the
 * reload or the next boot being refused. Before, several of these were checked only at boot, and threw there
 * whatever the check mode: a config could pass its write and its reload and then stop the next restart (B3).
 *
 * A shared instance with the stored-config check at `warn` (the forgiving path a unit test opts into, #839) and a
 * new client per scenario; the unit-default `strict` refusal and a restart get cases of their own.
 */
private val storedSchemaRepairTests = stringSpec {

    val warn = mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name)
    val cxt = TestInstances.storedConfigWarn("storedRepair")

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("repairWrite", client).also { it.userId = 8410L }

    /** Stores a config defining [client] plus whatever [build] declares, then reloads it on [on]. */
    fun storeAndReload(on: KdrCxt, client: String, build: GedraConfigBuilder.() -> Unit): ConfigReloadResult {
        val config = gedraConfig(on, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client,
                    usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            build()
        }
        GedraConfigService.get(on).writeConfig(writer(on, client), config)
        return GedraConfigReload.reloadClient(on, client)
    }

    /** The raw definition of [typeName] in [client]'s compiled variant. */
    fun defOf(on: KdrCxt, client: String, typeName: String): Map<String, Any?> =
        SchemaService.get(on).storeFor(client).defs[typeName].toJsonMapOrEmpty()

    fun propertyOf(def: Map<String, Any?>, name: String): Map<String, Any?> =
        def[SCH.properties].toJsonMapOrEmpty()[name].toJsonMapOrEmpty()

    "an unregistered options source is dropped, and the field takes free input (B3)" {
        val client = "rep841src"
        val result = storeAndReload(cxt, client) {
            type("Pick") {
                type = SCT.kObject
                property("pick", "A choice from an unregistered source.") { optionsSource("noSuchOptionsSource841") }
                property("note", "A note.")
            }
        }
        result.loaded shouldBe 1
        val issue = result.issues.single()
        issue.message shouldContain "noSuchOptionsSource841"
        issue.elementKind shouldBe GCEL.type
        issue.elementId shouldBe "${clientNamespace(client)}.Pick"
        issue.origin shouldBe GedraConfigOrigin.stored
        propertyOf(defOf(cxt, client, "${clientNamespace(client)}.Pick"), "pick").containsKey(SCH.optionsSource) shouldBe false
        ClientService.get(cxt).present(client).shouldNotBeNull()
    }

    "a g-visibleWhen that does not parse is dropped, and the field shows for everyone" {
        val client = "rep841vis"
        val result = storeAndReload(cxt, client) {
            type("Gated") {
                type = SCT.kObject
                property("secret", "Shown to some.") { visibleWhen = "((not an expression" }
            }
        }
        result.issues.single().message shouldContain "does not parse"
        propertyOf(defOf(cxt, client, "${clientNamespace(client)}.Gated"), "secret").containsKey(SCH.visibleWhen) shouldBe false
    }

    "a g-errors message that cannot render is dropped, and the rest stay" {
        val client = "rep841err"
        val result = storeAndReload(cxt, client) {
            type("Worded") {
                type = SCT.kObject
                property("word", "A word.") {
                    errors {
                        badValue($$"No good: ${nosuchparam}")
                        wrongType("Not text.")
                    }
                }
            }
        }
        result.issues.single().message shouldContain "nosuchparam"
        val errors = propertyOf(defOf(cxt, client, "${clientNamespace(client)}.Worded"), "word")[SCH.errors].toJsonMapOrEmpty()
        errors.containsKey("badValue") shouldBe false
        errors.containsKey("wrongType") shouldBe true
    }

    // Issue #822: a `g-` key that is not one of ours, or one of ours with a value of the wrong shape, costs only
    // that keyword -- where the parser alone would refuse the whole type.
    "an unknown or ill-shaped g- keyword is dropped, and the field stands" {
        val client = "rep822kw"
        val result = storeAndReload(cxt, client) {
            type("Keyed") {
                type = SCT.kObject
                property("typo", "A misspelled keyword.") { data["g-allowCoerse"] = true }
                property("lax", "A keyword of the wrong shape.") { data[SCH.allowCoerce] = "yes" }
            }
        }
        result.issues.map { it.message }.let { messages ->
            messages.size shouldBe 2
            messages.any { "'g-allowCoerse'" in it } shouldBe true
            messages.any { "'${SCH.allowCoerce}'" in it } shouldBe true
        }
        val def = defOf(cxt, client, "${clientNamespace(client)}.Keyed")
        propertyOf(def, "typo").containsKey("g-allowCoerse") shouldBe false
        propertyOf(def, "lax").containsKey(SCH.allowCoerce) shouldBe false
        propertyOf(def, "lax")[SCH.type] shouldBe SCT.string
    }

    "a layout naming a field its type lacks is dropped, and the type stands" {
        val client = "rep841lay"
        val result = storeAndReload(cxt, client) {
            type("Laid") {
                type = SCT.kObject
                property("topic", "A topic.")
                layout { field("noSuchField841", label = "Nope") }
            }
        }
        result.issues.single().message shouldContain "noSuchField841"
        val def = defOf(cxt, client, "${clientNamespace(client)}.Laid")
        def.containsKey(SCH.layout) shouldBe false
        propertyOf(def, "topic").containsKey(SCH.type) shouldBe true
    }

    "a key the layout does not know costs that key, and the rest of the layout stands" {
        // Issue #1550: a layout stored with a key the vocabulary has since retired, as `multiline` was, keeps its copy.
        val client = "rep1550lay"
        val result = storeAndReload(cxt, client) {
            type("Lined") {
                type = SCT.kObject
                property("topic", "A topic.")
                data[SCH.layout] = mapOf(
                    SL.label to "Lined up",
                    SL.schemaFields to listOf(mapOf(SL.field to "topic", SL.label to "Topic", "multiline" to true)),
                )
            }
        }
        result.issues.single().message shouldContain "schemaFields[0].multiline"
        val layout = defOf(cxt, client, "${clientNamespace(client)}.Lined")[SCH.layout].toJsonMapOrEmpty()
        layout[SL.label] shouldBe "Lined up"
        layout[SL.schemaFields].toJsonListOfMaps().single() shouldBe mapOf(SL.field to "topic", SL.label to "Topic")
    }

    "a type that will not compile is dropped, and the client's other types stand" {
        val client = "rep841ref"
        val result = storeAndReload(cxt, client) {
            type("Broken") {
                type = SCT.kObject
                property("link", "Points at nothing.") { ref("noSuchType841") }
            }
            type("Fine") {
                type = SCT.kObject
                property("text", "Some text.")
            }
        }
        val issue = result.issues.single()
        issue.message shouldContain "does not compile"
        issue.elementId shouldBe "${clientNamespace(client)}.Broken"
        val store = SchemaService.get(cxt).storeFor(client)
        store.defs.containsKey("${clientNamespace(client)}.Broken") shouldBe false
        store.types.containsKey("${clientNamespace(client)}.Fine") shouldBe true
    }

    // An extension of a type that will not compile cannot be resolved once its base is dropped (issue #990): it is
    // dropped and named too, and a trait over it goes with it -- rather than vanishing from the composition unreported
    // while the unions still reference it.
    "an extension whose base will not compile is dropped and named with it" {
        val client = "rep990ext"
        val ns = clientNamespace(client)
        val result = storeAndReload(cxt, client) {
            type("Broken") {
                type = SCT.kObject
                property("link", "Points at nothing.") { ref("noSuchType990") }
            }
            type("Grown") {
                extends("Broken")
                property("more", "What the extension adds.")
            }
            trait("GrownEntry", "${client}Grown", setOf(GedraDataType.formDoc), dataType = "$ns.Grown")
            type("Fine") {
                type = SCT.kObject
                property("text", "Some text.")
            }
        }
        result.issues.single { it.elementId == "$ns.Broken" }.message shouldContain "does not compile"
        result.issues.single { it.elementId == "$ns.Grown" }.message shouldContain "'$ns.Broken', which is not a type here"
        val store = SchemaService.get(cxt).storeFor(client)
        store.types.containsKey("$ns.Grown") shouldBe false
        store.types.containsKey("$ns.Fine") shouldBe true
        SchemaService.get(cxt).supportedGedraTraitsFor(client, ClientService.get(cxt).present(client)!!)
            .map { it.traitId }.contains("${client}Grown") shouldBe false
    }

    // A trait whose own type will not compile goes with it (part 3 of #841): the unions and every supported-traits
    // reader leave it out, so a workflow collecting it is dropped by the check that already exists -- rather than
    // the unions referencing a missing type and taking every one of the client's changes down with them.
    "a trait whose type will not compile is dropped with it, and its sibling trait stands" {
        val client = "rep841trait"
        val result = storeAndReload(cxt, client) {
            trait("BadEntry", "${client}Bad", setOf(GedraDataType.formDoc), "A trait pointing at nothing.") {
                property("link", "Points at nothing.") { ref("noSuchType841b") }
            }
            trait("GoodEntry", "${client}Good", setOf(GedraDataType.formDoc), "A sound trait.") {
                property("text", "Some text.")
            }
            workflow("collectBad", WfEntry.creation) {
                task("only", "Only") {
                    trait("${client}Bad")
                    save("create", "Create")
                }
            }
        }
        val badEntry = result.issues.single { it.elementId == "${clientNamespace(client)}.BadEntry" }
        badEntry.message shouldContain "does not compile"
        result.issues.any { it.elementKind == GCEL.workflow && it.elementId == "collectBad" } shouldBe true
        val supported = SchemaService.get(cxt).supportedGedraTraitsFor(client, ClientService.get(cxt).present(client)!!)
            .map { it.traitId }
        supported.contains("${client}Good") shouldBe true
        supported.contains("${client}Bad") shouldBe false
        SchemaService.get(cxt).storeFor(client).types.containsKey("${clientNamespace(client)}.GoodEntry") shouldBe true
    }

    "a client cfact redeclaring a global one is dropped, and the client stands" {
        val client = "rep841cf"
        val result = storeAndReload(cxt, client) {
            cfact(CDF.coreDetailsComplete, "rep841", "A redeclaration.")
            cfact("rep841Own", "rep841", "The client's own.")
        }
        val issue = result.issues.single()
        issue.elementKind shouldBe GCEL.cfact
        issue.elementId shouldBe CDF.coreDetailsComplete
        val names = SchemaService.get(cxt).cfactsFor(client).names
        names.contains("rep841Own") shouldBe true
        ClientService.get(cxt).present(client).shouldNotBeNull()
    }

    // B3's other half: the fault no longer stops the next boot -- the restart repairs it the same way. And in unit,
    // where stored config is strict by default, both the reload and the restart refuse, naming the variable.
    "the next boot repairs it too, and strict still refuses" {
        val db = mapOf("KDR_DB_NAME" to "storedRepair_restart", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "rep841boot"
        // Its own instance (issue #1075): the first of three boots over its own database (`KDR_DB_NAME`); the restarts'
        // boot-time repair is under test.
        val first = Startup.mkTestBootCxt("storedRepair1", "storedSchemaRepair1", db + warn)
        storeAndReload(first, client) {
            type("Pick") {
                type = SCT.kObject
                property("pick", "From nowhere.") { optionsSource("noSuchOptionsSource841") }
            }
        }.issues.size shouldBe 1

        // Its own instance (issue #1075): a restart over the same database, whose boot-time repair is under test.
        val restarted = Startup.mkTestBootCxt("storedRepair2", "storedSchemaRepair2", db + warn)
        ClientService.get(restarted).present(client).shouldNotBeNull()
        val pick = propertyOf(defOf(restarted, client, "${clientNamespace(client)}.Pick"), "pick")
        pick.containsKey(SCH.optionsSource) shouldBe false

        // Its own instance (issue #1075): the boot itself is under test: a strict restart over the same database is
        // expected to refuse.
        shouldThrow<KdrException> { Startup.mkTestBootCxt("storedRepair3", "storedSchemaRepair3", db) }
            .message.shouldNotBeNull() shouldContain GCFG.storedCheckEnvVar.name
    }

    // Issue #821: a stored row that already redeclares a global cfact -- written before the write was trialled, or
    // restored -- no longer refuses the next boot. The node starts, reports it, and drops that one declaration.
    "a restart over a stored cfact redeclaring a global one starts, and drops the declaration" {
        val db = mapOf("KDR_DB_NAME" to "storedRepair_cfact", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "rep821boot"
        // Its own instance (issue #1075): the first of two boots over its own database (`KDR_DB_NAME`); the restart's
        // boot-time repair is under test.
        val first = Startup.mkTestBootCxt("storedRepairCf1", "storedSchemaRepairCf1", db + warn)
        storeAndReload(first, client) {
            cfact(CDF.coreDetailsComplete, "rep821", "A redeclaration.")
            cfact("rep821Own", "rep821", "The client's own.")
        }.issues.size shouldBe 1

        // Its own instance (issue #1075): the restart over the same database, whose boot-time repair is under test.
        val restarted = Startup.mkTestBootCxt("storedRepairCf2", "storedSchemaRepairCf2", db + warn)
        val issue = ClientConfigIssues.get(restarted).issuesFor(client).single()
        issue.elementKind shouldBe GCEL.cfact
        issue.elementId shouldBe CDF.coreDetailsComplete
        val registry = SchemaService.get(restarted).cfactsFor(client)
        registry.names.contains("rep821Own") shouldBe true
        // The global declaration stands, not the client's.
        registry.defs.getValue(CDF.coreDetailsComplete).group shouldBe
            SchemaService.get(restarted).cfactsFor(null).defs.getValue(CDF.coreDetailsComplete).group
    }

    "a client with nothing wrong is repaired of nothing" {
        storeAndReload(cxt, "rep841ok") {
            type("Clean") {
                type = SCT.kObject
                property("text", "Some text.")
            }
        }.issues.shouldBeEmpty()
    }
}

/**
 * The rest of a client's stored configuration costs only itself too (issue #841, part 2): a UiBlock or fragment
 * overlay at fault is dropped rather than served (a reload used to take both unchecked, and the boot refused a bad
 * UiBlock even in production), a stored config breaking the extends rule is skipped rather than refusing the whole
 * reload, and an unresolvable layout pull is reported under the holding config's mode rather than refusing the boot.
 *
 * A shared instance with the stored-config check at `warn` (#839) and a new client per scenario.
 */
private val storedOverlayRepairTests = stringSpec {

    val warn = mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name)
    val cxt = TestInstances.storedConfigWarn("overlayRepair")

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("overlayWrite", client).also { it.userId = 8411L }

    fun clientDef(client: String, extendsFrom: String? = null) = ClientDef(
        clientId = client, name = client,
        usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = extendsFrom,
    )

    fun storeAndReload(on: KdrCxt, client: String, build: GedraConfigBuilder.() -> Unit): ConfigReloadResult {
        val config = gedraConfig(on, "${client}cfg", clientNamespace(client), client) {
            defineClient(clientDef(client))
            build()
        }
        GedraConfigService.get(on).writeConfig(writer(on, client), config)
        return GedraConfigReload.reloadClient(on, client)
    }

    "a UiBlock overlay naming a cfact nothing declares is dropped, and the block serves without it" {
        val client = "ovl841ui"
        val result = storeAndReload(cxt, client) {
            uiBlockOverlay(HMENU.block) {
                items(HFLD.menu) { item { set(HFLD.id, "extra841"); set(UIB.cfactExpression, "noSuchFact841") } }
            }
        }
        val issue = result.issues.single()
        issue.elementKind shouldBe GCEL.uiBlock
        issue.message shouldContain "noSuchFact841"
        UiBlockService.registeredUiBlocks(cxt).none { it.client == client } shouldBe true
        ClientService.get(cxt).present(client).shouldNotBeNull()
    }

    "a fragment overlay with keys no base declares is dropped, and the client reads the default copy" {
        val client = "ovl841frag"
        val result = storeAndReload(cxt, client) {
            fragmentOverlay("home") {
                namespace("home") {
                    key("title", "Overlaid title")
                    key("noSuchKey841", "Nowhere")
                }
            }
        }
        val issue = result.issues.single()
        issue.elementKind shouldBe GCEL.fragment
        issue.message shouldContain "noSuchKey841"
        MarkdownFragmentService.registeredFragmentSources(cxt).none { it.client == client } shouldBe true
        val asClient = cxt.mkSubContext("read", client)
        MarkdownFragmentService.get(cxt).resolveFragment(asClient, "home", "home", "title") shouldBe
            MarkdownFragmentService.get(cxt).resolveFragment(cxt, "home", "home", "title")
    }

    // The reload used to refuse outright (`KdrException.mkInput`) on the first config breaking the extends rule;
    // it is now that config's problem alone, judged as stored config.
    "a stored config breaking the extends rule is skipped, not the reload" {
        val client = "ovl841ext"
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            // No source client of that name: a data config may extend only a code-defined one.
            defineClient(clientDef(client, extendsFrom = "nosuchbase"))
        }
        GedraConfigService.get(cxt).writeConfig(writer(cxt, client), config)
        val result = GedraConfigReload.reloadClient(cxt, client)
        result.loaded shouldBe 0
        result.issues.single().message shouldContain "which is not defined in code"
        ClientService.get(cxt).known(client) shouldBe null
    }

    // Checked only at boot, since the fragment registry it needs belongs to a later service. Forgiving drops
    // nothing -- delivery already renders an unresolved pull as written -- so the layout stands.
    "an unresolvable layout pull in stored config no longer refuses the restart" {
        val db = mapOf("KDR_DB_NAME" to "overlayRepair_pull", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "ovl841pull"
        // Its own instance (issue #1075): the first of three boots over its own database (`KDR_DB_NAME`); the restarts'
        // boot-time repair is under test.
        val first = Startup.mkTestBootCxt("overlayRepair1", "storedOverlayRepair1", db + warn)
        storeAndReload(first, client) {
            type("Pulled") {
                type = SCT.kObject
                property("topic", "A topic.")
                layout { field("topic", label = "%{@t(\"noSuchFile841.ns.key\")}") }
            }
        }

        // Its own instance (issue #1075): a restart over the same database, whose boot-time repair is under test.
        val restarted = Startup.mkTestBootCxt("overlayRepair2", "storedOverlayRepair2", db + warn)
        val issue = ClientConfigIssues.get(restarted).issuesFor(client).single()
        issue.message shouldContain "noSuchFile841"
        issue.elementId shouldBe "${clientNamespace(client)}.Pulled"

        // Its own instance (issue #1075): the boot itself is under test: a strict restart over the same database is
        // expected to refuse.
        shouldThrow<KdrException> { Startup.mkTestBootCxt("overlayRepair3", "storedOverlayRepair3", db) }
            .message.shouldNotBeNull() shouldContain GCFG.storedCheckEnvVar.name
    }
}

/**
 * Source-code schema is judged by the source check mode (issue #841, part 3): a fault in a component's own types --
 * here an unregistered `g-optionsSource` and a layout naming a field its type lacks -- still refuses the boot
 * outside production, but in production the faulty keyword or layout is dropped and the node serves, as every
 * other source-config check does there. Before, these refused the boot even in production.
 */
private val sourceSchemaForgivenessTests = stringSpec {

    val typeName = "${SourceFaultComponent.namespace}.Faulty"

    "outside production a component's schema fault still refuses the boot, naming the source variable" {
        val failure = shouldThrow<KdrException> {
            // Its own instance (issue #1075): the boot itself is under test: SourceFaultComponent is expected to refuse
            // it.
            Startup.mkTestBootCxt("srcFaultUnit", "sourceFaultUnitTest", additionalComponents = listOf(SourceFaultComponent()))
        }
        failure.message.shouldNotBeNull() shouldContain GCFG.checkEnvVar.name
    }

    "in production the faulty keyword and layout are dropped, and the node serves" {
        // Its own instance (issue #1075): a production boot with SourceFaultComponent: what that boot drops and serves
        // is under test.
        val cxt: KdrCxt = Startup.mkBootCxt(
            "srcFaultProd", "sourceFaultProdTest",
            // Not a test instance (so SchemaService's test-instance refusal is not what fires), yet in-memory, so no real
            // database is needed.
            Startup.realNodeOverlay(ENV.prod),
            additionalComponents = listOf(SourceFaultComponent()),
        )
        val def = SchemaService.get(cxt).storeFor(null).defs[typeName].toJsonMapOrEmpty()
        def[SCH.properties].toJsonMapOrEmpty()["pick"].toJsonMapOrEmpty().containsKey(SCH.optionsSource) shouldBe false
        def.containsKey(SCH.layout) shouldBe false

        val issues = ClientConfigIssues.get(cxt).issuesFor(GID.globalClient).filter { it.elementId == typeName }
        issues.size shouldBe 2
        issues.all { it.origin == GedraConfigOrigin.source && it.elementKind == GCEL.type } shouldBe true
    }
}

// Declared with what was `SourceSchemaForgivenessTest`.
/** A component whose one global type carries two faults the boot check finds. */
class SourceFaultComponent : ComponentDefinition {
    override val providerName: String = "sourceFaultFixture"

    /** The fixture's own owner root (issue #950). */
    override val ownerRoot: String = namespace

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "sourceFault", namespace, GID.globalClient) {
            type("Faulty") {
                type = SCT.kObject
                property("pick", "From an unregistered source.") { optionsSource("noSuchOptionsSource841c") }
                layout { field("noSuchField841c", label = "Nope") }
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val namespace = "sourcefault"
    }
}
