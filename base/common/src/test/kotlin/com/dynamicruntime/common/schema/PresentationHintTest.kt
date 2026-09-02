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
        // A check that ran and found nothing is a clean bill.
        BootCheckResult("clean", "ENV", BootCheckMode.warn, emptyList()).status shouldBe PSTAT.ok
        // A check that ran and found something is worth attention -- but never `error`: a *fatal* strict
        // finding throws before it is ever recorded (the node would not have booted), and force-allowed drift
        // is downgraded to `warn` before recording, so no finding that reaches a result was fatal to this node.
        // A warn check and a recorded strict check therefore both land on `warning`, not `error`.
        BootCheckResult("noisy", "ENV", BootCheckMode.warn, listOf("a finding")).status shouldBe PSTAT.warning
        BootCheckResult("drift", "ENV", BootCheckMode.strict, listOf("a finding")).status shouldBe PSTAT.warning
        // `off` is tested first: a disabled check records an empty findings list so it stays visible, but that
        // means *not checked*, not a clean bill -- so it is `info`, findings present or not.
        BootCheckResult("ignored", "ENV", BootCheckMode.off, listOf("a finding")).status shouldBe PSTAT.info
        BootCheckResult("ignored-clean", "ENV", BootCheckMode.off, emptyList()).status shouldBe PSTAT.info
    }
})
