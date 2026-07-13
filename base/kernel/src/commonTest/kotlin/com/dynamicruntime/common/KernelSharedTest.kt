package com.dynamicruntime.common

import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.jsonMap
import kotlin.test.Test
import kotlin.test.assertEquals
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
