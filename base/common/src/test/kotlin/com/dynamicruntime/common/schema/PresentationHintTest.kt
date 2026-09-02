package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The presentation-hint vocabulary (issue #540): a display hint declared beside the schema, carried through the
 * parser onto `SchType`, and *ignored by validation*. That last part is the point of it being a hint -- an
 * endpoint can say how it wants to be read without changing what it accepts. Also covers the boot-check verdict
 * the frontend colours, computed here because the server owns the findings+mode semantics.
 */
class PresentationHintTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    "a presentation hint survives the parser onto SchType, at type and property level" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Row") {
                    type = SCT.kObject
                    presentation = PRES.table
                    property("id", "an id", required = true) { presentation = PRES.identifier }
                    property("status", "a verdict", required = true) { presentation = PRES.status }
                    property("plain", "no hint", required = true)
                }
            },
        )
        val row = types.getValue("core.Row")
        row.presentation shouldBe PRES.table
        row.properties.getValue("id").valueType.presentation shouldBe PRES.identifier
        row.properties.getValue("status").valueType.presentation shouldBe PRES.status
        row.properties.getValue("plain").valueType.presentation shouldBe null
    }

    "a presentation hint changes nothing about validation" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Row") {
                    type = SCT.kObject
                    property("id", "an id", required = true) { presentation = PRES.identifier }
                }
            },
        )
        val row = types.getValue("core.Row")
        // A hinted field validates exactly as an unhinted one would: a present value passes, a missing required
        // value still fails. The hint is advisory display metadata, nothing the validator consults.
        validate(row, mapOf("id" to "abc")).shouldBeEmpty()
        validate(row, emptyMap<String, Any?>()).size shouldBe 1
    }

    "the boot-check status is the verdict, computed from findings and mode (issue #540)" {
        BootCheckResult("clean", "ENV", BootCheckMode.warn, emptyList()).status shouldBe PSTAT.ok
        BootCheckResult("noisy", "ENV", BootCheckMode.warn, listOf("a finding")).status shouldBe PSTAT.warning
        BootCheckResult("ignored", "ENV", BootCheckMode.off, listOf("a finding")).status shouldBe PSTAT.info
        BootCheckResult("fatal", "ENV", BootCheckMode.strict, listOf("a finding")).status shouldBe PSTAT.error
    }
})
