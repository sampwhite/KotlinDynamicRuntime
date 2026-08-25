package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The presence axis (issue #433): a boot role, capability tags, and how the two combine.
 *
 * These are also the **worked example** of capability tags. Nothing in the codebase declares one yet -- the
 * consumer/admin split has not been designed, so tagging a real service would encode a guess about it. A test
 * exercises the mechanism without leaving a guess behind to be unpicked later.
 */
class PresenceTest : StringSpec({

    fun node(role: String, vararg tags: String) = NodeProfile(role, tags.toSet())

    "an undeclared contribution is present everywhere" {
        Presence.anywhere.admits(node(BOOT.app)) shouldBe true
        Presence.anywhere.admits(node(BOOT.edge)) shouldBe true
        Presence.anywhere.admits(node(BOOT.app, "anything")) shouldBe true
        Presence.anywhere.isUnconstrained shouldBe true
    }

    "a role constraint admits only that role" {
        val appOnly = Presence(roles = setOf(BOOT.app))
        appOnly.admits(node(BOOT.app)) shouldBe true
        appOnly.admits(node(BOOT.edge)) shouldBe false
    }

    /**
     * The case that decides the whole shape. A deployment serving both backend surfaces carries **both tags**
     * rather than a combined role -- so no declaration ever names the combination, and adding a third surface
     * later does not touch a single existing gate.
     */
    "a node carrying several tags satisfies each of them separately" {
        val adminOnly = Presence(tags = setOf("adminSurface"))
        val consumerOnly = Presence(tags = setOf("consumerSurface"))
        val both = node(BOOT.app, "adminSurface", "consumerSurface")

        adminOnly.admits(both) shouldBe true
        consumerOnly.admits(both) shouldBe true
        adminOnly.admits(node(BOOT.app, "consumerSurface")) shouldBe false
        consumerOnly.admits(node(BOOT.app, "adminSurface")) shouldBe false
    }

    "naming several tags means any one of them" {
        val either = Presence(tags = setOf("adminSurface", "consumerSurface"))
        either.admits(node(BOOT.app, "adminSurface")) shouldBe true
        either.admits(node(BOOT.app, "consumerSurface")) shouldBe true
        either.admits(node(BOOT.app, "somethingElse")) shouldBe false
        either.admits(node(BOOT.app)) shouldBe false
    }

    "naming both axes requires both" {
        val p = Presence(roles = setOf(BOOT.app), tags = setOf("adminSurface"))
        p.admits(node(BOOT.app, "adminSurface")) shouldBe true
        p.admits(node(BOOT.edge, "adminSurface")) shouldBe false
        p.admits(node(BOOT.app)) shouldBe false
    }

    /**
     * Narrowing is checked, never combined -- the registry asks the component and then the entry. Combining
     * them would need "constrains nothing" and "admits nothing" to be different values, and an empty set
     * cannot be both.
     */
    "a member narrows its component and can never widen it" {
        val component = Presence(roles = setOf(BOOT.app))
        val member = Presence(tags = setOf("adminSurface"))
        fun carried(n: NodeProfile) = component.admits(n) && member.admits(n)

        carried(node(BOOT.app, "adminSurface")) shouldBe true
        carried(node(BOOT.app)) shouldBe false
        // The member names no role, yet cannot reach a role its component excludes.
        carried(node(BOOT.edge, "adminSurface")) shouldBe false
    }

    "a node reads its role and tags from config, defaulting to the application role and no tags" {
        val plain = KdrInstanceConfig("t", ENV.unit, ENV.liveSource, null)
        NodeProfile.of(plain).role shouldBe BOOT.app
        NodeProfile.of(plain).tags shouldBe emptySet()

        val tagged = KdrInstanceConfig("t", ENV.unit, ENV.liveSource, null)
        tagged.put(ACFG.bootTags, listOf("adminSurface", "consumerSurface"))
        NodeProfile.of(tagged).tags shouldBe setOf("adminSurface", "consumerSurface")
    }

    // Blank entries are dropped rather than becoming a tag no declaration can ever name.
    "tags are trimmed, and empty ones discarded" {
        val c = KdrInstanceConfig("t", ENV.unit, ENV.liveSource, null)
        c.put(ACFG.bootTags, listOf(" adminSurface ", "", "  "))
        NodeProfile.of(c).tags shouldBe setOf("adminSurface")
    }
})
