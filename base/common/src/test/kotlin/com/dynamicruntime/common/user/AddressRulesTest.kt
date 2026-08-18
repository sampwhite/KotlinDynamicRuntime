package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * What a controlled address says about the user it creates (issue #352).
 *
 * Parsing, which is nearly all of this issue's risk: every later decision reads its answer, and an address is
 * supplied by whoever is registering. The two halves are tested apart on purpose -- [AddressRules.readTags]
 * says what a local part names, [AddressRules.isControlledDomain] says whether anyone should be listening --
 * because the interesting mistakes are in the second and would hide inside the first.
 */
class AddressRulesTest : StringSpec({

    fun cxtIn(env: String, adminDomain: String? = null): KdrCxt {
        val config = KdrInstanceConfig("addr-$env-$adminDomain", env, ENV.liveSource)
        adminDomain?.let { config.put(ACFG.adminEmailDomain, it) }
        return KdrCxt("addr", config)
    }

    fun tags(address: String) = AddressRules.readTags(address)

    // --- what a local part names -----------------------------------------------

    "an address with no plus tag names nothing" {
        tags("user1@example.com").clientId shouldBe null
        tags("user1@example.com").persona shouldBe null
    }

    "a plus tag names a client" {
        tags("user1+acme@example.com").clientId shouldBe "acme"
        tags("user1+acme@example.com").persona shouldBe null
    }

    // The reverse is the intuitive reading and it is wrong, which is why it has a test of its own.
    "a client with no persona is an ordinary user" {
        tags("user1+acme@example.com").persona shouldBe null
    }

    "a percent suffix names a persona within the client" {
        val t = tags("user1+hub%admin@example.com")
        t.clientId shouldBe "hub"
        t.persona shouldBe "admin"
    }

    // The client id is read as far as it can be rather than refused, so a tag that carries something else
    // after it still names its client.
    "the client id ends at the first character a client id cannot hold" {
        tags("user1+acme#featureX@example.com").clientId shouldBe "acme"
        tags("user1+acme#featureX@example.com").persona shouldBe null
    }

    "a persona ends the same way" {
        val t = tags("user1+acme%admin#featureX@example.com")
        t.clientId shouldBe "acme"
        t.persona shouldBe "admin"
    }

    "a bare plus names nothing" {
        tags("user1+@example.com").clientId shouldBe null
    }

    "a tag that could not start a client id names nothing" {
        tags("user1+1acme@example.com").clientId shouldBe null
        tags("user1+%admin@example.com").clientId shouldBe null
    }

    // A persona is a suffix on a client id, so there is nowhere for one to appear without the other -- and
    // `AddressTags` cannot represent it either.
    "a persona without a client is not reachable" {
        tags("user1+%admin@example.com").persona shouldBe null
    }

    "an underscore may start a client id" {
        tags("user1+_internal@example.com").clientId shouldBe "_internal"
    }

    "a trailing percent with nothing after it names no persona" {
        val t = tags("user1+acme%@example.com")
        t.clientId shouldBe "acme"
        t.persona shouldBe null
    }

    // The address is split on the *last* `@`, so a local part carrying one cannot smuggle a domain in.
    "the domain is taken from the last at sign" {
        AddressRules.isControlledDomain(cxtIn(ENV.local), "user1+acme@evil.test@example.com") shouldBe true
        AddressRules.isControlledDomain(cxtIn(ENV.local), "user1+acme@example.com@evil.test") shouldBe false
    }

    // --- whether anybody should be listening ------------------------------------

    "the example domain is controlled outside production" {
        AddressRules.isControlledDomain(cxtIn(ENV.local), "user1@example.com") shouldBe true
        AddressRules.isControlledDomain(cxtIn(ENV.unit), "user1@example.com") shouldBe true
    }

    // An address nobody can receive mail at should not name a client on a deployment that has real ones.
    "the example domain is not controlled in production" {
        AddressRules.isControlledDomain(cxtIn(ENV.prod), "user1@example.com") shouldBe false
        AddressRules.tagsFor(cxtIn(ENV.prod), "user1+acme@example.com").clientId shouldBe null
    }

    "the configured admin domain is controlled, in production too" {
        AddressRules.isControlledDomain(cxtIn(ENV.prod, "acme.com"), "user1@acme.com") shouldBe true
        AddressRules.tagsFor(cxtIn(ENV.prod, "acme.com"), "user1+beta@acme.com").clientId shouldBe "beta"
    }

    "a subdomain of the admin domain is controlled" {
        AddressRules.isControlledDomain(cxtIn(ENV.local, "acme.com"), "user1@mail.acme.com") shouldBe true
    }

    // The mistake that would otherwise sell a choice of client for the price of a domain registration.
    "a domain merely ending in the admin domain is not controlled" {
        AddressRules.isControlledDomain(cxtIn(ENV.local, "acme.com"), "user1@notacme.com") shouldBe false
    }

    "an ordinary domain reads no tags at all" {
        val cxt = cxtIn(ENV.local, "acme.com")
        AddressRules.isControlledDomain(cxt, "user1@gmail.com") shouldBe false
        AddressRules.tagsFor(cxt, "user1+acme%admin@gmail.com").clientId shouldBe null
        AddressRules.tagsFor(cxt, "user1+acme%admin@gmail.com").persona shouldBe null
    }

    "an address with no domain names nothing" {
        AddressRules.isControlledDomain(cxtIn(ENV.local), "user1") shouldBe false
        tags("user1+acme").clientId shouldBe null
    }
})
