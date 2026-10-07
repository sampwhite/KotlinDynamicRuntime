package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GCI
import kotlin.test.Test
import kotlin.test.assertEquals

/** The copy editor's file view (issue #1062): the rows, what a query shows, the pending changes and their save. */
class CopyFileViewTest {
    private fun key(file: String, ns: String, key: String, value: String, shownOn: String? = "the app") =
        CopyKeyView(file, ns, key, if (file == "mail") "backend" else "frontend", value, shownOn)

    private fun override(file: String, ns: String, key: String, value: String, origin: String = "stored", base: String? = null, source: String? = null, orphan: Boolean = false) =
        CopyOverrideView(file, ns, key, "frontend", base, value, "copy", origin, source, orphan, "the app")

    private val keys = listOf(
        key("sampleContent", "welcome", "title", "Welcome aboard", shownOn = null),
        key("home", "home", "brand", "Acme Co"),
        key("home", "home", "title", "Welcome"),
        key("home", "nav", "title", "Documents"),
        key("mail", "common", "footer", "Sent automatically.", shownOn = "the mails"),
    )
    private val overrides = listOf(
        override("home", "home", "brand", "Acme Co", base = "KDR", source = "ACME KDR"),
        override("home", "home", "renamed", "Stale", origin = "source", orphan = true),
        override("home", "nav", "title", "Documents", origin = "source", base = "Docs"),
    )
    private val rows = copyKeyRows(keys, overrides)

    private fun at(file: String, ns: String, key: String) = CopyAddress(file, ns, key)
    private fun row(file: String, ns: String, key: String) = rows.single { it.address == at(file, ns, key) }
    private fun shown(groups: List<CopyFileGroup>) = groups.flatMap { g -> g.namespaces.flatMap { n -> n.rows.map { "${g.fileId}:${it.address.nsKey}" } } }

    @Test
    fun theRowsJoinEachKeyToItsOverrideAndPlaceAnOrphanInItsFile() {
        assertEquals(
            listOf("sampleContent:welcome.title", "home:home.brand", "home:home.title", "home:nav.title", "home:home.renamed", "mail:common.footer"),
            rows.map { "${it.address.fileId}:${it.address.nsKey}" },
        )
        assertEquals("KDR", row("home", "home", "brand").override?.baseValue)
        assertEquals(null, row("home", "home", "title").override)
        assertEquals(true, row("home", "home", "renamed").orphan)
        assertEquals("Stale", row("home", "home", "renamed").value)
    }

    @Test
    fun aChosenFileShowsItsKeysByNamespaceAndWordsSearchEveryFile() {
        val home = copyFileGroups(rows, CopyViewQuery("home"))
        assertEquals(listOf("home"), home.map { it.fileId })
        assertEquals(listOf("home", "nav"), home.single().namespaces.map { it.namespace })
        assertEquals(listOf("brand", "title", "renamed"), home.single().namespaces[0].rows.map { it.address.key })
        // Words find a value wherever it lives, the shown files first; the address matches too; case is ignored.
        assertEquals(listOf("home:home.title", "sampleContent:welcome.title"), shown(copyFileGroups(rows, CopyViewQuery("mail", "welcome"))))
        assertEquals(listOf("mail:common.footer"), shown(copyFileGroups(rows, CopyViewQuery("home", "COMMON.foot"))))
        assertEquals(emptyList(), copyFileGroups(rows, CopyViewQuery("home", "nothing says this")))
        // A blank filter is no filter.
        assertEquals(true, copyRowMatches(row("home", "home", "brand"), "  "))
    }

    @Test
    fun onlyChangedKeepsTheOverriddenKeysAndThePendingOnes() {
        val pending = mapOf(at("home", "home", "title") to PendingCopy("Hello"))
        assertEquals(listOf("home:home.brand", "home:home.renamed", "home:nav.title"), shown(copyFileGroups(rows, CopyViewQuery("home", onlyChanged = true))))
        assertEquals(
            listOf("home:home.brand", "home:home.title", "home:home.renamed", "home:nav.title"),
            shown(copyFileGroups(rows, CopyViewQuery("home", onlyChanged = true), pending)),
        )
    }

    @Test
    fun theViewOpensOnNoFileAndShowsTheChosenOneWhileItHasKeys() {
        assertEquals(listOf("home", "mail", "sampleContent"), copyViewFiles(rows).map { it.fileId })
        assertEquals(null, copyViewOpenFile(rows, null))
        assertEquals("mail", copyViewOpenFile(rows, "mail"))
        assertEquals(null, copyViewOpenFile(rows, "goneNow"))
        // No file chosen and no words: nothing listed, a choice to make.
        assertEquals(emptyList(), copyFileGroups(rows, CopyViewQuery(null)))
        assertEquals(false, copyViewSpansFiles(CopyViewQuery(null)))
    }

    @Test
    fun onlyChangedWithNoFileChosenListsEveryFilesChangedKeys() {
        val query = CopyViewQuery(null, onlyChanged = true)
        assertEquals(true, copyViewSpansFiles(query))
        val pending = mapOf(at("mail", "common", "footer") to PendingCopy("X"))
        assertEquals(
            listOf("home:home.brand", "home:home.renamed", "home:nav.title", "mail:common.footer"),
            shown(copyFileGroups(rows, query, pending)),
        )
        // A chosen file keeps it to that file.
        assertEquals(false, copyViewSpansFiles(CopyViewQuery("home", onlyChanged = true)))
    }

    @Test
    fun aFilesPanelCountsItsKeysAndHowManyShow() {
        val home = copyFileGroups(rows, CopyViewQuery("home")).single()
        assertEquals("4 keys", copyFileCountText(home, rows))
        val narrowed = copyFileGroups(rows, CopyViewQuery("home", onlyChanged = true)).single()
        assertEquals("3 of 4 keys", copyFileCountText(narrowed, rows))
        assertEquals("1 key", copyFileCountText(copyFileGroups(rows, CopyViewQuery("mail")).single(), rows))
    }

    @Test
    fun anEditIsPendingUntilItReadsAsTheValueAgain() {
        val title = row("home", "home", "title")
        val edited = pendingAfterEdit(emptyMap(), title, "Hello")
        assertEquals("Hello", edited[title.address]?.value)
        assertEquals(emptyMap(), pendingAfterEdit(edited, title, "Welcome"))
        // An empty value is a value to set, not a reset.
        assertEquals(false, pendingAfterEdit(emptyMap(), title, "")[title.address]?.reset)
    }

    @Test
    fun aSaveIsOfOneFileSoAnotherFileWaits() {
        val pending = mapOf(at("home", "home", "title") to PendingCopy("Hello"))
        assertEquals("home", pendingCopyFile(pending))
        assertEquals(null, pendingCopyFile(emptyMap()))
        assertEquals(true, copyRowEditable(row("home", "nav", "title"), pending))
        assertEquals(false, copyRowEditable(row("mail", "common", "footer"), pending))
        assertEquals(true, copyRowEditable(row("mail", "common", "footer"), emptyMap()))
    }

    @Test
    fun onlyAStoredValueOffersAResetAndNotWhileAChangeIsPending() {
        val brand = row("home", "home", "brand")
        assertEquals(true, copyRowOffersReset(brand, emptyMap()))
        assertEquals(false, copyRowOffersReset(brand, mapOf(brand.address to PendingCopy(null))))
        assertEquals(false, copyRowOffersReset(row("home", "nav", "title"), emptyMap()))  // set in source
        assertEquals(false, copyRowOffersReset(row("home", "home", "title"), emptyMap()))  // the shipped copy
    }

    @Test
    fun aKeySaysWhatIsPendingElseWhatTheClientChanged() {
        val brand = row("home", "home", "brand")
        assertEquals("changed by copy (stored)", copyRowStatus(brand, emptyMap()))
        assertEquals("edited, not saved", copyRowStatus(brand, mapOf(brand.address to PendingCopy("X"))))
        assertEquals("reset, not saved", copyRowStatus(brand, mapOf(brand.address to PendingCopy(null))))
        assertEquals(null, copyRowStatus(row("home", "home", "title"), emptyMap()))
        assertEquals(true, copyRowStatus(row("home", "home", "renamed"), emptyMap())!!.startsWith("orphan"))
    }

    @Test
    fun aPendingChangeShowsAsItWillReadOnceSaved() {
        val brand = row("home", "home", "brand")
        assertEquals("Acme Co", copyRowShownValue(brand, emptyMap()))
        assertEquals("Acme Inc", copyRowShownValue(brand, mapOf(brand.address to PendingCopy("Acme Inc"))))
        // A reset returns to the client's source value when its source sets one, else the shipped copy.
        assertEquals("ACME KDR", copyRowShownValue(brand, mapOf(brand.address to PendingCopy(null))))
        val shippedOnly = copyKeyRows(listOf(key("home", "home", "brand", "Mine")), listOf(override("home", "home", "brand", "Mine", base = "KDR")))
        assertEquals("KDR", copyRowShownValue(shippedOnly.single(), mapOf(at("home", "home", "brand") to PendingCopy(null))))
    }

    @Test
    fun theSaveRequestListsTheFilesChangesInTheFilesOrder() {
        val pending = linkedMapOf(
            at("home", "nav", "title") to PendingCopy("Docs"),
            at("home", "home", "brand") to PendingCopy(null),
        )
        assertEquals(
            mapOf(
                COV.client to "acme",
                COV.fileId to "home",
                CPY.changes to listOf(
                    mapOf(COV.namespaceField to "home", COV.key to "brand", CPY.reset to true),
                    mapOf(COV.namespaceField to "nav", COV.key to "title", COV.value to "Docs"),
                ),
            ),
            copyApplyRequest("acme", pending, rows.map { it.address }),
        )
    }

    @Test
    fun theSaveResultParsesAndItsNoteCountsTheChanges() {
        val result = parseCopyApplyResult(
            mapOf(
                COV.configName to "copy",
                CPY.keys to listOf(mapOf(COV.namespaceField to "home", COV.key to "brand"), mapOf(COV.namespaceField to "home", COV.key to "title")),
                CPY.issues to listOf(mapOf(GCI.message to "m")),
                CPY.mode to EDM.draft,
            ),
        )
        assertEquals(2, result.keyCount)
        assertEquals(listOf("m"), result.issues)
        assertEquals(
            "Saved 2 changes to home in copy. Saved as a draft: the client's sandbox shows it, and it goes live once published.",
            copyAppliedNote("home", result),
        )
        assertEquals("Saved 1 change to mail in copy.", copyAppliedNote("mail", CopyApplyResult("copy", 1, emptyList())))
        assertEquals(EDM.live, parseCopyApplyResult(mapOf(COV.configName to "copy")).mode)
    }

    @Test
    fun theSaveBarCountsWhatIsPending() {
        assertEquals("1 unsaved change to home", pendingCopyText(mapOf(at("home", "home", "brand") to PendingCopy("X"))))
        assertEquals(
            "2 unsaved changes to mail",
            pendingCopyText(mapOf(at("mail", "common", "footer") to PendingCopy("X"), at("mail", "common", "htmlStyle") to PendingCopy("Y"))),
        )
    }

    @Test
    fun aRefusalMarksTheKeysItNamesAsWholeKeys() {
        val pending = listOf(at("mail", "common", "htmlStyle"), at("mail", "common", "footer"), at("home", "home", "title"))
        val message = "Copy edit refused: loading client 'acme' would find 1 problem(s). common.htmlStyle: backend pull %{@t(\"nope.x.y\")} names no fragment."
        assertEquals(setOf(at("mail", "common", "htmlStyle")), copyKeysNamedIn(message, pending))
        // `home.title` is not named by `home.titleBar`, nor by `x.home.title`.
        assertEquals(emptySet(), copyKeysNamedIn("home.titleBar is wrong; so is x.home.title", pending))
        assertEquals(setOf(at("home", "home", "title")), copyKeysNamedIn("'home.title' pulls nothing.", pending))
    }
}
