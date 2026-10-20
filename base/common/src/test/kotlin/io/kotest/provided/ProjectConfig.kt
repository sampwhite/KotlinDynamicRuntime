package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.coroutines.CoroutineDispatcherFactory
import io.kotest.engine.coroutines.ThreadPerSpecCoroutineContextFactory

/**
 * Runs this module's specs concurrently when asked (issue #1078): `./gradlew test -Pkdr.concurrentSpecs=4` runs four
 * specs at a time, each on a thread of its own. Not the default -- an occasional run to find concurrency bugs, worth
 * doing after a change to caching or to how a client's configuration is resolved (see the `kdr-testing` skill).
 * Unset, specs run one after another, as Kotest's own defaults have them.
 *
 * Both settings are needed for specs to overlap: the execution mode alone runs them as coroutines on the one test
 * worker thread, and these specs block. Kotest finds a project config only at this name, in each module's own test
 * sources, so `base:common`, `base:kdn`, and `sample` each hold this same file; keep the copies identical.
 */
object ProjectConfig : AbstractProjectConfig() {
    // Validated where the Gradle property is passed on (`kdr.kotlin-conventions`), since a failure here is not reported.
    private val specs: Int? = System.getProperty("kdr.concurrentSpecs")?.toIntOrNull()?.takeIf { it >= 1 }

    override val specExecutionMode: SpecExecutionMode? = specs?.let { SpecExecutionMode.LimitedConcurrency(it) }
    override val coroutineDispatcherFactory: CoroutineDispatcherFactory? = specs?.let { ThreadPerSpecCoroutineContextFactory }
}
