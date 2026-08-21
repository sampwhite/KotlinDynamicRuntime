package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the new-form page's off-screen decisions (issue #408): which catalog
 * endpoint is the create surface ([findFormCreateEndpoint]), that input is held to the request contract
 * ([checkInput]), and how a just-created form is summarized ([summarizeCreatedForm], [formatTimestamp]). Maps
 * in, verdict out — no React, no DOM, no server.
 */
class NewFormPageTest {

    private fun ep(method: String, path: String): EndpointInfo =
        EndpointInfo(path, method, "item", "gedra", null, emptyMap(), emptyMap())

    /**
     * The endpoint is matched by its client-scoped path, not the bare shared constant: the catalog answers with
     * `/gedra/<client>/formDoc/create`, which is the whole point of using it. The GET on the same resource and
     * an unrelated POST must both be passed over.
     */
    @Test
    fun findsTheScopedCreatePost() {
        val endpoints = listOf(
            ep(HttpMethod.GET.name, "/gedra/acme/formDoc"),
            ep(HttpMethod.POST.name, "/user/admin/create"),
            ep(HttpMethod.POST.name, "/gedra/acme/formDoc/create"),
        )
        assertEquals("/gedra/acme/formDoc/create", findFormCreateEndpoint(endpoints)?.path)
    }

    /** The bare shared path (a caller with no client of their own) still ends with the trait suffix. */
    @Test
    fun findsTheSharedCreatePostToo() {
        val endpoints = listOf(ep(HttpMethod.POST.name, GEP.formDocCreate))
        assertEquals(GEP.formDocCreate, findFormCreateEndpoint(endpoints)?.path)
    }

    /**
     * The section is dropped by position, so the shared and client-scoped forms of a path share a suffix --
     * and go on doing so under a **renamed section**, which is the property the derivation exists for. Cutting
     * a hardcoded "/gedra" would pass the first two of these and fail the rest.
     */
    @Test
    fun dropsTheSectionByPosition() {
        assertEquals("/formDoc/create", pathAfterSection("/gedra/formDoc/create"))
        assertEquals("/acme/formDoc/create", pathAfterSection("/gedra/acme/formDoc/create"))
        // A renamed section: the suffix still matches what a client-scoped path under it ends with.
        val renamed = pathAfterSection("/forms/formDoc/create")
        assertEquals("/formDoc/create", renamed)
        assertTrue("/forms/acme/formDoc/create".endsWith(renamed))
    }

    /** A surface with no create endpoint returns null, which the page reports rather than crashing on. */
    @Test
    fun noCreateEndpointIsNull() {
        assertNull(findFormCreateEndpoint(listOf(ep(HttpMethod.GET.name, "/gedra/acme/formDoc"))))
        assertNull(findFormCreateEndpoint(emptyList()))
    }

    /** A one-entry union type: a `name` branch and an `expenseReport` branch, the latter carrying a title. */
    private fun entriesUnion(): SchType {
        val defs = mapOf(
            "t.NameEntry" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(GE.traitId to mapOf(SCH.type to SCT.string, SCH.const to "name")),
            ),
            "t.ExpenseEntry" to mapOf(
                SCH.type to SCT.kObject,
                SCH.title to "Expense report",
                SCH.properties to mapOf(GE.traitId to mapOf(SCH.type to SCT.string, SCH.const to "expenseReport")),
            ),
            "t.Union" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.dRef to "t.NameEntry"), mapOf(SCH.dRef to "t.ExpenseEntry")),
                SCH.discriminator to mapOf(SCH.propertyName to GE.traitId),
            ),
        )
        return parseSchemaTypes(defs).getValue("t.Union")
    }

    /** The summary reads each entry's trait by its friendly label (the branch title, or a humanized id), the
     *  document's name from a `name`-trait entry, and a formatted created time. */
    @Test
    fun summarizesTraitsNameAndTime() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u123",
            GDF.createdAt to "2026-08-21T19:49:51.568Z",
            GDF.entries to listOf(
                mapOf(GE.traitId to "name", GE.data to mapOf("name" to "Q3 expenses")),
                mapOf(GE.traitId to "expenseReport", GE.data to mapOf("year" to 2026)),
            ),
        )
        val info = summarizeCreatedForm(item, entriesUnion())
        assertEquals("gd.fd.acme.u123", info.gedraId)
        assertEquals("Q3 expenses", info.title)
        // "name" has no title so it humanizes; "expenseReport"'s branch declares one.
        assertEquals(listOf("Name", "Expense report"), info.traitLabels)
        assertEquals("2026-08-21 19:49 UTC", info.createdAt)
    }

    /**
     * The title comes from the `name` **trait**, not from any entry that happens to carry a `name` field.
     * Here an expense entry has its own vendor `name` and is added first; the document's title is still the
     * one somebody actually chose, and does not depend on the order entries were added in.
     */
    @Test
    fun aFieldLevelNameIsNotTheDocumentTitle() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u7",
            GDF.entries to listOf(
                mapOf(GE.traitId to "expenseReport", GE.data to mapOf("name" to "Acme Supplies Ltd")),
                mapOf(GE.traitId to "name", GE.data to mapOf("name" to "Q3 expenses")),
            ),
        )
        assertEquals("Q3 expenses", summarizeCreatedForm(item, entriesUnion()).title)
    }

    /** An entry carrying a `name` field under some other trait does not title the document at all. */
    @Test
    fun aNameFieldOnAnotherTraitTitlesNothing() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u8",
            GDF.entries to listOf(
                mapOf(GE.traitId to "siteVisit", GE.data to mapOf("name" to "North depot")),
            ),
        )
        assertNull(summarizeCreatedForm(item, entriesUnion()).title)
    }

    /** A form with no name-bearing trait has no title -- a legitimate state, not a fake one. */
    @Test
    fun anUnnamedFormHasNoTitle() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u9",
            GDF.entries to listOf(mapOf(GE.traitId to "expenseReport", GE.data to mapOf("year" to 2026))),
        )
        val info = summarizeCreatedForm(item, entriesUnion())
        assertNull(info.title)
        assertNull(info.createdAt) // no timestamp on the row -> nothing to show
        assertEquals(listOf("Expense report"), info.traitLabels)
    }

    /** The timestamp formatter trims to minute precision and labels the zone; a non-ISO value is left alone. */
    @Test
    fun formatsTimestamps() {
        assertEquals("2026-08-21 19:49 UTC", formatTimestamp("2026-08-21T19:49:51.568Z"))
        assertEquals("whenever", formatTimestamp("whenever"))
    }

    /** A one-field object type: `title`, string, required so absence is a failure. */
    private fun titleType(): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("title" to mapOf(SCH.type to SCT.string, SCH.description to "A title")),
            SCH.required to listOf("title"),
        )
        return parseSchemaTypes(mapOf("t.Form" to schema)).getValue("t.Form")
    }

    /** A clean check yields the coerced payload to send and no failures. */
    @Test
    fun validInputProducesAPayload() {
        val check = checkInput(titleType(), mapOf("title" to "Expenses"))
        assertTrue(check.isValid)
        assertEquals(mapOf("title" to "Expenses"), check.payload)
    }

    /** A failing check withholds the payload (so it is never sent) while reporting the failure. */
    @Test
    fun invalidInputWithholdsThePayload() {
        val check = checkInput(titleType(), emptyMap())
        assertTrue(check.failures.isNotEmpty())
        assertNull(check.payload)
    }
}
