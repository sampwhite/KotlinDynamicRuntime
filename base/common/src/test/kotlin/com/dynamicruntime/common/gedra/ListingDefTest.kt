package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A client's listing-wide settings (issue #1599): which of its configs' `listingDef` it runs with -- stored over code,
 * else the defaults -- and that one client's setting is never another's.
 */
class ListingDefTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("listingDef1599")

    fun config(name: String, client: String, origin: GedraConfigOrigin, layout: WorkflowLayout?) =
        gedraConfig(cxt, name, "client.$client", client, origin) { layout?.let { listing(ListingDef(it)) } }

    "a client runs its stored listing setting over its code's, the code's over the default, and only its own" {
        val code = config("acmeCode", "acme", GedraConfigOrigin.source, WorkflowLayout.combined)
        val stored = config("acmeStored", "acme", GedraConfigOrigin.stored, WorkflowLayout.split)
        val none = config("acmeOther", "acme", GedraConfigOrigin.stored, null)
        val globex = config("globexCode", "globex", GedraConfigOrigin.source, WorkflowLayout.combined)
        // Its code's alone, a stored config declaring none beside it.
        GedraConfigCollector().apply { listOf(code, none, globex).forEach { add(cxt, it) } }
            .listingFor("acme").workflowLayout shouldBe WorkflowLayout.combined
        // A stored one stands over it, whichever was added first.
        GedraConfigCollector().apply { listOf(code, stored).forEach { add(cxt, it) } }
            .listingFor("acme").workflowLayout shouldBe WorkflowLayout.split
        // A client declaring none has the defaults, never another client's.
        GedraConfigCollector().apply { listOf(globex, none).forEach { add(cxt, it) } }
            .listingFor("acme").workflowLayout shouldBe WorkflowLayout.split
    }

    "a config declares its listing settings once, and a sandbox's copy carries them" {
        val combined = config("acmeCode", "acme", GedraConfigOrigin.source, WorkflowLayout.combined)
        SandboxConfigs.rebind(combined).listing?.workflowLayout shouldBe WorkflowLayout.combined
        shouldThrow<KdrException> {
            gedraConfig(cxt, "twice", "client.acme", "acme") {
                listing(ListingDef(WorkflowLayout.split))
                listing(ListingDef(WorkflowLayout.combined))
            }
        }
    }
})
