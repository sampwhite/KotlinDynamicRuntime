package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GC
import com.dynamicruntime.common.gedra.GedraConfigCache
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.util.toOptLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The two-revision config cache (issue #615): per revision class it resolves the **latest** revision and the
 * **latest published** one, and the service's reads served from it equal the SQL they replace.
 *
 * The three states the issue names, walked in order on one class: latest with nothing published; latest equal
 * to published; latest ahead of published; and the transition when the newer revision is published. Its own
 * client, since the in-memory database is shared across specs.
 */
class GedraConfigCacheTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraCfgCache", "gedraCfgCacheTest")
    val client = "cfgcacheclient"
    val name = "cached"
    val classId = GedraId.of(GedraConfigType.configDoc, client, name)

    fun service(): GedraConfigService = GedraConfigService.get(cxt)

    /** A context bound to the client, as both the writes and the client-confined reads need. */
    fun asClient(): KdrCxt = cxt.mkSubContext("cfgCache", client).also { it.userId = 8000L }

    fun write(desc: String) = service().writeConfig(
        asClient(),
        gedraConfig(cxt, name, "${client}config", client) { cfact("ready", "grp", desc) },
    )

    fun revisions() = run {
        val cache = service().configCache.shouldNotBeNull()
        cache.checkRefresh(cxt)
        GedraConfigCache.revisionsOf(cache, classId.fullId)
    }

    fun version(row: Map<String, Any?>?) = row?.get(GC.version).toOptLong()

    "the cache resolves latest and latest-published through every state of a class" {
        // Nothing stored yet: the class is absent from the cache.
        revisions().shouldBeNull()

        // v1, unpublished: latest is v1 and nothing is published.
        write("v1")
        revisions().shouldNotBeNull().let {
            version(it.latest) shouldBe 1L
            it.latestPublished.shouldBeNull()
        }

        // Publish it: latest == published, the same row.
        service().publish(asClient(), classId)
        revisions().shouldNotBeNull().let {
            version(it.latest) shouldBe 1L
            version(it.latestPublished) shouldBe 1L
        }

        // Write again after publishing: a new revision, so latest runs ahead of published.
        write("v2")
        revisions().shouldNotBeNull().let {
            version(it.latest) shouldBe 2L
            version(it.latestPublished) shouldBe 1L
        }

        // The transition: publishing the newer revision moves published up to meet latest.
        service().publish(asClient(), classId)
        revisions().shouldNotBeNull().let {
            version(it.latest) shouldBe 2L
            version(it.latestPublished) shouldBe 2L
        }
    }

    // The reads are cache-first with a SQL fallback; nulling the cache around a second call forces the SQL the
    // cached answer must equal, the way `GedraDataCacheTest` proves the data reads.
    "the cached reads equal the SQL they replace" {
        val svc = service()
        write("v3") // a third, unpublished revision on top of the published v2 above
        val viaCache = svc.readLatest(asClient(), classId).shouldNotBeNull()
        val listViaCache = svc.listConfigs(asClient()).map { it.gedraId.fullId to it.version }

        val held = svc.configCache
        svc.configCache = null
        try {
            val viaSql = svc.readLatest(asClient(), classId).shouldNotBeNull()
            viaSql.gedraId shouldBe viaCache.gedraId
            viaSql.version shouldBe viaCache.version
            viaCache.version shouldBe 3
            svc.listConfigs(asClient()).map { it.gedraId.fullId to it.version } shouldContainExactly listViaCache
        } finally {
            svc.configCache = held
        }
    }
})
