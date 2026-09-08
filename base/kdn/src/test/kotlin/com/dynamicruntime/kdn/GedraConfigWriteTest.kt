package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigRow
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds

/**
 * The config write service (issue #633): the version/publish transition, per-slot diff-before-stamp, and the
 * implied-delete flag, driven against a live in-memory database.
 *
 * The instance clock is frozen and stepped by hand so the stamps a write leaves are deterministic: the whole
 * point of storing a config as one entry per slot is that each slot carries *when it last changed*, and that is
 * only observable if two writes land at two known, distinct times. Its own client, as `GedraDataCacheTest`
 * explains -- every test shares one in-memory database.
 */
class GedraConfigWriteTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraCfgWrite", "gedraCfgWriteTest")
    val client = "gcfgwclient"
    val ownerId = 91001L

    fun service(): GedraConfigService = GedraConfigService.get(cxt)

    /** A context acting as the owner inside this spec's client. */
    fun asOwner(): KdrCxt = cxt.mkSubContext("gcfgw", client).also { it.userId = ownerId }

    // A config exercising a multi-instance slot (cfactDef, keyed by name): `alpha` is never touched after the
    // first write, `beta`'s description varies -- so the two demonstrate untouched vs re-stamped side by side.
    fun cfg(name: String, betaDesc: String, includeBeta: Boolean = true): GedraConfig =
        gedraConfig(cxt, name, "${name}ns", client) {
            cfact("alpha", "grp", "Alpha fact", toFrontend = false)
            if (includeBeta) cfact("beta", "grp", betaDesc, toFrontend = false)
        }

    /** The cfact-slot entries of [row], by cfact name -> the stored entry envelope (for its stamps). */
    fun cfacts(row: GedraConfigRow): Map<String, Map<String, Any?>> =
        row.entries.filter { it[GE.traitId].toOptStr() == CCT.cfactDef }
            .associate { (it[GE.data].toJsonMapOrEmpty()[CCT.name].toOptStr() ?: "") to it }

    // Stored stamps are millisecond-resolution (JSON serialization truncates), so compare at that resolution
    // rather than against the microsecond-precision live clock.
    fun createdMs(entry: Map<String, Any?>) = entry[GE.createdAt].toOptInstant()?.toEpochMilliseconds()
    fun updatedMs(entry: Map<String, Any?>) = entry[GE.updatedAt].toOptInstant()?.toEpochMilliseconds()

    "the version/publish transition, with per-slot diff-before-stamp" {
        cxt.instanceConfig.clock.freeze()
        val classId = GedraId.of(GedraConfigType.configDoc, client, "wcfg")
        val t0 = cxt.instanceNow().toEpochMilliseconds()

        // First write: version 1, unpublished, both slots freshly stamped at t0.
        val v1 = service().writeConfig(asOwner(), cfg("wcfg", "Beta v1"))
        v1.version shouldBe 1
        v1.isPublished shouldBe false
        v1.gedraId shouldBe classId.withRevision(1)
        cfacts(v1).keys shouldContainExactlyInAnyOrder listOf("alpha", "beta")
        createdMs(cfacts(v1).getValue("beta")) shouldBe t0
        updatedMs(cfacts(v1).getValue("beta")) shouldBe t0

        // Edit before publishing: rewrites the same revision in place. `beta` changed, so its `updated` half
        // moves while its `created` half is preserved; `alpha` is byte-identical, so its whole envelope stands.
        cxt.instanceConfig.clock.advanceBy(1.seconds)
        val t1 = cxt.instanceNow().toEpochMilliseconds()
        val edited = service().writeConfig(asOwner(), cfg("wcfg", "Beta v2"))
        edited.version shouldBe 1
        edited.gedraId shouldBe classId.withRevision(1)
        val a1 = cfacts(edited).getValue("alpha")
        val b1 = cfacts(edited).getValue("beta")
        createdMs(a1) shouldBe t0
        updatedMs(a1) shouldBe t0 // untouched slot keeps both stamps
        createdMs(b1) shouldBe t0 // changed slot preserves who/when it was first written
        updatedMs(b1) shouldBe t1 // ...and moves only its updated half
        b1[GE.data].toJsonMapOrEmpty()[CCT.description] shouldBe "Beta v2"

        // Publish the editable latest.
        val published = service().publish(asOwner(), classId)
        published.version shouldBe 1
        published.isPublished shouldBe true
        published.publishedAt.shouldNotBeNull()

        // The next write after publishing starts a new revision. The prior revision's `alpha` was unchanged, so
        // it carries its original t0 stamps across the version bump rather than looking freshly authored -- the
        // heart of per-slot accounting. `beta` changed again, so its updated half moves to t2.
        cxt.instanceConfig.clock.advanceBy(1.seconds)
        val t2 = cxt.instanceNow().toEpochMilliseconds()
        val v2 = service().writeConfig(asOwner(), cfg("wcfg", "Beta v3"))
        v2.version shouldBe 2
        v2.isPublished shouldBe false
        v2.gedraId shouldBe classId.withRevision(2)
        val a2 = cfacts(v2).getValue("alpha")
        val b2 = cfacts(v2).getValue("beta")
        createdMs(a2) shouldBe t0
        updatedMs(a2) shouldBe t0 // unchanged across the bump: stamps intact, not re-authored
        createdMs(b2) shouldBe t0
        updatedMs(b2) shouldBe t2
        b2[GE.data].toJsonMapOrEmpty()[CCT.description] shouldBe "Beta v3"

        // The published revision 1 is untouched by the new revision.
        published.version shouldBe 1
    }

    "an authoritative write drops a slot the config no longer carries (implied delete on)" {
        service().writeConfig(asOwner(), cfg("wcfgDel", "Beta")).let {
            cfacts(it).keys shouldContainExactlyInAnyOrder listOf("alpha", "beta")
        }
        // Default impliedDelete = true: beta, absent from the new config, is dropped from the revision.
        val after = service().writeConfig(asOwner(), cfg("wcfgDel", "Beta", includeBeta = false))
        after.version shouldBe 1 // still editable, so rewritten in place
        cfacts(after).keys shouldContainExactlyInAnyOrder listOf("alpha")
    }

    "an additive write carries a dropped slot forward with its stamps (implied delete off)" {
        cxt.instanceConfig.clock.freeze()
        val classId = GedraId.of(GedraConfigType.configDoc, client, "wcfgKeep")
        val t0 = cxt.instanceNow().toEpochMilliseconds()
        service().writeConfig(asOwner(), cfg("wcfgKeep", "Beta"))

        cxt.instanceConfig.clock.advanceBy(5.seconds)
        // impliedDelete = false: beta is not in the new config, so it is carried forward untouched -- its t0
        // stamps survive, proving it was preserved rather than re-authored.
        val after = service().writeConfig(asOwner(), cfg("wcfgKeep", "Beta", includeBeta = false), impliedDelete = false)
        cfacts(after).keys shouldContainExactlyInAnyOrder listOf("alpha", "beta")
        val beta = cfacts(after).getValue("beta")
        createdMs(beta) shouldBe t0
        updatedMs(beta) shouldBe t0

        // Sanity: the class list actually distinguishes these ids.
        classId.fullId shouldNotBe GedraId.of(GedraConfigType.configDoc, client, "wcfgDel").fullId
    }
})
