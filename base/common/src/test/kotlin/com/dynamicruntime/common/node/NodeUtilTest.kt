package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers node-identity resolution from the `KDR_`-prefixed environment variables (issue #44). Values are
 * injected through the instance config (which [KdrCxt.getEnvVar] consults before the real environment), so
 * the tests never touch the process environment.
 */
class NodeUtilTest : StringSpec({

    fun cxt(vararg env: Pair<String, String>): KdrCxt {
        val c = KdrCxt.mkSimpleCxt("test")
        for ((k, v) in env) c.instanceConfig.put(k, v)
        return c
    }

    "defaults to 127.0.0.1 and the default port when nothing is configured" {
        // The host name may come from the OS `HOSTNAME`, so only the parts this test controls are asserted.
        val id = NodeUtil.extractNodeId(cxt())
        id.nodeIpAddress shouldBe "127.0.0.1"
        id.port shouldBe NodeUtil.defaultPort
    }

    "KDR_PORT overrides the port the server binds to" {
        NodeUtil.extractNodeId(cxt(NodeUtil.port to "7071")).port shouldBe 7071
    }

    "KDR_NODE_IP_ADDRESS and KDR_HOSTNAME override the node label parts" {
        val id = NodeUtil.extractNodeId(cxt(NodeUtil.nodeIpAddress to "10.0.0.5", NodeUtil.hostName to "kdr-box"))
        id.nodeIpAddress shouldBe "10.0.0.5"
        id.hostname shouldBe "kdr-box"
        id.label shouldBe "10.0.0.5:${NodeUtil.defaultPort}"
    }

    "a set-but-non-integer KDR_PORT fails loudly instead of falling back" {
        shouldThrow<KdrException> { NodeUtil.extractNodeId(cxt(NodeUtil.port to "not-a-port")) }
    }
})
