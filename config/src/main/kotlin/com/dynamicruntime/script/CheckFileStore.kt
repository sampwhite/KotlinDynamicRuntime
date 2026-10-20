package com.dynamicruntime.script

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileStoreCheck
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.kdn.Startup
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Finds what writes and deletes that stopped part-way left in the file store, and repairs it when asked (issue
 * #1147):
 *
 * ```sh
 * kdr-run com.dynamicruntime.script.CheckFileStoreKt [bucket...] [--repair] [--min-age-minutes N]
 * ```
 *
 * Every bucket a component declares, or only those named. **It reports by default and changes nothing**; with
 * `--repair` it removes what is no file and rewrites the metadata of content its metadata does not match
 * ([FileStoreCheck]), leaving alone anything that changed in the last `--min-age-minutes` (an hour unless said),
 * since a write under way looks exactly like a write that stopped. Checking reads every file in every bucket it
 * is asked about, so it is run when wanted rather than at every boot.
 *
 * It boots the runtime ([Startup.mkBootCxt]) only to the `collected` stage, with the file store's service alone (issue
 * #1289), and starts no HTTP server. So it reaches the store the deployment's own configuration names
 * (`KDR_FILE_STORE_*`, `KDR_S3_*`) and never the database: it runs with the server running or not, beside a server
 * holding the database's lock, and with the database unreachable. It refuses the `memory` store, which holds nothing
 * beyond the run that made it.
 *
 * Exit codes: 0 when nothing was found or everything found was repaired, 2 when something found is still there,
 * 1 for a usage mistake or a store that cannot be checked.
 */
@Suppress("ConstPropertyName")
object CheckFileStore {
    /** Flags the script accepts. */
    @Suppress("ConstPropertyName")
    object CFSF {
        const val repair = "--repair"
        const val minAgeMinutes = "--min-age-minutes"
    }

    const val failureExit = 1
    const val foundExit = 2

    /** How long, by default, something must have been left unchanged before a repair touches it. */
    const val defaultMinAgeMinutes = 60L

    /** What a run is asked to do, once the arguments have been read. */
    data class Options(val buckets: List<String>, val repair: Boolean, val minAgeMinutes: Long)

    /** Reads the arguments into [Options], or null when they do not parse. */
    fun parseArgs(args: List<String>): Options? {
        val buckets = mutableListOf<String>()
        var repair = false
        var minAge = defaultMinAgeMinutes
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == CFSF.repair -> repair = true
                arg == CFSF.minAgeMinutes -> {
                    minAge = args.getOrNull(i + 1)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
                    i++
                }
                arg.startsWith("-") -> return null
                else -> buckets.add(arg)
            }
            i++
        }
        return Options(buckets, repair, minAge)
    }

    fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: return usage()
        val cxt = bootScriptCxt("checkFileStore", BootStage.collected, setOf(FileStoreService.serviceName))
        return runOn(cxt, options) { println(it) }
    }

    /** The run itself, on a booted [cxt]: each line of what it says goes to [out]. */
    fun runOn(cxt: KdrCxt, options: Options, out: (String) -> Unit): Int {
        val service = FileStoreService.get(cxt)
        if (service.providerType == FST.memory) {
            return fail(
                "The file store is '${FST.memory}', which holds nothing beyond the run that made it: there is nothing " +
                    "to check. Name the deployment's store (KDR_FILE_STORE_TYPE and its variables).",
            )
        }
        val declared = service.declaredBuckets().map { it.name }.sorted()
        val unknown = options.buckets - declared.toSet()
        if (unknown.isNotEmpty()) {
            return fail("No declared bucket ${unknown.joinToString { "'$it'" }}; declared: ${declared.joinToString().ifEmpty { "none" }}.")
        }
        val buckets = options.buckets.ifEmpty { declared }
        out("Checking the ${service.providerType} file store (${service.store.location}): ${buckets.joinToString().ifEmpty { "no buckets" }}.")
        val found = FileStoreCheck.find(cxt, service.store, buckets)
        if (found.isEmpty()) {
            out("Nothing left behind.")
            return 0
        }
        for (leftover in found) {
            out(
                "${leftover.bucket}: ${leftover.describe()} Last changed ${leftover.modifiedAt.formatDate()}; a repair " +
                    "would ${leftover.repairText()}.",
            )
        }
        if (!options.repair) {
            out(
                "${found.size} found; nothing was changed. ${CFSF.repair} repairs them, leaving alone anything that " +
                    "changed in the last ${options.minAgeMinutes} minute(s).",
            )
            return foundExit
        }
        // The wall clock's now, as every object's time is: not the instance's, which a test may have moved.
        val repairs = FileStoreCheck.repair(cxt, service.store, found, Clock.System.now(), options.minAgeMinutes.minutes)
        for (r in repairs) out("${r.leftover.bucket}: '${r.leftover.name}' ${r.note}.")
        val left = repairs.count { !it.done }
        out("${repairs.size - left} repaired, $left left alone.")
        return if (left == 0) 0 else foundExit
    }

    private fun fail(message: String): Int {
        System.err.println(message)
        return failureExit
    }

    private fun usage(): Int = fail(
        "Usage: kdr-run com.dynamicruntime.script.CheckFileStoreKt [bucket...] [${CFSF.repair}] [${CFSF.minAgeMinutes} N]\n" +
            "  bucket              a declared bucket to check (default: every one)\n" +
            "  ${CFSF.repair}            repair what is found; without it nothing is changed\n" +
            "  ${CFSF.minAgeMinutes} N leave alone what changed in the last N minutes (default $defaultMinAgeMinutes)",
    )
}

fun main(args: Array<String>) {
    exitProcess(CheckFileStore.run(args.toList()))
}
