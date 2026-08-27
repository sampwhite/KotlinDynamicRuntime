package com.dynamicruntime.common.operator

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.BootCheckResult
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.formatDate
import java.lang.management.ManagementFactory

/**
 * Keys of the `/operator/system/info` report. Self-named per the code guide, even though the report is
 * deliberately schema-less -- these are still model values, and a diagnostic map is exactly the kind of thing
 * that grows typos when its keys are literals.
 */
@Suppress("ConstPropertyName")
object OSI {
    // Top-level sections.
    const val node = "node"
    const val gc = "gc"
    const val runtime = "runtime"
    const val memory = "memory"
    const val threads = "threads"
    const val classes = "classes"
    const val os = "os"
    const val gcCollectors = "gcCollectors"
    const val memoryPools = "memoryPools"

    // node
    const val nodeId = "nodeId"
    const val nodeStartTime = "nodeStartTime"
    const val currentTime = "currentTime"
    const val uptimeMs = "uptimeMs"
    const val uptimeText = "uptimeText"

    // gc (`collect` is the request field that asks for one)
    const val collect = "collect"
    const val requested = "requested"
    const val heapUsedBefore = "heapUsedBefore"
    const val heapUsedAfter = "heapUsedAfter"
    const val freed = "freed"
    const val durationMs = "durationMs"

    // runtime / os
    const val vmName = "vmName"
    const val vmVendor = "vmVendor"
    const val vmVersion = "vmVersion"
    const val specVersion = "specVersion"
    const val pid = "pid"
    const val jvmStartTimeMs = "jvmStartTimeMs"
    const val jvmUptimeMs = "jvmUptimeMs"
    const val inputArguments = "inputArguments"
    const val name = "name"
    const val arch = "arch"
    const val version = "version"
    const val availableProcessors = "availableProcessors"
    const val systemLoadAverage = "systemLoadAverage"

    // memory
    const val heapUsed = "heapUsed"
    const val heapCommitted = "heapCommitted"
    const val heapMax = "heapMax"
    const val heapInit = "heapInit"
    const val nonHeapUsed = "nonHeapUsed"
    const val nonHeapCommitted = "nonHeapCommitted"
    const val nonHeapMax = "nonHeapMax"
    const val nonHeapInit = "nonHeapInit"
    const val runtimeTotal = "runtimeTotal"
    const val runtimeFree = "runtimeFree"
    const val runtimeMax = "runtimeMax"

    // threads / classes
    const val count = "count"
    const val daemonCount = "daemonCount"
    const val peakCount = "peakCount"
    const val totalStartedCount = "totalStartedCount"
    const val loaded = "loaded"
    const val totalLoaded = "totalLoaded"
    const val unloaded = "unloaded"

    // gcCollectors / memoryPools entries
    const val collectionCount = "collectionCount"
    const val collectionTimeMs = "collectionTimeMs"
    const val type = "type"
    const val used = "used"
    const val committed = "committed"
    const val max = "max"
}

/** Schema type name for the system-info output. Free-form by design -- see [operatorSchema]. */
private const val systemInfoType = "SystemInfo"

/**
 * The operator surface: endpoints for someone running the deployment rather than using it. The `operator`
 * section requires [com.dynamicruntime.common.http.request.ROLE.operator], so an administrator reaches it too
 * (the ladder ranks admin above operator) while an ordinary user does not.
 *
 * `/operator/system/info` reports this node's identity, uptime, and JVM statistics, and will **request a
 * garbage collection first if asked** ([OSI.collect], off by default).
 *
 * The collection is opt-in because a full GC is a real pause: an endpoint that triggers one on every call is
 * a denial-of-service lever pointed at the deployment by anyone who can reach it, including a monitor polling
 * it on a timer. Defaulting off means the ordinary read is cheap and the expensive thing is a deliberate act.
 * Gating the section on the operator role is the second half of that answer -- which is why this is not
 * alongside `health` (anonymous, and it only ever reads).
 *
 * The report is deliberately **schema-less**: [systemInfoType] declares no properties, and a property-less
 * object type parses with `additionalProperties = true` (`SchParser`), so any map validates -- including under
 * `validateResponseSchema`, which tests turn on. That is the intended trade for a diagnostic dump: the set of
 * VM statistics is the JVM's to define and changes between releases, so pinning it to a schema would mean
 * editing a type every time a bean gains a field, and the catalog would advertise a contract the endpoint
 * cannot really promise. Every *other* endpoint should declare its output type.
 */
fun operatorSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, SECT.operator) {
    type(systemInfoType) {
        // No properties on purpose: this is the free-form diagnostic map described above.
        type = SCT.kObject
    }
    generalEndpoint(
        "/operator/system/info",
        "Reports this node's identity, uptime and JVM statistics, optionally requesting a garbage collection first.",
        HttpMethod.GET,
        outputRef = systemInfoType,
        inputFields = {
            field(
                OSI.collect,
                "Whether to request a garbage collection before reading the statistics. Off by default: a " +
                    "collection is a real pause, so it is asked for deliberately rather than on every call.",
            ) {
                type = SCT.boolean
            }
        },
    ) { c, request -> systemInfo(c, collect = request[OSI.collect] == true) }

    BootCheckResult.defineInfoType(this)
    listEndpoint(
        "/operator/boot/checks",
        "Reports every boot check this node ran: the mode each resolved to, and what each found.",
        outputRef = BCHK.infoTypeName,
        // No `limit`: the checks are a fixed, small set registered at boot, and paging a report somebody
        // opened to find out whether anything is wrong would be a way to hide the answer on page two.
        noLimit = true,
    ) { c, _ ->
        BootCheckRegistry.get(c).results().map { it.toInfo() }
    }

    // The environment-variable reference (issue #371), assembled server-side as one Markdown document and
    // rendered by the frontend the way the README is. The genuinely-new half of #371: it shows the value each
    // variable resolved to *on this node*, which no static reference can. Operator-gated because it names
    // infrastructure detail (`KDR_DB_HOST`, `KDR_DB_USER`); it only ever enumerates environment variables and
    // never reaches into the secrets file real passwords live in.
    type(OENV.referenceType) {
        type = SCT.kObject
        property(OENV.markdown, "The assembled environment-variable reference, as Markdown.", required = true)
    }
    generalEndpoint(
        OENV.envReferencePath,
        "The environment variables this node declares, assembled as Markdown with each variable's resolved value here.",
        HttpMethod.GET,
        outputRef = OENV.referenceType,
    ) { c, _ -> mapOf(OENV.markdown to renderEnvVarReference(c)) }
}

/**
 * Gathers the VM's statistics, first requesting a collection when [collect] is set and reporting what it
 * appeared to reclaim.
 *
 * `System.gc()` is a *request*, not a command -- a JVM may ignore it entirely (and some collectors largely
 * do). So the report says [OSI.requested] rather than claiming a collection happened, and the before/after
 * heap readings are what actually shows whether anything came of it.
 *
 * When nothing was asked for, the `gc` block is just `requested: false`: reporting a `freed` of zero would
 * read as "a collection ran and reclaimed nothing", which is a different fact. The heap figures are in
 * [OSI.memory] either way. The collection happens before the statistics are read, so they describe the VM
 * *after* it.
 */
private fun systemInfo(cxt: KdrCxt, collect: Boolean): Map<String, Any?> {
    val node = NodeService.get(cxt)
    val memoryBean = ManagementFactory.getMemoryMXBean()

    val gcReport: Map<String, Any?> = if (collect) {
        val heapBefore = memoryBean.heapMemoryUsage.used
        val gcStart = System.nanoTime()
        System.gc()
        val gcMillis = (System.nanoTime() - gcStart) / 1_000_000.0
        val heapAfter = memoryBean.heapMemoryUsage.used
        linkedMapOf(
            OSI.requested to true,
            OSI.heapUsedBefore to heapBefore,
            OSI.heapUsedAfter to heapAfter,
            OSI.freed to heapBefore - heapAfter,
            OSI.durationMs to gcMillis,
        )
    } else {
        linkedMapOf(OSI.requested to false)
    }

    val now = cxt.now()
    val uptime = now - NodeService.vmStartTime
    val runtimeBean = ManagementFactory.getRuntimeMXBean()
    val threadBean = ManagementFactory.getThreadMXBean()
    val classBean = ManagementFactory.getClassLoadingMXBean()
    val osBean = ManagementFactory.getOperatingSystemMXBean()
    val jvmRuntime = Runtime.getRuntime()
    val heap = memoryBean.heapMemoryUsage
    val nonHeap = memoryBean.nonHeapMemoryUsage

    return linkedMapOf(
        OSI.node to linkedMapOf(
            OSI.nodeId to node.nodeId.label,
            OSI.nodeStartTime to NodeService.vmStartTime.formatDate(),
            OSI.currentTime to now.formatDate(),
            OSI.uptimeMs to uptime.inWholeMilliseconds,
            OSI.uptimeText to uptime.toString(),
        ),
        OSI.gc to gcReport,
        OSI.runtime to linkedMapOf(
            OSI.vmName to runtimeBean.vmName,
            OSI.vmVendor to runtimeBean.vmVendor,
            OSI.vmVersion to runtimeBean.vmVersion,
            OSI.specVersion to runtimeBean.specVersion,
            OSI.pid to runtimeBean.pid,
            OSI.jvmStartTimeMs to runtimeBean.startTime,
            OSI.jvmUptimeMs to runtimeBean.uptime,
            OSI.inputArguments to runtimeBean.inputArguments,
        ),
        OSI.memory to linkedMapOf(
            OSI.heapUsed to heap.used,
            OSI.heapCommitted to heap.committed,
            OSI.heapMax to heap.max,
            OSI.heapInit to heap.init,
            OSI.nonHeapUsed to nonHeap.used,
            OSI.nonHeapCommitted to nonHeap.committed,
            OSI.nonHeapMax to nonHeap.max,
            OSI.nonHeapInit to nonHeap.init,
            OSI.runtimeTotal to jvmRuntime.totalMemory(),
            OSI.runtimeFree to jvmRuntime.freeMemory(),
            OSI.runtimeMax to jvmRuntime.maxMemory(),
        ),
        OSI.threads to linkedMapOf(
            OSI.count to threadBean.threadCount,
            OSI.daemonCount to threadBean.daemonThreadCount,
            OSI.peakCount to threadBean.peakThreadCount,
            OSI.totalStartedCount to threadBean.totalStartedThreadCount,
        ),
        OSI.classes to linkedMapOf(
            OSI.loaded to classBean.loadedClassCount,
            OSI.totalLoaded to classBean.totalLoadedClassCount,
            OSI.unloaded to classBean.unloadedClassCount,
        ),
        OSI.os to linkedMapOf(
            OSI.name to osBean.name,
            OSI.arch to osBean.arch,
            OSI.version to osBean.version,
            OSI.availableProcessors to osBean.availableProcessors,
            OSI.systemLoadAverage to osBean.systemLoadAverage,
        ),
        OSI.gcCollectors to ManagementFactory.getGarbageCollectorMXBeans().map { collector ->
            linkedMapOf(
                OSI.name to collector.name,
                OSI.collectionCount to collector.collectionCount,
                OSI.collectionTimeMs to collector.collectionTime,
            )
        },
        OSI.memoryPools to ManagementFactory.getMemoryPoolMXBeans().map { pool ->
            linkedMapOf<String, Any?>(
                OSI.name to pool.name,
                OSI.type to pool.type.name,
                OSI.used to pool.usage?.used,
                OSI.committed to pool.usage?.committed,
                OSI.max to pool.usage?.max,
            )
        },
    )
}
