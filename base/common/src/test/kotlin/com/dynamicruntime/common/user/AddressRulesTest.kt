package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * What a controlled address says about the user it creates (issue #352): only whether it is one of the
 * deployment's own, since the `+client%persona` tags were retired (issue #750). The interesting mistakes are
 * in the domain check -- an address is supplied by whoever is registering -- so that is what is exercised.
 */
class AddressRulesTest : StringSpec({

    fun cxtIn(env: String, adminDomain: String? = null): KdrCxt {
        val config = KdrInstanceConfig("addr-$env-$adminDomain", env, ENV.liveSource)
        adminDomain?.let { config.put(ACFG.adminEmailDomain, it) }
        return KdrCxt("addr", config)
    }

    // --- whether anybody should be listening ------------------------------------

    "the example domain is controlled outside production" {
        AddressRules.isControlledDomain(cxtIn(ENV.local), "user1@example.com") shouldBe true
        AddressRules.isControlledDomain(cxtIn(ENV.unit), "user1@example.com") shouldBe true
    }

    // An address nobody can receive mail at should not name a client on a deployment that has real ones.
    "the example domain is not controlled in production" {
        AddressRules.isControlledDomain(cxtIn(ENV.prod), "user1@example.com") shouldBe false
    }

    "the configured admin domain is controlled, in production too" {
        AddressRules.isControlledDomain(cxtIn(ENV.prod, "acme.com"), "user1@acme.com") shouldBe true
    }

    "a subdomain of the admin domain is controlled" {
        AddressRules.isControlledDomain(cxtIn(ENV.local, "acme.com"), "user1@mail.acme.com") shouldBe true
    }

    // The mistake that would otherwise sell a choice of client for the price of a domain registration.
    "a domain merely ending in the admin domain is not controlled" {
        AddressRules.isControlledDomain(cxtIn(ENV.local, "acme.com"), "user1@notacme.com") shouldBe false
    }

    "an ordinary domain is not controlled" {
        AddressRules.isControlledDomain(cxtIn(ENV.local, "acme.com"), "user1@gmail.com") shouldBe false
    }

    "an address with no domain is not controlled" {
        AddressRules.isControlledDomain(cxtIn(ENV.local), "user1") shouldBe false
    }

    "a self-registered user lands in the placeholder client" {
        AddressRules.defaultClient(cxtIn(ENV.local)) shouldBe CL.public
    }

    // --- whether Google is authoritative for the perimeter (issue #429) ---------

    "the hosted-domain claim is authoritative only when it equals the configured admin domain" {
        val cxt = cxtIn(ENV.local, "acme.com")
        AddressRules.isGoogleAuthoritative(cxt, "acme.com") shouldBe true
        AddressRules.isGoogleAuthoritative(cxt, "ACME.com") shouldBe true // normalized the same way the domain is
        AddressRules.isGoogleAuthoritative(cxt, "evil.com") shouldBe false // present-but-different must fail
    }

    // The case the whole fix exists for: a Google consumer account carries no hd, so there is nothing to match
    // even though its address is verified and on the domain.
    "an absent or empty hosted domain is never authoritative" {
        AddressRules.isGoogleAuthoritative(cxtIn(ENV.local, "acme.com"), null) shouldBe false
        AddressRules.isGoogleAuthoritative(cxtIn(ENV.local, "acme.com"), "  ") shouldBe false
    }

    // Exactness, unlike isControlledDomain's subdomain latitude for addresses: a perimeter wants the tighter rule.
    "a subdomain of the admin domain is not an authoritative hosted domain" {
        AddressRules.isControlledDomain(cxtIn(ENV.local, "acme.com"), "user1@eu.acme.com") shouldBe true
        AddressRules.isGoogleAuthoritative(cxtIn(ENV.local, "acme.com"), "eu.acme.com") shouldBe false
    }

    // Fail closed: neither an unset nor a Gmail-hosted admin domain can name a Workspace whose hd could match.
    "with no admin domain, or a gmail one, nothing is authoritative" {
        AddressRules.isGoogleAuthoritative(cxtIn(ENV.local), "acme.com") shouldBe false
        AddressRules.isGoogleAuthoritative(cxtIn(ENV.local, "gmail.com"), "gmail.com") shouldBe false
        AddressRules.isGoogleAuthoritative(cxtIn(ENV.local, "googlemail.com"), "googlemail.com") shouldBe false
    }
})
