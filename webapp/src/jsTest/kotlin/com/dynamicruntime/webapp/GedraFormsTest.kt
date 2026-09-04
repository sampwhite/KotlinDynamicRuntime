package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.DUF
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.GedraEditAction
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
     * The values endpoint (issue #581) is `/formDoc/values` by GET -- distinct from the single-form GET
     * (`/formDoc`) and the list (`/formDocs`), and absent (null) on a surface that carries no such endpoint.
     */
    @Test
    fun findsTheScopedValuesGet() {
        val endpoints = listOf(
            ep(HttpMethod.GET.name, "/gedra/acme/formDoc"),
            ep(HttpMethod.GET.name, "/gedra/acme/formDocs"),
            ep(HttpMethod.GET.name, "/gedra/acme/formDoc/values"),
        )
        assertEquals("/gedra/acme/formDoc/values", findFormValuesEndpoint(endpoints)?.path)
        // The single-form and list finders do not pick the values endpoint.
        assertEquals("/gedra/acme/formDoc", findFormGetEndpoint(endpoints)?.path)
        // A surface without it: null, so the filter boxes stay plain text.
        assertNull(findFormValuesEndpoint(listOf(ep(HttpMethod.GET.name, "/gedra/acme/formDocs"))))
    }

    /**
     * The user-picker label (issue #581): a real name wins, else the public name (the username), else the
     * email -- so a user found by their username is shown by it, not by an email that carried no part of what
     * was typed. A placeholder username (`@<email>`) is not a public name; the email stands alone.
     */
    @Test
    fun buildsTheUserPickLabel() {
        assertEquals("Ada Lovelace — ada@x.test", userPickLabel("Ada Lovelace", "ada_l", "ada@x.test"))
        // No real name: the username is the public name and leads.
        assertEquals("grace_h — grace@x.test", userPickLabel(null, "grace_h", "grace@x.test"))
        // A placeholder username is not shown; the email stands alone.
        assertEquals("bob@x.test", userPickLabel(null, "@bob@x.test", "bob@x.test"))
        // A blank real name falls through to the username.
        assertEquals("zoe_q — zoe@x.test", userPickLabel("  ", "zoe_q", "zoe@x.test"))
        // Nothing that adds to the email: the email alone.
        assertEquals("sam@x.test", userPickLabel(null, "", "sam@x.test"))
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

    /** One computed display value, as the backend attaches it (issue #537). */
    private fun displayValue(traitId: String, label: String, value: String): Map<String, Any?> =
        mapOf(UF.traitId to traitId, UF.label to label, UF.value to value)

    /** The summary reads each trait by its friendly label, the document's heading and columns from the
     *  client's computed `displayValues`, and a formatted created time. */
    @Test
    fun summarizesTraitsDisplayValuesAndTime() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u123",
            GDF.createdAt to "2026-08-21T19:49:51.568Z",
            GDF.displayValues to listOf(displayValue("name", "Name", "Q3 expenses")),
            GDF.entries to listOf(
                mapOf(GE.traitId to "name", GE.data to mapOf("name" to "Q3 expenses")),
                mapOf(GE.traitId to "expenseReport", GE.data to mapOf("year" to 2026)),
            ),
        )
        val info = summarizeForm(item, entriesUnion())
        assertEquals("gd.fd.acme.u123", info.gedraId)
        // The heading is the first non-blank display value; the column carries label + value.
        assertEquals("Q3 expenses", info.title)
        assertEquals(listOf("Name"), info.displayValues.map { it.label })
        assertEquals("Q3 expenses", info.displayValues.single().value)
        // "name" has no branch title so it humanizes; "expenseReport"'s branch declares one.
        assertEquals(listOf("Name", "Expense report"), info.traitLabels)
        assertEquals("2026-08-21 19:49 UTC", info.createdAt)
    }

    /** The heading is the first **non-blank** display value: a blank one (the row lacks that trait) is skipped. */
    @Test
    fun theHeadingIsTheFirstNonBlankDisplayValue() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u7",
            GDF.displayValues to listOf(
                displayValue("auditor", "Auditor", ""),
                displayValue("name", "Name", "Q3 expenses"),
            ),
            GDF.entries to listOf(mapOf(GE.traitId to "name", GE.data to mapOf("name" to "Q3 expenses"))),
        )
        val info = summarizeForm(item, entriesUnion())
        assertEquals("Q3 expenses", info.title)
        // Both columns are present in the client's order, blank cell and all.
        assertEquals(listOf("Auditor", "Name"), info.displayValues.map { it.label })
    }

    /** A form whose display values are all blank -- the row carries none of the presented traits -- is
     *  untitled, but its columns still stand (so the table keeps a stable set). */
    @Test
    fun allBlankDisplayValuesLeaveTheFormUntitledButKeepTheColumns() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u8",
            GDF.displayValues to listOf(displayValue("name", "Name", "")),
            GDF.entries to listOf(mapOf(GE.traitId to "siteVisit", GE.data to mapOf("place" to "North depot"))),
        )
        val info = summarizeForm(item, entriesUnion())
        assertNull(info.title)
        assertEquals(listOf("Name"), info.displayValues.map { it.label })
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

    /** The owner and the last write (issue #562): carried when the row has them, null when it does not. */
    @Test
    fun carriesTheOwnerAndTheLastWriteWhenPresent() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u10",
            GDF.createdAt to "2026-08-21T19:49:51.568Z",
            GDF.updatedAt to "2026-08-22T08:05:00.000Z",
            GDF.owner to mapOf(DUF.name to "Ada", DUF.email to "ada@example.com"),
            GDF.entries to emptyList<Any?>(),
        )
        val info = summarizeForm(item, entriesUnion())
        assertEquals("2026-08-22 08:05 UTC", info.updatedAt)
        assertEquals("Ada", info.ownerName)
        assertEquals("ada@example.com", info.ownerEmail)
        // An ordinary caller's own row carries no owner, and a row without a write time has none to show.
        val bare = summarizeForm(mapOf(GDF.gedraId to "gd.fd.acme.u11", GDF.entries to emptyList<Any?>()), entriesUnion())
        assertNull(bare.updatedAt)
        assertNull(bare.ownerName)
        assertNull(bare.ownerEmail)
    }

    /** The timestamp formatter trims to minute precision and labels the zone; a non-ISO value is left alone. */
    @Test
    fun formatsTimestamps() {
        assertEquals("2026-08-21 19:49 UTC", formatTimestamp("2026-08-21T19:49:51.568Z"))
        assertEquals("whenever", formatTimestamp("whenever"))
    }

    // --- edit / patch (issue #417) ----------------------------------------------------------------------

    /** The patch endpoint is the POST whose path ends with `/patch` -- told apart from the create POST and
     *  from the same-suffix nothing-else by method and suffix. */
    @Test
    fun findsTheScopedPatchPost() {
        val endpoints = listOf(
            ep(HttpMethod.POST.name, "/gedra/acme/formDoc/create"),
            ep(HttpMethod.GET.name, "/gedra/acme/patch"), // wrong method, must be passed over
            ep(HttpMethod.POST.name, "/gedra/acme/patch"),
        )
        assertEquals("/gedra/acme/patch", findFormPatchEndpoint(endpoints)?.path)
        assertNull(findFormPatchEndpoint(listOf(ep(HttpMethod.POST.name, "/gedra/acme/formDoc/create"))))
    }

    /** [seededEdits] turns a stored form's entries into addOrReplace edits carrying trait and data, so the
     *  edit form opens on what the form currently holds. */
    @Test
    fun seedsEditsFromStoredEntries() {
        val form = mapOf(
            GDF.gedraId to "gd.fd.acme.u1",
            GDF.entries to listOf(
                mapOf(GE.traitId to "name", GE.entryId to "e1", GE.data to mapOf("name" to "Q3")),
                mapOf(GE.traitId to "expenseReport", GE.data to mapOf("year" to 2026)),
            ),
        )
        val edits = seededEdits(form)
        assertEquals(2, edits.size)
        assertEquals(GedraEditAction.addOrReplace.name, edits[0][GED.action])
        assertEquals("name", edits[0][GE.traitId])
        assertEquals(mapOf("name" to "Q3"), edits[0][GE.data])
        // The stored entryId is deliberately not seeded, even when the entry has one: a gedra holds one entry
        // per trait, so an absent id already names it, and seeding it broke switching a section's trait.
        assertEquals(false, edits[0].containsKey(GE.entryId))
        assertEquals(false, edits[1].containsKey(GE.entryId))
    }

    /** [formDocPatchBody] wraps one edited target back into the `targets`-grouped-by-kind body the endpoint reads. */
    @Test
    fun wrapsATargetIntoThePatchBody() {
        val target = mapOf(GDF.gedraId to "gd.fd.acme.u1", GPF.edits to listOf<Map<String, Any?>>())
        val body = formDocPatchBody(target)
        @Suppress("UNCHECKED_CAST")
        val group = (body[GPF.targets] as Map<String, Any?>)[GedraDataType.formDoc.name] as List<Map<String, Any?>>
        assertEquals(listOf(target), group)
    }

    /** [formDocPatchTargetType] reaches the one-target `PatchTarget` shape inside the patch input type. */
    @Test
    fun reachesThePatchTargetType() {
        val defs = mapOf(
            "t.PatchTarget" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    GDF.gedraId to mapOf(SCH.type to SCT.string),
                    GPF.edits to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.kObject)),
                ),
            ),
            "t.PatchTargets" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    GedraDataType.formDoc.name to
                        mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "t.PatchTarget")),
                ),
            ),
            "t.PatchInput" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(GPF.targets to mapOf(SCH.dRef to "t.PatchTargets")),
            ),
        )
        val target = formDocPatchTargetType(parseSchemaTypes(defs).getValue("t.PatchInput"))
        assertTrue(target?.properties?.containsKey(GDF.gedraId) == true)
        assertTrue(target?.properties?.containsKey(GPF.edits) == true)
        assertNull(formDocPatchTargetType(null))
    }

    /** [appliedTraitLabels] names the traits a patch actually changed, by their friendly labels; an all-noop
     *  patch names none, which the page reports as "no changes". */
    @Test
    fun namesTheTraitsAPatchApplied() {
        val patched = listOf(
            mapOf(
                GDF.gedraId to "gd.fd.acme.u1",
                GPF.outcomes to listOf(
                    mapOf(GE.traitId to "expenseReport", GPF.applied to true),
                    mapOf(GE.traitId to "name", GPF.applied to false), // unchanged, excluded
                ),
            ),
        )
        assertEquals(listOf("Expense report"), appliedTraitLabels(patched, entriesUnion()))
        // Nothing applied -> no labels.
        val noop = listOf(mapOf(GPF.outcomes to listOf(mapOf(GE.traitId to "name", GPF.applied to false))))
        assertTrue(appliedTraitLabels(noop, entriesUnion()).isEmpty())
    }
}
