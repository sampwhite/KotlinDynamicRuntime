package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.startup.NodeProfile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Coverage for the edge's context roots (issue #386): the values it binds, and the boot it refuses.
 *
 * Exercised against `EdgeService.checkContextRoots` and `EdgeComponent.isLoaded` directly rather than through
 * a boot -- both are pure functions of the instance config, and a full boot would register the component into
 * the process-wide `InstanceRegistry` for every later test in the module.
 *
 * The guard is reached through `checkContextRoots` rather than `checkInit`, which also binds the node as a
 * content server: since issue #383 a missing service throws, so `checkInit` needs a booted node and is no
 * longer a pure function of the config.
 */
class EdgeRootsTest : StringSpec({

    fun config(role: String? = BOOT.edge) =
        KdrInstanceConfig("edgeRootsTest", ENV.unit, ENV.liveSource, role)

    /**
     * The gate that lets the component be *discovered* rather than referenced: `ServiceLoader` finds it in
     * every launcher, including the ordinary one, and it declines there. `InstanceRegistry` gates both schema
     * and services on this, so declining keeps the whole component out rather than half of it.
     */
    "the component is present only in the edge role" {
        val presence = EdgeComponent().presence(KdrCxt.mkSimpleCxt("t", config()))
        presence.admits(NodeProfile(BOOT.edge, emptySet())) shouldBe true
        // BOOT.app is what a node with no declared role normalizes to, which is the case that matters: an
        // ordinary launcher finds this component through ServiceLoader and must not load it.
        presence.admits(NodeProfile(BOOT.app, emptySet())) shouldBe false
        presence.admits(NodeProfile("somethingElse", emptySet())) shouldBe false
    }

    // The normalization BOOT.app asks for, exercised where it actually happens.
    "a node that declares no role reads as the application role" {
        NodeProfile.of(KdrInstanceConfig("t", ENV.unit, ENV.liveSource, null)).role shouldBe BOOT.app
        NodeProfile.of(KdrInstanceConfig("t", ENV.unit, ENV.liveSource, BOOT.edge)).role shouldBe BOOT.edge
    }

    "the component contributes its roots and its port" {
        val c = config()
        EdgeComponent().applyInstanceConfig(KdrCxt.mkSimpleCxt("t", c))
        c.get(ACFG.apiContextRoot) shouldBe EdgeRoot.ea
        c.get(ACFG.contentContextRoot) shouldBe EdgeRoot.ec
        c.get(ACFG.appContextRoot) shouldBe EdgeRoot.ew
        c.get(ACFG.staticContextRoot) shouldBe EdgeRoot.es
        // The launcher carries no copy of this number; the role owns it.
        c.get(ACFG.defaultPort) shouldBe EdgeRole.defaultPort
    }

    // A deployment may still choose its own; these are what makes a node an edge, not a preference about it.
    "a deployment's explicit root wins over the default" {
        val c = config().apply { put(ACFG.apiContextRoot, "zz") }
        EdgeComponent().applyInstanceConfig(KdrCxt.mkSimpleCxt("t", c))
        c.get(ACFG.apiContextRoot) shouldBe "zz"
    }

    /**
     * The guard that matters. An edge and the backends it fronts are reachable on the same host, so a shared
     * root makes a path ambiguous -- and the failure is a *mis-route*, which surfaces as the wrong server
     * answering rather than as an error. Refusing at boot is the only report that cannot be scrolled past.
     */
    "an edge configured with an application's root refuses to boot" {
        val c = config().apply { put(ACFG.apiContextRoot, ContextRoot.kda) }
        val cxt = KdrCxt.mkSimpleCxt("t", c)
        EdgeComponent().applyInstanceConfig(cxt)
        val e = shouldThrow<KdrException> { EdgeService().checkContextRoots(cxt) }
        e.fullMessage() shouldContain ContextRoot.kda
        e.fullMessage() shouldContain "ambiguous"
    }

    "every application root is caught, not just the api one" {
        for (clash in listOf(ContextRoot.cp, ContextRoot.wa, ContextRoot.st)) {
            val c = config().apply { put(ACFG.contentContextRoot, clash) }
            val cxt = KdrCxt.mkSimpleCxt("t", c)
            EdgeComponent().applyInstanceConfig(cxt)
            shouldThrow<KdrException> { EdgeService().checkContextRoots(cxt) }
        }
    }

    "the edge's own roots pass the check" {
        val c = config()
        val cxt = KdrCxt.mkSimpleCxt("t", c)
        EdgeComponent().applyInstanceConfig(cxt)
        EdgeService().checkContextRoots(cxt) // does not throw
    }
})
