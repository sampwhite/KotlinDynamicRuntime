package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobTaskResult
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The data-file sweep's names (issue #1839). Each name matches its value. */
@Suppress("ConstPropertyName")
object DFSW {
    /** The job type. */
    const val jobType = "dataFileSweep"

    /** Its profile: a deployment tunes it under `jobProfiles.dataFileSweep`. */
    const val profile = "dataFileSweep"

    // The kinds of task, each a task key's first part: `<kind>|<what>`.
    /** An upload past its time: its file's id. */
    const val upload = "upload"

    /** A file no form links: its id. */
    const val unlinked = "unlinked"

    /** An object of an owner's folder nothing names: the owner and its key. */
    const val objectKind = "object"

    /** A staged upload nothing holds: the owner, its key, and its token. */
    const val staged = "staged"

    /** What parts a task key. */
    const val sep = "|"

    /** How many hours a file no form links, and bytes nothing names, are kept before the sweep removes them. */
    const val defaultGraceHours = 168

    val graceEnvVar = EnvVarDef(
        "KDR_FILE_ORPHAN_GRACE_HOURS", group = ENVGRP.fileStore, defaultDoc = "$defaultGraceHours",
        description = "How many hours the nightly sweep of data files (issue #1839) keeps a file uploaded for a form " +
            "that no form links -- one replaced in its field, or uploaded and never saved -- and bytes in the store " +
            "that no file's record names, before it removes them. The default is a week, so a person who uploads a " +
            "file and saves its form days later still finds it. An upload past its own time is removed at once.",
    )
}

/** The hours [DFSW.graceEnvVar] says, or its default: a positive whole number of them. */
fun fileOrphanGraceHours(cxt: KdrCxt): Int =
    cxt.getEnvVar(DFSW.graceEnvVar)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DFSW.defaultGraceHours

/**
 * The nightly sweep of data files (issue #1839): removes what uploads leave behind, in every client, and on demand.
 *
 * - **Uploads past their time** ([DFSW.upload]): their parts, anything put together from them, their row, and their
 *   record, which held no bytes yet -- at once, since an upload past its time takes no more chunks.
 * - **Files no form links** ([DFSW.unlinked]): ready, uploaded more than [fileOrphanGraceHours] ago, and named by no link
 *   row -- one replaced in its field, or uploaded for a form never saved. Soft-deleted, as a removal is; a file only a
 *   deleted form links is kept, since restoring the form would want it.
 * - **Objects nothing names** ([DFSW.objectKind]): bytes under a client's `files/` that no live record, and no upload
 *   finishing, names, written more than the grace ago; and a file put together under `staging/` for an upload no longer
 *   under way, written more than an upload's hours ago. Per owner: a sandbox's bytes are in its parent's folder.
 * - **Staged uploads nothing holds** ([DFSW.staged]): S3's multipart uploads (or a store's own), begun more than an
 *   upload's hours ago, whose token no upload row has.
 *
 * **One task per thing removed**, keyed by its kind and what it names, and each **asks again** before it removes
 * anything, since a run's list is made before its tasks run: a file a form linked since, an upload that finished, bytes
 * a new upload of the same file now shares -- each is left. **A dry run** counts what it would remove, notes each in
 * the trace, and removes nothing.
 *
 * Theme: #DFILE.
 */
fun dataFileSweepJob(): JobDef = JobDef(
    jobType = DFSW.jobType,
    description = "Removes what uploads of data files leave behind: uploads past their time, files no form links, bytes " +
        "no file names, and staged uploads nothing holds.",
    // I/O against the store: each task mostly waits on it.
    profile = JobProfile(DFSW.profile, virtualThreads = 8),
    tasks = ::sweepTasks,
    countTasks = { run, client -> sweepTasks(run, client).size },
    runTask = ::sweepOne,
    schedule = JobSchedule.daily("04:00"),
    resourceOf = { it },
)

/** When an object nothing names, or a file no form links, became old enough to remove: [fileOrphanGraceHours] ago. */
private fun graceCutoff(cxt: KdrCxt): Instant = cxt.instanceNow() - fileOrphanGraceHours(cxt).hours

/** When a staged upload nothing holds became old enough to remove: an upload's hours ago, the longest one may run. */
private fun uploadCutoff(cxt: KdrCxt): Instant = cxt.instanceNow() - DFILE.uploadHours.hours

/** The sweep's tasks for [client]: none on a node with no file store, which keeps no files to sweep. */
private fun sweepTasks(run: JobRunCxt, client: String): List<String> {
    val cxt = run.cxt
    if (FileStoreService.getOrNull(cxt) == null) return emptyList()
    val keys = mutableListOf<String>()
    DataFileService.expiredUploads(cxt, client).forEach { keys.add(key(DFSW.upload, it)) }
    DataFileService.unlinkedFiles(cxt, client, graceCutoff(cxt)).forEach { keys.add(key(DFSW.unlinked, it)) }
    // A folder is its owner's: a sandbox's bytes are in its parent's, swept with the parent.
    if (!isSandboxClient(client)) {
        DataFileService.unnamedObjects(cxt, client, graceCutoff(cxt), uploadCutoff(cxt)).forEach { keys.add(key(DFSW.objectKind, client, it)) }
        DataFileService.strayStaged(cxt, client, uploadCutoff(cxt)).forEach { (k, token) -> keys.add(key(DFSW.staged, client, k, token)) }
    }
    return keys
}

private fun key(vararg parts: String): String = parts.joinToString(DFSW.sep)

/** Runs the task [taskKey]: removes what it names when that still holds, or with a dry run says it would. */
private fun sweepOne(run: JobRunCxt, taskKey: String): JobTaskResult {
    val cxt = run.cxt
    val parts = taskKey.split(DFSW.sep)
    val dry = run.dryRun
    val removed = when (parts.first()) {
        DFSW.upload -> DataFileService.sweepUpload(cxt, parts[1], dry)
        DFSW.unlinked -> DataFileService.sweepUnlinked(cxt, parts[1], graceCutoff(cxt), dry)
        DFSW.objectKind -> DataFileService.sweepObject(cxt, parts[1], parts[2], graceCutoff(cxt), uploadCutoff(cxt), dry)
        DFSW.staged -> DataFileService.sweepStaged(cxt, parts[1], parts[2], parts[3], uploadCutoff(cxt), dry)
        else -> throw KdrException("The data-file sweep has no task of the kind '${parts.first()}' ('$taskKey').")
    }
    if (!removed) return JobTaskResult.nothingToDo
    if (dry) run.trace("A real run would remove it.", taskKey)
    return JobTaskResult.done
}
