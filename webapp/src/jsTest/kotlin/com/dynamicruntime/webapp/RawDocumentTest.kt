package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Assembling a standalone schema document for the raw view (issue #262).
 *
 * What is worth pinning down is not the pretty-printing but the `$defs` bag: shown without it every `$ref`
 * dangles, and shown with the *whole* catalog's defs it drowns one endpoint's contract in every other
 * endpoint's types. Both failures look like a working view until someone tries to read one.
 */
class RawDocumentTest {

    private fun ref(name: String) = mapOf(SCH.dRef to "#/${SCH.dDefs}/$name")

    private val defs: Map<String, Any?> = mapOf(
        "api.Address" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("location" to ref("api.GeoPoint")),
        ),
        "api.GeoPoint" to mapOf(SCH.type to SCT.kObject),
        // Reachable only through a discriminator's defaultMapping, which is a BARE REF STRING rather than a
        // {"$ref": ...} object -- the case a keyword search walks straight past.
        "api.Unknown" to mapOf(SCH.type to SCT.kObject),
        "api.Thing" to mapOf(
            SCH.type to SCT.kObject,
            SCH.oneOf to listOf(ref("api.Address")),
            SCH.discriminator to mapOf(
                SCH.propertyName to "kind",
                SCH.defaultMapping to "#/${SCH.dDefs}/api.Unknown",
            ),
        ),
        // Nothing points at this one.
        "api.Unrelated" to mapOf(SCH.type to SCT.kObject),
    )

    private val catalog = Catalog(emptyList(), defs)

    private fun defsOf(document: Map<String, Any?>): Map<*, *> =
        assertNotNull(document[SCH.dDefs] as? Map<*, *>, $$"document should carry a $defs bag")

    @Test
    fun carriesTheTypesAReferenceReaches() {
        val document = catalog.rawDocument(
            mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("address" to ref("api.Address"))),
        )
        val bag = defsOf(document)
        // GeoPoint is reached only *through* Address, so a one-level copy would miss it and leave a dangling ref.
        assertTrue("api.Address" in bag.keys, "the referenced type: ${bag.keys}")
        assertTrue("api.GeoPoint" in bag.keys, "and what that type itself references: ${bag.keys}")
    }

    // The whole point of closing over reachability rather than shipping `defs` wholesale: one endpoint's
    // contract should not arrive buried in every other endpoint's types.
    @Test
    fun leavesOutTypesNothingReaches() {
        val document = catalog.rawDocument(
            mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("address" to ref("api.Address"))),
        )
        assertFalse("api.Unrelated" in defsOf(document).keys)
        assertFalse("api.Thing" in defsOf(document).keys)
    }

    // The payoff of sharing the kernel's walk instead of writing a second one here.
    @Test
    fun reachesAUnionsDefaultBranchThroughItsBareRefString() {
        val document = catalog.rawDocument(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("t" to ref("api.Thing"))))
        assertTrue("api.Unknown" in defsOf(document).keys, "default branch should be carried: ${defsOf(document).keys}")
    }

    // A schema that references nothing is already standalone; an empty bag would be noise in a view whose
    // whole job is to be read.
    @Test
    fun addsNoDefsBagWhenThereIsNothingToResolve() {
        val schema = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)))
        val document = catalog.rawDocument(schema)
        assertEquals(schema, document)
        assertFalse(SCH.dDefs in document.keys)
    }
}
