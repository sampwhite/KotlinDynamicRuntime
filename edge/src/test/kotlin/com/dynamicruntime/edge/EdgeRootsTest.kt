package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContextRoot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Coverage for the edge's context roots (issue #386): the values it binds, and the boot it refuses.
 *
 * Exercised against `EdgeService.checkInit` and `EdgeComponent.isLoaded` directly rather than through a boot --
 * both are pure functions of the instance config, and a full boot would register the component into the
 * process-wide `InstanceRegistry` for every later test in the module.
 */
class EdgeRootsTest : StringSpec({

    fun config(role: String? = EdgeRole.name) =
        KdrInstanceConfig("edgeRootsTest", ENV.unit, ENV.liveSource, role)

    "the component binds the edge's own roots" {
        val c = config()
        EdgeComponent().isLoaded(KdrCxt.mkSimpleCxt("t", c)) shouldBe true
        c.get(ACFG.apiContextRoot) shouldBe EdgeRoot.ea
        c.get(ACFG.contentContextRoot) shouldBe EdgeRoot.ec
        c.get(ACFG.appContextRoot) shouldBe EdgeRoot.ew
        c.get(ACFG.staticContextRoot) shouldBe EdgeRoot.es
    }

    // A deployment may still choose its own; these are what makes a node an edge, not a preference about it.
    "a deployment's explicit root wins over the default" {
        val c = config().apply { put(ACFG.apiContextRoot, "zz") }
        EdgeComponent().isLoaded(KdrCxt.mkSimpleCxt("t", c))
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
        EdgeComponent().isLoaded(cxt)
        val e = shouldThrow<KdrException> { EdgeService().checkInit(cxt) }
        e.fullMessage() shouldContain ContextRoot.kda
        e.fullMessage() shouldContain "ambiguous"
    }

    "every application root is caught, not just the api one" {
        for (clash in listOf(ContextRoot.cp, ContextRoot.wa, ContextRoot.st)) {
            val c = config().apply { put(ACFG.contentContextRoot, clash) }
            val cxt = KdrCxt.mkSimpleCxt("t", c)
            EdgeComponent().isLoaded(cxt)
            shouldThrow<KdrException> { EdgeService().checkInit(cxt) }
        }
    }

    "the edge's own roots pass the check" {
        val c = config()
        val cxt = KdrCxt.mkSimpleCxt("t", c)
        EdgeComponent().isLoaded(cxt)
        EdgeService().checkInit(cxt) // does not throw
    }
})
