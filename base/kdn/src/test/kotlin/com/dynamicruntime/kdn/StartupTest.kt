package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogConfig
import com.dynamicruntime.common.logging.LogLevel
import com.dynamicruntime.common.logging.LogSinks
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.authTopic
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Boots real instances through [Startup.mkTestBootCxt] and checks the
 * component/service model: the startup [SchemaService] runs, schema from every
 * component is assembled into one compiled store, and an instance is initialized
 * only once. Each test uses a distinct instance name because
 * [com.dynamicruntime.common.startup.InstanceRegistry] caches instances VM-globally -- and refuses a cached name asked
 * for with another setup (issue #1075).
 *
 * Also, since issue #1739, the tests that were `TopicStartupInitTest`, each kept whole below.
 */
class StartupTest : StringSpec({
    include(startupTests)
    include(topicStartupInitTests)
})

/** This file's own tests, as the class describes them. */
private val startupTests = stringSpec {

    "booting initializes SchemaService and assembles schema from all components" {
        // Its own instance (issue #1075): the boot itself is under test: what an instance assembles at startup.
        val cxt = Startup.mkTestBootCxt("test", "startupTest")

        // The test variant boots into the unit environment, in-memory.
        cxt.instanceConfig.env shouldBe ENV.unit
        cxt.instanceConfig.get(ACFG.inMemoryOnly) shouldBe true

        // The startup service ran and is published under its name.
        val service = SchemaService.get(cxt)

        val schema = cxt.getGlobalSchema()
        // Types contributed by BOTH the common (via NodeService) and kdn components are present.
        schema.types shouldContainKey "kdr.node.Health"
        schema.types shouldContainKey "kdr.kdn.RuntimeInfo"
        // The health endpoint contributed by NodeService is indexed by its collation key (path:method).
        schema.endpoints shouldContainKey "/health:GET"
        // The store the context exposes is the one the service compiled.
        cxt.getGlobalSchema() shouldBe service.schemaStore

        // Logging is on in a unit test now (issue #524): a sink is installed and info-level is enabled, where
        // a unit boot previously installed no sink and dropped every backend log call.
        LogSinks.sinks.isNotEmpty() shouldBe true
        LogConfig.isEnabled("startup", LogLevel.info) shouldBe true
    }

    // The isolation win (issue #524): the component set is a per-boot parameter, not VM-global state, so a
    // component handed to one instance cannot appear in another. Under the old registry, a `register`ed
    // component persisted for the JVM and would load into every later instance its gates admitted.
    "a component given to one instance does not leak into another" {
        // Its own instance (issue #1075): the boot itself is under test: a component given to one boot must not reach
        // another.
        val withFixture = Startup.mkTestBootCxt(
            "wf", "isoWithFixture", additionalComponents = listOf(MarkerFixtureComponent()),
        )
        // Its own instance (issue #1075): booted without the fixture, after the one above, so a leak would show.
        val without = Startup.mkTestBootCxt("wo", "isoWithoutFixture")

        // It loaded where it was named...
        withFixture.instanceConfig.get(fixtureMarker) shouldBe true
        // ...and is entirely absent from an instance that did not name it -- booted second, so a leak would show.
        without.instanceConfig.get(fixtureMarker) shouldBe null
    }

    "booting the same instance twice reuses the cached instance config" {
        // Its own instance (issue #1075): the boot itself is under test: booting one instance name twice must reuse it.
        val cxt1 = Startup.mkTestBootCxt("a", "reuseTest")
        val cxt2 = Startup.mkTestBootCxt("b", "reuseTest")

        (cxt1.instanceConfig === cxt2.instanceConfig) shouldBe true
        SchemaService.get(cxt1) shouldBe SchemaService.get(cxt2)
    }

    "a cached instance asked for with another setup is refused, naming both (issue #1075)" {
        // Its own instance (issue #1075): the registry's handling of a reused name is under test.
        class Probe
        val first = Startup.mkTestBootCxt("first", "setupGuardTest", mapOf(ACFG.obfuscateSensitiveErrors to true, "probe" to Probe()))
        // The same setup again is the same instance -- an object value counts by its class, so a fresh one per call is no change.
        val again = Startup.mkTestBootCxt("again", "setupGuardTest", mapOf(ACFG.obfuscateSensitiveErrors to true, "probe" to Probe()))
        (first.instanceConfig === again.instanceConfig) shouldBe true
        val keySource = Probe()
        // Another setting, or another component, is a different setup under the same name.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("other", "setupGuardTest", mapOf(ACFG.obfuscateSensitiveErrors to false, "probe" to keySource))
        }.message.orEmpty() shouldContain "setupGuardTest"
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "more", "setupGuardTest", mapOf(ACFG.obfuscateSensitiveErrors to true, "probe" to keySource),
                additionalComponents = listOf(MarkerFixtureComponent()),
            )
        }
    }
}

/**
 * Every topic's tables must be created during **startup**, as the system user -- never lazily by whichever
 * request happens to touch a topic first.
 *
 * Creating a table is DDL, and `SqlDatabase.executeSchemaChangeSql` refuses to run DDL on behalf of an end
 * user. So if creation were deferred to first use, a signed-in user's request that landed on a topic whose
 * tables were not all present would fail with "Schema-change SQL may only be executed by the system user".
 *
 * That is not hypothetical, and it is nastier than it looks: an *anonymous* caller's userId is the system user
 * id (both 0), so an anonymous first touch satisfies the guard by coincidence and everything appears fine. The
 * failure surfaces only when a **signed-in** user makes the first request after a deployment that added a table
 * to an existing topic, against a database that already holds the rest of it -- i.e. on upgrade, and never in a
 * fresh in-memory test. Adding `LinkedUsers` to the `auth` topic (issue #157) hit exactly that.
 *
 * Asserting the topics exist immediately after boot is what keeps the creation in startup: it fails if anyone
 * makes topic init lazy again.
 */
private val topicStartupInitTests = stringSpec {

    "the auth topic and its tables are created during startup, before any request" {
        val cxt = TestInstances.default("topicInit")
        val service = SqlTopicService.get(cxt)
        // Populated by checkReady, so it is already there without a single request having been made.
        service.topics.keys shouldContain authTopic
    }

    "every topic the schema store declares is created, not just the one a test happens to use" {
        val cxt = TestInstances.default("topicInitAll")
        val service = SqlTopicService.get(cxt)
        val declared = cxt.getGlobalSchema().tables.values.map { it.topic }.toSet()
        declared.forEach { service.topics.keys shouldContain it }
    }
}

// Declared with what was `StartupTest`.
/** A do-nothing component that marks the instance it is loaded into, so a test can see where it did (not) go. */
private const val fixtureMarker = "startupTestFixtureLoaded"

private class MarkerFixtureComponent : ComponentDefinition {
    override fun applyInstanceConfig(cxt: KdrCxt) {
        cxt.instanceConfig.put(fixtureMarker, true)
    }
}
