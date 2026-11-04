package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The request fields the app's own pages edit in a text area are long text in their schemas too (issue #1592), so the
 * endpoint catalog, which reads the schema and not the pages, draws a box of several lines for each.
 */
class EndpointLongTextTest : StringSpec({
    val cxt = TestInstances.default("endpointLongText")
    val store = SchemaService.get(cxt).schemaStore

    /** Per input field of the endpoint at [path], whether it is long text. */
    fun longTextOf(path: String, vararg fields: String): Map<String, Boolean> {
        val inputs = store.endpoints.values.single { it.path == path }.inputFields.orEmpty().associateBy { it.name }
        return fields.associateWith { inputs.getValue(it).schema[SCH.longText] == true }
    }

    "the copy value, the heading introductions, and the self-serve home copy are long text" {
        longTextOf(CPY.setPath, COV.value) shouldBe mapOf(COV.value to true)
        longTextOf(DSV.headingEdit, DSV.label, DSV.description) shouldBe mapOf(DSV.label to false, DSV.description to true)
        longTextOf(DSV.sharedHeadingEdit, DSV.label, DSV.description) shouldBe mapOf(DSV.label to false, DSV.description to true)
        longTextOf(AEP.createOwnClient, CLD.homeTitle, CLD.homeIntro, CLD.homeNote) shouldBe
            mapOf(CLD.homeTitle to false, CLD.homeIntro to true, CLD.homeNote to true)
    }

    "a trait narrowing's introduction is long text, and its name is not" {
        val narrowing = store.types.values.single { it.name?.endsWith(".${CIT.narrowingTypeName}") == true }
        listOf(CIT.label, CIT.description).associateWith { narrowing.properties.getValue(it).valueType.longText } shouldBe
            mapOf(CIT.label to false, CIT.description to true)
    }
})
