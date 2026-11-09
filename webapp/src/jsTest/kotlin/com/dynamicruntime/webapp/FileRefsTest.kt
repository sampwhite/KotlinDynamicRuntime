package com.dynamicruntime.webapp

import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.util.renderMarkdownInline
import com.dynamicruntime.common.util.MarkdownHooks
import com.dynamicruntime.common.home.HFLD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for a client's file refs on its page (issue #1176): how the listing, a change
 * and what a ref takes are read, what the section decides before it sends anything, and what it says.
 */
class FileRefsTest {
    private val hash = "ab".repeat(32)
    private val pngFile = WebAssetFile("image/png", 12_345, hash, "2026-10-12T19:49:51.568Z", "our logo.png")

    /**
     * A ref as the backend lists one. [missing] says only that: an upload whose file has gone keeps the hash and
     * name its entry holds, and it is a file on the server that has neither -- which [nameless] says.
     */
    private fun ref(
        refId: String = "logo", source: String = FREF.sourceStore, root: String? = null, path: String? = null,
        live: Boolean = true, stored: Boolean = true, missing: Boolean = false, nameless: Boolean = false,
        file: WebAssetFile? = pngFile, by: String? = "Chief One", description: String? = null, configName: String? = "copy",
        removedInDraft: Boolean = false, removable: Boolean? = null, setAt: String? = null, setBy: String? = null,
    ) = FileRefView(
        refId, source, root, path, hash.takeIf { !nameless }, "$refId:$hash".takeIf { !nameless }, description, configName,
        live, stored, missing, file.takeIf { !missing }, by,
        removedInDraft = removedInDraft, removable = removable, setAt = setAt, setBy = setBy,
    )

    private val takes = FileRefTakes(2 * 1024 * 1024, listOf("image/png", "image/svg+xml"), listOf("PNG", "SVG", "ICO", "JPEG", "WebP"), 200, emptyList())

    @Test
    fun theListingIsReadRefByRefWithItsFileWhereThereIsOne() {
        val items = listOf(
            mapOf(
                CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, FREF.name to "logo:$hash",
                CCT.description to "The logo.", FREF.configName to "copy", FREF.live to true, FREF.stored to true,
                FREF.missing to false, FREF.uploadedByName to "Chief One",
                FREF.file to mapOf(
                    FMETA.mimeType to "image/png", FMETA.size to 12345, FMETA.hash to hash,
                    FMETA.uploadedAt to "2026-10-12T19:49:51.568Z", FMETA.fileName to "our logo.png",
                ),
            ),
            // A file the client was shipped with, whose file is not there: no hash, no name, no record.
            mapOf(
                CCT.refId to "crest", CCT.source to FREF.sourceLocal, CCT.root to "brand", CCT.path to "marks/crest.svg",
                FREF.configName to "art", FREF.live to true, FREF.stored to false, FREF.missing to true,
            ),
            mapOf(CCT.refId to "hero", CCT.source to FREF.sourceClientLocal, CCT.root to "approved", CCT.path to "hero.png", FREF.live to false, FREF.stored to true),
            mapOf(CCT.description to "No id at all"),
        )
        val refs = parseFileRefs(items)
        assertEquals(listOf("logo", "crest", "hero"), refs.map { it.refId })

        val logo = refs[0]
        assertEquals(FREF.sourceStore, logo.source)
        assertEquals(hash, logo.hash)
        assertEquals("logo:$hash", logo.name)
        assertEquals("The logo.", logo.description)
        assertEquals("copy", logo.configName)
        assertTrue(logo.live && logo.stored && !logo.missing)
        assertEquals(12345, logo.file?.size)
        assertEquals("our logo.png", logo.file?.fileName)
        assertEquals("Chief One", logo.uploadedByName)

        val crest = refs[1]
        assertEquals("brand" to "marks/crest.svg", crest.root to crest.path)
        assertTrue(crest.missing && !crest.stored)
        assertNull(crest.hash)
        assertNull(crest.name)
        assertNull(crest.file)

        assertEquals(FREF.sourceClientLocal, refs[2].source)
        assertFalse(refs[2].live)
    }

    // Whether a ref is stored is said by the listing, and is not where its file is: a file on the server typed into
    // stored configuration is stored, and a client's own file inherited from a template is not.
    @Test
    fun whetherARefIsStoredIsWhatTheListingSaysWhateverItsSource() {
        fun item(source: String, stored: Boolean) = mapOf(CCT.refId to "x", CCT.source to source, FREF.stored to stored)
        assertTrue(parseFileRef(item(FREF.sourceLocal, stored = true))!!.stored)
        assertFalse(parseFileRef(item(FREF.sourceClientLocal, stored = false))!!.stored)
        assertFalse(parseFileRef(item(FREF.sourceStore, stored = false))!!.stored)
    }

    // A backend from before a ref said these: an upload is all there was, and an upload is stored.
    @Test
    fun aListingThatSaysLessIsReadAsWhatItThenMeant() {
        val old = parseFileRef(mapOf(CCT.refId to "logo", CCT.hash to hash, FREF.name to "logo:$hash", FREF.live to true))
        assertNotNull(old)
        assertEquals(FREF.sourceStore, old.source)
        assertTrue(old.stored)
        // A file on the server that does not say is not assumed removable: the backend would refuse, for a shipped one.
        assertFalse(parseFileRef(mapOf(CCT.refId to "crest", CCT.source to FREF.sourceLocal))!!.stored)
        assertNull(parseFileRef(mapOf(CCT.hash to hash)))
    }

    @Test
    fun aChangeIsReadWithTheRefAsItNowIsAndLiveWhenItSaysNoMode() {
        val refItem = mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, FREF.name to "logo:$hash", FREF.live to false, FREF.stored to true)
        val drafted = parseFileRefChange(mapOf(CCT.refId to "logo", FREF.configName to "copy", FREF.mode to EDM.draft, FREF.ref to refItem))
        assertEquals("logo", drafted.refId)
        assertEquals("copy", drafted.configName)
        assertEquals(EDM.draft, drafted.mode)
        assertEquals("logo", drafted.ref?.refId)
        assertEquals(false, drafted.ref?.live)

        val removed = parseFileRefChange(mapOf(CCT.refId to "logo", FREF.configName to "copy"))
        assertEquals(EDM.live, removed.mode)
        assertNull(removed.ref)
        // An empty ref is no ref.
        assertNull(parseFileRefChange(mapOf(CCT.refId to "logo", FREF.ref to emptyMap<String, Any?>())).ref)
    }

    @Test
    fun whatARefTakesIsTheBackendsToSay() {
        val read = parseFileRefTakes(
            mapOf(
                WAS.maxSize to 2097152, WAS.mimeTypes to listOf("image/png", "image/x-icon"), WAS.kinds to listOf("PNG", "ICO"),
                FREF.refLimit to 200, FREF.roots to listOf("approved"),
            ),
        )
        assertEquals(2097152, read.maxSize)
        assertEquals(listOf("image/png", "image/x-icon"), read.mimeTypes)
        assertEquals(listOf("PNG", "ICO"), read.kinds)
        assertEquals(200, read.refLimit)
        assertEquals(listOf("approved"), read.roots)
        // A backend that says nothing offers no root and holds a page to no size of its own.
        val bare = parseFileRefTakes(emptyMap())
        assertEquals(emptyList(), bare.roots)
        assertEquals(0, bare.maxSize)

        fileRefTakesText(takes).let { assertTrue("PNG" in it && "WebP" in it && "2 MB" in it, it) }
        fileRefTakesText(bare).let { assertTrue("MB" !in it && "KB" !in it, it) }
        // The wording a web asset's slot uses, shared: kinds with no size, a size with no kinds.
        assertEquals(webAssetTakesText(WebAssetSlot(WAS.favicon, "Favicon", 0, emptyList(), listOf("PNG", "SVG"), null, null)), imageTakesText(listOf("PNG", "SVG"), 0))
        assertNull(fileRefFileProblem(takes, 2L * 1024 * 1024))
        fileRefFileProblem(takes, 3L * 1024 * 1024)!!.let { assertTrue("3 MB" in it && "2 MB" in it, it) }
        // With no limit known the browser says nothing, and the backend does.
        assertNull(fileRefFileProblem(bare, 9L * 1024 * 1024))
        assertNull(fileRefFileProblem(null, 9L * 1024 * 1024))
    }

    @Test
    fun aChangeIsADraftForAClientWithASandboxAndOnASandboxsOwnPage() {
        assertFalse(savesAsDraft(null, hasSandbox = false))
        assertTrue(savesAsDraft(null, hasSandbox = true))
        assertTrue(savesAsDraft("acme", hasSandbox = false))
        // What the page tells the backend it expects is what it told its user: the two come from the one test.
        assertEquals(EDM.live, expectedMode(savesAsDraft(null, hasSandbox = false)))
        assertEquals(EDM.draft, expectedMode(savesAsDraft(null, hasSandbox = true)))
        assertEquals(EDM.draft, expectedMode(savesAsDraft("acme", hasSandbox = false)))
    }

    @Test
    fun theIntroSaysHowAChangeTakesEffectForThisClient() {
        // Live at once: nothing of drafts. With a sandbox, or on one's own page, a change is a draft and the list is the
        // sandbox's view, a file removed as a draft in it too (issue #1188).
        val live = fileRefsIntro(null, hasSandbox = false, staticHere = false)
        assertFalse("draft" in live)
        assertTrue("draft" in fileRefsIntro(null, hasSandbox = true, staticHere = false))
        val inSandbox = fileRefsIntro("acme", hasSandbox = false, staticHere = false)
        assertTrue("acme" in inSandbox && "draft" in inSandbox)
        // A sandbox's page is a sandbox's page whatever else its row says.
        assertEquals(inSandbox, fileRefsIntro("acme", hasSandbox = true, staticHere = false))

        // A client that takes nothing stored here is told that, and nothing of drafts it cannot make.
        for (static in listOf(fileRefsIntro(null, hasSandbox = true, staticHere = true), fileRefsIntro("acme", hasSandbox = false, staticHere = true))) {
            assertTrue(static != live)
            assertFalse(static.contains("draft"))
        }

        // Where the list is the sandbox's view, an empty one does not say the client has none.
        assertTrue(fileRefsEmptyText(draft = false) != fileRefsEmptyText(draft = true))
    }

    @Test
    fun aNewIdHasToBeANameTheClientDoesNotHave() {
        val existing = listOf(ref("logo"))
        assertNull(fileRefNewIdProblem("heroImage", existing))
        val noId = fileRefNewIdProblem("", existing)
        val taken = fileRefNewIdProblem("logo", existing)
        assertTrue(noId != null && taken != null && noId != taken)
        assertTrue("logo" in taken!!)
        // The kernel's own rule, which the backend holds an id to: said before anything is sent.
        for (bad in listOf("my logo", "logo.png", "2logo", "a/b")) assertNotNull(fileRefNewIdProblem(bad, existing), "should refuse '$bad'")
        // What stands where a ref's id would for the form that adds one can be no ref's id.
        assertNotNull(FREF.refIdProblem(FACT.adding))
    }

    @Test
    fun aFormSaysTheFirstThingWrongWithItInTheOrderItIsFilled() {
        val existing = listOf(ref("logo"))
        // An upload: the id, then the file.
        assertNull(fileRefAddUploadProblem("hero", existing, takes, 1024))
        // An upload: the id, then the file -- each said as its own check says it.
        assertEquals(fileRefNewIdProblem("", existing), fileRefAddUploadProblem("", existing, takes, 9L * 1024 * 1024))
        assertEquals(fileRefFileProblem(takes, 9L * 1024 * 1024), fileRefAddUploadProblem("hero", existing, takes, 9L * 1024 * 1024))

        // A file the deployment keeps: the id, then where, then its path.
        assertNull(fileRefLocalProblem("hero", existing, "approved", "hero.png"))
        assertEquals(fileRefNewIdProblem("logo", existing), fileRefLocalProblem("logo", existing, null, ""))
        val noWhere = fileRefLocalProblem("hero", existing, null, "")
        val noPath = fileRefLocalProblem("hero", existing, "approved", "")
        assertTrue(noWhere != null && noPath != null && noWhere != noPath)

        // Where: the one chosen while it is still open, else the only one there is, else none.
        assertEquals("seasonal", fileRefChosenRoot("seasonal", listOf("approved", "seasonal")))
        assertEquals("approved", fileRefChosenRoot(null, listOf("approved")))
        assertNull(fileRefChosenRoot(null, listOf("approved", "seasonal")))
        assertEquals("approved", fileRefChosenRoot("closed", listOf("approved")))
        assertNull(fileRefChosenRoot("closed", listOf("approved", "seasonal")))
        assertNull(fileRefChosenRoot("approved", emptyList()))

        // A new ref's description is what was typed, trimmed -- and none at all when that is nothing.
        assertEquals("The hero.", fileRefNewDescription("  The hero. "))
        assertNull(fileRefNewDescription("   "))
        assertNull(fileRefNewDescription(""))
    }

    @Test
    fun aRefIsMarkedByWhatIsUnusualAboutIt() {
        assertEquals(emptyList(), fileRefTags(ref()))
        assertEquals(listOf("Draft"), fileRefTags(ref(live = false)))
        assertEquals(listOf("Missing", "Shipped"), fileRefTags(ref(source = FREF.sourceLocal, stored = false, missing = true)))
        assertEquals(listOf("Draft", "Missing"), fileRefTags(ref(live = false, missing = true)))

        assertTrue("logo" in fileRefAltText(ref()))
        assertEquals("Our logo.", fileRefAltText(ref(description = "Our logo.")))
    }

    @Test
    fun whatAFileIsAndWhereAreSaidByItsSource() {
        fileRefHoldsText(ref()).let { assertTrue("image/png" in it && "13 KB" in it && "our logo.png" in it, it) }
        assertEquals("13 KB", fileRefHoldsText(ref(file = WebAssetFile("", 12_345, hash, "", null))))
        val notThere = fileRefHoldsText(ref(missing = true))
        assertFalse("KB" in notThere)
        // A listing that says a ref is there and gives no record of its file is read as one that is not.
        assertEquals(notThere, fileRefHoldsText(ref(file = null)))

        val at = formatTimestamp(pngFile.uploadedAt)
        // The upload's who and when are the file's -- whoever first stored those bytes (issue #1192).
        fileRefWhereText(ref()).let { assertTrue(at in it && "Chief One" in it && "'copy'" in it, it) }
        fileRefWhereText(ref(by = null, configName = null)).let { assertTrue(at in it && "Chief One" !in it && "configuration" !in it, it) }
        // An upload whose file has gone has no date to give; who is not known either.
        assertFalse(at in fileRefWhereText(ref(missing = true, by = null)))
        // Who set this ref is its entry's stamp, said with the configuration.
        fileRefWhereText(ref(setAt = pngFile.uploadedAt, setBy = "Chief Two")).let { assertTrue("Chief Two" in it, it) }
        // Shipped, a file on the server, and one the deployment keeps each say their own source and where.
        val shippedWhere = fileRefWhereText(ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false, by = null, configName = "art"))
        val localWhere = fileRefWhereText(ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", by = null))
        val keptWhere = fileRefWhereText(ref(source = FREF.sourceClientLocal, root = "approved", path = "marks/hero.png", by = null))
        assertTrue("brand/logo.png" in shippedWhere && "'art'" in shippedWhere && "brand/logo.png" in localWhere)
        assertTrue(shippedWhere.removeSuffix("'art'.") != localWhere.removeSuffix("'copy'."))
        assertTrue("marks/hero.png" in keptWhere && "approved" in keptWhere && at in keptWhere)
        // An inherited ref served its template's file, the client having none of its own (issue #1180).
        val inherited = parseFileRef(
            mapOf(CCT.refId to "hero", CCT.source to FREF.sourceClientLocal, CCT.root to "approved", CCT.path to "hero.png",
                FREF.live to true, FREF.stored to false, FREF.configName to "from_tpl_base", FREF.fromTemplate to "tpl"),
        )!!
        assertEquals("tpl", inherited.fromTemplate)
        fileRefWhereText(inherited).let { assertTrue("tpl" in it && "hero.png" in it && "'from_tpl_base'" in it, it) }
        // With no file there is no date to give.
        assertFalse(at in fileRefWhereText(ref(source = FREF.sourceClientLocal, root = "approved", path = "hero.png", missing = true, nameless = true)))
        // A source a later backend adds is called what it is called, and not passed off as an upload.
        fileRefWhereText(ref(source = "vault")).let { assertTrue("'vault'" in it && "uploaded" !in it, it) }
    }

    @Test
    fun aRefWithNoFileToShowHasNoAddressAndNoPreviewWhateverItsEntryStillNames() {
        val address = { name: String -> "/st/kdr.en/ref/$name" }
        assertEquals("/st/kdr.en/ref/logo:$hash", fileRefAddress(ref(), address))
        assertEquals(mapOf(FREF.client to "acme", CCT.refId to "logo", CCT.hash to hash), fileRefPreviewArgs("acme", ref()))

        // An upload whose file has gone: the listing still gives the hash and the name its entry holds, and says it
        // is missing. Drawn from those, the page would show a broken image and an address that answers 404.
        val gone = ref(missing = true)
        assertEquals(hash, gone.hash)
        assertNotNull(gone.name)
        assertNull(fileRefAddress(gone, address))
        assertNull(fileRefPreviewArgs("acme", gone))
        // A file on the server that is not there has neither to begin with.
        val nameless = ref(source = FREF.sourceLocal, missing = true, nameless = true)
        assertNull(fileRefAddress(nameless, address))
        assertNull(fileRefPreviewArgs("acme", nameless))

        // Whose address it is: the static root answers each caller with their own client's ref.
        assertTrue(fileRefAddressLead(ref()) != fileRefAddressLead(ref(live = false)))
        // The kernel's own address, so the backend serves what the page shows.
        assertEquals("/kdr.en/ref/logo:$hash", FREF.staticPath("kdr.en", FREF.versionedName("logo", hash)))
    }

    @Test
    fun onlyAStoredRefIsRemovedOrRewordedAndAShippedOneSaysWhatCanBeDone() {
        assertTrue(fileRefRemovable(ref()))
        assertNull(fileRefShippedHint(ref()))
        val shipped = ref(source = FREF.sourceLocal, stored = false)
        assertFalse(fileRefRemovable(shipped))
        // What the backend says wins: a shipped ref over a stored entry its load left out can be removed, and is not
        // told it cannot be; a stored one the draft no longer holds cannot (issue #1192).
        val overridden = ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false, by = null, configName = "art", removable = true)
        assertTrue(fileRefRemovable(overridden))
        assertNull(fileRefShippedHint(overridden))
        assertFalse(fileRefRemovable(ref(removable = false)))
        assertEquals(true, parseFileRef(mapOf(CCT.refId to "logo", FREF.removable to true, FREF.stored to false))?.removable)
        assertNull(parseFileRef(mapOf(CCT.refId to "logo"))?.removable)
        assertNotNull(fileRefShippedHint(shipped))
    }

    @Test
    fun aRemovalIsAskedAboutNamingWhereItActsAndWhatItLeaves() {
        // Live, it names the client as a delete target does; from a draft, the draft, and a sandbox's is its parent's.
        fileRefRemoveQuestion(ref(), "acme", draft = false).let { assertTrue("logo" in it && deleteTargetText("acme") in it, it) }
        val fromDraft = fileRefRemoveQuestion(ref(), "acme", draft = true)
        assertTrue("acme" in fromDraft && deleteTargetText("acme") !in fromDraft)
        assertEquals(fromDraft, fileRefRemoveQuestion(ref(), "acme:sandbox", draft = true))

        // A draft's removal says the client's people keep the file until it is published -- and says nothing of it for
        // a draft they were never served.
        val live = fileRefRemoveConsequence(ref(), draft = false)
        val served = fileRefRemoveConsequence(ref(), draft = true)
        val neverServed = fileRefRemoveConsequence(ref(live = false), draft = true)
        assertTrue(live != served)
        assertTrue(served.startsWith(neverServed) && served != neverServed)
    }

    @Test
    fun aChangeIsSaidWithHowItTookEffect() {
        val live = FileRefChange("logo", "copy", EDM.live, ref())
        val draft = FileRefChange("logo", "copy", EDM.draft, ref(live = false))
        // Each note names the ref; a draft's says so after what a live one says.
        val uploaded = fileRefUploadedNote(live)
        assertTrue("logo" in uploaded && "copy" in uploaded)
        assertTrue(fileRefUploadedNote(draft).startsWith(uploaded) && fileRefUploadedNote(draft) != uploaded)

        val named = FileRefChange("hero", "copy", EDM.live, ref("hero", source = FREF.sourceClientLocal, root = "approved", path = "marks/hero.png"))
        assertTrue("marks/hero.png" in fileRefNamedNote(named))
        assertTrue("hero" in fileRefNamedNote(FileRefChange("hero", "copy", EDM.live, null)))
        assertTrue(fileRefNamedNote(FileRefChange("hero", "copy", EDM.draft, named.ref)).startsWith(fileRefNamedNote(named).removeSuffix(".")))

        val reworded = fileRefDescribedNote(FileRefChange("logo", "copy", EDM.live, ref(description = "New.")))
        assertTrue(reworded != fileRefDescribedNote(FileRefChange("logo", "copy", EDM.live, ref())))
        assertTrue(fileRefDescribedNote(FileRefChange("logo", "copy", EDM.draft, ref(description = "New."))).startsWith(reworded))

        // A removal says which of five things happened, each its own way: gone, gone in a draft, marked in a draft (the
        // client's people are still served it), the shipped image shown again, or another configuration still holding one.
        val removals = listOf(
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.live, null)),
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.draft, null)),
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.draft, ref(removedInDraft = true))),
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.live, ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false))),
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.live, ref(configName = "art"))),
        )
        assertEquals(5, removals.toSet().size)
        assertTrue(removals.all { "logo" in it })
        assertTrue("art" in removals[4])
        assertTrue("logo" in fileRefRestoredNote(FileRefChange("logo", "copy", EDM.draft, ref())))
    }

    // Issue #1188: a ref a draft removed is a row of its own kind -- marked, previewed from what its people are
    // served, and offered only to be put back.
    @Test
    fun aRefRemovedInADraftIsMarkedPreviewedFromWhatIsServedAndOnlyPutBack() {
        val items = listOf(mapOf(CCT.refId to "logo", CCT.hash to hash, FREF.live to true, FREF.stored to true, FREF.removedInDraft to true))
        val removed = parseFileRefs(items).single()
        assertTrue(removed.removedInDraft)
        assertFalse(parseFileRefs(listOf(mapOf(CCT.refId to "logo"))).single().removedInDraft)

        assertEquals(listOf("Removed in draft"), fileRefTags(ref(removedInDraft = true)))
        assertFalse(fileRefRemovable(ref(removedInDraft = true)))
        assertTrue(fileRefRestorable(ref(removedInDraft = true)))
        assertTrue(fileRefRemovable(ref()))
        assertFalse(fileRefRestorable(ref()))
        assertEquals(
            mapOf(FREF.client to "acme", CCT.refId to "logo", CCT.hash to hash, FREF.served to true),
            fileRefPreviewArgs("acme", ref(removedInDraft = true)),
        )
        assertNotNull(fileRefRemovedInDraftText(ref(removedInDraft = true)))
        assertNull(fileRefRemovedInDraftText(ref()))
    }

    // Issue #1190: finding one among many -- words in an id or description, the marks as toggles -- in a stated order,
    // and no more than a screenful until all are asked for.
    @Test
    fun aClientsFilesAreFoundByWordsAndMarksInAStatedOrder() {
        val logo = ref("logo", description = "The company logo")
        val hero = ref("Hero", live = false)
        val gone = ref("banner", missing = true)
        val shipped = ref("crest", source = FREF.sourceLocal, stored = false, by = null)
        val removed = ref("badge", removedInDraft = true)
        val all = listOf(logo, hero, gone, shipped, removed)

        // Words in the id or the description, ignoring case.
        assertEquals(listOf("logo"), all.filter { fileRefMatches(it, FileRefFilter("COMPANY")) }.map { it.refId })
        assertEquals(listOf("Hero"), all.filter { fileRefMatches(it, FileRefFilter("her")) }.map { it.refId })
        // Marks: any of those chosen; with words too, both.
        assertEquals(listOf("Hero", "banner"), all.filter { fileRefMatches(it, FileRefFilter(marks = setOf("Draft", "Missing"))) }.map { it.refId })
        assertEquals(emptyList(), all.filter { fileRefMatches(it, FileRefFilter("logo", setOf("Missing"))) }.map { it.refId })
        assertEquals(
            listOf("Removed in draft" to 1, "Draft" to 1, "Missing" to 1, "Shipped" to 1),
            fileRefMarkCounts(all),
        )

        // What needs attention first -- missing, removed in a draft, a draft -- then by id, ignoring case.
        assertEquals(listOf("banner", "badge", "Hero", "crest", "logo"), fileRefOrdered(all).map { it.refId })
        // The same however the list arrived: drawn from a change's answer, a new ref at the end, or read again.
        assertEquals(fileRefOrdered(all).map { it.refId }, fileRefOrdered(all.reversed()).map { it.refId })

        // A screenful, then the rest on asking.
        val many = (1..25).map { ref("ref" + it.toString().padStart(2, '0')) }
        val first = fileRefPage(many, FileRefFilter(), showAll = false)
        assertEquals(fileRefFirstShown, first.shown.size)
        assertEquals(5, first.hidden)
        fileRefPageText(first, FileRefFilter())!!.let { assertTrue("20" in it && "25" in it, it) }
        assertEquals(25, fileRefPage(many, FileRefFilter(), showAll = true).shown.size)
        assertNull(fileRefPageText(fileRefPage(many, FileRefFilter(), showAll = true), FileRefFilter()))
        val narrowed = FileRefFilter("ref1")
        fileRefPageText(fileRefPage(many, narrowed, showAll = false), narrowed)!!.let { assertTrue("10" in it && "25" in it, it) }
        val none = FileRefFilter("zzz")
        assertNotNull(fileRefPageText(fileRefPage(many, none, showAll = false), none))
        // A filter every file matches says the count once, as no filter does.
        val wide = FileRefFilter("ref")
        assertEquals(fileRefPageText(first, FileRefFilter()), fileRefPageText(fileRefPage(many, wide, showAll = false), wide))
        val most = FileRefFilter("ref") to (many + ref("other"))
        fileRefPageText(fileRefPage(most.second, most.first, showAll = false), most.first)!!.let {
            assertTrue("20" in it && "25" in it && "26" in it, it)
        }

        // What this page just added or changed is drawn where it was made: past the cap, and outside the filter.
        val added = many + ref("zebra")
        val kept = fileRefPage(added, FileRefFilter(), showAll = false, kept = setOf("zebra"))
        assertEquals(21, kept.shown.size)
        assertEquals("zebra", kept.shown.last().refId)
        assertEquals(5, kept.hidden)
        val filtered = fileRefPage(added, FileRefFilter("ref1"), showAll = false, kept = setOf("zebra"))
        assertEquals(listOf("ref10", "ref11", "ref12"), filtered.shown.take(3).map { it.refId })
        assertTrue(filtered.shown.any { it.refId == "zebra" })
    }

    @Test
    fun aCopySaysWhatItDidInWords() {
        assertNotNull(copyOutcomeText(true))
        assertTrue(copyOutcomeText(true) != copyOutcomeText(false))
        assertNull(copyOutcomeText(null))
    }

    @Test
    fun anExistingRefIsPointedAtAKeptFileFromItsRowWhereARootIsOpen() {
        val open = FileRefTakes(takes.maxSize, takes.mimeTypes, takes.kinds, takes.refLimit, listOf("approved"))
        // Offered for any ref -- uploaded, shipped, already a kept file -- once a root is open, and not before.
        for (r in listOf(ref("logo"), ref("logo", source = FREF.sourceLocal, stored = false), ref("logo", source = FREF.sourceClientLocal, root = "approved", path = "a.png"))) {
            assertTrue(fileRefRepointable(r, open), "should offer for ${r.source}")
            assertFalse(fileRefRepointable(r, takes), "no root is open")
            assertFalse(fileRefRepointable(r, null), "what a ref takes is not known")
        }
        // A ref a draft removed is only put back.
        assertFalse(fileRefRepointable(ref("logo", removedInDraft = true), open))

        // The editor starts at what a kept file names now, and empty for anything else.
        assertEquals("approved" to "a.png", fileRefRepointStart(ref("logo", source = FREF.sourceClientLocal, root = "approved", path = "a.png")))
        assertEquals(null to "", fileRefRepointStart(ref("logo", source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false)))
        assertEquals(null to "", fileRefRepointStart(ref("logo")))

        // Where, then its path; and not the file it already names.
        val kept = ref("logo", source = FREF.sourceClientLocal, root = "approved", path = "a.png")
        val noWhere = fileRefRepointProblem(kept, null, "")
        val noPath = fileRefRepointProblem(kept, "approved", "")
        val same = fileRefRepointProblem(kept, "approved", "a.png")
        assertEquals(3, setOf(noWhere, noPath, same).filterNotNull().size)
        assertTrue("logo" in same!!)
        assertNull(fileRefRepointProblem(kept, "approved", "b.png"))
        assertNull(fileRefRepointProblem(kept, "seasonal", "a.png"))
        // The same path under an upload or a shipped file is not the same file.
        assertNull(fileRefRepointProblem(ref("logo", source = FREF.sourceLocal, root = "approved", path = "a.png", stored = false), "approved", "a.png"))
        assertNull(fileRefRepointProblem(ref("logo"), "approved", "a.png"))

        // Its row is redrawn from the change's answer, in its place: the new source and path.
        val refs = listOf(ref("logo"), ref("banner"))
        val moved = withFileRefChange(refs, FileRefChange("logo", "copy", EDM.live, kept))!!
        assertEquals(listOf("logo", "banner"), moved.map { it.refId })
        assertEquals(FREF.sourceClientLocal to "a.png", moved[0].source to moved[0].path)
    }

    @Test
    fun theListIsRedrawnFromWhatAChangeAnswered() {
        val refs = listOf(ref("logo"), ref("banner"))
        val replaced = ref("logo", description = "New.")
        assertEquals(listOf("New.", null), withFileRefChange(refs, FileRefChange("logo", "copy", EDM.live, replaced))!!.map { it.description })
        assertEquals(listOf("logo", "banner", "hero"), withFileRefChange(refs, FileRefChange("hero", "copy", EDM.live, ref("hero")))!!.map { it.refId })
        assertEquals(listOf("banner"), withFileRefChange(refs, FileRefChange("logo", "copy", EDM.live, null))!!.map { it.refId })
        // A removal that reveals a shipped ref keeps the row, as the shipped one.
        val shipped = ref("logo", source = FREF.sourceLocal, stored = false)
        assertEquals(listOf(false, true), withFileRefChange(refs, FileRefChange("logo", "copy", EDM.live, shipped))!!.map { it.stored })
        // A removal from a draft answers the row as it now is -- marked, not gone -- so it stays where it was.
        assertEquals(
            listOf(true, false),
            withFileRefChange(refs, FileRefChange("logo", "copy", EDM.draft, ref("logo", removedInDraft = true)))!!.map { it.removedInDraft },
        )
        // Nothing listed yet: nothing to redraw.
        assertNull(withFileRefChange(null, FileRefChange("logo", "copy", EDM.live, replaced)))
    }

    @Test
    fun theLimitIsSaidOnceItIsNearAndTheFormGivesWayToItOnceItIsReached() {
        fun some(n: Int) = List(n) { ref("r$it") }
        assertNull(fileRefCountText(some(10), takes))
        assertNull(fileRefCountText(some(179), takes))
        assertTrue(fileRefCountText(some(180), takes)!!.let { "180" in it && "200" in it })
        assertNull(fileRefCountText(some(180), null))

        assertNull(fileRefAtLimitText(some(199), takes))
        val atLimit = fileRefAtLimitText(some(200), takes)!!
        assertTrue("200" in atLimit)
        // A client whose every file was shipped with it cannot remove one here (issue #1169): it is told what it can do.
        val shipped = List(200) { ref("s$it", source = FREF.sourceLocal, stored = false) }
        assertTrue(fileRefAtLimitText(shipped, takes)!! != atLimit)
        // A backend that names no limit holds the page to none.
        val unlimited = FileRefTakes(0, emptyList(), emptyList(), 0, emptyList())
        assertNull(fileRefAtLimitText(some(500), unlimited))
        assertNull(fileRefCountText(some(500), unlimited))
        assertNull(fileRefAtLimitText(some(500), null))
    }

    // Copy naming a ref (issue #1186): what the shell hands the page, and where a ref image is drawn from.
    @Test
    fun theShellsRefsAreReadOnlyAsNamesOfTheirOwnIdWithAHash() {
        val raw = mapOf(
            "logo" to "logo:$hash",
            "banner" to "logo:$hash", // another id's name
            "crest" to "crest:nothash",
            "bad id" to "bad id:$hash",
            "hero" to 42,
        )
        assertEquals(mapOf("logo" to "logo:$hash"), fileRefNamesFrom(raw))
        assertEquals(emptyMap(), fileRefNamesFrom(null))
    }

    @Test
    fun aRefImageIsDrawnFromItsServedNameAndOneNotServedIsNot() {
        val address = refAddressFrom(mapOf("logo" to "logo:$hash")) { "/st/kdr.en/ref/$it" }
        assertEquals("/st/kdr.en/ref/logo:$hash", address("logo"))
        assertNull(address("banner"))
        // The shell's facts say so for every copy surface, from the home config's own field.
        val facts = shellFactsOf(homeConfigFrom(UiConfig(FragmentRef("home", "b1"), emptyMap(), emptyMap(), mapOf(HFLD.fileRefs to mapOf("logo" to "logo:$hash")))))
        assertEquals(mapOf("logo" to "logo:$hash"), facts.fileRefs)
        // And through the kernel renderer, as Markdown draws copy: drawn, or its alt text.
        val hooks = MarkdownHooks(refAddress = address)
        assertEquals("<img src=\"/st/kdr.en/ref/logo:$hash\" alt=\"Logo\">", "![Logo](ref:logo)".renderMarkdownInline(hooks))
        assertEquals("Banner", "![Banner](ref:banner)".renderMarkdownInline(hooks))
    }

    @Test
    fun everyCopySurfaceDrawsTheCallersRefsUnlessItSaysWhereFrom() {
        val facts = ShellFacts(fileRefs = mapOf("logo" to "logo:$hash"))
        val addressOf: (String) -> String = { "/st/kdr.en/ref/$it" }
        // What a page gets with no prop of its own: the shell's refs.
        val shell = markdownHooksFor(null, null, facts, addressOf)
        assertEquals("<img src=\"/st/kdr.en/ref/logo:$hash\" alt=\"L\">", "![L](ref:logo)".renderMarkdownInline(shell))
        // A surface drawing another client's copy says where from, and the shell's are not asked.
        val own = markdownHooksFor(null, { id -> "download/$id" }, facts, addressOf)
        assertEquals("<img src=\"download/logo\" alt=\"L\">", "![L](ref:logo)".renderMarkdownInline(own))
        // Before the shell has been told, a ref image is its alt text; the link resolver rides along either way.
        val untold = markdownHooksFor({ "/docs/$it" }, null, ShellFacts(), addressOf)
        assertEquals("L <a href=\"/docs/x.md\">d</a>", "![L](ref:logo) [d](x.md)".renderMarkdownInline(untold))
    }

    @Test
    fun aPageAboutAClientDrawsItsRefsFromTheNamedDownloadNotTheCallersStaticRoot() {
        val refs = listOf(ref(), ref(refId = "gone", missing = true))
        val address = refPreviewAddressFrom("acme", refs) { args -> "download?" + args.entries.joinToString("&") { "${it.key}=${it.value}" } }
        assertEquals("download?client=acme&refId=logo&hash=$hash", address("logo"))
        assertNull(address("gone"))
        assertNull(address("banner"))
    }

    @Test
    fun theMarkdownForARefNamesItAndTakesItsAltTextFromItsOwnWords() {
        assertEquals("![The file logo](ref:logo)", fileRefImageMarkdown(ref()))
        assertEquals("![Our logo, as of 2026](ref:logo)", fileRefImageMarkdown(ref(description = "Our [logo], as of\n2026")))
        // What the copy editor offers: each ref by id beside its words, a missing file said so.
        assertEquals(
            listOf("logo \u2014 The logo." to "logo", "gone \u2014 file missing" to "gone"),
            fileRefInsertChoices(listOf(ref(description = "The logo."), ref(refId = "gone", missing = true))),
        )
    }

    @Test
    fun theCopyEditorReadsTheRefsOnlyForCopyThatMayNameOne() {
        assertTrue(copyNamesRef("Welcome ![Logo](ref:logo)"))
        assertFalse(copyNamesRef("Welcome to the refinery."))
        assertFalse(copyNamesRef(null))
    }

    @Test
    fun anImageGoesInWhereTheCursorWasOrOnALineOfItsOwnAtTheEnd() {
        assertEquals("Hello ![x](ref:a)world" to 17, insertAtCaret("Hello world", 6, "![x](ref:a)"))
        assertEquals("Hello\n![x](ref:a)" to 17, insertAtCaret("Hello", null, "![x](ref:a)"))
        assertEquals("Hello\n![x](ref:a)" to 17, insertAtCaret("Hello\n", null, "![x](ref:a)"))
        assertEquals("![x](ref:a)" to 11, insertAtCaret("", 99, "![x](ref:a)"))
    }
}
