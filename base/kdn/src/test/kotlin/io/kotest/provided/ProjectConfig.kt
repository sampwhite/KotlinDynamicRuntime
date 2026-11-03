package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.PrepareSpecListener
import io.kotest.core.spec.Spec
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.coroutines.CoroutineDispatcherFactory
import io.kotest.engine.coroutines.ThreadPerSpecCoroutineContextFactory
import java.io.File
import kotlin.reflect.KClass

/**
 * Runs this module's specs concurrently when asked (issue #1078): `./gradlew test -Pkdr.concurrentSpecs=4` runs four
 * specs at a time, each on a thread of its own. Not the default -- an occasional run to find concurrency bugs, worth
 * doing after a change to caching or to how a client's configuration is resolved (see the `kdr-testing` skill).
 * Unset, specs run one after another, as Kotest's own defaults have them.
 *
 * Both settings are needed for specs to overlap: the execution mode alone runs them as coroutines on the one test
 * worker thread, and these specs block. Kotest finds a project config only at this name, in each module's own test
 * sources, so `base:common`, `base:kdn`, and `sample` each hold this same file; keep the copies identical.
 *
 * And records the specs this JVM runs, in order ([SpecOrder], issue #1581).
 */
object ProjectConfig : AbstractProjectConfig() {
    // Validated where the Gradle property is passed on (`kdr.kotlin-conventions`), since a failure here is not reported.
    private val specs: Int? = System.getProperty("kdr.concurrentSpecs")?.toIntOrNull()?.takeIf { it >= 1 }

    override val specExecutionMode: SpecExecutionMode? = specs?.let { SpecExecutionMode.LimitedConcurrency(it) }
    override val coroutineDispatcherFactory: CoroutineDispatcherFactory? = specs?.let { ThreadPerSpecCoroutineContextFactory }
    override val extensions: List<Extension> = listOf(SpecOrder)
}

/**
 * Writes each spec this JVM runs, as it starts, to `jvm-<worker>.txt` in the directory the build names
 * (`kdr.specOrderDir`, issue #1581): its position and its class, one line each. A module whose specs are shared among
 * JVMs runs a given spec after a different set of others than one JVM would, and a spec on a shared instance can depend
 * on what ran before it (#1575 did), so the file is what reproduces such a failure: Kotest runs specs in order of their
 * qualified names, so naming the specs up to the failing one with `--tests` runs them in the same order -- in one JVM,
 * where a module shares its specs among several, with `-Pkdr.testForks=1`. Nothing is written when the build names no
 * directory.
 *
 * Recorded as a spec is prepared, before it is built, since building it is where a spec's setup runs.
 */
private object SpecOrder : PrepareSpecListener {
    private val file: File? = System.getProperty("kdr.specOrderDir")?.let { dir ->
        val worker = System.getProperty("org.gradle.test.worker") ?: ProcessHandle.current().pid().toString()
        File(dir, "jvm-$worker.txt").also {
            it.parentFile.mkdirs()
            it.writeText("")
        }
    }
    private var position = 0

    override suspend fun prepareSpec(kclass: KClass<out Spec>) {
        val out = file ?: return
        // Concurrent specs (`kdr.concurrentSpecs`) prepare on several threads at once.
        synchronized(this) { out.appendText("${++position}\t${kclass.qualifiedName}\n") }
    }
}
