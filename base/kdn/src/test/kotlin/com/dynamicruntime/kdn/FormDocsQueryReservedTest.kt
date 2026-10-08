package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.formDocsQueryDefName
import com.dynamicruntime.common.gedra.gedraSearchParams
import com.dynamicruntime.common.gedra.reservedQueryFieldNames
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The forms-listing query type against the kernel's reserved names (issue #987). The list is written by hand
 * beside the search generator, and the type is authored with the endpoint, so nothing but this holds them
 * together: a field added to the type and not to the list would be one a client's trait usage could ask for
 * without being told, and the webapp would draw it as a trait search box.
 */
class FormDocsQueryReservedTest : StringSpec({
    "the listing's query type declares exactly the reserved names the framework does not append" {
        val cxt = TestInstances.default("formDocsReserved")
        val schema = SchemaService.get(cxt)
        // The type as served carries the global usage rules' search fields too; what is left is its own.
        val searchFields = gedraSearchParams(schema.traitUsagesFor(GID.globalClient)).map { it.name }.toSet()
        val declared = schema.storeFor(null).types.getValue(formDocsQueryDefName()).properties.keys - searchFields
        declared shouldBe reservedQueryFieldNames - setOf(EP.limit, EP.after)
    }
})
