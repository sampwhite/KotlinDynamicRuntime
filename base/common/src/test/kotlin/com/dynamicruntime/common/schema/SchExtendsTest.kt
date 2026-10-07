package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.overlay.MCH
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Extensions (issue #990): a named type declared as another type plus a delta, resolved before parsing by #985's
 * merger -- properties merged by default, the layout by field, every other key replaced -- and refused where it cannot
 * be resolved. And the `$ref` beside schema keys that used to parse clean and do nothing, now refused at each site.
 */
class SchExtendsTest : StringSpec({
    fun prop(type: String = SCT.string) = mapOf<String, Any?>(SCH.type to type, SCH.description to "A field.")
    val base = mapOf<String, Any?>(
        SCH.type to SCT.kObject,
        SCH.description to "The base.",
        SCH.properties to mapOf("a" to prop(), "b" to prop(), "c" to prop(SCT.integer)),
        SCH.required to listOf("a"),
        SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "a", SL.label to "Alpha"), mapOf(SL.field to "b", SL.label to "Beta"))),
    )
    fun extension(vararg entries: Pair<String, Any?>) = mapOf<String, Any?>(SCH.extends to "kdr.B", *entries)
    fun resolve(ext: Map<String, Any?>) = resolveExtensions(mapOf("kdr.B" to base, "client.acme.A" to ext))
    fun props(resolved: ExtensionsResolved) = (resolved.defs.getValue("client.acme.A") as Map<*, *>)[SCH.properties] as Map<*, *>

    "an extension adds, replaces and removes properties, and keeps the rest" {
        val resolved = resolve(
            extension(
                SCH.properties to mapOf<String, Any?>("d" to prop(), "b" to prop(SCT.boolean), "c" to null, "a" to emptyMap<String, Any?>()),
            ),
        )
        resolved.refused shouldBe emptyMap()
        props(resolved).keys shouldBe setOf("a", "b", "d")
        (props(resolved)["b"] as Map<*, *>)[SCH.type] shouldBe SCT.boolean
        (props(resolved)["a"] as Map<*, *>)[SCH.type] shouldBe SCT.string
        // The rest of the base comes through; the directive does not.
        val a = resolved.defs.getValue("client.acme.A") as Map<*, *>
        a[SCH.required] shouldBe listOf("a")
        a.containsKey(SCH.extends) shouldBe false
        // And it compiles as an ordinary named type.
        parseSchemaTypes(resolved.defs).getValue("client.acme.A").properties.keys shouldBe setOf("a", "b", "d")
    }

    "a property the base gains later comes through, since the extension names only what it changes" {
        val grown = base + (SCH.properties to (base[SCH.properties] as Map<*, *>) + ("e" to prop()))
        val resolved = resolveExtensions(mapOf("kdr.B" to grown, "client.acme.A" to extension(SCH.properties to mapOf("d" to prop()))))
        props(resolved).keys shouldBe setOf("a", "b", "c", "e", "d")
    }

    "g-merge restates the set instead, and is not left in the type" {
        val resolved = resolve(
            extension(SCH.merge to mapOf(SCH.properties to MCH.restate), SCH.properties to mapOf<String, Any?>("a" to emptyMap<String, Any?>(), "d" to prop())),
        )
        props(resolved).keys shouldBe setOf("a", "d")
        (resolved.defs.getValue("client.acme.A") as Map<*, *>).containsKey(SCH.merge) shouldBe false
    }

    "the base's layout merges into the extension by field, and an entry for a field the extension removes goes" {
        val resolved = resolve(
            extension(
                SCH.properties to mapOf<String, Any?>("c" to null, "b" to null, "d" to prop()),
                SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "a", SL.label to "First"), mapOf(SL.field to "d", SL.label to "Delta"))),
            ),
        )
        val fields = ((resolved.defs.getValue("client.acme.A") as Map<*, *>)[SCH.layout] as Map<*, *>)[SL.schemaFields] as List<*>
        fields.map { (it as Map<*, *>)[SL.field] to it[SL.label] } shouldBe listOf("a" to "First", "d" to "Delta")
    }

    "an extension that cannot be resolved is left out and named" {
        fun refusal(defs: Map<String, Any?>, name: String = "client.acme.A") = resolveExtensions(defs).let {
            it.defs.containsKey(name) shouldBe false
            it.refused.getValue(name).message
        }
        refusal(mapOf("client.acme.A" to extension())) shouldContain "not a type here"
        refusal(mapOf("kdr.B" to base, "kdr.C" to extension(), "client.acme.A" to mapOf(SCH.extends to "kdr.C"))) shouldContain "itself extends"
        refusal(mapOf("kdr.B" to mapOf(SCH.type to SCT.string), "client.acme.A" to extension())) shouldContain "not an object type"
        refusal(mapOf("kdr.B" to base, "client.acme.A" to extension(SCH.merge to mapOf(SCH.properties to "sideways")))) shouldContain "merge"
        refusal(mapOf("client.acme.A" to mapOf(SCH.extends to 5))) shouldContain "as text"
    }

    "a document with no extension comes back as it was" {
        val defs = mapOf<String, Any?>("kdr.B" to base)
        resolveExtensions(defs).defs shouldBeSameInstanceAs defs
    }

    "g-extends below the top of a named type is refused by the parser" {
        val nested = mapOf<String, Any?>(
            "kdr.B" to base,
            "client.acme.A" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("x" to mapOf(SCH.type to SCT.kObject, SCH.extends to "kdr.B", SCH.description to "Nested.")),
            ),
        )
        val message = shouldThrow<KdrException> { parseSchemaTypes(nested) }.message.orEmpty()
        message shouldContain SCH.extends
        // Not an alteration, so the message does not send its reader to declare a new type: they are declaring one.
        message shouldNotContain "alteration"
        message shouldContain "a named type of its own"
    }

    // --- a `$ref` takes its type whole (issue #990) ---

    val refT = typeRefPath("kdr.T", "kdr")
    val target = mapOf<String, Any?>("kdr.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("n" to prop())))
    fun withType(body: Map<String, Any?>) = target + ("kdr.Holder" to body)

    "schema keys beside a property's \$ref are refused, naming the key; its use-site annotations still parse" {
        val refused = shouldThrow<KdrException> {
            parseSchemaTypes(
                withType(
                    mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("t" to mapOf(SCH.dRef to refT, SCH.properties to emptyMap<String, Any?>()))),
                ),
            )
        }.message.orEmpty()
        refused shouldContain "'${SCH.properties}'"
        refused shouldContain SCH.extends
        val annotated = mapOf(
            SCH.dRef to refT, SCH.description to "The target.", SCH.title to "Target",
            SCH.optionalContents to true, SCH.presentation to PRES.detail, SCH.dComment to "A note.",
        )
        parseSchemaTypes(withType(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("t" to annotated))))
            .getValue("kdr.Holder").properties.getValue("t").title shouldBe "Target"
    }

    "schema keys beside an items \$ref, or a union branch's, are refused too" {
        shouldThrow<KdrException> {
            parseSchemaTypes(withType(mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to refT, SCH.maxLength to 3))))
        }.message.orEmpty() shouldContain "'${SCH.maxLength}'"
        val union = mapOf(
            SCH.oneOf to listOf(mapOf(SCH.dRef to refT, SCH.type to SCT.kObject)),
            SCH.discriminator to mapOf(SCH.propertyName to "n"),
        )
        shouldThrow<KdrException> { parseSchemaTypes(withType(union)) }.message.orEmpty() shouldContain "'${SCH.type}'"
    }
})
