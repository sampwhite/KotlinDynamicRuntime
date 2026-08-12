package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Boots real instances through [Startup.mkTestBootCxt] and checks the
 * component/service model: the startup [SchemaService] runs, schema from every
 * component is assembled into one compiled store, and an instance is initialized
 * only once. Each test uses a distinct instance name because
 * [com.dynamicruntime.common.startup.InstanceRegistry] caches instances VM-globally.
 */
class StartupTest : StringSpec({

    "booting initializes SchemaService and assembles schema from all components" {
        val cxt = Startup.mkTestBootCxt("test", "startupTest")

        // The test variant boots into the unit environment, in-memory.
        cxt.instanceConfig.env shouldBe ENV.unit
        cxt.instanceConfig.get(ACFG.inMemoryOnly) shouldBe true

        // The startup service ran and is published under its name.
        val service = SchemaService.get(cxt)
        service.shouldNotBeNull()

        val schema = cxt.getSchema()
        // Types contributed by BOTH the common (via NodeService) and kdn components are present.
        schema.types shouldContainKey "node.Health"
        schema.types shouldContainKey "kdn.RuntimeInfo"
        // The health endpoint contributed by NodeService is indexed by its collation key (path:method).
        schema.endpoints shouldContainKey "/health:GET"
        // The store the context exposes is the one the service compiled.
        cxt.getSchema() shouldBe service.schemaStore
    }

    "booting the same instance twice reuses the cached instance config" {
        val cxt1 = Startup.mkTestBootCxt("a", "reuseTest")
        val cxt2 = Startup.mkTestBootCxt("b", "reuseTest")

        (cxt1.instanceConfig === cxt2.instanceConfig) shouldBe true
        SchemaService.get(cxt1) shouldBe SchemaService.get(cxt2)
    }
})
