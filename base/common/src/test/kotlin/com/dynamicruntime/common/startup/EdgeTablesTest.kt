package com.dynamicruntime.common.startup

import com.dynamicruntime.common.CommonComponent
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.sql.TOPIC
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * An edge's database is exactly one topic (issue #434).
 *
 * **Why this is worth asserting rather than observing.** It is true today as a *consequence* of two other
 * changes -- #435 moved the cache-state table under [TOPIC.instance], and #433 declared the auth and gedra
 * tables application-only -- so nothing states it and nothing would notice it stopping being true. A component
 * adding an ungated table tomorrow would give an edge a second topic, and a topic is the unit of database
 * assignment: the symptom is a perimeter node that suddenly needs a database nobody provisioned for it, at
 * deployment time rather than here.
 *
 * Exercised through the collector rather than a boot, deliberately: the collector *is* the mechanism that
 * decides what a node carries, and a real boot would register components into the process-wide
 * [InstanceRegistry] for every later test in the module.
 */
class EdgeTablesTest : StringSpec({

    fun collectFor(role: String?): SchemaCollector {
        val config = KdrInstanceConfig("edgeTables", ENV.unit, ENV.liveSource, role)
        val cxt = KdrCxt("edgeTables", config)
        val collector = SchemaCollector(NodeProfile.of(config))
        CommonComponent().addSchema(cxt, collector)
        return collector
    }

    "an edge's tables are all in the one shared topic" {
        val topics = collectFor(BOOT.edge).tables.map { it.topic }.distinct()
        topics shouldBe listOf(TOPIC.instance)
    }

    /**
     * The two it does carry, named so the test says what an edge actually needs rather than only what it does
     * not: the instance config (which holds the key its env-auth cookie is encrypted with, so not optional)
     * and the cache state (which `RequestService.handleRequest` reaches on every request).
     */
    "an edge carries the instance config and the cache state, and nothing else" {
        val names = collectFor(BOOT.edge).tables.map { it.tableName }
        names shouldContain "InstanceConfig"
        names shouldContain "KdrCacheState"
        names.size shouldBe 2
    }

    "an edge carries no account or application data tables" {
        val names = collectFor(BOOT.edge).tables.map { it.tableName }
        names shouldNotContain "AuthUsers"
        names.none { it.startsWith("Gedra") } shouldBe true
    }

    // The other half of the claim: the application is unaffected, so this is suppression rather than removal.
    "an application still carries all of them" {
        val names = collectFor(null).tables.map { it.tableName }
        names shouldContain "InstanceConfig"
        names shouldContain "KdrCacheState"
        names shouldContain "AuthUsers"
        names.any { it.startsWith("Gedra") } shouldBe true
        // Strictly more than an edge, and a superset of it -- suppression, not a different set of tables.
        val edgeNames = collectFor(BOOT.edge).tables.map { it.tableName }
        names.containsAll(edgeNames) shouldBe true
        (names.size > edgeNames.size) shouldBe true
    }
})
