package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
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
        NodeUtil.extractNodeId(cxt(NodeUtil.port.name to "7071")).port shouldBe 7071
    }

    "KDR_NODE_IP_ADDRESS and KDR_HOSTNAME override the node's address and host name" {
        val id = NodeUtil.extractNodeId(cxt(NodeUtil.nodeIpAddress.name to "10.0.0.5", NodeUtil.hostName.name to "kdr-box"))
        id.nodeIpAddress shouldBe "10.0.0.5"
        id.hostname shouldBe "kdr-box"
        id.label shouldBe "10.0.0.5:${NodeUtil.defaultPort}"
    }

    "a set-but-non-integer KDR_PORT fails loudly instead of falling back" {
        shouldThrow<KdrException> { NodeUtil.extractNodeId(cxt(NodeUtil.port.name to "not-a-port")) }
    }

    // Issue #1871: a node's name, what identifies it, beside the address it is reached at.
    "the node's name is KDR_NODE_NAME, else node-<port>" {
        NodeUtil.extractNodeId(cxt()).name shouldBe "node-${NodeUtil.defaultPort}"
        NodeUtil.extractNodeId(cxt(NodeUtil.port.name to "7073")).name shouldBe "node-7073"
        val named = NodeUtil.extractNodeId(cxt(NodeUtil.nodeName.name to "web-2", NodeUtil.port.name to "7073"))
        named.name shouldBe "web-2"
        // The address is unchanged by the name: it is where the node is reached.
        named.label shouldBe "127.0.0.1:7073"
        // A blank one is unset.
        NodeUtil.extractNodeId(cxt(NodeUtil.nodeName.name to "  ")).name shouldBe "node-${NodeUtil.defaultPort}"
    }

    "a set-but-invalid KDR_NODE_NAME fails loudly instead of falling back" {
        for (bad in listOf("two words", "-leading", "a/b", "x".repeat(65))) {
            shouldThrow<KdrException> { NodeUtil.extractNodeId(cxt(NodeUtil.nodeName.name to bad)) }
        }
    }

    "under a boot role, the name is the role's own: KDR_EDGE_NODE_NAME, never the unprefixed KDR_NODE_NAME" {
        val config = KdrInstanceConfig("nodeRole", ENV.local, ENV.liveSource, "edge")
        config.put(NodeUtil.nodeName.name, "shared")
        config.put("KDR_EDGE_PORT", "7075")
        NodeUtil.extractNodeId(KdrCxt.mkSimpleCxt("nodeRole", config)).name shouldBe "node-7075"
        config.put("KDR_EDGE_NODE_NAME", "edge-1")
        NodeUtil.extractNodeId(KdrCxt.mkSimpleCxt("nodeRole", config)).name shouldBe "edge-1"
    }
})
