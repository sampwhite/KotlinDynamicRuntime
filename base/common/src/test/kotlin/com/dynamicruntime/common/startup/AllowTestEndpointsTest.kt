package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for whether a deployment exposes `forTestingOnly` endpoints (issue #125): the env var, the `unit`
 * environment, or `inMemoryOnly` each enables them; a plain deployment does not.
 */
class AllowTestEndpointsTest : StringSpec({

    fun config(env: String) = KdrInstanceConfig("allowTestEp", env, ENV.liveSource)

    "the unit environment always allows test endpoints" {
        SchemaService.allowTestEndpoints(config(ENV.unit)) shouldBe true
    }

    "inMemoryOnly allows test endpoints, in any environment" {
        SchemaService.allowTestEndpoints(config(ENV.local).apply { put(ACFG.inMemoryOnly, true) }) shouldBe true
        SchemaService.allowTestEndpoints(config(ENV.dev).apply { put(ACFG.inMemoryOnly, true) }) shouldBe true
    }

    "the env var allows test endpoints when set true" {
        val c = config(ENV.local).apply { put(SchemaService.allowTestEndpointsEnvVar, "true") }
        SchemaService.allowTestEndpoints(c) shouldBe true
    }

    "off by default for a plain deployment (no env var, not unit, not in-memory)" {
        SchemaService.allowTestEndpoints(config(ENV.local)) shouldBe false
        SchemaService.allowTestEndpoints(config(ENV.dev)) shouldBe false
    }
})
