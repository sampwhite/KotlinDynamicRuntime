package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filekind.FKFAM
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useState
import web.cssom.ClassName

/**
 * The Files page (issue #1863): the caller's data files -- their own, or their client's for an editor or an
 * administrator -- with how many forms use each, the forms themselves on request, and Remove for a file no form uses.
 * The rules are pure, in `FilesListing.kt`.
 *
 * Theme: #DFILE.
 */

private val filesScope = MainScope()

/** The Files page's requests. */
object FilesApi {
    suspend fun list(filter: FilesFilter, limit: Int): FilesPageData =
        filesPageFrom(Http.getApi(DFILE.listPath + queryString(filesListArgs(filter, limit))))

    suspend fun usedBy(fileId: String): FileUses =
        fileUsesFrom(Http.getApi(DFILE.usedByPath + queryString(mapOf(DFILE.fileId to fileId)))[EP.item].toJsonMapOrEmpty())

    suspend fun remove(fileId: String) {
        Http.deleteApi(DFILE.deletePath, mapOf(DFILE.fileId to fileId))
    }
}

val FilesPage = FC<Props> {
    val generation = useRefreshGeneration()
    val facts = useShellFacts()
    val now = useRelativeClock()
    var filter by useState(FilesFilter())
    var shown by useState(listingBatch)
    var page by useState<FilesPageData?>(null)
    var loadError by useState<DisplayError?>(null)
    var growing by useState(false)
    var reread by useState(0)
    // The row whose forms, or whose preview, is open: one at a time, as a row's confirmation is.
    var openUses by useState<String?>(null)
    var openPreview by useState<String?>(null)
    val rows = useRowActions()

    useEffect(generation, filter, shown, reread) {
        filesScope.launch {
            try {
                page = FilesApi.list(filter, shown)
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            } finally {
                growing = false
            }
        }
    }

    /** A change of filter starts over at the first batch, as every growing listing does. */
    fun refilter(next: FilesFilter) {
        filter = next
        shown = listingBatch
        rows.clear()
    }

    div {
        className = ClassName("card wide")
        h1 { +(if (facts.seesClientForms == true) "Files" else "My files") }
        p {
            className = ClassName("subtitle")
            +(
                (if (facts.seesClientForms == true) "Every file uploaded for your client's forms" else "The files you uploaded for forms") +
                    ", and the forms that use each. A file no form uses can be removed here; one a form uses is taken out " +
                    "of the form first."
            )
        }
        div {
            className = ClassName("files-filters")
            choiceRow("Kind") {
                Select {
                    value = filter.kind ?: filesAny
                    options = optionsToJs(fileKindChoices())
                    style = choiceStyle()
                    onChange = { v -> refilter(FilesFilter(filterValue(v as? String), filter.status, filter.used)) }
                }
            }
            choiceRow("Status") {
                Select {
                    value = filter.status ?: filesAny
                    options = optionsToJs(fileStatusChoices())
                    style = choiceStyle()
                    onChange = { v -> refilter(FilesFilter(filter.kind, filterValue(v as? String), filter.used)) }
                }
            }
            choiceRow("Used") {
                Select {
                    value = filter.used?.toString() ?: filesAny
                    options = optionsToJs(fileUseChoices())
                    style = choiceStyle()
                    onChange = { v ->
                        refilter(FilesFilter(filter.kind, filter.status, filterValue(v as? String)?.toBooleanStrictOrNull()))
                    }
                }
            }
        }
        loadError?.let { errorText("Couldn't read the files.", it) }
        val loaded = page
        when {
            loaded == null && loadError == null -> p { className = ClassName("subtitle"); +"Loading…" }
            loaded != null && loaded.rows.isEmpty() -> p { className = ClassName("subtitle"); +"No files." }
            loaded != null -> {
                val showOwner = filesShowOwner(loaded.rows, facts.userId)
                table {
                    className = ClassName("op-table files-table")
                    thead {
                        tr {
                            th { +"File" }
                            th { +"Status" }
                            th { +"Uploaded" }
                            if (showOwner) th { +"Uploaded by" }
                            th { +"Used by" }
                            th { +"" }
                        }
                    }
                    tbody {
                        for (row in loaded.rows) {
                            fileRow(row, showOwner, now, rows, openUses == row.fileId, openPreview == row.fileId,
                                toggleUses = { openUses = if (openUses == row.fileId) null else row.fileId },
                                togglePreview = { openPreview = if (openPreview == row.fileId) null else row.fileId },
                                remove = {
                                    rows.start(row.fileId, removeAction)
                                    filesScope.launch {
                                        try {
                                            FilesApi.remove(row.fileId)
                                            rows.finish()
                                            reread += 1
                                        } catch (e: Throwable) {
                                            rows.finish()
                                            rows.fail(row.fileId, e) { reread += 1 }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                showMoreBar(loaded.rows.size, shown, loaded.available, "file", "files", growing) { n ->
                    growing = true
                    shown = n
                }
            }
        }
    }
}

private const val removeAction = "remove"

/** One file's row of the table, and under it, when open, the forms that use it or the file itself. */
private fun ChildrenBuilder.fileRow(
    row: FileRow,
    showOwner: Boolean,
    now: kotlin.time.Instant,
    rows: RowActions,
    usesOpen: Boolean,
    previewOpen: Boolean,
    toggleUses: () -> Unit,
    togglePreview: () -> Unit,
    remove: () -> Unit,
) {
    val view = row.view
    val columns = if (showOwner) 6 else 5
    tr {
        key = row.fileId.unsafeCast<Key>()
        td {
            if (view.readable) {
                a {
                    href = DataFileApi.contentUrl(view.fileId)
                    asDynamic()["target"] = "_blank"
                    asDynamic()["rel"] = "noopener"
                    +view.title
                }
            } else {
                span { +view.title }
            }
            val facts = listOfNotNull(view.kind?.label, view.size?.let { byteSizeText(it) })
            if (facts.isNotEmpty()) div { className = ClassName("subtitle"); +facts.joinToString(" · ") }
        }
        td { +fileStatusText(view.status) }
        td { row.uploadedAt?.let { RelativeTime { iso = it; this.now = now } } }
        if (showOwner) td { +fileOwnerText(row) }
        td {
            if (row.forms == 0) {
                +fileUsedByText(0)
            } else {
                Button {
                    type = "link"
                    size = "small"
                    onClick = { toggleUses() }
                    +(if (usesOpen) "Hide" else fileUsedByText(row.forms))
                }
            }
        }
        td {
            div {
                className = ClassName("files-actions")
                if (view.readable && (view.kind?.family == FKFAM.image || view.kind?.family == FKFAM.video)) {
                    Button {
                        type = "link"
                        size = "small"
                        onClick = { togglePreview() }
                        +(if (previewOpen) "Hide preview" else "Preview")
                    }
                }
                if (fileRemovable(row)) {
                    confirmedRemove(
                        rows, row.fileId, removeAction,
                        question = if (row.uploading) "Abort this upload?" else "Remove ${view.title}?",
                        consequence = "From ${deleteTargetText(formClientOf(row.fileId) ?: "")}. This cannot be undone.",
                        onConfirm = remove,
                    )
                } else {
                    fileKeptText(row)?.let { span { className = ClassName("type-hint"); +it } }
                }
            }
            rowFailure(rows.status, row.fileId, "Couldn't remove it.")
        }
    }
    if (usesOpen) {
        tr {
            key = "${row.fileId}:uses".unsafeCast<Key>()
            td {
                asDynamic()["colSpan"] = columns
                FileUsesList { fileId = row.fileId }
            }
        }
    }
    if (previewOpen) {
        tr {
            key = "${row.fileId}:preview".unsafeCast<Key>()
            td {
                asDynamic()["colSpan"] = columns
                DataFileValue { fileId = row.fileId }
            }
        }
    }
}

private external interface FileUsesListProps : Props {
    var fileId: String
}

/** The forms that use a file, each a link to the form, read when the row is opened. */
private val FileUsesList = FC<FileUsesListProps> { props ->
    var uses by useState<FileUses?>(null)
    var failure by useState<DisplayError?>(null)
    useEffect(props.fileId) {
        filesScope.launch {
            try {
                uses = FilesApi.usedBy(props.fileId)
            } catch (e: Throwable) {
                failure = userFacingError(e)
            }
        }
    }
    failure?.let { errorText("Couldn't read the forms that use it.", it) }
    val loaded = uses
    when {
        loaded == null && failure == null -> span { className = ClassName("type-hint"); +"Reading the forms…" }
        loaded != null -> {
            ul {
                className = ClassName("files-uses")
                for ((at, use) in loaded.uses.withIndex()) {
                    li {
                        key = "$at:${use.formId}".unsafeCast<Key>()
                        a {
                            href = routeHref(listOf(HP.page to HMENU.pageForms, HP.gedra to use.formId))
                            +use.formId
                        }
                        span { className = ClassName("type-hint"); +" · the field ${use.fieldPath} of ${use.traitId}" }
                    }
                }
            }
            fileHiddenUsesText(loaded.hidden)?.let { p { className = ClassName("type-hint"); +it } }
        }
    }
}
