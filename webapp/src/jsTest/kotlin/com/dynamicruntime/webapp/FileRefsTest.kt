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
 * and what a ref takes are read, and every sentence the section says of them.
 */
class FileRefsTest {
    private val hash = "ab".repeat(32)
    private val pngFile = WebAssetFile("image/png", 12_345, hash, "2026-10-12T19:49:51.568Z", "our logo.png")

    private fun ref(
        refId: String = "logo", source: String = FREF.sourceStore, root: String? = null, path: String? = null,
        live: Boolean = true, stored: Boolean = true, missing: Boolean = false, file: WebAssetFile? = pngFile,
        by: String? = "Chief One", description: String? = null,
    ) = FileRefView(
        refId, source, root, path, hash.takeIf { !missing }, "$refId:$hash".takeIf { !missing }, description, "copy",
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

    // A backend from before a ref said these: an upload is all there was, and an upload is stored.
    @Test
    fun aListingThatSaysLessIsReadAsWhatItThenMeant() {
        val old = parseFileRef(mapOf(CCT.refId to "logo", CCT.hash to hash, FREF.name to "logo:$hash", FREF.live to true))
        assertNotNull(old)
        assertEquals(FREF.sourceStore, old.source)
        assertTrue(old.stored)
        // A local ref that does not say is not assumed removable: the backend would refuse, for a shipped one.
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
        assertNull(fileRefFileProblem(takes, 2L * 1024 * 1024))
        assertEquals("This file is 3 MB; a file ref's file may be at most 2 MB.", fileRefFileProblem(takes, 3L * 1024 * 1024))
        // With no limit known the browser says nothing, and the backend does.
        assertNull(fileRefFileProblem(bare, 9L * 1024 * 1024))
        assertNull(fileRefFileProblem(null, 9L * 1024 * 1024))
    }

    @Test
    fun theIntroSaysHowAChangeTakesEffectForThisClient() {
        assertTrue(fileRefsIntro(null, hasSandbox = false).endsWith("A change takes effect at once for everyone in the client."))
        assertTrue(fileRefsIntro(null, hasSandbox = true).contains("a change here is a draft"))
        val inSandbox = fileRefsIntro("acme", hasSandbox = false)
        assertTrue(inSandbox.startsWith("The files acme's sandbox shows"))
        assertTrue(inSandbox.contains("a draft of acme's"))
    }

    @Test
    fun aNewIdHasToBeANameTheClientDoesNotHave() {
        val existing = listOf(ref("logo"))
        assertNull(fileRefNewIdProblem("heroImage", existing))
        assertEquals("Give the file an id: a name for it, such as heroImage.", fileRefNewIdProblem("", existing))
        assertEquals("This client already has a file 'logo'. Replace it from its own row.", fileRefNewIdProblem("logo", existing))
        // The kernel's own rule, which the backend holds an id to: said before anything is sent.
        for (bad in listOf("my logo", "logo.png", "2logo", "a/b")) assertNotNull(fileRefNewIdProblem(bad, existing), "should refuse '$bad'")
    }

    @Test
    fun aRefIsMarkedByWhatIsUnusualAboutIt() {
        assertEquals(emptyList(), fileRefTags(ref()))
        assertEquals(listOf("Draft"), fileRefTags(ref(live = false)))
        assertEquals(listOf("Missing", "Shipped"), fileRefTags(ref(source = FREF.sourceLocal, stored = false, missing = true)))
        assertEquals(listOf("Draft", "Missing"), fileRefTags(ref(live = false, missing = true)))
    }

    @Test
    fun whatAFileIsAndWhereAreSaidByItsSource() {
        assertEquals("image/png · 13 KB · our logo.png", fileRefHoldsText(ref()))
        assertEquals("Its file is not there to be served: the address answers as not found.", fileRefHoldsText(ref(missing = true)))

        assertEquals("Uploaded ${formatTimestamp(pngFile.uploadedAt)} by Chief One.", fileRefWhereText(ref()))
        assertEquals("Uploaded ${formatTimestamp(pngFile.uploadedAt)}.", fileRefWhereText(ref(by = null)))
        val changed = ", last changed ${formatTimestamp(pngFile.uploadedAt)}"
        assertEquals(
            "Shipped with the client, as a file on the server: brand/logo.png$changed.",
            fileRefWhereText(ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false, by = null)),
        )
        assertEquals(
            "A file on the server: brand/logo.png$changed.",
            fileRefWhereText(ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", by = null)),
        )
        assertEquals(
            "A file the deployment keeps for this client: marks/hero.png in approved$changed.",
            fileRefWhereText(ref(source = FREF.sourceClientLocal, root = "approved", path = "marks/hero.png", by = null)),
        )
        // With no file there is no date to give.
        assertEquals(
            "A file the deployment keeps for this client: hero.png in approved.",
            fileRefWhereText(ref(source = FREF.sourceClientLocal, root = "approved", path = "hero.png", missing = true)),
        )
    }

    @Test
    fun onlyAStoredRefIsRemovedAndAMissingOneHasNoAddressOrPreview() {
        assertTrue(fileRefRemovable(ref()))
        assertFalse(fileRefRemovable(ref(source = FREF.sourceLocal, stored = false)))

        val address = { name: String -> "/st/kdr.en/ref/$name" }
        assertEquals("/st/kdr.en/ref/logo:$hash", fileRefAddress(ref(), address))
        // A draft has an address too, which is the sandbox's until it is published.
        assertEquals("Served at ", fileRefAddressLead(ref()))
        assertEquals("Served in the sandbox, and to the client's people once published, at ", fileRefAddressLead(ref(live = false)))
        assertNull(fileRefAddress(ref(missing = true), address))
        // The kernel's own address, so the backend serves what the page shows.
        assertEquals("/kdr.en/ref/logo:$hash", FREF.staticPath("kdr.en", FREF.versionedName("logo", hash)))

        assertEquals(mapOf(FREF.client to "acme", CCT.refId to "logo", CCT.hash to hash), fileRefPreviewArgs("acme", ref()))
        assertNull(fileRefPreviewArgs("acme", ref(missing = true)))
    }

    @Test
    fun aRemovalIsAskedAboutNamingWhereItActs() {
        assertEquals("Remove the file 'logo' of acme (live data)?", fileRefRemoveQuestion(ref(), "acme", draft = false))
        assertEquals("Remove the file 'logo' from the draft of acme?", fileRefRemoveQuestion(ref(), "acme", draft = true))
        // On a sandbox's page the draft is its parent's.
        assertEquals("Remove the file 'logo' from the draft of acme?", fileRefRemoveQuestion(ref(), "acme:sandbox", draft = true))
        assertTrue(fileRefRemoveConsequence(draft = false).startsWith("Its address stops answering for the client's people, at once."))
        assertTrue(fileRefRemoveConsequence(draft = true).startsWith("The sandbox stops showing it."))
    }

    @Test
    fun aChangeIsSaidWithHowItTookEffect() {
        val live = FileRefChange("logo", "copy", EDM.live, ref())
        val draft = FileRefChange("logo", "copy", EDM.draft, ref(live = false))
        assertEquals("Uploaded 'logo', in copy.", fileRefUploadedNote(live))
        assertEquals(savedNote("Uploaded 'logo', in copy.", EDM.draft), fileRefUploadedNote(draft))
        assertTrue(fileRefUploadedNote(draft).contains("Saved as a draft"))

        val named = FileRefChange("hero", "copy", EDM.live, ref("hero", source = FREF.sourceClientLocal, root = "approved", path = "marks/hero.png"))
        assertEquals("'hero' now names marks/hero.png, in copy.", fileRefNamedNote(named))

        assertEquals("Removed 'logo'.", fileRefRemovedNote(FileRefChange("logo", "copy", EDM.live, null)))
        // A removal made as a draft is not said to be something the sandbox "shows".
        assertEquals(
            "Removed 'logo'. A draft: the sandbox shows the change, and the client's people keep what they have until it is published.",
            fileRefRemovedNote(FileRefChange("logo", "copy", EDM.draft, null)),
        )
        // Still there after a removal: the image the client was shipped with, which the upload stood in front of.
        val revealed = FileRefChange("logo", "copy", EDM.live, ref(source = FREF.sourceLocal, root = "brand", path = "logo.png", stored = false))
        assertEquals("Removed the upload of 'logo': the image the client was shipped with is shown again.", fileRefRemovedNote(revealed))
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
    fun theLimitIsSaidOnlyOnceItIsNear() {
        fun some(n: Int) = List(n) { ref("r$it") }
        assertNull(fileRefCountText(some(10), takes))
        assertNull(fileRefCountText(some(179), takes))
        assertEquals("180 of the 200 a client is served.", fileRefCountText(some(180), takes))
        assertNull(fileRefCountText(some(180), null))
    }
}
