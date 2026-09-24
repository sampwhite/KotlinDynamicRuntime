package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigReloadResult
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A fault in a client's stored schema costs only itself (issue #841): the offending keyword, message, layout, type
 * change or cfact is dropped as the client's variant is built -- at boot and on a reload alike -- rather than the
 * reload or the next boot being refused. Before, several of these were checked only at boot, and threw there
 * whatever the check mode: a config could pass its write and its reload and then stop the next restart (B3).
 *
 * A shared instance with the stored-config check at `warn` (the forgiving path a unit test opts into, #839) and a
 * new client per scenario; the unit-default `strict` refusal and a restart get cases of their own.
 */
class StoredSchemaRepairTest : StringSpec({

    val warn = mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name)
    val cxt = Startup.mkTestBootCxt("storedRepair", "storedSchemaRepairTest", warn)

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("repairWrite", client).also { it.userId = 8410L }

    /** Stores a config defining [client] plus whatever [build] declares, then reloads it on [on]. */
    fun storeAndReload(on: KdrCxt, client: String, build: GedraConfigBuilder.() -> Unit): ConfigReloadResult {
        val config = gedraConfig(on, "${client}cfg", "${client}config", client) {
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
        issue.elementId shouldBe "${client}config.Pick"
        issue.origin shouldBe GedraConfigOrigin.stored
        propertyOf(defOf(cxt, client, "${client}config.Pick"), "pick").containsKey(SCH.optionsSource) shouldBe false
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
        propertyOf(defOf(cxt, client, "${client}config.Gated"), "secret").containsKey(SCH.visibleWhen) shouldBe false
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
        val errors = propertyOf(defOf(cxt, client, "${client}config.Worded"), "word")[SCH.errors].toJsonMapOrEmpty()
        errors.containsKey("badValue") shouldBe false
        errors.containsKey("wrongType") shouldBe true
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
        val def = defOf(cxt, client, "${client}config.Laid")
        def.containsKey(SCH.layout) shouldBe false
        propertyOf(def, "topic").containsKey(SCH.type) shouldBe true
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
        issue.elementId shouldBe "${client}config.Broken"
        val store = SchemaService.get(cxt).storeFor(client)
        store.defs.containsKey("${client}config.Broken") shouldBe false
        store.types.containsKey("${client}config.Fine") shouldBe true
    }

    "a client cfact redeclaring a global one is dropped, and the client stands" {
        val client = "rep841cf"
        val result = storeAndReload(cxt, client) {
            cfact(SVY.surveyComplete, "rep841", "A redeclaration.")
            cfact("rep841Own", "rep841", "The client's own.")
        }
        val issue = result.issues.single()
        issue.elementKind shouldBe GCEL.cfact
        issue.elementId shouldBe SVY.surveyComplete
        val names = SchemaService.get(cxt).cfactsFor(client).names
        names.contains("rep841Own") shouldBe true
        ClientService.get(cxt).present(client).shouldNotBeNull()
    }

    // B3's other half: the fault no longer stops the next boot -- the restart repairs it the same way. And in unit,
    // where stored config is strict by default, both the reload and the restart refuse, naming the variable.
    "the next boot repairs it too, and strict still refuses" {
        val db = mapOf("KDR_DB_NAME" to "storedRepair_restart", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "rep841boot"
        val first = Startup.mkTestBootCxt("storedRepair1", "storedSchemaRepair1", db + warn)
        storeAndReload(first, client) {
            type("Pick") {
                type = SCT.kObject
                property("pick", "From nowhere.") { optionsSource("noSuchOptionsSource841") }
            }
        }.issues.size shouldBe 1

        val restarted = Startup.mkTestBootCxt("storedRepair2", "storedSchemaRepair2", db + warn)
        ClientService.get(restarted).present(client).shouldNotBeNull()
        val pick = propertyOf(defOf(restarted, client, "${client}config.Pick"), "pick")
        pick.containsKey(SCH.optionsSource) shouldBe false

        shouldThrow<KdrException> { Startup.mkTestBootCxt("storedRepair3", "storedSchemaRepair3", db) }
            .message.shouldNotBeNull() shouldContain GCFG.storedCheckEnvVar.name
    }

    "a client with nothing wrong is repaired of nothing" {
        storeAndReload(cxt, "rep841ok") {
            type("Clean") {
                type = SCT.kObject
                property("text", "Some text.")
            }
        }.issues.shouldBeEmpty()
    }
})
