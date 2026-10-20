package com.dynamicruntime.script

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileStoreClear
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.webasset.ClientWebAssetService
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * Empties the deployment's file store (issue #1244): every declared bucket, as clearing a reference deployment's data
 * needs, while the buckets themselves stay -- the application never creates one, and its boot check refuses to start
 * without it.
 *
 * ```sh
 * kdr-run com.dynamicruntime.script.ClearFileStoreKt [--remove]
 * ```
 *
 * **It reports by default and changes nothing**: per bucket, how many files would go and how many stay. `--remove`
 * removes them. `kdr-node reset-data` runs it on the node, with the service stopped, after asking.
 *
 * **What stays: the web assets of clients defined in source** -- the sample's Acme, say. A client's favicon and brand
 * mark live only in the file store (`clients/<client>/web/`), in no database row, and a reset recreates a source
 * client with the same id, so keeping them brings its images back unasked. A client made at runtime is not recreated,
 * so its images go with everything else: kept, they would turn up for whoever next made a client of that id.
 *
 * It boots only to the `collected` stage, with the file store's service alone (issue #1289): the source clients are
 * known from the components' contributions, and nothing reaches the database, which a reset is about to remove.
 */
@Suppress("ConstPropertyName")
object ClearFileStore {
    /** Flags the script accepts. */
    @Suppress("ConstPropertyName")
    object CLFF {
        const val remove = "--remove"
    }

    const val failureExit = 1

    fun run(args: List<String>): Int {
        val unknown = args.filter { it != CLFF.remove }
        if (unknown.isNotEmpty()) return fail("Unknown argument(s): ${unknown.joinToString(" ")}. Usage: [${CLFF.remove}]")
        val cxt = bootScriptCxt("clearFileStore", BootStage.collected, setOf(FileStoreService.serviceName))
        return runOn(cxt, remove = CLFF.remove in args) { println(it) }
    }

    /** The run itself, on a booted [cxt]: each line of what it says goes to [out]. */
    fun runOn(cxt: KdrCxt, remove: Boolean, out: (String) -> Unit): Int {
        val service = FileStoreService.get(cxt)
        if (service.providerType == FST.memory) {
            return fail("The file store is '${FST.memory}', which holds nothing beyond the run that made it: there is nothing to clear.")
        }
        val kept = sourceClients(cxt).map { ClientWebAssetService.folderOf(it) }
        val buckets = service.declaredBuckets().map { it.name }.sorted()
        out("The ${service.providerType} file store (${service.store.location}): ${buckets.joinToString().ifEmpty { "no buckets" }}.")
        out("Kept: the web assets of the clients defined in source (${kept.size}).")
        var removed = 0
        for (bucket in buckets) {
            val plan = FileStoreClear.plan(cxt, service.store, bucket) { key -> kept.any { key.startsWith(it) } }
            if (remove) {
                removed += FileStoreClear.clear(cxt, service.store, plan, Clock.System.now())
                out("$bucket: removed ${plan.remove.size} file(s), kept ${plan.keep.size}.")
            } else {
                out("$bucket: would remove ${plan.remove.size} file(s), keep ${plan.keep.size}.")
            }
        }
        out(if (remove) "Removed $removed file(s)." else "Nothing was changed; ${CLFF.remove} removes them.")
        return 0
    }

    /** The clients the components define in source, which a reset recreates with the same ids. */
    fun sourceClients(cxt: KdrCxt): List<String> =
        SchemaCollector.get(cxt)?.gedraConfigs?.configs.orEmpty()
            .filter { it.origin == GedraConfigOrigin.source }
            .mapNotNull { it.client?.clientId }
            .distinct().sorted()

    private fun fail(message: String): Int {
        System.err.println(message)
        return failureExit
    }
}

fun main(args: Array<String>) {
    exitProcess(ClearFileStore.run(args.toList()))
}
