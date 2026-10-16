package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.DirectAccess
import com.dynamicruntime.common.http.request.DirectAccessMode
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * A request that reached the node directly -- through no proxy, from where `KDR_DIRECT_ACCESS` allows -- reads the
 * operator surface without a login (issue #1255). That is what lets a command run on the node over SSH, such as
 * `kdr-node status`, be one `curl`. Through the real dispatcher, since the grant is the section gate's and the
 * catalog's, not any handler's. The setting and the header test themselves are in `DirectAccessTest`.
 *
 * Callers are anonymous throughout, and nothing here involves env auth: a direct request is a fact about the
 * channel, not about a person.
 */
class DirectOperatorReadsTest : StringSpec({

    // The default setting, `loopback`, on the shared default instance: these callers change nothing on it.
    val cxt = TestInstances.default("direct")

    // Private instances, each for a setting no shared entry has, and booted only by the test that needs it.
    fun withMode(mode: DirectAccessMode): KdrCxt = Startup.mkTestBootCxt(
        "direct-$mode", "kdnDirect-$mode", mapOf(DirectAccess.directAccessEnvVar.name to mode.name),
    )

    /** An anonymous caller whose requests come from [peer], as the server would report it. */
    fun caller(peer: String?, onCxt: KdrCxt = cxt): TestUser {
        val client = TestHttpClient(onCxt.instanceConfig)
        client.peerAddress = peer
        return TestUser(client, onCxt, emptyMap())
    }

    "an anonymous read from the node itself reaches the operator surface" {
        for (peer in listOf("127.0.0.1", "::1")) {
            val local = caller(peer)
            local.getData(OPS.systemInfoPath).isEmpty() shouldBe false
            local.getItems(OPS.bootChecksPath).isEmpty() shouldBe false
        }
    }

    "a write from the node itself still needs the roles" {
        caller("127.0.0.1").expectError(EXC.authNeeded, OPS.cacheReloadPath, data = emptyMap())
    }

    "no other section opens to a direct read" {
        val local = caller("127.0.0.1")
        local.expectError(EXC.authNeeded, ADEP.users)
        local.expectError(EXC.authNeeded, UADEP.users)
        local.expectError(EXC.authNeeded, CFD.cfactsPath)
        local.expectError(EXC.authNeeded, AEP.profileUiConfig)
    }

    "a read through a proxy, or from another machine, is refused" {
        val viaCaddy = caller("127.0.0.1")
        viaCaddy.client.setHeader("X-Forwarded-For", "203.0.113.7")
        viaCaddy.expectError(EXC.authNeeded, OPS.systemInfoPath)
        // What Jetty's proxy writes on its own.
        val viaJetty = caller("127.0.0.1")
        viaJetty.client.setHeader(DirectAccess.forwardedHeader, "for=203.0.113.7")
        viaJetty.expectError(EXC.authNeeded, OPS.systemInfoPath)

        caller("203.0.113.7").expectError(EXC.authNeeded, OPS.systemInfoPath)
        // A test-mode request carries no address unless the test gives one, so the suite is never direct by accident.
        caller(null).expectError(EXC.authNeeded, OPS.systemInfoPath)
    }

    "network admits a direct read from any address, and off admits none" {
        val network = withMode(DirectAccessMode.network)
        caller("10.0.0.5", network).getData(OPS.systemInfoPath).isEmpty() shouldBe false

        caller("127.0.0.1", withMode(DirectAccessMode.off)).expectError(EXC.authNeeded, OPS.systemInfoPath)
    }

    /**
     * The catalog asks the gate's question, so it lists a direct caller the operator reads and not the writes. On
     * the env-auth instance, because a caller without env auth is shown only the published API whatever they may
     * call (issues #489, #1128) -- a rule about publication, not access, which the operator surface is not part of.
     */
    "the catalog shows a direct caller the operator reads, and not the writes" {
        val envAuthed = TestInstances.envAuth("directCatalog")
        val listed = TestUser.pathsIn(caller("127.0.0.1", envAuthed).catalog())
        listed shouldContain OPS.systemInfoPath
        listed shouldNotContain OPS.cacheReloadPath

        // Still env-authed (no forwarded header), but not from this machine.
        TestUser.pathsIn(caller("203.0.113.7", envAuthed).catalog()) shouldNotContain OPS.systemInfoPath
    }
})
