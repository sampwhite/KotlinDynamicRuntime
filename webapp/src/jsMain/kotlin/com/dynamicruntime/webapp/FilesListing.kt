package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * The Files page (issue #1863), the pure half: a listing's rows read into views, the filters as the listing's input, and
 * what a row offers. Covered under `jsNodeTest`; the components are in `FilesPage.kt`.
 *
 * Theme: #DFILE.
 */

/**
 * One file of the listing: its record ([view]), who uploaded it ([ownerId], [ownerName]), when its upload began
 * ([uploadedAt]), and how many live forms link it ([forms]), whether or not the caller may read them.
 */
class FileRow(val view: DataFileView, val ownerId: Long?, val ownerName: String?, val uploadedAt: String?, val forms: Int) {
    val fileId: String get() = view.fileId
    val uploading: Boolean get() = view.status == DFSTAT.uploading
}

/** A listing's page: its [rows], how many there are in all ([available]), and whether more can be shown. */
class FilesPageData(val rows: List<FileRow>, val available: Int)

/** The listing's envelope (`/files/list`) read into its rows. */
fun filesPageFrom(envelope: Map<String, Any?>): FilesPageData {
    val rows = envelope[EP.items].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }.mapNotNull { item ->
        val id = item[DFILE.fileId].toOptStr() ?: return@mapNotNull null
        FileRow(
            view = dataFileViewFrom(id, item),
            ownerId = item[DFILE.ownerId].toOptLong(),
            ownerName = item[DFILE.ownerName].toOptStr(),
            uploadedAt = item[DFILE.uploadedAt].toOptStr(),
            forms = item[DFILE.forms].toOptLong()?.toInt() ?: 0,
        )
    }
    return FilesPageData(rows, envelope[EP.numAvailable].toOptLong()?.toInt() ?: rows.size)
}

/** The listing's filters: a [kind], a [status], and whether a form uses the file ([used]); null is any. */
class FilesFilter(val kind: String? = null, val status: String? = null, val used: Boolean? = null)

/** [filter] and the rows to show, [limit], as the listing's input. */
fun filesListArgs(filter: FilesFilter, limit: Int): Map<String, Any?> = buildMap {
    put(EP.limit, limit)
    filter.kind?.let { put(DFILE.kind, it) }
    filter.status?.let { put(DFILE.status, it) }
    filter.used?.let { put(DFILE.linked, it) }
}

/** What the "any" choice of a filter is called, as its value: a Select's value cannot be null. */
const val filesAny = "any"

/** The kinds a file may be, as the kind filter offers them. */
fun fileKindChoices(): List<SchOption> = listOf(SchOption(filesAny, "Any kind")) + FileKinds.kinds.map { SchOption(it.id, it.label) }

/** The statuses the status filter offers: only the two reached today. */
fun fileStatusChoices(): List<SchOption> =
    listOf(SchOption(filesAny, "Any status"), SchOption(DFSTAT.ready, "Ready"), SchOption(DFSTAT.uploading, "Uploading"))

/** Whether a form uses the file, as the use filter offers it. */
fun fileUseChoices(): List<SchOption> =
    listOf(SchOption(filesAny, "Used or not"), SchOption("true", "Used by a form"), SchOption("false", "Used by no form"))

/** A filter's chosen [value] as the listing takes it: null for [filesAny]. */
fun filterValue(value: String?): String? = value?.takeIf { it != filesAny && it.isNotEmpty() }

/** A file's status in words. */
fun fileStatusText(status: String?): String = when (status) {
    DFSTAT.ready -> "Ready"
    DFSTAT.uploading -> "Uploading"
    DFSTAT.pending -> "Converting"
    DFSTAT.failed -> "Not converted"
    else -> status ?: "—"
}

/** How many forms use a file, in words: `No form`, `1 form`, `3 forms`. */
fun fileUsedByText(forms: Int): String = when (forms) {
    0 -> "No form"
    1 -> "1 form"
    else -> "$forms forms"
}

/**
 * Whether row [row] offers Remove: only a file no live form uses, since the server refuses the others -- an upload under
 * way among them, whose removal aborts it. A linked file says instead why it stays ([fileKeptText]).
 */
fun fileRemovable(row: FileRow): Boolean = row.forms == 0

/** Why a file a form uses has no Remove: the forms keep it. */
fun fileKeptText(row: FileRow): String? = when {
    row.forms == 0 -> null
    row.forms == 1 -> "A form uses it; remove it from that form to delete it."
    else -> "${row.forms} forms use it; remove it from them to delete it."
}

/**
 * Whether the listing shows who uploaded each file: when any file is not [me]'s -- an editor's or an administrator's
 * listing of the client -- since a listing of one's own files would show one name down the whole column.
 */
fun filesShowOwner(rows: List<FileRow>, me: Long?): Boolean = rows.any { it.ownerId != me }

/** Who uploaded [row]'s file, in a word: their name, else their user id. */
fun fileOwnerText(row: FileRow): String = row.ownerName ?: row.ownerId?.let { "User $it" } ?: "—"

/** One use of a file, as `/files/usedBy` answers it: the form, and the field of which of its traits names the file. */
class FileUse(val formId: String, val traitId: String, val fieldPath: String)

/** The uses `/files/usedBy` answers, the caller's to read, and how many forms they may not read use the file too. */
class FileUses(val uses: List<FileUse>, val hidden: Int)

/** `/files/usedBy`'s answer read into its uses. */
fun fileUsesFrom(item: Map<String, Any?>): FileUses = FileUses(
    uses = item[DFILE.usedBy].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }.mapNotNull { use ->
        val form = use[DFILE.formId].toOptStr() ?: return@mapNotNull null
        FileUse(form, use[DFILE.traitId].toOptStr().orEmpty(), use[DFILE.fieldPath].toOptStr().orEmpty())
    },
    hidden = item[DFILE.hiddenForms].toOptLong()?.toInt() ?: 0,
)

/** What the uses say of the forms the caller may not read, or null when there are none. */
fun fileHiddenUsesText(hidden: Int): String? = when (hidden) {
    0 -> null
    1 -> "And 1 form you may not open."
    else -> "And $hidden forms you may not open."
}
