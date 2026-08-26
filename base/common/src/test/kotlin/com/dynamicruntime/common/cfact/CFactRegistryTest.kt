package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The cfact registry: who may declare a name, what a client may do to one, and what a scope assembles
 * (issue #455).
 *
 * The assertions worth reading are the **refusals**. A registry that accepts everything still serves a
 * plausible listing and still parses every expression -- it just stops being the thing that turns a typo into
 * a refused boot, which is the only reason it exists. So a duplicate, a client redefinition and an
 * undeclared target fact are each pinned here rather than left to a consumer to discover.
 */
class CFactRegistryTest : StringSpec({

    fun def(name: String, group: String = "Test") = CFactDef(name, group, "True when $name.")

    fun cxtFor(roles: Set<String> = emptySet(), loggedIn: Boolean = false): KdrCxt {
        val cxt = KdrCxt("cfact", KdrInstanceConfig("cfactTest", ENV.unit, ENV.liveSource))
        cxt.userProfile = UserProfile(authId = if (loggedIn) "someone" else null, roles = roles)
        return cxt
    }

    // --- declaring ---------------------------------------------------------------------------------------

    "a name an expression could never write is refused at declaration" {
        // The parser reads a name off an expression by its characters, so a declaration it cannot spell would
        // register and then be unreferenceable -- found only by whoever tried to use it.
        shouldThrow<KdrException> { def("has a space") }.fullMessage() shouldContain "cannot be a cfact name"
        shouldThrow<KdrException> { def("#always") }.fullMessage() shouldContain "cannot be a cfact name"
        shouldThrow<KdrException> { def("") }.fullMessage() shouldContain "cannot be a cfact name"
        // A name that merely *reads* like a literal is fine: the sigil is what marks one, not the word.
        def("never").name shouldBe "never"
    }

    "a client may add a name" {
        val registries = buildCFactRegistries(
            global = mapOf("app" to def("app")),
            sources = emptyMap(),
            perClient = mapOf("acme" to listOf(def("acmeUnderAudit"))),
        )
        // Its own on top of everybody's, which is what keeps a shared expression parsing here.
        registries.forClient("acme").names shouldBe setOf("app", "acmeUnderAudit")
        // ...and invisible to everyone else, including the client that declared nothing.
        registries.forClient("globex").names shouldBe setOf("app")
        registries.forClient(null).names shouldBe setOf("app")
    }

    "a client may not redefine a name a component declared" {
        // The failure this prevents: an expression in shared data meaning one thing everywhere and another
        // here -- discovered by the one client it is wrong for.
        val e = shouldThrow<KdrException> {
            buildCFactRegistries(
                global = mapOf("loggedIn" to def("loggedIn", "Caller")),
                sources = emptyMap(),
                perClient = mapOf("acme" to listOf(def("loggedIn", "Acme"))),
            )
        }
        e.fullMessage() shouldContain "redeclares the cfact 'loggedIn'"
        e.fullMessage() shouldContain "never redefine"
    }

    "two clients may take the same name, and that is deliberate" {
        // Refusing this would fail one client's boot because of what *another* client declared, which leaks:
        // a client could learn what its neighbors are building by trying names until one was refused. The two
        // registries cannot see each other, so nothing an expression parses against is ambiguous. Pinned here
        // because it looks exactly like a check somebody forgot.
        val registries = buildCFactRegistries(
            global = emptyMap(),
            sources = emptyMap(),
            perClient = mapOf(
                "acme" to listOf(CFactDef("underAudit", "Site audits", "True while acme has an audit open.")),
                "globex" to listOf(CFactDef("underAudit", "Compliance", "True while globex is under review.")),
            ),
        )
        registries.forClient("acme").defs.getValue("underAudit").group shouldBe "Site audits"
        registries.forClient("globex").defs.getValue("underAudit").group shouldBe "Compliance"
    }

    "the built registry does not follow the collection it was built from" {
        // What arrives is what a collector accumulated and goes on owning. A registry that aliased it would
        // be a way round every check above -- a name added afterward would never be held to "add, never
        // redefine" -- and an expression parsed against `names` would have the set move under it.
        val collected = linkedMapOf("app" to def("app"))
        val registries = buildCFactRegistries(collected, emptyMap(), emptyMap())
        collected["addedLater"] = def("addedLater")
        registries.global.names shouldBe setOf("app")
    }

    "a client declaring one name twice is reported, not collapsed" {
        val e = shouldThrow<KdrException> {
            buildCFactRegistries(
                global = emptyMap(),
                sources = emptyMap(),
                perClient = mapOf("acme" to listOf(def("audit", "First"), def("audit", "Second"))),
            )
        }
        e.fullMessage() shouldContain "declares the cfact 'audit' twice"
    }

    "every problem is reported in one boot" {
        // Somebody fixing a client's declarations should see all of them at once rather than one per attempt.
        val e = shouldThrow<KdrException> {
            buildCFactRegistries(
                global = mapOf("app" to def("app")),
                sources = emptyMap(),
                perClient = mapOf(
                    "acme" to listOf(def("app"), def("dup"), def("dup")),
                    "globex" to listOf(def("app")),
                ),
            )
        }
        e.fullMessage() shouldContain "3 problem(s)"
    }

    // --- assembling --------------------------------------------------------------------------------------

    val registry = CFactRegistry(
        defs = mapOf(
            "loggedIn" to def("loggedIn"),
            "isAdmin" to def("isAdmin"),
            "underAudit" to def("underAudit"),
        ),
        // `underAudit` deliberately has none: a declared name nothing produces is the ordinary shape of a
        // client's declaration, and it has to be legal to write before anything can produce it.
        sources = mapOf<String, CFactSource>(
            "loggedIn" to CFactSource { it.userProfile.isLoggedIn },
            "isAdmin" to CFactSource { it.userProfile.roles.contains(ROLE.admin) },
        ),
    )

    "a scope assembles the cfacts its sources find present" {
        registry.assemble(cxtFor()) shouldBe emptySet()
        registry.assemble(cxtFor(loggedIn = true)) shouldBe setOf("loggedIn")
        registry.assemble(cxtFor(roles = setOf(ROLE.admin), loggedIn = true)) shouldBe setOf("loggedIn", "isAdmin")
    }

    "facts about a target join the request's own" {
        registry.assemble(cxtFor(loggedIn = true), setOf("underAudit")) shouldBe setOf("loggedIn", "underAudit")
    }

    "an undeclared target fact is refused rather than dropped" {
        // It comes from a component's own code, so an unknown one is a mistake -- and dropping it silently
        // would show or hide something with no word said anywhere.
        val e = shouldThrow<KdrException> { registry.assemble(cxtFor(), setOf("undrAudit")) }
        e.fullMessage() shouldContain "undrAudit"
        e.fullMessage() shouldContain "not declared here"
    }

    "the registry is what an expression parses against" {
        // The whole point of the vocabulary being closed: a mistyped negation would otherwise name a cfact
        // that is never present, and so be always true.
        registry.parse("loggedIn,~isAdmin").matches(setOf("loggedIn")) shouldBe true
        shouldThrow<KdrException> { registry.parse("~isAdmn") }.fullMessage() shouldContain "not a registered cfact"
        // An omitted expression is "always", which is what a default case with no condition relies on.
        registry.parse(null).render() shouldBe CFACT.alwaysName
    }
})
