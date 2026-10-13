package com.dynamicruntime.webapp

import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.webasset.WAS
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
    ) = FileRefView(
        refId, source, root, path, hash.takeIf { !nameless }, "$refId:$hash".takeIf { !nameless }, description, configName,
        live, stored, missing, file.takeIf { !missing }, by,
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

        assertEquals("PNG, SVG, ICO, JPEG or WebP, up to 2 MB.", fileRefTakesText(takes))
        assertEquals("An image.", fileRefTakesText(bare))
        // The wording a web asset's slot uses, shared: kinds with no size, a size with no kinds.
        assertEquals("PNG or SVG.", imageTakesText(listOf("PNG", "SVG"), 0))
        assertEquals("An image, up to 1 MB.", imageTakesText(emptyList(), 1024 * 1024))
        assertNull(fileRefFileProblem(takes, 2L * 1024 * 1024))
        assertEquals("This file is 3 MB; a file ref's file may be at most 2 MB.", fileRefFileProblem(takes, 3L * 1024 * 1024))
        // With no limit known the browser says nothing, and the backend does.
        assertNull(fileRefFileProblem(bare, 9L * 1024 * 1024))
        assertNull(fileRefFileProblem(null, 9L * 1024 * 1024))
    }

    @Test
    fun aChangeIsADraftForAClientWithASandboxAndOnASandboxsOwnPage() {
        assertFalse(fileRefIsDraft(null, hasSandbox = false))
        assertTrue(fileRefIsDraft(null, hasSandbox = true))
        assertTrue(fileRefIsDraft("acme", hasSandbox = false))
        // What the page tells the backend it expects is what it told its user: the two come from the one test.
        assertEquals(EDM.live, fileRefExpectedMode(fileRefIsDraft(null, hasSandbox = false)))
        assertEquals(EDM.draft, fileRefExpectedMode(fileRefIsDraft(null, hasSandbox = true)))
        assertEquals(EDM.draft, fileRefExpectedMode(fileRefIsDraft("acme", hasSandbox = false)))
    }

    @Test
    fun theIntroSaysHowAChangeTakesEffectForThisClient() {
        assertEquals(
            "Named images this client's configuration holds, each served at an address of its own. A change takes effect at once for everyone in the client.",
            fileRefsIntro(null, hasSandbox = false, staticHere = false),
        )
        val drafts = fileRefsIntro(null, hasSandbox = true, staticHere = false)
        assertTrue(drafts.contains("a change here is a draft"))
        // The list is the sandbox's view, and a file removed as a draft is not in it: said, since the list cannot.
        assertTrue(drafts.contains("What is listed is what the sandbox shows: the client's latest configuration, drafts included."))
        assertTrue(drafts.contains("A file removed as a draft is not listed, though the client's people are served it until that is published."))
        assertTrue(drafts.contains("from the stored configuration below"))

        val inSandbox = fileRefsIntro("acme", hasSandbox = false, staticHere = false)
        assertTrue(inSandbox.startsWith("The files of acme's sandbox."))
        assertTrue(inSandbox.contains("acme's latest configuration, drafts included"))
        assertTrue(inSandbox.contains("though acme's people are served it"))
        assertTrue(inSandbox.endsWith("A change made here is a draft of acme's, and goes live for its people once that is published."))
        // A sandbox's page is a sandbox's page whatever else its row says.
        assertEquals(inSandbox, fileRefsIntro("acme", hasSandbox = true, staticHere = false))

        // A client that takes nothing stored here is told that, and nothing of drafts it cannot make.
        for (static in listOf(fileRefsIntro(null, hasSandbox = true, staticHere = true), fileRefsIntro("acme", hasSandbox = false, staticHere = true))) {
            assertTrue(static.endsWith("This client's configuration comes from source code on this node, so its files are listed here and not changed."))
            assertFalse(static.contains("draft"))
        }

        assertEquals("This client has no files.", fileRefsEmptyText(draft = false))
        // Where the list is the sandbox's view, an empty one does not say the client has none.
        assertEquals("No files in the latest configuration, which is what the sandbox shows.", fileRefsEmptyText(draft = true))
    }

    @Test
    fun aNewIdHasToBeANameTheClientDoesNotHave() {
        val existing = listOf(ref("logo"))
        assertNull(fileRefNewIdProblem("heroImage", existing))
        assertEquals("Give the file an id: a name for it, such as heroImage.", fileRefNewIdProblem("", existing))
        assertEquals("This client already has a file 'logo'. Replace it from its own row.", fileRefNewIdProblem("logo", existing))
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
        assertEquals("Give the file an id: a name for it, such as heroImage.", fileRefAddUploadProblem("", existing, takes, 9L * 1024 * 1024))
        assertEquals("This file is 9 MB; a file ref's file may be at most 2 MB.", fileRefAddUploadProblem("hero", existing, takes, 9L * 1024 * 1024))

        // A file the deployment keeps: the id, then where, then its path.
        assertNull(fileRefLocalProblem("hero", existing, "approved", "hero.png"))
        assertEquals("This client already has a file 'logo'. Replace it from its own row.", fileRefLocalProblem("logo", existing, null, ""))
        assertEquals("Choose where the file is.", fileRefLocalProblem("hero", existing, null, ""))
        assertEquals("Give the file's path.", fileRefLocalProblem("hero", existing, "approved", ""))

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

        assertEquals("The file logo", fileRefAltText(ref()))
        assertEquals("Our logo.", fileRefAltText(ref(description = "Our logo.")))
    }

    @Test
    fun whatAFileIsAndWhereAreSaidByItsSource() {
        assertEquals("image/png · 13 KB · our logo.png", fileRefHoldsText(ref()))
        assertEquals("13 KB", fileRefHoldsText(ref(file = WebAssetFile("", 12_345, hash, "", null))))
        val notThere = "Its file is not there to be served: the address answers as not found."
        assertEquals(notThere, fileRefHoldsText(ref(missing = true)))
        // A listing that says a ref is there and gives no record of its file is read as one that is not.
        assertEquals(notThere, fileRefHoldsText(ref(file = null)))

        val at = formatTimestamp(pngFile.uploadedAt)
        assertEquals("Uploaded $at by Chief One. In the configuration 'copy'.", fileRefWhereText(ref()))
        assertEquals("Uploaded $at.", fileRefWhereText(ref(by = null, configName = null)))
        // An upload whose file has gone has no date to give; who is not known either.
        assertEquals("Uploaded. In the configuration 'copy'.", fileRefWhereText(ref(missing = true, by = null)))
        assertEquals(
            "Shipped with the client, as a file on the server: brand/logo.png, last changed $at. In the configuration 'art'.",
            fileRefWhereText(ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false, by = null, configName = "art")),
        )
        assertEquals(
            "A file on the server: brand/logo.png, last changed $at. In the configuration 'copy'.",
            fileRefWhereText(ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", by = null)),
        )
        assertEquals(
            "A file the deployment keeps for this client: marks/hero.png in approved, last changed $at. In the configuration 'copy'.",
            fileRefWhereText(ref(source = FREF.sourceClientLocal, root = "approved", path = "marks/hero.png", by = null)),
        )
        // With no file there is no date to give.
        assertEquals(
            "A file the deployment keeps for this client: hero.png in approved. In the configuration 'copy'.",
            fileRefWhereText(ref(source = FREF.sourceClientLocal, root = "approved", path = "hero.png", missing = true, nameless = true)),
        )
        // A source a later backend adds is called what it is called, and not passed off as an upload.
        assertEquals("Kept as 'vault', which this page does not know. In the configuration 'copy'.", fileRefWhereText(ref(source = "vault")))
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
        assertEquals("Served to this client's people at ", fileRefAddressLead(ref()))
        assertEquals("Served in the sandbox, and to the client's people once published, at ", fileRefAddressLead(ref(live = false)))
        // The kernel's own address, so the backend serves what the page shows.
        assertEquals("/kdr.en/ref/logo:$hash", FREF.staticPath("kdr.en", FREF.versionedName("logo", hash)))
    }

    @Test
    fun onlyAStoredRefIsRemovedOrRewordedAndAShippedOneSaysWhatCanBeDone() {
        assertTrue(fileRefRemovable(ref()))
        assertNull(fileRefShippedHint(ref()))
        val shipped = ref(source = FREF.sourceLocal, stored = false)
        assertFalse(fileRefRemovable(shipped))
        assertEquals(
            "Shipped with the client, so it is not removed or reworded here: uploading a file over it replaces it, and removing that upload shows this one again.",
            fileRefShippedHint(shipped),
        )
    }

    @Test
    fun aRemovalIsAskedAboutNamingWhereItActsAndWhatItLeaves() {
        assertEquals("Remove the file 'logo' of acme (live data)?", fileRefRemoveQuestion(ref(), "acme", draft = false))
        assertEquals("Remove the file 'logo' from the draft of acme?", fileRefRemoveQuestion(ref(), "acme", draft = true))
        // On a sandbox's page the draft is its parent's.
        assertEquals("Remove the file 'logo' from the draft of acme?", fileRefRemoveQuestion(ref(), "acme:sandbox", draft = true))

        // Under an upload there may be an image the client was shipped with, which then takes its place.
        assertEquals(
            "The client's people stop being served it at once, or are served the image the client was shipped with under this id, where there is one.",
            fileRefRemoveConsequence(ref(), draft = false),
        )
        assertEquals(
            "The sandbox stops showing it, or shows the image the client was shipped with under this id, where there is one. " +
                "The client's people are served it until the configuration is published.",
            fileRefRemoveConsequence(ref(), draft = true),
        )
        // A draft the client's people were never served: nothing is said of their keeping it.
        assertEquals(
            "The sandbox stops showing it, or shows the image the client was shipped with under this id, where there is one.",
            fileRefRemoveConsequence(ref(live = false), draft = true),
        )
    }

    @Test
    fun aChangeIsSaidWithHowItTookEffect() {
        val live = FileRefChange("logo", "copy", EDM.live, ref())
        val draft = FileRefChange("logo", "copy", EDM.draft, ref(live = false))
        assertEquals("Uploaded 'logo', in copy.", fileRefUploadedNote(live))
        assertEquals("Uploaded 'logo', in copy. Saved as a draft: the client's sandbox shows it, and it goes live once published.", fileRefUploadedNote(draft))

        val named = FileRefChange("hero", "copy", EDM.live, ref("hero", source = FREF.sourceClientLocal, root = "approved", path = "marks/hero.png"))
        assertEquals("'hero' now names marks/hero.png, in copy.", fileRefNamedNote(named))
        assertEquals("'hero' now names that file, in copy.", fileRefNamedNote(FileRefChange("hero", "copy", EDM.live, null)))
        assertTrue(fileRefNamedNote(FileRefChange("hero", "copy", EDM.draft, named.ref)).endsWith("it goes live once published."))

        assertEquals("Reworded 'logo'.", fileRefDescribedNote(FileRefChange("logo", "copy", EDM.live, ref(description = "New."))))
        assertEquals("Cleared the description of 'logo'.", fileRefDescribedNote(FileRefChange("logo", "copy", EDM.live, ref())))
        assertTrue(fileRefDescribedNote(FileRefChange("logo", "copy", EDM.draft, ref(description = "New."))).startsWith("Reworded 'logo'. Saved as a draft"))

        assertEquals("Removed 'logo'.", fileRefRemovedNote(FileRefChange("logo", "copy", EDM.live, null)))
        // A removal made as a draft is not said to be something the sandbox "shows".
        assertEquals(
            "Removed 'logo'. A draft: the sandbox shows the change, and the client's people keep what they have until it is published.",
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.draft, null)),
        )
        // Still there after a removal and held by no stored configuration: the image the client was shipped with.
        val revealed = FileRefChange("logo", "copy", EDM.live, ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false))
        assertEquals("Removed the upload of 'logo': the image the client was shipped with is shown again.", fileRefRemovedNote(revealed))
        // Still there and stored: another configuration held one too, and nothing was "shipped".
        assertEquals(
            "Removed one entry of 'logo'; another configuration, art, still holds one.",
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.live, ref(configName = "art"))),
        )
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
        // Nothing listed yet: nothing to redraw.
        assertNull(withFileRefChange(null, FileRefChange("logo", "copy", EDM.live, replaced)))
    }

    @Test
    fun theLimitIsSaidOnceItIsNearAndTheFormGivesWayToItOnceItIsReached() {
        fun some(n: Int) = List(n) { ref("r$it") }
        assertNull(fileRefCountText(some(10), takes))
        assertNull(fileRefCountText(some(179), takes))
        assertEquals("This client has 180 of the 200 a client is served.", fileRefCountText(some(180), takes))
        assertNull(fileRefCountText(some(180), null))

        assertNull(fileRefAtLimitText(some(199), takes))
        assertEquals("This client has 200 files, which is as many as a client is served. Remove one to add another.", fileRefAtLimitText(some(200), takes))
        // A backend that names no limit holds the page to none.
        val unlimited = FileRefTakes(0, emptyList(), emptyList(), 0, emptyList())
        assertNull(fileRefAtLimitText(some(500), unlimited))
        assertNull(fileRefCountText(some(500), unlimited))
        assertNull(fileRefAtLimitText(some(500), null))
    }
}
