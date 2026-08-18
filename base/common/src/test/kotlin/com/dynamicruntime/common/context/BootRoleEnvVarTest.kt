package com.dynamicruntime.common.context

import com.dynamicruntime.common.node.NodeUtil
import com.dynamicruntime.common.user.ENVA
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for the per-role environment-variable namespace (issue #377): with a boot role set, a lookup for
 * `KDR_PORT` tries `KDR_EDGE_PORT` first and falls back to `KDR_PORT`.
 *
 * This is what lets an edge and an application run side by side on one machine wanting different values for the
 * same variable, which is the whole reason a second launcher can exist at all.
 */
class BootRoleEnvVarTest : StringSpec({

    fun config(role: String? = null) =
        KdrInstanceConfig("roleTest", ENV.local, ENV.liveSource, role)

    /**
     * The half that protects every existing deployment: without a role nothing is prefixed, so this change can
     * only ever *add* a name that was not being read before. Asserted first because it is the property that
     * has to hold, not merely the behavior that is convenient.
     */
    "no role means no prefixing at all" {
        KdrInstanceConfig.envVarNamesFor("KDR_PORT", null) shouldBe listOf("KDR_PORT")
        KdrInstanceConfig.envVarNamesFor("KDR_PORT", "") shouldBe listOf("KDR_PORT")
    }

    "a role inserts itself after the prefix, and the plain name remains the fallback" {
        KdrInstanceConfig.envVarNamesFor("KDR_PORT", "edge") shouldBe listOf("KDR_EDGE_PORT", "KDR_PORT")
        // Upper-cased to match how every other variable is spelled on the wire, whatever case the role is in.
        KdrInstanceConfig.envVarNamesFor("KDR_IN_MEMORY_ONLY", "Edge") shouldBe
            listOf("KDR_EDGE_IN_MEMORY_ONLY", "KDR_IN_MEMORY_ONLY")
    }

    "a key that is not one of ours is left alone" {
        // Nothing to namespace: the prefix is what marks a variable as this application's.
        KdrInstanceConfig.envVarNamesFor("HOSTNAME", "edge") shouldBe listOf("HOSTNAME")
    }

    "the role-prefixed value wins, and the plain one is used when it is absent" {
        val c = config("edge").apply {
            put("KDR_PORT", "7070")
            put("KDR_EDGE_PORT", "7080")
        }
        c.getEnvVar("KDR_PORT") shouldBe "7080"

        val fallback = config("edge").apply { put("KDR_PORT", "7070") }
        fallback.getEnvVar("KDR_PORT") shouldBe "7070"
    }

    "the same lookup on an unrolled node ignores the prefixed value entirely" {
        // The edge's variable is simply not this node's business -- it must not leak into an ordinary boot
        // merely because both processes read the same file.
        val c = config().apply {
            put("KDR_PORT", "7070")
            put("KDR_EDGE_PORT", "7080")
        }
        c.getEnvVar("KDR_PORT") shouldBe "7070"
    }

    /**
     * Every variable gains the override, not a hand-picked list -- which is the argument for putting the rule
     * in the lookup rather than adding a constant per variable. A variable added years from now is namespaced
     * without anybody remembering to do anything.
     */
    "the namespace covers variables nobody thought about when it was built" {
        val c = config("edge").apply { put("KDR_EDGE_" + ENVA.trustEnvAuthHeaderEnvVar.removePrefix("KDR_"), "true") }
        c.getEnvBool(ENVA.trustEnvAuthHeaderEnvVar) shouldBe true
    }

    /**
     * The port is the deliberate exception, and the reason is the failure mode. Everywhere else the unprefixed
     * name is a fine fallback -- a general value applies to every role. A port cannot be shared: two nodes on
     * one machine binding one collide, and quietly, because a bind failure followed by a health check answers
     * from whichever server already owns it.
     *
     * Asserted through `extractNodeId` rather than the lookup, because the guarantee that matters is about the
     * port a node actually binds.
     */
    "a role never inherits the unprefixed port, and falls to its own default instead" {
        val c = KdrInstanceConfig("rolePort", ENV.local, ENV.liveSource, "edge").apply {
            put("KDR_PORT", "7070")
            put(ACFG.defaultPort, 8010)
        }
        NodeUtil.extractNodeId(KdrCxt.mkSimpleCxt("t", c)).port shouldBe 8010

        // Its own variable is still obeyed -- the role is not being denied a port, only a shared one.
        c.put("KDR_EDGE_PORT", "7085")
        NodeUtil.extractNodeId(KdrCxt.mkSimpleCxt("t", c)).port shouldBe 7085
    }

    "an unrolled node still reads the plain port exactly as before" {
        val c = KdrInstanceConfig("plainPort", ENV.local, ENV.liveSource).apply { put("KDR_PORT", "7070") }
        NodeUtil.extractNodeId(KdrCxt.mkSimpleCxt("t", c)).port shouldBe 7070
    }
})
