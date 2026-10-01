package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.schema.SCT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A component's global names live under the owner root it declares (issue #950), and a contribution under another
 * owner's root -- `kdr` included -- says so by name, config by config. Driven through the collector the boot uses,
 * with a fake component in hand, so each refusal is reached directly.
 */
class ComponentOwnershipTest : StringSpec({

    fun cxtIn(env: String): KdrCxt = KdrCxt("ownership", KdrInstanceConfig("ownership-$env", env, ENV.liveSource))
    val devCxt = cxtIn(ENV.local)

    class Fixture(override val providerName: String, override val ownerRoot: String?) : ComponentDefinition

    val abc = Fixture("abcComponent", "abc")

    /** A module in [namespace] declaring one type, [typeName] (bare, so it takes the namespace, unless dotted). */
    fun module(namespace: String, typeName: String = "Thing", contributesTo: String? = null) =
        schemaModule(devCxt, namespace, contributesTo) { type(typeName) { type = SCT.kObject; property("x", "X.") } }

    fun contribute(component: ComponentDefinition, cxt: KdrCxt = devCxt, collector: SchemaCollector = SchemaCollector(), block: SchemaCollector.() -> Unit): SchemaCollector {
        collector.contributingAs(cxt, component) { collector.block() }
        return collector
    }

    fun refusal(component: ComponentDefinition = abc, block: SchemaCollector.() -> Unit): String =
        shouldThrow<KdrException> { contribute(component, block = block) }.fullMessage()

    "a component contributes under its own root, at the root or beneath it" {
        val collector = contribute(abc) {
            addModule(module("abc"))
            addModule(module("abc.forms"))
        }
        collector.defs.keys shouldBe setOf("abc.Thing", "abc.forms.Thing")
        collector.gedraConfigs.issues.shouldBeEmpty()
    }

    "a contribution under another owner's root is refused unless it says so" {
        refusal { addModule(module("kdr.extra")) } shouldContain "contributesTo = \"kdr\""
        // Named, it is taken: one component both adds its own behavior and extends core's.
        val collector = contribute(abc) {
            addModule(module("abc"))
            addModule(module("kdr.extra", contributesTo = OWNR.kdrRoot))
        }
        collector.defs.keys shouldBe setOf("abc.Thing", "kdr.extra.Thing")
        // The opt-in names the root the namespace is under, not some other one.
        refusal { addModule(module("kdr.extra", contributesTo = "xyz")) } shouldContain "says it contributes to the root 'xyz'"
    }

    "the client root, a malformed root, and a component with no root are refused" {
        refusal { addModule(module("client.acme")) } shouldContain "reserved 'client' root"
        refusal { addModule(module("2abc")) } shouldContain "has no owner root"
        refusal(Fixture("rootless", null)) { addModule(module("abc")) } shouldContain "declares no owner root"
    }

    // A dotted name is taken as written -- right for a reference, wrong for a declaration -- so a declaration is held
    // to its container's root as well as the container to the component's.
    "a type declared outside its contribution's root is refused" {
        refusal { addModule(module("abc", typeName = "kdr.core.Thing")) } shouldContain
            "'kdr.core.Thing' is declared in 'abc' but is not under its root 'abc'"
    }

    // Declarations add: a second one of a name would otherwise replace the first by load order.
    "a type declared twice is refused, whichever component declares it again" {
        val collector = contribute(abc) { addModule(module("abc")) }
        shouldThrow<KdrException> {
            contribute(Fixture("abcAgain", "abc"), collector = collector) { addModule(module("abc")) }
        }.fullMessage() shouldContain "'abc.Thing' is declared twice"
    }

    "a global config is held to the same rules as a module" {
        // A global trait id takes the root of the namespace it is declared in (issue #951) -- a contribution to `kdr`
        // declares `kdr:` traits.
        fun config(namespace: String, contributesTo: String? = null) =
            gedraConfig(devCxt, "abcTraits", namespace, contributesTo = contributesTo) {
                trait("NoteEntry", "${namespace.substringBefore('.')}:note", setOf(GedraDataType.formDoc)) { property("text", "A note.") }
            }
        refusal { addGedraConfig(devCxt, config("kdr.notes")) } shouldContain "contributesTo = \"kdr\""
        contribute(abc) { addGedraConfig(devCxt, config("kdr.notes", contributesTo = OWNR.kdrRoot)) shouldBe true }
        contribute(abc) { addGedraConfig(devCxt, config("abc.notes")) shouldBe true }
    }

    // Source config is strict outside production and forgiven in it: logged, recorded, and taken as declared.
    "production forgives a misplaced namespace, saying so" {
        val collector = contribute(abc, cxt = cxtIn(ENV.prod)) { addModule(module("kdr.extra")) }
        collector.defs.keys shouldBe setOf("kdr.extra.Thing")
        collector.gedraConfigs.issues.single().message shouldContain "not the component's own root 'abc'"
    }

    // A collector built by hand -- as many tests build one -- attributes nothing to a component, so nothing is judged.
    "outside a component's contributions nothing is attributed, and nothing is judged" {
        val collector = SchemaCollector()
        collector.addModule(module("anything"))
        collector.addModule(module("anything"))
        collector.defs.keys shouldBe setOf("anything.Thing")
    }
})
