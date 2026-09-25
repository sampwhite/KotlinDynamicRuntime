package com.dynamicruntime.config

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.job.JPF
import com.dynamicruntime.common.job.JSCH
import com.dynamicruntime.common.job.JobTraceLevel
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

/** A deployment's typed job settings (issue #870) land where the job service reads them, keyed as it reads them. */
class AppConfigJobSettingsTest : StringSpec({
    "jobProfile and jobScheduler write the settings made, and only those" {
        val builder = AppConfigBuilder(KdrCxt.mkSimpleCxt("jobSettings"), LinkedHashMap())
        with(builder) {
            jobProfile("nightly") {
                leaseTimeoutMs = 60_000
                trace = JobTraceLevel.launch
            }
            // A second call on the same profile adds to it rather than replacing it.
            jobProfile("nightly") { retryLimit = 5 }
            jobScheduler { tickMs = 30_000 }
        }
        builder.data[JPF.jobProfiles].toJsonMapOrEmpty()["nightly"].toJsonMapOrEmpty() shouldContainExactly mapOf(
            JPF.leaseTimeoutMs to 60_000L, JPF.trace to JobTraceLevel.launch.name, JPF.retryLimit to 5,
        )
        builder.data[JSCH.jobScheduler].toJsonMapOrEmpty()[JSCH.tickMs] shouldBe 30_000L
    }
})
