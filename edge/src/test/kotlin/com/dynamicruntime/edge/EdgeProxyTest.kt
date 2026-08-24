package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.request.ContextRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The forwarding rules that are invisible when they are wrong (issue #419).
 *
 * Both strips produce a perfectly working request when they fail -- for the wrong person -- so nothing
 * observes them except a test that looks. These are written against [EnvAuthForwarding] rather than a booted
 * proxy for that reason: the rule is worth asserting directly, not through a stack that could mask it.
 */
class EdgeProxyTest : StringSpec({

    val perimeter = ENVAUTH.cookie to "an-encrypted-perimeter-session"

    "the env-auth cookie never reaches the upstream" {
        val f = EnvAuthForwarding.forwarded(listOf(perimeter), "someone@gyassa.com")
        f.cookie shouldBe null
    }

    /**
     * The failure this guards is a *replayable perimeter credential*: the cookie is `Path=/` on a host shared
     * with the upstream, so a browser attaches it to every proxied path without being asked.
     */
    "the env-auth cookie is removed from among others, which survive" {
        val f = EnvAuthForwarding.forwarded(
            listOf("session" to "abc", perimeter, "theme" to "dark"),
            "someone@gyassa.com",
        )
        f.cookie shouldNotBe null
        f.cookie!! shouldNotContain ENVAUTH.cookie
        f.cookie shouldContain "session=abc"
        f.cookie shouldContain "theme=dark"
    }

    "an upstream's own cookies are not this edge's business" {
        val f = EnvAuthForwarding.forwarded(listOf("a" to "1", "b" to "2"), null)
        f.cookie shouldBe "a=1; b=2"
    }

    /**
     * The identity is whatever the edge resolved, and a null address must leave the header *absent* rather
     * than merely unset -- which is what makes the unconditional remove in `copyRequestHeaders` the whole
     * defense against a client sending its own.
     */
    "no resolved address means no identity header at all" {
        EnvAuthForwarding.forwarded(listOf("a" to "1"), null).envEmail shouldBe null
    }

    "the resolved address is what travels" {
        EnvAuthForwarding.forwarded(emptyList(), "someone@gyassa.com").envEmail shouldBe "someone@gyassa.com"
    }

    /**
     * The two root sets must stay disjoint. If they ever overlap, the front handler declines a root it also
     * forwards -- or forwards one the edge serves itself -- and the symptom is the wrong server answering
     * rather than an error, which is the failure mode the whole roots design exists to avoid.
     */
    "the roots an edge forwards and the roots it serves do not overlap" {
        EDGEUP.proxiedRoots.intersect(EdgeRoot.all.toSet()) shouldBe emptySet()
    }

    "what an edge forwards is the application's roots" {
        EDGEUP.proxiedRoots shouldBe setOf(ContextRoot.kda, ContextRoot.cp, ContextRoot.wa, ContextRoot.st)
    }

    // The default exists so a bare local edge finds the ordinary development application; an explicit
    // configuration is what a deployment (or a second workspace) uses instead.
    "the upstream comes from config, and falls back to the development application" {
        val c = KdrInstanceConfig("upstreamTest", ENV.unit, ENV.liveSource, BOOT.edge)
        upstreamFor(KdrCxt.mkSimpleCxt("t", c)) shouldBe EDGEUP.defaultUpstream
        c.put(ACFG.edgeUpstream, "http://elsewhere:9999")
        upstreamFor(KdrCxt.mkSimpleCxt("t", c)) shouldBe "http://elsewhere:9999"
    }
})
