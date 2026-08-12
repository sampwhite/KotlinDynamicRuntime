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
 * Proof-of-concept (issue #56) that the shared kernel code runs on BOTH targets. Because it lives in
 * `commonTest` and is written in the multiplatform `kotlin.test`, this exact source is compiled and executed
 * by the `jvm` target (backend) AND the `js` target (frontend) -- the same tests, both sides. It deliberately
 * touches the meatier moved code (the JSON parser and the JSON-Schema parser/validator), not just a trivial
 * utility, to demonstrate that even those transpile and behave identically.
 *
 * The existing Kotest suites still live in `base:common` and cover this code thoroughly on the JVM; a fuller
 * cross-target suite is a follow-up (it means choosing a multiplatform test framework).
 */
class KernelSharedTest {

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
