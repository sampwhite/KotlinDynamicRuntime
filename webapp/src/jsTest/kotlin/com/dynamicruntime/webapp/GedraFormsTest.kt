package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GSORT
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
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.SVYS
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * The form's client read from its gedra id (issue #714): the third dot-separated segment. An absent, blank,
     * or unparseable id yields null, so an edit page falls back to the caller's own client-scoped copy.
     */
    @Test
    fun readsTheFormClientFromItsId() {
        assertEquals("acme", formClientOf("gd.fd.acme.u20260914202810102fudi8Q"))
        assertEquals("globex", formClientOf("gd.fd.globex.u20260914202810121FYV76A"))
        // Absent or malformed: null, so the caller's own client-scoped copy is used instead.
        assertNull(formClientOf(null))
        assertNull(formClientOf(""))
        assertNull(formClientOf("not-a-gedra-id"))
    }

    /**
     * Choosing a client (issue #714): the `client` selector is set or dropped, the `user` scope is dropped either
     * way (a user belongs to one client), and every other filter is kept for `loadForClient` to whitelist.
     */
    @Test
    fun choosingAClientSetsTheSelectorAndDropsTheUserScope() {
        val applied = mapOf(EI.user to "42", EI.q to "roof", "acmeSiteAuditContains" to "Grace")
        assertEquals(
            mapOf(EI.q to "roof", "acmeSiteAuditContains" to "Grace", EI.client to "acme"),
            formsSearchForClient(applied, "acme"),
        )
        // Clearing drops the selector and the user scope alike; blank reads as clearing.
        assertEquals(mapOf(EI.q to "roof"), formsSearchForClient(mapOf(EI.client to "acme", EI.user to "42", EI.q to "roof"), null))
        assertEquals(mapOf(EI.q to "roof"), formsSearchForClient(mapOf(EI.client to "acme", EI.q to "roof"), ""))
    }

    /**
     * A shared chosen-client link (issue #714 review): the `client` selector is applied only for a caller who
     * may see across clients; an ordinary caller gets the rest of the search and no filter they cannot clear.
     */
    @Test
    fun theHashClientAppliesOnlyToACrossClientCaller() {
        val hp = mapOf(HP.page to "forms", EI.client to "acme", EI.q to "roof")
        assertEquals(mapOf(EI.client to "acme", EI.q to "roof"), formsInitialSearch(hp, seeAllClients = true))
        assertEquals(mapOf(EI.q to "roof"), formsInitialSearch(hp, seeAllClients = false))
    }

    /**
     * Which client a resolved endpoint path is the copy for (issue #714): the form's client when the backend
     * answered with its copy, null when the shared endpoint answered (a client that varies nothing) -- so the
     * sibling workflow paths stay bare too -- and null with no form client to compare against.
     */
    @Test
    fun readsTheClientBackOutOfAResolvedPath() {
        assertEquals("acme", clientOfResolvedPath("/gedra/acme/workflow/view", GEP.workflowView, "acme"))
        assertNull(clientOfResolvedPath(GEP.workflowView, GEP.workflowView, "public"))
        assertNull(clientOfResolvedPath("/gedra/acme/workflow/view", GEP.workflowView, null))
        assertNull(clientOfResolvedPath(null, GEP.workflowView, "acme"))
    }

    /**
     * The editors' one way home (issue #726): the forms page, the listing's search and sort carried back out of
     * the editor's hash, and the form just worked on flagged to flash -- with the editor's own navigation keys
     * (the open form, the edit flag, the task) left behind so none rides back into the listing as a "search".
     */
    @Test
    fun theListingReturnCarriesSearchAndSortAndFlagsTheForm() {
        val editorHash = mapOf(
            HP.page to "editForm", HP.gedra to "gd.fd.acme.u1", HP.from to "forms", HP.edit to "1", HP.task to "t2",
            "acmeSiteAuditContains" to "dana", EI.q to "roof", GSORT.sort to "updated", GSORT.sortDir to "desc",
        )
        val back = formsListingReturn(editorHash, "gd.fd.acme.u1").toMap()
        assertEquals("forms", back[HP.page])
        assertEquals("gd.fd.acme.u1", back[HP.highlight])
        // The search and the sort come home.
        assertEquals("dana", back["acmeSiteAuditContains"])
        assertEquals("roof", back[EI.q])
        assertEquals("updated", back[GSORT.sort])
        assertEquals("desc", back[GSORT.sortDir])
        // The editor's own keys do not.
        assertTrue(HP.gedra !in back && HP.from !in back && HP.edit !in back && HP.task !in back)
    }

    /**
     * The survey's "View raw" (issue #726): the listing page with the form open in place, carrying the listing's
     * search and sort out of the survey's hash and none of the survey's own keys (edit flag, task, `from`).
     */
    @Test
    fun theRawViewHashOpensTheFormOnTheListingWithSearchAndSort() {
        // A DIFFERENT id in the source hash than the one passed (review): the argument must win, so a version
        // that carried the hash's own `g` through would be caught rather than pass on coinciding values.
        val surveyHash = mapOf(
            HP.page to "surveyEdit", HP.gedra to "gd.fd.acme.OTHER", HP.from to "forms", HP.edit to "1", HP.task to "t2",
            EI.q to "roof", GSORT.sort to "updated", GSORT.sortDir to "desc",
        )
        val view = formsRawViewHash(surveyHash, "gd.fd.acme.u1").toMap()
        assertEquals("forms", view[HP.page])
        assertEquals("gd.fd.acme.u1", view[HP.gedra])
        assertEquals("roof", view[EI.q])
        assertEquals("updated", view[GSORT.sort])
        assertEquals("desc", view[GSORT.sortDir])
        assertTrue(HP.from !in view && HP.edit !in view && HP.task !in view && HP.highlight !in view)
    }

    /**
     * The raw editor's "View info" (issue #726): the survey's read-only view of the form, carrying the listing's
     * search and sort and `from=forms` (so the survey's back link still leads home), and none of the editor's
     * own keys.
     */
    @Test
    fun theSurveyViewHashOpensViewInfoWithSearchAndSort() {
        // A different id in the source hash than the one passed (review), so the argument is seen to win.
        val editorHash = mapOf(
            HP.page to "editForm", HP.gedra to "gd.fd.acme.OTHER", HP.highlight to "x", HP.edit to "1",
            EI.q to "roof", GSORT.sort to "updated", GSORT.sortDir to "desc",
        )
        val view = formsSurveyViewHash(editorHash, "gd.fd.acme.u1").toMap()
        assertEquals(pageSurveyEdit, view[HP.page])
        assertEquals("forms", view[HP.from])
        assertEquals("gd.fd.acme.u1", view[HP.gedra])
        assertEquals("roof", view[EI.q])
        assertEquals("updated", view[GSORT.sort])
        assertEquals("desc", view[GSORT.sortDir])
        assertTrue(HP.highlight !in view && HP.edit !in view)
    }

    /** The note under a chosen client names the client and says where a new form would go. */
    @Test
    fun theChosenClientNoteNamesTheClient() {
        val note = chosenClientNote("Acme")
        assertTrue(note.startsWith("Showing Acme's forms"))
        assertTrue(note.contains(formsAllClientsLabel))
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
            GDF.client to "acme",
            GDF.createdAt to "2026-08-21T19:49:51.568Z",
            GDF.updatedAt to "2026-08-22T08:05:00.000Z",
            GDF.owner to mapOf(DUF.name to "Ada", DUF.email to "ada@example.com"),
            GDF.entries to emptyList<Any?>(),
        )
        val info = summarizeForm(item, entriesUnion())
        assertEquals("2026-08-22 08:05 UTC", info.updatedAt)
        assertEquals("Ada", info.ownerName)
        assertEquals("ada@example.com", info.ownerEmail)
        // The owning client (issue #668) feeds the Client column for an allClients caller.
        assertEquals("acme", info.client)
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

    /** [formDocPatchBody] wraps one edited target back into the `targets`-grouped-by-kind body the endpoint reads,
     *  and carries `allowAdditionalTraits` only when asked (issue #667). */
    @Test
    fun wrapsATargetIntoThePatchBody() {
        val target = mapOf(GDF.gedraId to "gd.fd.acme.u1", GPF.edits to listOf<Map<String, Any?>>())
        val body = formDocPatchBody(target)
        @Suppress("UNCHECKED_CAST")
        val group = (body[GPF.targets] as Map<String, Any?>)[GedraDataType.formDoc.name] as List<Map<String, Any?>>
        assertEquals(listOf(target), group)
        // An ordinary edit sends no escape hatch; only a free-form trait does (issue #667).
        assertFalse(body.containsKey(GDF.allowAdditionalTraits))
        assertEquals(true, formDocPatchBody(target, allowAdditionalTraits = true)[GDF.allowAdditionalTraits])
    }

    /**
     * Free-form trait entry (issue #667) is offered only on the cross-client admin surface: an `allClients` admin
     * editing on the shared/global copy (no resolved client), whose union lists only the global traits. A
     * per-client copy already offers that client's full set, and an ordinary caller never gets it.
     */
    @Test
    fun freeformTraitEntryOnlyOnTheCrossClientAdminSurface() {
        assertTrue(freeformTraitEntry(canSeeAllClients = true, resolvedClient = null))
        assertFalse(freeformTraitEntry(canSeeAllClients = true, resolvedClient = "acme"))
        assertFalse(freeformTraitEntry(canSeeAllClients = false, resolvedClient = null))
        assertFalse(freeformTraitEntry(canSeeAllClients = false, resolvedClient = "acme"))
    }

    /**
     * A patch target that names a trait the edit union does not list needs `allowAdditionalTraits` (issue #667);
     * one whose every edit names a known trait, or an absent target type, needs nothing -- so an ordinary edit
     * never sends the flag.
     */
    @Test
    fun detectsAFreeFormTraitOutsideTheEditUnion() {
        val defs = unionDefs() + mapOf(
            "t.PatchTarget" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    GDF.gedraId to mapOf(SCH.type to SCT.string),
                    GPF.edits to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "t.Union")),
                ),
            ),
        )
        val targetType = parseSchemaTypes(defs).getValue("t.PatchTarget")
        val unknown = mapOf(GPF.edits to listOf(mapOf(GE.traitId to "acmeSiteAudit", GED.action to GedraEditAction.addOrReplace.name)))
        assertTrue(patchNamesUnknownTrait(targetType, unknown))
        val known = mapOf(GPF.edits to listOf(mapOf(GE.traitId to "name", GED.action to GedraEditAction.addOrReplace.name)))
        assertFalse(patchNamesUnknownTrait(targetType, known))
        // No target type, or no edits: false -- an ordinary form never sends the flag.
        assertFalse(patchNamesUnknownTrait(null, unknown))
        assertFalse(patchNamesUnknownTrait(targetType, emptyMap()))
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


    /**
     * The forms search round-trips through the hash (issue #592): the navigation keys (page, the open form, the
     * `from`) are dropped and everything else -- trait filters, the scope-bar user, the free-text q -- is the
     * applied search, so a bookmarked URL and the edit round-trip both reproduce the filter.
     */
    @Test
    fun formsSearchRoundTripsThroughTheHash() {
        val applied = mapOf("acmeSiteAuditContains" to "dana", EI.user to "7", EI.q to "plan")
        val params = formsSearchHashParams(applied)
        // A hash as it would stand on the list, with navigation keys mixed in.
        // `edit=1` (issue #694) and `task=` (issue #700) are the survey child page's own keys: the chip's link and
        // the rail put them in the hash the back link is built from, and neither may ride back into the listing
        // as a "search".
        val hash = params.toMap() +
            mapOf(HP.page to "forms", HP.gedra to "gd.fd.acme.u1", HP.from to "forms", HP.edit to "1", HP.task to "extra")
        assertEquals(applied, formsSearchFromHash(hash))
        // The navigation keys are never taken for search.
        val decoded = formsSearchFromHash(hash)
        assertTrue(HP.page !in decoded && HP.gedra !in decoded && HP.from !in decoded)
        assertTrue(HP.edit !in decoded && HP.task !in decoded)
        // A blank value is not a filter, so it neither encodes nor decodes.
        assertTrue(formsSearchHashParams(mapOf("x" to "  ")).isEmpty())
        assertEquals(emptyMap(), formsSearchFromHash(mapOf(HP.page to "forms", "x" to "")))
        // No search: nothing but the navigation keys.
        assertEquals(emptyList(), formsSearchHashParams(emptyMap()))
    }

    /**
     * Seeding whitelists the hash against the listing's declared keys (issue #592 review): a stale trait filter
     * from before a client's usage rules changed, or a stray paging param, is dropped rather than sent to an
     * endpoint that would refuse the undeclared property with a 400. `user` and `q` and current trait filters
     * are declared and kept; `offset`/`limit`/`includeUsers` are declared but are not applied-search values.
     */
    @Test
    fun formsSearchKeysWhitelistsToDeclaredSearchFields() {
        val inputSchema = mapOf(
            SCH.properties to mapOf(
                EP.offset to emptyMap<String, Any?>(),
                EP.limit to emptyMap<String, Any?>(),
                EI.includeUsers to emptyMap<String, Any?>(),
                EI.user to emptyMap<String, Any?>(),
                EI.q to emptyMap<String, Any?>(),
                GSORT.sort to emptyMap<String, Any?>(),
                GSORT.sortDir to emptyMap<String, Any?>(),
                "acmeSiteAuditContains" to emptyMap<String, Any?>(),
            ),
        )
        val keys = formsSearchKeys(inputSchema)
        // Kept: the trait filter, the scope-bar user, the free-text q.
        assertTrue("acmeSiteAuditContains" in keys && EI.user in keys && EI.q in keys)
        // Dropped: paging, the owner flag, and the sort column/direction (#666) -- the sort rides its own hash
        // params and state, so treating it as an applied filter would double-emit it and wrongly read the listing
        // as narrowed (its empty state would never show).
        assertTrue(EP.offset !in keys && EP.limit !in keys && EI.includeUsers !in keys)
        assertTrue(GSORT.sort !in keys && GSORT.sortDir !in keys)
        // A stale/hand-added key the schema no longer declares is not in the whitelist, so seeding drops it.
        assertTrue("staleTraitContains" !in keys)
        val hashSearch = mapOf("acmeSiteAuditContains" to "dana", "staleTraitContains" to "x", EP.offset to "50")
        assertEquals(mapOf("acmeSiteAuditContains" to "dana"), hashSearch.filterKeys { it in keys })
    }

    /**
     * The saved-but-off-screen note for an **edit** (issue #669): shown only when a row was saved, a filter is
     * active, and the row is not on the loaded page -- so an edit that lands the row in view, or one with no
     * filter, is silent (the flash is feedback enough), while a filtered-out save leaves a clue. A create is
     * never silent; see the tests below (issue #758).
     */
    @Test
    fun savedNotShownNoteAppearsOnlyForAFilteredOutSave() {
        val filter = mapOf("acmeSiteAuditContains" to "dana")
        // Saved, filtered, and absent from the page: the note appears.
        assertTrue(savedNotShownNote("g.fd.acme.new", filter, listOf("g.fd.acme.a", "g.fd.acme.b")) != null)
        // Saved and on the page: no note -- the flash shows it.
        assertNull(savedNotShownNote("g.fd.acme.new", filter, listOf("g.fd.acme.new", "g.fd.acme.b")))
        // A filter, but nothing was saved (an ordinary filtered view): no note.
        assertNull(savedNotShownNote(null, filter, listOf("g.fd.acme.a")))
        // Saved and absent, but no filter is active: left unremarked (the row was on the list the user came from).
        assertNull(savedNotShownNote("g.fd.acme.new", emptyMap(), listOf("g.fd.acme.a")))
    }

    /**
     * A **create** is never silent (issue #758): when the new row is not on the returned page the note says the
     * form was created -- naming the filter when one is the reason, and saying so even with none (a sort or a
     * full first page can hide it), since the flash was going to be the only sign the create happened. On the
     * page, the flash is the confirmation and there is no note.
     */
    @Test
    fun aCreatedFormThatIsNotShownIsStillAnnounced() {
        val filter = mapOf("acmeSiteAuditContains" to "dana")
        val others = listOf("g.fd.acme.a", "g.fd.acme.b")
        val filtered = savedNotShownNote("g.fd.acme.new", filter, others, created = true)
        assertTrue(filtered != null && filtered.startsWith("The form was created") && filtered.contains("filter"))
        val unfiltered = savedNotShownNote("g.fd.acme.new", emptyMap(), others, created = true)
        assertTrue(unfiltered != null && unfiltered.startsWith("The form was created") && !unfiltered.contains("filter"))
        // An edit's wording is unchanged, so the two cannot be mistaken for each other.
        assertTrue(savedNotShownNote("g.fd.acme.new", filter, others)!!.startsWith("The form you just saved"))
        // On the page: the flash says it.
        assertNull(savedNotShownNote("g.fd.acme.new", filter, listOf("g.fd.acme.new"), created = true))
        // No id to look for (a response that carried none): still announced, plainly (#758 review).
        assertEquals("The form was created.", savedNotShownNote(null, filter, others, created = true))
    }

    /**
     * Every create surface returns through the editors' one way home (issue #758), flagged as a create: the
     * launching listing's search and sort carried back, the new row to flash, and the transient `created` mark.
     * An edit's return carries no such mark, and a create whose response had no id returns with no flag at all.
     */
    @Test
    fun aCreateReturnsToItsListingFlaggedAsCreated() {
        val createHash = mapOf(HP.page to "newForm", HP.from to "forms", EI.client to "acme", GSORT.sort to "name")
        val back = formsListingReturn(createHash, "gd.fd.acme.new", created = true).toMap()
        assertEquals("forms", back[HP.page])
        assertEquals("gd.fd.acme.new", back[HP.highlight])
        assertTrue(HP.created in back)
        assertEquals("acme", back[EI.client])
        assertEquals("name", back[GSORT.sort])
        assertTrue(HP.from !in back)
        assertTrue(HP.created !in formsListingReturn(createHash, "gd.fd.acme.u1").toMap())
        // A create whose response carried no id still says it was a create, so the arrival can announce it.
        val noId = formsListingReturn(createHash, null, created = true).toMap()
        assertTrue(HP.highlight !in noId && HP.created in noId)
        // The mark is a navigation key, so it never rides back in as a "search" term...
        assertTrue(HP.created !in formsSearchFromHash(mapOf(HP.created to "1", EI.q to "roof")))
        // ...and it is not spelled like a trait id a client could declare a filter on (#758 review).
        assertTrue(HP.created != GSORT.created)
    }

    /**
     * The whole path, return into arrival (#758 review): what a create writes into the hash is what the listing
     * reads back, so the flag's spelling cannot drift between the two. On the page: silent. Filtered out: the
     * create wording. An edit's return through the same path keeps the edit wording; no id: announced plainly.
     */
    @Test
    fun aCreatesReturnIsReadBackByTheArrivalNote() {
        val others = listOf("g.fd.acme.a")
        val createHash = mapOf(HP.page to "newForm", EI.q to "roof")
        val arriving = formsListingReturn(createHash, "g.fd.acme.new", created = true).toMap()
        val applied = formsSearchFromHash(arriving)
        assertNull(formsArrivalNote(arriving, applied, listOf("g.fd.acme.new")))
        assertTrue(formsArrivalNote(arriving, applied, others)!!.startsWith("The form was created"))
        val edited = formsListingReturn(createHash, "g.fd.acme.new").toMap()
        assertTrue(formsArrivalNote(edited, applied, others)!!.startsWith("The form you just saved"))
        val noId = formsListingReturn(createHash, null, created = true).toMap()
        assertEquals("The form was created.", formsArrivalNote(noId, applied, others))
        // An ordinary arrival -- nothing saved -- says nothing.
        assertNull(formsArrivalNote(mapOf(HP.page to "forms", EI.q to "roof"), applied, others))
    }

    /**
     * The note names the **reason** (#758 review): under a custom sort a filter is not necessarily at fault, so
     * the note says either could be rather than sending the user to clear a filter; with no filter a created
     * row's absence is the sort's doing, and the note says how to bring it up. The default order (absent, or
     * newest-updated first) is not a custom sort.
     */
    @Test
    fun theNoteBlamesTheSortWhenTheSortCanBeAtFault() {
        val others = listOf("g.fd.acme.a")
        val filter = mapOf(EI.q to "roof")
        val both = savedNotShownNote("g.fd.acme.new", filter, others, created = true, customSort = true)!!
        assertTrue(both.contains("filter") && both.contains("sort") && !both.contains("Clear the filter"))
        val sortOnly = savedNotShownNote("g.fd.acme.new", emptyMap(), others, created = true, customSort = true)!!
        assertTrue(sortOnly.contains("sort") && !sortOnly.contains("filter"))
        // An edit with no filter stays unremarked, custom sort or not.
        assertNull(savedNotShownNote("g.fd.acme.new", emptyMap(), others, customSort = true))
        assertTrue(!formsCustomSort(mapOf(HP.page to "forms")))
        assertTrue(!formsCustomSort(mapOf(GSORT.sort to GSORT.updated, GSORT.sortDir to GSORT.desc)))
        assertTrue(formsCustomSort(mapOf(GSORT.sort to GSORT.updated, GSORT.sortDir to GSORT.asc)))
        assertTrue(formsCustomSort(mapOf(GSORT.sort to "name")))
    }

    /**
     * A create returns only while the user is still on the page it was launched from (#758 review): a slow
     * response must not pull them out of wherever they went next. Create-for-user returns with the launching
     * search and sort, the user's client chosen, and any `user` scope dropped.
     */
    @Test
    fun aCreateReturnsOnlyFromThePageItWasLaunchedOn() {
        val launched = mapOf(HP.page to "newForm", HP.from to "forms", GSORT.sort to "name")
        val back = formsCreateReturn(launched, launched, "g.fd.acme.new")!!.toMap()
        assertEquals("forms", back[HP.page])
        assertEquals("name", back[GSORT.sort])
        assertTrue(HP.created in back)
        assertNull(formsCreateReturn(launched, mapOf(HP.page to "users"), "g.fd.acme.new"))
        assertNull(formsCreateReturn(launched, mapOf(HP.page to "forms"), "g.fd.acme.new"))
        val forUser = mapOf(HP.page to "createForUser", EI.user to "42", EI.q to "roof", GSORT.sort to "name")
        val context = formsSearchForClient(formsSearchFromHash(forUser), "acme")
        val home = formsCreateReturn(forUser, forUser, "g.fd.acme.new", context)!!.toMap()
        assertEquals("acme", home[EI.client])
        assertEquals("roof", home[EI.q])
        assertEquals("name", home[GSORT.sort])
        assertTrue(EI.user !in home)
    }

    // --- survey status column (issue #694) --------------------------------------------------------------

    /** A row's state entries carrying a `surveyCompletion` entry with the given booleans. */
    private fun surveyStates(complete: Boolean?, valid: Boolean?): List<Map<String, Any?>> {
        val data = buildMap<String, Any?> {
            if (complete != null) put(SVY.complete, complete)
            if (valid != null) put(SVY.valid, valid)
        }
        // A second, unrelated state entry too, so the finder must select by trait id rather than take the first.
        return listOf(
            mapOf(GE.traitId to "otherState", GE.data to mapOf("x" to 1)),
            mapOf(GE.traitId to SVY.surveyCompletion, GE.data to data),
        )
    }

    /**
     * The status derivation (issue #694): invalid data trumps incompleteness, an incomplete-but-valid form
     * needs info, and a complete-and-valid one is valid. The chip label and colour ride the enum.
     */
    @Test
    fun derivesSurveyStatusInvalidTrumpsIncomplete() {
        // Invalid whenever the present data fails schema, complete or not.
        assertEquals(SurveyStatus.invalid, surveyStatusFrom(surveyStates(complete = false, valid = false)))
        assertEquals(SurveyStatus.invalid, surveyStatusFrom(surveyStates(complete = true, valid = false)))
        // Valid data but a required trait missing: needs info.
        assertEquals(SurveyStatus.needsInfo, surveyStatusFrom(surveyStates(complete = false, valid = true)))
        // Complete and valid: valid.
        assertEquals(SurveyStatus.valid, surveyStatusFrom(surveyStates(complete = true, valid = true)))
        // The wire vocabulary is the kernel's (issue #695): the filter sends what the column reads.
        assertEquals(SurveyStatus.invalid, SurveyStatus.fromWire(SVYS.invalid))
        assertEquals(SurveyStatus.needsInfo, SurveyStatus.fromWire(SVYS.needsInfo))
        assertEquals(SurveyStatus.valid, SurveyStatus.fromWire(SVYS.valid))
        assertNull(SurveyStatus.fromWire("bogus"))
        assertNull(SurveyStatus.fromWire(null))
        // The chip contract each status renders under.
        assertEquals("Needs Info" to "warning", SurveyStatus.needsInfo.label to SurveyStatus.needsInfo.pstat)
        assertEquals("Invalid" to "error", SurveyStatus.invalid.label to SurveyStatus.invalid.pstat)
        assertEquals("Valid" to "ok", SurveyStatus.valid.label to SurveyStatus.valid.pstat)
    }

    /** No survey state -> no status (a client with no survey, or a row not yet computed): the column shows
     *  nothing for the row rather than guessing. Missing booleans are treated the same, defensively. */
    @Test
    fun surveyStatusIsNullWithoutSurveyState() {
        assertNull(surveyStatusFrom(emptyList()))
        assertNull(surveyStatusFrom(listOf(mapOf(GE.traitId to "otherState", GE.data to mapOf("x" to 1)))))
        assertNull(surveyStatusFrom(surveyStates(complete = null, valid = null)))
        assertNull(surveyStatusFrom(surveyStates(complete = true, valid = null)))
    }

    /** [summarizeForm] carries the survey status read from the row's `states` (issue #694), and none for a row
     *  that carried no state (the listing fetched without `withStates`, or a client with no survey). */
    @Test
    fun summarizeReadsSurveyStatusFromStates() {
        val item = mapOf(
            GDF.gedraId to "gd.fd.acme.u20",
            GDF.entries to emptyList<Any?>(),
            GDF.states to listOf(
                mapOf(GE.traitId to SVY.surveyCompletion, GE.data to mapOf(SVY.complete to false, SVY.valid to true)),
            ),
        )
        assertEquals(SurveyStatus.needsInfo, summarizeForm(item, entriesUnion()).surveyStatus)
        val bare = mapOf(GDF.gedraId to "gd.fd.acme.u21", GDF.entries to emptyList<Any?>())
        assertNull(summarizeForm(bare, entriesUnion()).surveyStatus)
    }

}
