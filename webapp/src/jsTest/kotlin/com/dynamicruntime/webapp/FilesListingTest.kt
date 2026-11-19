package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filekind.FKIND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Files page (issue #1863), the pure half: a listing read, its filters, and what a row offers. */
class FilesListingTest {
    private fun item(id: String, forms: Long, owner: Long = 7, status: String = DFSTAT.ready): Map<String, Any?> = mapOf(
        DFILE.fileId to id, DFILE.name to "$id.png", DFILE.kind to FKIND.png, DFILE.size to 1200L, DFILE.status to status,
        DFILE.ownerId to owner, DFILE.ownerName to "Pat", DFILE.uploadedAt to "2026-11-19T10:00:00Z", DFILE.forms to forms,
    )

    @Test
    fun aListingReadsIntoRowsAndItsTotal() {
        val page = filesPageFrom(mapOf(EP.items to listOf(item("a", 2), item("b", 0)), EP.numAvailable to 140L))
        assertEquals(listOf("a", "b"), page.rows.map { it.fileId })
        assertEquals(140, page.available)
        assertEquals(2, page.rows[0].forms)
        assertEquals("Pat", fileOwnerText(page.rows[0]))
        assertEquals("a.png", page.rows[0].view.title)
    }

    @Test
    fun theFiltersAreTheListingsInputAndAnyIsLeftOut() {
        assertEquals(mapOf<String, Any?>(EP.limit to 100), filesListArgs(FilesFilter(), 100))
        assertEquals(
            mapOf<String, Any?>(EP.limit to 200, DFILE.kind to FKIND.pdf, DFILE.status to DFSTAT.ready, DFILE.linked to false),
            filesListArgs(FilesFilter(FKIND.pdf, DFSTAT.ready, false), 200),
        )
        assertNull(filterValue(filesAny))
        assertEquals("true", filterValue("true"))
        assertEquals(filesAny, fileKindChoices().first().value)
    }

    @Test
    fun onlyAFileNoFormUsesIsRemovableAndOneThatIsSaysWhy() {
        val items = listOf(item("a", 1), item("b", 0), item("c", 3), item("d", 0, status = DFSTAT.uploading))
        val page = filesPageFrom(mapOf(EP.items to items))
        assertEquals(listOf(false, true, false, true), page.rows.map { fileRemovable(it) })
        assertEquals("A form uses it; remove it from that form to delete it.", fileKeptText(page.rows[0]))
        assertEquals("3 forms use it; remove it from them to delete it.", fileKeptText(page.rows[2]))
        assertNull(fileKeptText(page.rows[1]))
        assertTrue(page.rows[3].uploading)
        assertEquals(listOf("1 form", "No form", "3 forms"), listOf(1, 0, 3).map { fileUsedByText(it) })
    }

    @Test
    fun theOwnerIsShownOnlyWhenSomeFileIsAnothersUses() {
        val mine = filesPageFrom(mapOf(EP.items to listOf(item("a", 0, owner = 7), item("b", 0, owner = 7)))).rows
        assertFalse(filesShowOwner(mine, 7))
        assertTrue(filesShowOwner(mine + filesPageFrom(mapOf(EP.items to listOf(item("c", 0, owner = 9)))).rows, 7))

        val uses = fileUsesFrom(
            mapOf(
                DFILE.usedBy to listOf(mapOf(DFILE.formId to "gd.fd.x", DFILE.traitId to "t", DFILE.fieldPath to "photo")),
                DFILE.hiddenForms to 2L,
            ),
        )
        assertEquals("photo", uses.uses.single().fieldPath)
        assertEquals("And 2 forms you may not open.", fileHiddenUsesText(uses.hidden))
        assertNull(fileHiddenUsesText(0))
        // Without the server's words, the ids stand in; with them, the trait and field as the form's pages name them.
        assertEquals("t · photo", fileUseText(uses.uses.single()))
        assertEquals("Site media · Photo", fileUseText(FileUse("gd.fd.x", "sample:siteMedia", "photo", "Site media", "Photo")))
    }

    @Test
    fun anUploadPastItsTimeSaysItStopped() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        fun uploading(expires: String) =
            filesPageFrom(mapOf(EP.items to listOf(item("u", 0, status = DFSTAT.uploading) + (DFILE.expiresAt to expires)))).rows.single()
        assertEquals("Stopped (no longer taken)", fileStatusText(uploading("2026-11-19T11:00:00Z"), now))
        assertEquals("Uploading", fileStatusText(uploading("2026-11-20T11:00:00Z"), now))
        assertEquals("Ready", fileStatusText(filesPageFrom(mapOf(EP.items to listOf(item("r", 0)))).rows.single(), now))
    }
}
