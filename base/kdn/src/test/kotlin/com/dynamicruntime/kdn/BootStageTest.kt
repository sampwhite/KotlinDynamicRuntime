package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.job.JSCH
import com.dynamicruntime.common.job.JobConfig
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.kdn.confdemo.ConfDemo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * How far a boot goes (issue #1289): a script boots only as far as it needs. Private instances, each booted to a
 * stage no shared one is -- the stage is the setup under test.
 */
class BootStageTest : StringSpec({

    "a collected boot runs only the services it names, and reaches no database" {
        val cxt = Startup.mkTestBootCxt(
            "collected", "bootStageCollected1289", stage = BootStage.collected, services = setOf(FileStoreService.serviceName),
        )
        BootStage.of(cxt.instanceConfig) shouldBe BootStage.collected
        // The file store works: its buckets come from the components' declarations, collected before any service.
        val store = FileStoreService.get(cxt)
        store.put(cxt, ClientWebAssetService.bucket, "probe/one", ContentData("text/plain", isBinary = true, bytes = "hello".toByteArray()))
        store.get(cxt, ClientWebAssetService.bucket, "probe/one").shouldNotBeNull().bytes.decodeToString() shouldBe "hello"
        // And nothing else ran: no database topic service, no compiled schema.
        cxt.instanceConfig.get(SqlTopicService.serviceName).shouldBeNull()
        cxt.instanceConfig.get(SchemaService.serviceName).shouldBeNull()
    }

    "an initialized boot leaves the database usable, and starts and provisions nothing" {
        // Everything a serving node's boot would set off: the scheduler on, and the conference demo provisioned at boot.
        val cxt = Startup.mkTestBootCxt(
            "initialized", "bootStageInitialized1289",
            overlay = mapOf(JSCH.jobScheduler to mapOf(JSCH.enabled to true), ConfDemo.provisionAtBootKey to true),
            stage = BootStage.initialized,
        )
        JobConfig.schedulerEnabled(cxt) shouldBe true
        JobService.get(cxt).isSchedulerRunning shouldBe false
        ClientService.get(cxt).present(ConfDemo.client).shouldBeNull()
        // The database is there to work on, as a script such as GrantRole needs.
        TestUser.create(cxt, "boot-stage@example.com").selfRoles() shouldContain "user"
    }

    "a cached instance is never handed out at another stage" {
        Startup.mkTestBootCxt("first", "bootStageReuse1289", stage = BootStage.initialized)
        shouldThrow<KdrException> { Startup.mkTestBootCxt("again", "bootStageReuse1289") }
            .message.shouldNotBeNull() shouldContain "booted to the 'initialized' stage"
    }

    "a collected boot naming a service no component registers is refused" {
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("unknown", "bootStageUnknown1289", stage = BootStage.collected, services = setOf("NoSuchService"))
        }.message.shouldNotBeNull() shouldContain "No service named NoSuchService"
    }
})
