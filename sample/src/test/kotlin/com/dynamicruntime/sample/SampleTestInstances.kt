package com.dynamicruntime.sample

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.kdn.Startup

/**
 * The instances `sample`'s tests share (issue #1736), as `base:kdn`'s `TestInstances` does for its own (issue #1075).
 * Every spec used to boot a sample node of its own, about fifty-five identical boots, which was most of the module's
 * time.
 *
 * **Shared by default, a private instance justified.** Each call returns a fresh context on the one shared instance.
 * A test isolates itself with user addresses of its own (`TestUser.create` finds an existing address), and never counts
 * or lists everything acme or globex holds: other specs' forms are there too, so it finds its own by owner or id.
 *
 * A test boots an instance of its own only when it changes something every other test would see, and says which in a
 * comment: publishing acme's configuration, the instance clock, the job scheduler, a component or setting no entry
 * here has.
 */
object SampleTestInstances {
    /** The sample component loaded (`KDR_LOAD_SAMPLE`): acme, globex, and the sample's traits and workflows. */
    fun default(cxtName: String): KdrCxt = Startup.mkTestBootCxt(
        cxtName, "sampleShared", mapOf(SampleComponent.loadSampleEnvVar.name to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    /** As [default], under the `reference` deployment flavor: the demo clients, joining, and the flavor's copy. */
    fun reference(cxtName: String): KdrCxt = Startup.mkTestBootCxt(
        cxtName, "sampleSharedReference",
        mapOf(FLV.deployFlavorsEnvVar.name to FLV.reference, SampleComponent.loadSampleEnvVar.name to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
}
