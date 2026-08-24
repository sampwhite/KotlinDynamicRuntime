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
 * Pure-logic coverage (issue #161) for the shared gedra-form helpers (issue #408): finding the client-scoped
 * create and list endpoints in the catalog, dropping a path's section, reaching the entry union, and
 * summarizing a stored form. Maps in, verdict out — no React, no DOM, no server.
 */
class GedraFormsTest {

    private fun ep(method: String, path: String): EndpointInfo =
        EndpointInfo(path, method, "item", "gedra", null, emptyMap(), emptyMap())

    /**
     * The create endpoint is matched by its client-scoped path, not the bare shared constant: the catalog
     * answers with `/gedra/<client>/formDoc/create`, which is the whole point of using it. The GET on the same
     * resource and an unrelated POST must both be passed over.
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
        assertEquals(GEP.formDocCreate, findFormCreateEndpoint(listOf(ep(HttpMethod.POST.name, GEP.formDocCreate)))?.path)
    }

    /**
     * The list endpoint is the GET whose path ends with `/formDocs` -- distinct from the single-form GET
     * (`/formDoc`, no trailing `s`) and from the create POST.
     */
    @Test
    fun findsTheScopedListGet() {
        val endpoints = listOf(
            ep(HttpMethod.GET.name, "/gedra/acme/formDoc"),
            ep(HttpMethod.POST.name, "/gedra/acme/formDoc/create"),
            ep(HttpMethod.GET.name, "/gedra/acme/formDocs"),
        )
        assertEquals("/gedra/acme/formDocs", findFormsListEndpoint(endpoints)?.path)
    }

    /**
     * The single-form GET is `/formDoc` (no trailing `s`) by the GET method -- not the list (`/formDocs`), and
     * not the DELETE that shares its path. This is what lets the view resolve a form past the loaded page.
     */
    @Test
    fun findsTheScopedSingleGet() {
        val endpoints = listOf(
            ep(HttpMethod.GET.name, "/gedra/acme/formDocs"),
            ep(HttpMethod.DELETE.name, "/gedra/acme/formDoc"),
            ep(HttpMethod.GET.name, "/gedra/acme/formDoc"),
        )
        assertEquals("/gedra/acme/formDoc", findFormGetEndpoint(endpoints)?.path)
        assertEquals("/gedra/acme/formDocs", findFormsListEndpoint(endpoints)?.path)
    }

    /**
     * The delete shares the single-form path and is told apart by the DELETE method -- so on a surface that
     * carries GET and DELETE for `/formDoc`, each finder picks its own without cross-matching.
     */
    @Test
    fun findsTheScopedDelete() {
        val endpoints = listOf(
            ep(HttpMethod.GET.name, "/gedra/acme/formDoc"),
            ep(HttpMethod.DELETE.name, "/gedra/acme/formDoc"),
        )
        assertEquals("/gedra/acme/formDoc", findFormDeleteEndpoint(endpoints)?.path)
        assertEquals(HttpMethod.DELETE.name, findFormDeleteEndpoint(endpoints)?.method)
        // The GET finder does not pick the DELETE, and vice versa.
        assertEquals(HttpMethod.GET.name, findFormGetEndpoint(endpoints)?.method)
        assertNull(findFormDeleteEndpoint(listOf(ep(HttpMethod.GET.name, "/gedra/acme/formDoc"))))
    }

    /** A surface with no such endpoint returns null, which the pages report rather than crashing on. */
    @Test
    fun missingEndpointsAreNull() {
        assertNull(findFormCreateEndpoint(listOf(ep(HttpMethod.GET.name, "/gedra/acme/formDoc"))))
        assertNull(findFormsListEndpoint(listOf(ep(HttpMethod.POST.name, "/gedra/acme/formDoc/create"))))
        assertNull(findFormCreateEndpoint(emptyList()))
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
        val renamed = pathAfterSection("/forms/formDoc/create")
        assertEquals("/formDoc/create", renamed)
        assertTrue("/forms/acme/formDoc/create".endsWith(renamed))
    }

    /** A one-branch-per-trait union: a `name` branch and an `expenseReport` branch, the latter with a title. */
    private fun unionDefs(): Map<String, Any?> = mapOf(
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

    private fun entriesUnion(): SchType = parseSchemaTypes(unionDefs()).getValue("t.Union")

    /** [entriesUnionOf] reaches the union under a form-document type's `entries` array. */
    @Test
    fun reachesTheEntriesUnion() {
        val defs = unionDefs() + mapOf(
            "t.FormDoc" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    GDF.entries to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "t.Union")),
                ),
            ),
        )
        val union = entriesUnionOf(parseSchemaTypes(defs).getValue("t.FormDoc"))
        assertTrue(union?.variants?.byValue?.containsKey("expenseReport") == true)
        // A type that is not shaped like a form document yields null rather than throwing.
        assertNull(entriesUnionOf(null))
    }

    /** The summary reads each trait by its friendly label, the document's name from a `name`-trait entry, and
     *  a formatted created time. */
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
        val info = summarizeForm(item, entriesUnion())
        assertEquals("gd.fd.acme.u123", info.gedraId)
        assertEquals("Q3 expenses", info.title)
        // "name" has no title so it humanizes; "expenseReport"'s branch declares one.
        assertEquals(listOf("Name", "Expense report"), info.traitLabels)
        assertEquals("2026-08-21 19:49 UTC", info.createdAt)
    }

    /**
     * The title comes from the `name` **trait**, not from any entry that happens to carry a `name` field. Here
     * an expense entry has its own vendor `name` and is added first; the document's title is still the one
     * somebody chose, and does not depend on the order entries were added in.
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
        assertEquals("Q3 expenses", summarizeForm(item, entriesUnion()).title)
    }

    /** An entry carrying a `name` field under some other trait does not title the document at all. */
    @Test
    fun aNameFieldOnAnotherTraitTitlesNothing() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u8",
            GDF.entries to listOf(mapOf(GE.traitId to "siteVisit", GE.data to mapOf("name" to "North depot"))),
        )
        assertNull(summarizeForm(item, entriesUnion()).title)
    }

    /** A form with no name-bearing trait and no timestamp: untitled and undated, both legitimate. */
    @Test
    fun anUnnamedFormHasNoTitle() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u9",
            GDF.entries to listOf(mapOf(GE.traitId to "expenseReport", GE.data to mapOf("year" to 2026))),
        )
        val info = summarizeForm(item, entriesUnion())
        assertNull(info.title)
        assertNull(info.createdAt)
        assertEquals(listOf("Expense report"), info.traitLabels)
    }

    /** The timestamp formatter trims to minute precision and labels the zone; a non-ISO value is left alone. */
    @Test
    fun formatsTimestamps() {
        assertEquals("2026-08-21 19:49 UTC", formatTimestamp("2026-08-21T19:49:51.568Z"))
        assertEquals("whenever", formatTimestamp("whenever"))
    }
}
