package com.dynamicruntime.common

import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toOptStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kernel answers the same on both targets (issue #56; renamed from `KernelSharedTest` in issue #1738). This source
 * runs on the JVM and under Kotlin/JS alike, so each case here is one where the two could plausibly part ways -- a
 * `Double` spelled as text, a gedra id read from a URL, an expression's arithmetic, the built-in functions, a schema's
 * validation -- and a regression on one target fails here and nowhere else. The JVM-only Kotest suites in `base:common`
 * cover the same code in depth; these are the cases worth holding on both sides.
 */
class CrossTargetParityTest {

    @Test
    fun templateAndJsonAndFormatWork() {
        assertEquals("Hi Kernel", $$"Hi ${name}".evalTemplate(mapOf("name" to "Kernel")))
        assertEquals(1L, """{"a":1}""".jsonMap()?.get("a"))
        assertEquals("42", 42L.fmt())
    }

    /**
     * `toOptStr` converts primitives as of issue #267, and does it through `fmt` for one reason: a bare
     * `toString` on a `Double` prints `1.0` on the JVM and `1` under Kotlin/JS. This runs on both, so a
     * regression to `toString` fails on exactly one target — which is the only way to catch it.
     */
    @Test
    fun toOptStrSpellsAValueTheSameWayOnEveryTarget() {
        assertEquals("1", (1.0 as Any?).toOptStr())
        assertEquals("10000000000", (1.0e10 as Any?).toOptStr())
        assertEquals("42", (42 as Any?).toOptStr())
        assertEquals("true", (true as Any?).toOptStr())
        // Still held at primitives: a map has no honest string form here.
        assertNull((mapOf("a" to 1) as Any?).toOptStr())
    }

    /**
     * A gedra id reads the same in a browser as on a server (issue #287) — which is the reason the type and
     * its parse are in the kernel while minting is not. A frontend never creates a gedra, but it routes on
     * ids, displays them, and has every reason to know that the thing in the URL is a form document belonging
     * to `acme` without asking.
     */
    @Test
    fun aGedraIdReadsTheSameOnEveryTarget() {
        val id = GedraId.parse("gd.fd.acme.e20260812130405123AbCd~7")
        assertEquals(GedraDataType.formDoc, id.kind)
        assertEquals("acme", id.client)
        assertEquals("7", id.suffix)
        assertEquals("gd.fd.acme.e20260812130405123AbCd~7", id.toString())
        // Built from parts, it spells itself the same way it was read.
        assertEquals(id, GedraId.of(id.kind, id.client, id.baseId, id.suffix))
    }

    /**
     * The `${...}` expression grammar evaluates the same in a browser as on a server, which is the point of
     * putting it in the kernel: a template previewed while being edited must not resolve one way there and
     * another way when it is finally sent.
     *
     * Arithmetic is the part that could plausibly diverge, for the reason the test above exists -- a `Double`
     * spells itself differently per target unless it goes through `fmt`. So the cases here deliberately
     * produce numbers, including one whose result is integral.
     */
    @Test
    fun theExpressionGrammarEvaluatesTheSameOnEveryTarget() {
        val data = mapOf("count" to 3L, "user" to mapOf("name" to "Ada"))
        assertEquals("7", $$"${1 + 2 * 3}".evalTemplate(data))
        assertEquals("3.5", $$"${7.0 / 2}".evalTemplate(data))
        assertEquals("5", $$"${10.0 / 2}".evalTemplate(data)) // integral double: "5", never "5.0"
        assertEquals("n=3", $$"""${"n=" ~ count}""".evalTemplate(data))
        assertEquals("Ada", $$"""${user.name ?: "anonymous"}""".evalTemplate(data))
        assertEquals("anonymous", $$"""${user.missing ?: "anonymous"}""".evalTemplate(data))
        assertEquals("many", $$"""${count > 1 ? "many" : "one"}""".evalTemplate(data))
    }

    /**
     * Built-in functions have to agree across targets too, and they are likelier to drift than the operators:
     * case conversion and date formatting are exactly the places a platform library would be reached for. These
     * go through `DateUtil` and Kotlin's own `uppercase`, so the same source answers the same way on both.
     */
    @Test
    fun theBuiltInFunctionsAnswerTheSameOnEveryTarget() {
        val data = mapOf<String, Any?>(
            "name" to "  ada  ", "items" to listOf(1, 2, 3), "at" to "2026-08-13T14:30:00.000Z",
        )
        assertEquals("ADA", $$"${upper(trim(name))}".evalTemplate(data))
        assertEquals("3", $$"${count(items)}".evalTemplate(data))
        assertEquals("2026-08-13", $$"${formatDay(at)}".evalTemplate(data))
        assertEquals("2026-08-13T14:30:00.000Z", $$"${formatDate(at)}".evalTemplate(data))
        // A count is a real number, so it compares and pluralises rather than being text that looks numeric.
        assertEquals("3 items", $$"""${count(items)} ${count(items) == 1 ? "item" : "items"}""".evalTemplate(data))
    }

    @Test
    fun schemaParsesAndValidatesIdenticallyOnEveryTarget() {
        val types = parseSchemaTypes(
            mapOf(
                "Person" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("name" to mapOf("type" to "string")),
                    "required" to listOf("name"),
                ),
            ),
        )
        val person = types.getValue("Person")
        assertTrue(validate(person, mapOf("name" to "Ada")).isEmpty(), "a valid object should pass")
        assertTrue(validate(person, emptyMap<String, Any?>()).isNotEmpty(), "a missing required field should fail")
    }
}
