package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientService

/** Constants for the address conventions (issue #352). */
@Suppress("ConstPropertyName")
object ADR {
    /** Introduces a persona, and is one of the characters that ends a client id. */
    const val personaChar = '%'

    /**
     * The domain reserved for examples, which is controlled everywhere but production.
     *
     * Reserved by RFC 2606 precisely so that nobody can own it, which is what makes it safe to treat as ours.
     * Excluded in production for the same reason it is safe elsewhere: an address nobody can receive mail at
     * should not be able to name a client on a deployment that has real ones.
     */
    const val exampleDomain = "example.com"
}

/**
 * What a **controlled** address says about the user it creates: which client, and which persona (issue #352).
 *
 * Two domains are controlled -- the deployment's configured admin domain, and [ADR.exampleDomain] outside
 * production -- and on those two the local part's `+` tag stops being an arbitrary label and starts carrying
 * meaning: `user1+acme%admin@example.com` is an `admin` of `acme`. Anywhere else a `+` tag means nothing at
 * all, exactly as it did before.
 *
 * This is the parsing half only. What a persona *grants* is [AdminRules]' business, which is the division the
 * design draws: an address can say who somebody is, and only the rules say what that is worth. It is also why
 * a persona is deliberately not a role -- whatever the vocabulary grows into, an address can never mint a
 * caller with global scope.
 *
 * **This supersedes the rule where a `+` tag disqualified an address from auto-admin**, and the inversion is
 * the thing to be careful about: an account deliberately created as a non-admin under the old rule reads as a
 * client assignment under the new one.
 */
object AddressRules {
    /**
     * The client and persona [address] names, both null when it names neither or is not controlled.
     *
     * The domain check comes first, so an ordinary user at an ordinary domain can plus-address as freely as
     * they always could and nothing here looks at it.
     */
    fun tagsFor(cxt: KdrCxt, address: String): AddressTags {
        if (!isControlledDomain(cxt, address)) {
            return AddressTags.none
        }
        return readTags(address)
    }

    /**
     * The client a user created with [address] belongs to.
     *
     * A named client this node does not carry falls back to [CL.public], logging a warning and otherwise
     * staying silent. Deliberately lenient, and the opposite of what the fixture does with an explicit client:
     * there is a person on the other end of a registration who cannot be told to fix their address, and
     * refusing the account teaches them nothing. Marked in the design as likely to evolve.
     *
     * The check is *present*, not *known*: a client this deployment declares but does not enable here is one
     * whose users cannot get in, so putting somebody in it would be creating an account that does not work.
     *
     * This is the one place the `user` package reaches into `gedra`, for `ClientService`. The registry is
     * foundational enough that user creation depending on it reads the right way round; keeping it to a single
     * file is what stops that being an argument anybody has to have again.
     */
    fun clientForNewUser(cxt: KdrCxt, address: String): String {
        val named = tagsFor(cxt, address).clientId ?: return CL.public
        val clients = ClientService.get(cxt)
        if (clients != null && clients.isPresent(named)) {
            return named
        }
        LogAuth.warn(cxt) {
            "Address '$address' names the client '$named', which this node does not carry; " +
                "creating the user in '${CL.public}' instead."
        }
        return CL.public
    }

    /**
     * Whether [address] sits on a domain whose `+` tags this deployment reads.
     *
     * Matched against the address's **domain part only**, and against a subdomain of it, exactly as
     * [AdminRules.isAutoAdminAddress] does -- a bare suffix test over the whole address would make
     * `notacme.com` match a configured `acme.com`, which is how somebody buys an admin account for the price
     * of a domain registration. The same mistake would here buy a choice of client.
     */
    fun isControlledDomain(cxt: KdrCxt, address: String): Boolean {
        val domain = domainOf(address) ?: return false
        AdminRules.adminEmailDomain(cxt.instanceConfig)?.let {
            if (matches(domain, it)) {
                return true
            }
        }
        return cxt.instanceConfig.env != ENV.prod && matches(domain, ADR.exampleDomain)
    }

    /**
     * Reads the tags out of [address] **without** checking the domain -- the parsing on its own, which is what
     * a test of the conventions wants to exercise. Callers deciding anything want [tagsFor].
     *
     * The client id is read up to the first character a client id could not hold, so
     * `user1+acme#featureX@example.com` names `acme` and nothing is refused for the rest. A persona is read
     * only when that terminating character is [ADR.personaChar], because a persona is defined as a suffix on
     * the client id rather than a tag of its own.
     */
    fun readTags(address: String): AddressTags {
        val at = address.lastIndexOf(ADMR.atChar)
        val local = if (at <= 0) return AddressTags.none else address.substring(0, at)
        val plus = local.indexOf(ADMR.plusAddressChar)
        if (plus < 0) {
            return AddressTags.none
        }
        val tag = local.substring(plus + 1)
        val idEnd = idEndIn(tag)
        // Empty covers both a bare `+` and a tag opening with something a client id cannot start with, which
        // are the same answer: this address names no client, whatever else it may be doing.
        if (idEnd == 0) {
            return AddressTags.none
        }
        val clientId = tag.substring(0, idEnd)
        if (idEnd >= tag.length || tag[idEnd] != ADR.personaChar) {
            return AddressTags(clientId, null)
        }
        val personaTag = tag.substring(idEnd + 1)
        val personaEnd = idEndIn(personaTag)
        return AddressTags(clientId, if (personaEnd == 0) null else personaTag.substring(0, personaEnd))
    }

    /** [address]'s domain part, lower-cased, or null when it has no usable one. */
    private fun domainOf(address: String): String? {
        val trimmed = address.trim().lowercase()
        val at = trimmed.lastIndexOf(ADMR.atChar)
        if (at <= 0 || at == trimmed.length - 1) {
            return null
        }
        return trimmed.substring(at + 1)
    }

    private fun matches(domain: String, controlled: String): Boolean =
        domain == controlled || domain.endsWith(ADMR.domainSep + controlled)

    /**
     * How far into [tag] a legal client id runs: zero when it does not start with one.
     *
     * The same charset `GedraId` holds a client to, spelled out here rather than shared, because the two are
     * answering different questions -- that one refuses a bad id, this one finds where a good one stops. ASCII
     * on purpose, as there: a client id admitting Cyrillic lookalikes would be a way to write two addresses
     * that read identically to a person and name different clients.
     */
    private fun idEndIn(tag: String): Int {
        if (tag.isEmpty() || !(tag[0].isAsciiLetter() || tag[0] == '_')) {
            return 0
        }
        var i = 1
        while (i < tag.length && (tag[i].isAsciiLetter() || tag[i] in '0'..'9' || tag[i] == '_')) {
            i++
        }
        return i
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}

/**
 * What a controlled address named: a client, and a persona within it. Both absent is the ordinary case.
 *
 * A persona without a client is not representable, which is the shape the conventions actually have -- the
 * persona is read as a suffix on the client id, so there is nowhere for one to appear without the other.
 */
class AddressTags(
    /** The client this address names, or null when it names none. */
    val clientId: String?,
    /**
     * The persona this address names within [clientId], or null for an ordinary user.
     *
     * **A client with no persona is an ordinary user** -- `user1+acme@example.com` is a normal user of `acme`,
     * not an administrator of it. Worth saying because the reverse is the intuitive reading and it is wrong.
     */
    val persona: String?,
) {
    override fun toString(): String = "client=$clientId, persona=$persona"

    companion object {
        /** What an address that names neither yields; shared, since it is by far the common answer. */
        val none: AddressTags = AddressTags(null, null)
    }
}
