package com.dynamicruntime.webapp

import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.util.MDR
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS

/**
 * A client's file refs on its page (issue #1176): the named images its configuration holds, which its
 * administrator lists, uploads, names where the deployment keeps one, rewords, and removes (`FileRefsSection`).
 *
 * Everything here is pure, and covered under `jsNodeTest`: how the listing is read, what the section decides --
 * whether a change is a draft, what a form may send, what is wrong with it before it is sent -- and what it says.
 * Where a ref's file is, is its `source` -- uploaded into the file store, shipped with the client as a file on
 * the server, or (issue #1164) a file the deployment keeps for the client -- and the wording follows it.
 */

/** One file ref as the listing answers it. [file] is the record of its file, absent when the file is [missing]. */
class FileRefView(
    val refId: String,
    /** Where its file is: one of `FREF.sources`. */
    val source: String,
    /** For a file on the server: the root it is under, and its path there. */
    val root: String?,
    val path: String?,
    /**
     * The hash of its bytes, and `<refId>:<hash>`, which its address carries. Absent for a file on the server that
     * is not there, since nobody authored its hash. An upload whose file has gone still says the hash its entry
     * holds -- so [missing], and not these, is what says there is nothing to show.
     */
    val hash: String?,
    val name: String?,
    val description: String?,
    /** The configuration holding its entry. */
    val configName: String?,
    /** Whether the client's own people are served it as listed; false for a draft only the sandbox shows. */
    val live: Boolean,
    /** Whether a stored configuration holds it. One that is not was shipped with the client, and cannot be removed. */
    val stored: Boolean,
    val missing: Boolean,
    val file: WebAssetFile?,
    val uploadedByName: String?,
    /**
     * The template whose file it is served (issue #1180): a ref the client inherits and has no file of its own for.
     * Null when the file is the client's own.
     */
    val fromTemplate: String? = null,
    /**
     * Whether it is one the client's own people are served that a draft removed (issue #1188): listed until a publish
     * takes it away, previewed from what they are served, and offered only to be put back.
     */
    val removedInDraft: Boolean = false,
    /**
     * Whether it can be removed and reworded, as the backend says (issue #1192): asked as the write asks it, so a row
     * offers what the backend will do. Null from a backend that does not say, when [stored] stands in.
     */
    val removable: Boolean? = null,
    /** When the stored entry naming it was last changed, and by whom: who set this ref, not who first stored its file. */
    val setAt: String? = null,
    val setBy: String? = null,
)

/** The listing's items as refs; one without an id is not one. */
fun parseFileRefs(items: List<Map<String, Any?>>): List<FileRefView> = items.mapNotNull(::parseFileRef)

/** One listed ref, or null for an item with no id. */
fun parseFileRef(item: Map<String, Any?>): FileRefView? {
    val refId = item[CCT.refId].toOptStr() ?: return null
    return FileRefView(
        refId = refId,
        // A listing from before a ref said where its file is names only uploads.
        source = item[CCT.source].toOptStr() ?: FREF.sourceStore,
        root = item[CCT.root].toOptStr(),
        path = item[CCT.path].toOptStr(),
        hash = item[CCT.hash].toOptStr(),
        name = item[FREF.name].toOptStr(),
        description = item[CCT.description].toOptStr(),
        configName = item[FREF.configName].toOptStr(),
        live = item[FREF.live] == true,
        // Absent from a backend that does not say: only a ref that names a root whole is taken for one the client
        // was shipped with, which is the one the backend would refuse to remove.
        stored = (item[FREF.stored] as? Boolean) ?: (item[CCT.source].toOptStr() != FREF.sourceLocal),
        missing = item[FREF.missing] == true,
        removedInDraft = item[FREF.removedInDraft] == true,
        removable = item[FREF.removable] as? Boolean,
        setAt = item[FREF.setAt].toOptStr(),
        setBy = item[FREF.setBy].toOptStr(),
        file = storedFileFrom(item[FREF.file]),
        uploadedByName = item[FREF.uploadedByName].toOptStr(),
        fromTemplate = item[FREF.fromTemplate].toOptStr(),
    )
}

/** What a change -- an upload, a naming, a rewording, a removal -- answered: where it landed, how, and the ref as it now is. */
class FileRefChange(
    val refId: String,
    val configName: String,
    /** `EDM.live`, or `EDM.draft` for a client with a sandbox. */
    val mode: String,
    /** The ref as its administrator is now shown it; null once it is gone. */
    val ref: FileRefView?,
)

/** A change's `results`; the mode is live from a backend that says none. */
fun parseFileRefChange(results: Map<String, Any?>): FileRefChange = FileRefChange(
    refId = results[CCT.refId].toOptStr().orEmpty(),
    configName = results[FREF.configName].toOptStr().orEmpty(),
    mode = results[FREF.mode].toOptStr() ?: EDM.live,
    ref = results[FREF.ref].toJsonMapOrEmpty().takeIf { it.isNotEmpty() }?.let(::parseFileRef),
)

/** What a client's file refs take, as the backend says it: so the page offers what will be accepted. */
class FileRefTakes(
    val maxSize: Long,
    /** The content types a file picker should offer. */
    val mimeTypes: List<String>,
    /** The kinds of image, as a person names them. */
    val kinds: List<String>,
    /** How many refs a client is served. */
    val refLimit: Int,
    /** The roots open to clients, where a file may be named and not uploaded. Empty on most deployments. */
    val roots: List<String>,
)

fun parseFileRefTakes(item: Map<String, Any?>): FileRefTakes = FileRefTakes(
    maxSize = (item[WAS.maxSize] as? Number)?.toLong() ?: 0L,
    mimeTypes = item[WAS.mimeTypes].toJsonListOfStrings(),
    kinds = item[WAS.kinds].toJsonListOfStrings(),
    refLimit = (item[FREF.refLimit] as? Number)?.toInt() ?: 0,
    roots = item[FREF.roots].toJsonListOfStrings(),
)

/**
 * What the section says above its list: what a file ref is, how a change to one takes effect, and -- where it is a
 * draft -- that the list is the sandbox's view. Live at once for a client that runs its latest configuration; a
 * draft for one with a sandbox ([hasSandbox]), unlike a web asset, which is never one; and for a sandbox's own
 * page ([sandboxOf] is its parent), where what is listed is the parent's latest. A client that takes nothing
 * stored on this node ([staticHere]) is listed and not changed, and is told that and nothing of drafts.
 */
fun fileRefsIntro(sandboxOf: String?, hasSandbox: Boolean, staticHere: Boolean): String {
    val what = "Named images this client's configuration holds, each served at an address of its own."
    val owner = sandboxOf ?: "the client"
    val listed = "What is listed is what the sandbox shows: ${if (sandboxOf != null) "$sandboxOf's" else "the client's"} latest " +
        "configuration, drafts included -- and a file removed as a draft, which $owner's people are served until that is " +
        "published, marked so and offered to be put back."
    return when {
        staticHere -> "$what This client's configuration comes from source code on this node, so its files are listed here and not changed."
        sandboxOf != null -> "The files of $sandboxOf's sandbox. $listed A change made here is a draft of $sandboxOf's, and goes live for its people once that is published."
        hasSandbox ->
            "$what This client has a sandbox, so a change here is a draft: the sandbox shows it, and it goes live " +
                "for the client's people once its configuration is published, from the stored configuration below. $listed"
        else -> "$what A change takes effect at once for everyone in the client."
    }
}

/** What stands in for the list when it is empty. Where the list is the sandbox's view, it does not say the client has none. */
fun fileRefsEmptyText(draft: Boolean): String =
    if (draft) "No files in the latest configuration, which is what the sandbox shows." else "This client has no files."

/** What a ref's file may be, for the hint beside an upload: `PNG, SVG, ICO, JPEG or WebP, up to 2 MB.` */
fun fileRefTakesText(takes: FileRefTakes): String = imageTakesText(takes.kinds, takes.maxSize)

/**
 * What is wrong with a picked file of [size] bytes before it is sent, or null: only what the browser can know
 * without reading it. Whether it is the image it claims to be is the backend's to say, in its own words.
 */
fun fileRefFileProblem(takes: FileRefTakes?, size: Long): String? {
    val max = takes?.maxSize ?: return null
    return if (max in 1 until size) "This file is ${byteSizeText(size)}; a file ref's file may be at most ${byteSizeText(max)}." else null
}

/**
 * What stops a new ref being added under [refId], or null: an id that is no name (the kernel's own rule, which the
 * backend holds it to), or one the client already has -- an existing ref is replaced from its own row, where it
 * can be seen, and not by typing its id here.
 */
fun fileRefNewIdProblem(refId: String, existing: List<FileRefView>): String? = when {
    refId.isEmpty() -> "Give the file an id: a name for it, such as heroImage."
    existing.any { it.refId == refId } -> "This client already has a file '$refId'. Replace it from its own row."
    else -> FREF.refIdProblem(refId)
}

/**
 * What stops a new ref being added by an upload of [size] bytes, or null: the id first, then the file -- the
 * order a person fills the form in, so the first thing said is the first thing to mend.
 */
fun fileRefAddUploadProblem(refId: String, existing: List<FileRefView>, takes: FileRefTakes?, size: Long): String? =
    fileRefNewIdProblem(refId, existing) ?: fileRefFileProblem(takes, size)

/**
 * The root a file is to be named in: the one [chosen], while it is still one of the [roots] open to clients; else
 * the only one there is; else none, and the form asks.
 */
fun fileRefChosenRoot(chosen: String?, roots: List<String>): String? = chosen?.takeIf { it in roots } ?: roots.singleOrNull()

/**
 * What stops a file the deployment keeps being named as a new ref, or null: the id, then where, then its path.
 * Whether there is such a file is the backend's to say, in the one sentence it says of every reason.
 */
fun fileRefLocalProblem(refId: String, existing: List<FileRefView>, root: String?, path: String): String? =
    fileRefNewIdProblem(refId, existing) ?: fileRefKeptFileProblem(root, path)

/** What is missing from a kept file named in the two fields, or null: where it is, then its path. */
fun fileRefKeptFileProblem(root: String?, path: String): String? =
    "Choose where the file is.".takeIf { root == null } ?: "Give the file's path.".takeIf { path.isEmpty() }

/**
 * Whether [ref]'s row offers to point it at a file the deployment keeps for the client (issue #1189): only where a
 * root is open to clients ([FileRefTakes.roots], which most deployments have none of), and not for a ref a draft
 * removed, which has no entry to change, only one to put back. A ref the client was shipped with is offered it, as
 * it is offered an upload: the result is a stored override, and removing it shows the shipped one again.
 */
fun fileRefRepointable(ref: FileRefView, takes: FileRefTakes?): Boolean = takes?.roots?.isNotEmpty() == true && !ref.removedInDraft

/**
 * Where a row's "Use a kept file…" starts, as the root and the path: from what [ref] names now when that is a kept
 * file -- the usual change is to a sibling -- and empty otherwise, since an upload's or a shipped file's place says
 * nothing about where the client's own files are.
 */
fun fileRefRepointStart(ref: FileRefView): Pair<String?, String> =
    if (ref.source == FREF.sourceClientLocal) ref.root to ref.path.orEmpty() else null to ""

/**
 * What stops [ref] being pointed at the kept file in [root] at [path], or null: the two fields first, then a file it
 * already names. Whether there is such a file is the backend's to say, in its one sentence. Pure, and covered under
 * `jsNodeTest`.
 */
fun fileRefRepointProblem(ref: FileRefView, root: String?, path: String): String? =
    fileRefKeptFileProblem(root, path)
        ?: "'${ref.refId}' already names that file.".takeIf { ref.source == FREF.sourceClientLocal && ref.root == root && ref.path == path }

/**
 * The description a **new** ref is sent with, from what was typed: trimmed, and none at all when that leaves
 * nothing -- a new ref has no description to keep or to clear. (An existing ref's is changed by its own editor,
 * which sends what was typed, blank included: blank clears it.)
 */
fun fileRefNewDescription(typed: String): String? = typed.trim().ifEmpty { null }

/**
 * Why no further file can be added, or null: the client is served as many as it may be. Said in place of the form,
 * since an upload would be sent whole and then refused -- and saying what **can** be done (issue #1169): a file the
 * client was shipped with cannot be removed here, so "remove one" is said only where some are its own.
 */
fun fileRefAtLimitText(refs: List<FileRefView>, takes: FileRefTakes?): String? {
    val limit = takes?.refLimit?.takeIf { it > 0 } ?: return null
    if (refs.size < limit) return null
    val full = "This client has $limit files, which is as many as a client is served."
    return if (refs.any { it.stored }) {
        "$full Remove one to add another, or replace one of them."
    } else {
        "$full Every one was shipped with it, so none can be removed here: replace one of them, or have the client's " +
            "source configuration changed."
    }
}

/** How many refs the client has of how many it may, once it is near enough the limit to say: `This client has 180 of the 200 a client is served.` */
fun fileRefCountText(refs: List<FileRefView>, takes: FileRefTakes?): String? {
    val limit = takes?.refLimit?.takeIf { it > 0 } ?: return null
    return if (refs.size * 10 >= limit * 9) "This client has ${refs.size} of the $limit a client is served." else null
}

/** The short marks beside a ref's id: what is unusual about it. */
fun fileRefTags(ref: FileRefView): List<String> = listOfNotNull(
    "Removed in draft".takeIf { ref.removedInDraft },
    "Draft".takeIf { !ref.live },
    "Missing".takeIf { ref.missing },
    "Shipped".takeIf { !ref.stored },
)

/** Every mark [fileRefTags] gives, in the order it gives them: what the section's toggles are drawn in. */
val fileRefMarks: List<String> = listOf("Removed in draft", "Draft", "Missing", "Shipped")

/**
 * What a person narrows a client's files to (issue #1190): words in a ref's id or description, and marks. A ref is
 * kept when the words are in either, ignoring case, and -- with marks chosen -- it carries any of them.
 */
class FileRefFilter(val text: String = "", val marks: Set<String> = emptySet()) {
    /** Whether it keeps anything less than every ref. */
    val isActive: Boolean get() = text.isNotBlank() || marks.isNotEmpty()
}

/** Whether [filter] keeps [ref] ([FileRefFilter]). Pure, and covered under `jsNodeTest`. */
fun fileRefMatches(ref: FileRefView, filter: FileRefFilter): Boolean {
    val words = filter.text.trim().lowercase()
    val inWords = words.isEmpty() || ref.refId.lowercase().contains(words) || ref.description?.lowercase()?.contains(words) == true
    return inWords && (filter.marks.isEmpty() || fileRefTags(ref).any { it in filter.marks })
}

/** The marks [refs] carry, each with how many carry it, in [fileRefMarks]' order: what is offered to filter by. */
fun fileRefMarkCounts(refs: List<FileRefView>): List<Pair<String, Int>> {
    val counts = refs.flatMap { fileRefTags(it) }.groupingBy { it }.eachCount()
    return fileRefMarks.mapNotNull { mark -> counts[mark]?.let { mark to it } }
}

/**
 * How soon a ref is listed: what needs attention first -- a file that is missing, then one a draft removed, then a
 * draft -- and the rest after. The first key of [fileRefOrdered].
 */
fun fileRefAttention(ref: FileRefView): Int = when {
    ref.missing -> 0
    ref.removedInDraft -> 1
    !ref.live -> 2
    else -> 3
}

/**
 * [refs] in the section's stated order (issue #1190): what needs attention first ([fileRefAttention]), then by id,
 * ignoring case. The listing's own order is the registry's, which means nothing to a person; this one is the same
 * however the list was arrived at -- read, or drawn from a change's answer -- so a row does not move on a re-read.
 * Pure, and covered under `jsNodeTest`.
 */
fun fileRefOrdered(refs: List<FileRefView>): List<FileRefView> =
    refs.sortedWith(compareBy<FileRefView>({ fileRefAttention(it) }, { it.refId.lowercase() }, { it.refId }))

/** How many refs the section draws before it offers the rest, so the page below it stays in reach. */
const val fileRefFirstShown = 20

/** What the section draws: the [shown] refs, of the [matched] its filter keeps, of [total]. */
class FileRefPage(val shown: List<FileRefView>, val matched: Int, val total: Int) {
    /** How many the filter keeps that are not drawn: what "Show all" would add. */
    val hidden: Int get() = matched - shown.size
}

/**
 * [refs] as the section draws them: those [filter] keeps, in order ([fileRefOrdered]), the first [fileRefFirstShown]
 * unless [showAll] -- and, whatever the filter and the cap, those in [kept]: the refs this page has just added or
 * changed, which a person must see the result of where they acted, not have land past the cap or out of the filter.
 * Pure, and covered under `jsNodeTest`.
 */
fun fileRefPage(refs: List<FileRefView>, filter: FileRefFilter, showAll: Boolean, kept: Set<String> = emptySet()): FileRefPage {
    val matched = fileRefOrdered(refs.filter { fileRefMatches(it, filter) || it.refId in kept })
    val shown = if (showAll) matched else matched.filterIndexed { i, ref -> i < fileRefFirstShown || ref.refId in kept }
    return FileRefPage(shown, matched.size, refs.size)
}

/** What is said of how much of the list is drawn, or null when it is all of it. Pure, and covered under `jsNodeTest`. */
fun fileRefPageText(page: FileRefPage, filter: FileRefFilter): String? {
    val files = if (page.total == 1) "file" else "files"
    return when {
        filter.isActive && page.matched == 0 -> "No file matches."
        page.hidden > 0 && filter.isActive && page.matched < page.total ->
            "Showing ${page.shown.size} of the ${page.matched} matching, of ${page.total} $files."
        page.hidden > 0 -> "Showing ${page.shown.size} of ${page.total} $files."
        filter.isActive -> "${page.matched} of ${page.total} $files match."
        else -> null
    }
}

/** What a preview's image is called, for whoever does not see it. */
fun fileRefAltText(ref: FileRefView): String = ref.description ?: "The file ${ref.refId}"

/**
 * What a ref's file is, in a line: its type, its size and its own name -- or, for one whose file is not there,
 * that it is not, which is all the backend says of it whatever the reason.
 */
fun fileRefHoldsText(ref: FileRefView): String {
    val file = ref.file
    if (ref.missing || file == null) return "Its file is not there to be served: the address answers as not found."
    return listOfNotNull(file.mimeType.ifEmpty { null }, byteSizeText(file.size), file.fileName).joinToString(" · ")
}

/**
 * Where a ref's file is, how it came to be there, and the configuration holding the ref -- by its source. An
 * upload has a who and a when, which are of the **file**: of whoever first stored those bytes, under whatever id. A
 * file on the server has a root and a path, and a when that is the file's own last change. Who set **this ref**, and
 * when, is its entry's own stamp, said with the configuration (issue #1192). A source this build does not know is
 * said as it is called, not passed off as an upload.
 */
fun fileRefWhereText(ref: FileRefView): String {
    val at = ref.file?.uploadedAt?.takeIf { it.isNotEmpty() }?.let(::formatTimestamp)
    val place = "${ref.root.orEmpty()}/${ref.path.orEmpty()}"
    val changed = at?.let { ", last changed $it" }.orEmpty()
    val where = when (ref.source) {
        FREF.sourceStore -> "Its file was first uploaded" + at?.let { " $it" }.orEmpty() + ref.uploadedByName?.let { " by $it" }.orEmpty() + "."
        FREF.sourceLocal ->
            if (ref.stored) "A file on the server: $place$changed." else "Shipped with the client, as a file on the server: $place$changed."
        FREF.sourceClientLocal -> ref.fromTemplate?.let { template ->
            // The template's default (issue #1180), until the deployment keeps one for this client.
            "The template $template's file, which this client is served until the deployment keeps one of its own: " +
                "${ref.path.orEmpty()} in ${ref.root.orEmpty()}$changed."
        } ?: "A file the deployment keeps for this client: ${ref.path.orEmpty()} in ${ref.root.orEmpty()}$changed."
        else -> "Kept as '${ref.source}', which this page does not know."
    }
    val set = ref.setAt?.let { ", set ${formatTimestamp(it)}" + ref.setBy?.let { by -> " by $by" }.orEmpty() }.orEmpty()
    return where + ref.configName?.let { " In the configuration '$it'$set." }.orEmpty()
}

/**
 * What is said of a ref the client was shipped with, where the actions of a stored one would be: why there is no
 * Remove and no rewording, and what can be done instead. Null for a stored ref, which has both.
 */
fun fileRefShippedHint(ref: FileRefView): String? =
    if (ref.stored || fileRefRemovable(ref)) null else "Shipped with the client, so it is not removed or reworded here: uploading a file over it replaces it, and removing that upload shows this one again."

/**
 * Whether a ref can be removed, or its description reworded: what the backend says ([FileRefView.removable], asked as
 * a removal asks it, issue #1192) -- or, from one that does not say, whether a stored configuration holds it -- and
 * never one a draft has already removed, which has no entry in the draft to change, only one to put back
 * ([fileRefRestorable]).
 */
fun fileRefRemovable(ref: FileRefView): Boolean = (ref.removable ?: ref.stored) && !ref.removedInDraft

/** Whether a ref is offered only to be put back: one a draft removed, which is all that can be done with it (issue #1188). */
fun fileRefRestorable(ref: FileRefView): Boolean = ref.removedInDraft

/**
 * What a row removed in a draft says of itself, under its facts: that its people are served it until a publish, and
 * what putting it back does. Null for any other row.
 */
fun fileRefRemovedInDraftText(ref: FileRefView): String? =
    if (!ref.removedInDraft) null
    else "A draft removed this file: the sandbox no longer shows it, and the client's people are served it until the " +
        "configuration is published. Putting it back restores it in the draft as it is published."

/**
 * The address a ref is served at, given how a versioned name becomes one ([addressOf]) -- or null for a ref with
 * no file to name. What copy naming the ref (`ref:<id>`, [fileRefImageMarkdown]) resolves to for the client's
 * people, shown beside it so an administrator can see which file that is.
 */
fun fileRefAddress(ref: FileRefView, addressOf: (String) -> String): String? = ref.name?.takeIf { !ref.missing }?.let(addressOf)

/**
 * What goes before a ref's address. It says **whose** address it is: the static root answers each caller with
 * their own client's ref, so the same address is another file, or none, to an administrator of every client who
 * opens it from elsewhere. A draft's is real -- the sandbox is served it there -- and is not yet what the client's
 * own people get.
 */
fun fileRefAddressLead(ref: FileRefView): String =
    if (ref.live) "Served to this client's people at " else "Served in the sandbox, and to the client's people once published, at "

/**
 * `state.fileRefs` as ref id to name (issue #1186): the refs the caller's client is served, each by the name the
 * static root serves it under, `<refId>:<hash>`. An entry that is not that -- an id that is no ref's, a name that is
 * not the id's with a hash -- is left out rather than drawn from, so copy naming it draws its alt text.
 */
fun fileRefNamesFrom(raw: Any?): Map<String, String> = raw.toJsonMapOrEmpty().mapNotNull { (refId, name) ->
    val prefix = "$refId${FREF.nameSep}"
    (name as? String)?.takeIf { FREF.refIdProblem(refId) == null && it.startsWith(prefix) && FREF.isHash(it.removePrefix(prefix)) }
        ?.let { refId to it }
}.toMap()

/**
 * Where copy naming the image `ref:<id>` is drawn from (issue #1186), given the [names] refs are served by and how
 * a name becomes an address: null for a ref not among them, which the renderer draws as its alt text.
 */
fun refAddressFrom(names: Map<String, String>, addressOf: (String) -> String): (String) -> String? =
    { refId -> names[refId]?.let(addressOf) }

/**
 * The Markdown that shows [ref] as an image in copy (issue #1186): `![<alt>](ref:<id>)`, its alt text the ref's
 * own words ([fileRefAltText]) -- what a reader is shown where the image cannot be drawn, a mail included. A
 * bracket would end the alt text early and a line break the image, so brackets are taken out and lines joined.
 */
fun fileRefImageMarkdown(ref: FileRefView): String {
    val alt = fileRefAltText(ref).replace(Regex("[\\[\\]]"), "").replace(Regex("\\s+"), " ").trim()
    return "![$alt](${MDR.refScheme}:${ref.refId})"
}

/**
 * [value] with [insert] put at [caret] -- the text cursor, as last seen in the box -- and where the cursor then is,
 * just after it. With no cursor known, or one past the end, it goes at the end, on a line of its own when the value
 * does not already end in one.
 */
fun insertAtCaret(value: String, caret: Int?, insert: String): Pair<String, Int> {
    if (caret == null || caret < 0 || caret > value.length) {
        val joined = if (value.isEmpty() || value.endsWith("\n")) value + insert else value + "\n" + insert
        return joined to joined.length
    }
    return (value.substring(0, caret) + insert + value.substring(caret)) to caret + insert.length
}

/** Whether a copy [value] may name a file ref as an image (issue #1186): what the copy editor reads the refs for. */
fun copyNamesRef(value: String?): Boolean = value?.contains("${MDR.refScheme}:") == true

/**
 * The refs the copy editor offers to insert, as choices: each by id, beside its own words, and a ref with no file
 * there said so -- it may still be named, and is drawn as its alt text until its file is.
 */
fun fileRefInsertChoices(refs: List<FileRefView>): List<Pair<String, String>> = refs.map { ref ->
    val words = listOfNotNull(ref.refId, ref.description, "file missing".takeIf { ref.missing })
    words.joinToString(" \u2014 ") to ref.refId
}

/**
 * The inputs of the address a ref is previewed from: the **named** download, since the client on the page need not
 * be the caller's own and a draft is served to nobody outside the sandbox -- with the file's hash, so that a
 * replaced file is a new address. Null when there is no file to show.
 */
fun fileRefPreviewArgs(clientId: String, ref: FileRefView): Map<String, Any?>? {
    val hash = ref.hash?.takeIf { !ref.missing } ?: return null
    // A ref a draft removed is not in what the sandbox shows: its file is the one the client's people are served.
    return if (ref.removedInDraft) {
        mapOf(FREF.client to clientId, CCT.refId to ref.refId, CCT.hash to hash, FREF.served to true)
    } else {
        mapOf(FREF.client to clientId, CCT.refId to ref.refId, CCT.hash to hash)
    }
}

/**
 * Where an image naming one of [clientId]'s refs is drawn from on a page **about** that client (issue #1186): its
 * preview, by the named download [urlOf] makes of [fileRefPreviewArgs] -- the static root answers the caller's own
 * client, which the client on the page need not be. Null for a ref [refs] does not list or whose file is not there.
 */
fun refPreviewAddressFrom(clientId: String, refs: List<FileRefView>, urlOf: (Map<String, Any?>) -> String): (String) -> String? =
    { refId -> refs.firstOrNull { it.refId == refId }?.let { fileRefPreviewArgs(clientId, it) }?.let(urlOf) }

/**
 * The question asked before a ref of [clientId]'s is removed, naming where that acts as every delete's does
 * (issue #1049) -- or, where it is a draft that is changed ([draft]), that it is.
 */
fun fileRefRemoveQuestion(ref: FileRefView, clientId: String, draft: Boolean): String =
    if (draft) "Remove the file '${ref.refId}' from the draft of ${sandboxParentOf(clientId) ?: clientId}?"
    else "Remove the file '${ref.refId}' of ${deleteTargetText(clientId)}?"

/**
 * What removing [ref] does, under [fileRefRemoveQuestion]. Under an upload there may be an image the client was
 * shipped with, which the listing cannot tell and which then takes its place -- so each says so. A draft's removal
 * leaves the client's people what they have, which for a ref they are not served yet ([FileRefView.live] false)
 * is nothing, and is not said.
 */
fun fileRefRemoveConsequence(ref: FileRefView, draft: Boolean): String {
    val shipped = "the image the client was shipped with under this id, where there is one"
    return if (draft) {
        "The sandbox stops showing it, or shows $shipped." +
            if (ref.live) " The client's people are served it until the configuration is published." else ""
    } else {
        "The client's people stop being served it at once, or are served $shipped."
    }
}

/** What a section says once an upload answers. */
fun fileRefUploadedNote(change: FileRefChange): String = savedNote("Uploaded '${change.refId}', in ${change.configName}.", change.mode)

/** What a section says once a file the deployment keeps has been named. */
fun fileRefNamedNote(change: FileRefChange): String =
    savedNote("'${change.refId}' now names ${change.ref?.path ?: "that file"}, in ${change.configName}.", change.mode)

/** What a section says once a ref's description has been reworded, or cleared. */
fun fileRefDescribedNote(change: FileRefChange): String =
    savedNote(if (change.ref?.description == null) "Cleared the description of '${change.refId}'." else "Reworded '${change.refId}'.", change.mode)

/**
 * What a section says once a removal answers. A ref still there afterwards that no stored configuration holds is
 * the one the client was shipped with, which the removed upload stood in front of; one a stored configuration
 * holds is another entry of the same id, which is said as that. A removal made as a draft is worded as one: the
 * note every other draft gets says the sandbox "shows it", and here there is nothing left to show.
 */
fun fileRefRemovedNote(change: FileRefChange): String {
    val left = change.ref
    // From a draft, a ref the client's people are served stays listed, marked, until the configuration is published.
    if (left?.removedInDraft == true) {
        return "Removed '${change.refId}' from the draft. The client's people are served it until the configuration is published; until then it can be put back."
    }
    val done = when {
        left == null -> "Removed '${change.refId}'."
        left.stored -> "Removed one entry of '${change.refId}'; another configuration, ${left.configName ?: "stored"}, still holds one."
        else -> "Removed the upload of '${change.refId}': the image the client was shipped with is shown again."
    }
    return if (change.mode == EDM.draft) "$done A draft: the sandbox shows the change, and the client's people keep what they have until it is published." else done
}

/** What a section says once a ref is put back (issue #1188): in the draft, as it is published. */
fun fileRefRestoredNote(change: FileRefChange): String =
    savedNote("Put '${change.refId}' back, in ${change.configName}, as the client's people are served it.", change.mode)

/**
 * [refs] as a change left them: the changed ref in its place, a new one at the end, a removed one gone -- drawn
 * from the answer, before the listing is read again.
 */
fun withFileRefChange(refs: List<FileRefView>?, change: FileRefChange): List<FileRefView>? {
    refs ?: return null
    val changed = change.ref ?: return refs.filter { it.refId != change.refId }
    return if (refs.any { it.refId == changed.refId }) refs.map { if (it.refId == changed.refId) changed else it } else refs + changed
}

/** What is under way on a ref, as [webAssetBusyKey] names it -- and [adding], which stands where a ref's id would for the form that adds one. */
@Suppress("ConstPropertyName")
object FACT {
    const val upload = "upload"
    const val local = "local"
    const val describe = "describe"
    const val remove = "remove"
    const val restore = "restore"

    /**
     * What stands in for a ref's id while one is being added. No ref can have it for an id -- an id holds no
     * space -- so a failure of the form is never drawn beside a ref; a test holds it to that.
     */
    const val adding = "new file"
}
