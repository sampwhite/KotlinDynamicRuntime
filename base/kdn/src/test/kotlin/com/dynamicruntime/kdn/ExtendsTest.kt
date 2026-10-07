package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignRefusal
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Extensions in a client's configuration (issue #990): a new type declared as another plus a delta, resolved on the
 * client's composed document -- so it extends the base as this client has it -- served resolved, used by a trait like
 * any named type, and following its base at the next load. The global type extended is core's `kdr.core.NameData`
 * (the `kdr:name` trait's data: one required `name`, at most 128 characters).
 */
class ExtendsTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("extends990", "extends990")
    val nameData = "kdr.core.NameData"

    fun write(client: String, config: String, defineIt: Boolean, trial: Boolean = false, build: GedraConfigBuilder.() -> Unit): GedraConfig {
        val built = gedraConfig(cxt, config, clientNamespace(client), client) {
            if (defineIt) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            }
            build()
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), built, trial = trial)
        return built
    }
    fun types(client: String) = SchemaService.get(cxt).storeFor(client)
    fun propertiesOf(client: String, type: String) = types(client).types.getValue(type).properties.keys

    // One client alters the global name type (a label on its field: presentation, a narrowing) in one config, and
    // extends it in another.
    val altering = "extalter990"
    write(altering, "alter", defineIt = true) {
        type(nameData) {
            keepProperty("name")
            layout { field("name", label = "What we call it") }
        }
    }
    write(altering, "ext", defineIt = false) {
        type("Named") {
            extends(nameData)
            property("nickname", "What friends call it.")
        }
        trait("NamedEntry", "named", setOf(GedraDataType.formDoc), dataType = "${clientNamespace(altering)}.Named")
    }
    GedraConfigReload.reloadClient(cxt, altering)
    val named = "${clientNamespace(altering)}.Named"

    "an extension of a global type is that type plus its delta, as the client has it" {
        propertiesOf(altering, named) shouldBe setOf("name", "nickname")
        // Built from this client's altered base: the label its alteration gave the field came with it.
        types(altering).layouts.getValue(named).fieldFor("name")?.label shouldBe "What we call it"
        // Nothing that refers to the base sees the extension.
        propertiesOf(altering, nameData) shouldBe setOf("name")
    }

    "another client's view of the base is its own" {
        val plain = "extplain990"
        write(plain, "ext", defineIt = true) {
            type("Named") {
                extends(nameData)
                property("nickname", "What friends call it.")
            }
        }
        GedraConfigReload.reloadClient(cxt, plain)
        propertiesOf(plain, "${clientNamespace(plain)}.Named") shouldBe setOf("name", "nickname")
        types(plain).layouts["${clientNamespace(plain)}.Named"]?.fieldFor("name")?.label shouldBe null
    }

    "the served schema carries the resolved type, and no directive" {
        val served = types(altering).servedDefs.getValue(named) as Map<*, *>
        served.containsKey(SCH.extends) shouldBe false
        served.containsKey(SCH.merge) shouldBe false
        (served[SCH.properties] as Map<*, *>).keys shouldBe setOf("name", "nickname")
    }

    "a trait over an extended type stores and validates against the resolved shape" {
        val user = TestUser.create(cxt, "u@$altering.test", userClient = altering)
        fun entry(vararg data: Pair<String, Any?>) = mapOf(GDF.entries to listOf(mapOf(GE.traitId to "named", GE.data to mapOf(*data))))
        user.postItem(GEP.formDocCreate, entry("name" to "Offsite", "nickname" to "The off"))[GDF.gedraId] shouldNotBe null
        // The base's rules hold on the extension: the name is required, and at most 128 characters.
        user.expectError(EXC.badInput, GEP.formDocCreate, entry("nickname" to "No name"))
        user.expectError(EXC.badInput, GEP.formDocCreate, entry("name" to "x".repeat(200)))
    }

    "an extension follows its base at the next load" {
        val growing = "extgrow990"
        write(growing, "base", defineIt = true) {
            type("Base") {
                type = SCT.kObject
                property("a", "A field.")
            }
        }
        write(growing, "ext", defineIt = false) {
            type("Grown") {
                extends("Base")
                property("b", "Another.")
            }
        }
        GedraConfigReload.reloadClient(cxt, growing)
        propertiesOf(growing, "${clientNamespace(growing)}.Grown") shouldBe setOf("a", "b")
        write(growing, "base", defineIt = true) {
            type("Base") {
                type = SCT.kObject
                property("a", "A field.")
                property("c", "One the base gained.")
            }
        }
        GedraConfigReload.reloadClient(cxt, growing)
        propertiesOf(growing, "${clientNamespace(growing)}.Grown") shouldBe setOf("a", "c", "b")
    }

    "an extension that cannot be resolved is refused at the write, outside production" {
        val refusing = "extrefuse990"
        write(refusing, "main", defineIt = true) {}
        GedraConfigReload.reloadClient(cxt, refusing)
        fun refused(build: GedraConfigBuilder.() -> Unit): String =
            shouldThrow<KdrException> { write(refusing, "bad", defineIt = false, trial = true, build = build) }.message.orEmpty()
        refused { type("Orphan") { extends("Nowhere") } } shouldContain "not a type here"
        // Extending a type the same configuration alters reads two ways at once.
        refused {
            type(nameData) {
                keepProperty("name")
                layout { field("name", label = "Ours") }
            }
            type("Both") { extends(nameData) }
        } shouldContain "reads two ways"
        // An alteration keeps its name, so it cannot extend.
        refused { type(nameData) { extends("Other") } } shouldContain "An alteration keeps the name it alters"
    }

    "Design View's shared editor refuses an extension, whose stored entry is only its delta" {
        // Published, so the refusal read is the extension's rather than the draft's a Design View save would take live.
        GedraConfigService.get(cxt).publish(cxt.mkSubContext("setup", altering), GedraId.of(GedraConfigType.configDoc, altering, "ext"))
        val designer = TestUser.create(cxt, "designer@$altering.test", level = ROLE.admin, userClient = altering)
        val read = designer.getItem(DSV.definition, mapOf(DSV.slot to CCT.schemaDef, DSV.key to named))
        read[DSV.canEditShared] shouldBe false
        read[DSV.sharedRefusalCode] shouldBe DesignRefusal.extendsType.name
        // The save refuses it too -- before the staleness check, since drawing the page again would not help.
        designer.expectError(
            400, DSV.sharedFieldEdit,
            mapOf(DSV.typeName to named, DSV.field to "name", DSV.entry to mapOf(SL.label to "Called"), DSV.sharedBasedOn to "stale"),
        ).toString() shouldContain "extends '$nameData'"
    }

    "a layout written on an extension merges with its base's by field" {
        val laid = "extlayout990"
        write(laid, "base", defineIt = true) {
            type("Base") {
                type = SCT.kObject
                property("a", "A field.")
                property("b", "Another.")
                layout {
                    field("a", label = "Alpha")
                    field("b", label = "Beta")
                }
            }
        }
        write(laid, "ext", defineIt = false) {
            type("Ext") {
                extends("Base")
                layout { field("b", label = "Bravo") }
            }
        }
        GedraConfigReload.reloadClient(cxt, laid)
        val layout = types(laid).layouts.getValue("${clientNamespace(laid)}.Ext")
        layout.fields.map { it.field to it.label } shouldBe listOf("a" to "Alpha", "b" to "Bravo")
    }

    "a component's global extension is resolved before the global document is parsed, and served resolved" {
        val withComponent = Startup.mkTestBootCxt(
            "extends990global", "extends990global", additionalComponents = listOf(GlobalExtensionComponent()),
        )
        val store = SchemaService.get(withComponent).schemaStore
        store.types.getValue("ext990.Wide").properties.keys shouldBe setOf("a", "extra")
        (store.servedDefs.getValue("ext990.Wide") as Map<*, *>).containsKey(SCH.extends) shouldBe false
    }
})

/** A component declaring a global type and a global extension of it (issue #990). */
class GlobalExtensionComponent : ComponentDefinition {
    override val providerName: String = "globalExtensionFixture"
    override val ownerRoot: String = "ext990"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "ext990Global", "ext990") {
            type("Base") {
                type = SCT.kObject
                property("a", "A field.")
            }
            type("Wide") {
                extends("Base")
                property("extra", "What the extension adds.")
            }
        },
    )
}
