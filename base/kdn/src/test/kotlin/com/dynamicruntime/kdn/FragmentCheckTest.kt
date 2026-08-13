package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FCHK
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Checking the Markdown fragment files an instance ships: the startup check, and the operator endpoint that
 * runs the same check against a **running** node.
 *
 * The endpoint is not a convenience. A production node deliberately only *warns* at boot over a broken
 * fragment -- refusing to serve every unrelated endpoint because one piece of copy is malformed is a worse
 * outcome than degrading that one message -- so without a way to ask a live node, the warning would be a log
 * line nobody reads. Somewhere a developer or test is running, the same defect stops the boot outright.
 */
class FragmentCheckTest : StringSpec({

    fun service(cxt: KdrCxt): MarkdownFragmentService =
        MarkdownFragmentService.get(cxt) ?: error("MarkdownFragmentService is required by this test.")

    // --- what the mode resolves to ---------------------------------------------

    /**
     * Keyed on the environment, not on `isTestInstance`: that flag is inferred from in-memory-ness and the
     * unit environment, so an ordinary local run against a real database is *not* a test instance -- and
     * keying on it would have handed a developer production behavior on their own machine.
     */
    "the check is strict outside prod and lenient in prod" {
        val cxt = Startup.mkTestBootCxt("fragMode", "fragModeTest")
        MarkdownFragmentService.fragmentCheckMode(cxt) shouldBe FRAG.strict

        // A prod-shaped config built directly: `env` is fixed at construction, and `mkTestBootCxt` forces
        // `unit`, so a booted test instance cannot be turned into a production one after the fact.
        val prodCxt = KdrCxt("fragProd", KdrInstanceConfig("fragProdTest", ENV.prod, ENV.deployed))
        MarkdownFragmentService.fragmentCheckMode(prodCxt) shouldBe FRAG.warn
    }

    "an explicit setting decides it either way" {
        val cxt = Startup.mkTestBootCxt("fragEnv", "fragEnvTest")
        cxt.instanceConfig.put(FRAG.checkEnvVar, FRAG.warn)
        MarkdownFragmentService.fragmentCheckMode(cxt) shouldBe FRAG.warn
        cxt.instanceConfig.put(FRAG.checkEnvVar, FRAG.off)
        MarkdownFragmentService.fragmentCheckMode(cxt) shouldBe FRAG.off
        // An unrecognized value falls back to the environment rule rather than silently disabling the check.
        cxt.instanceConfig.put(FRAG.checkEnvVar, "yes-please")
        MarkdownFragmentService.fragmentCheckMode(cxt) shouldBe FRAG.strict
    }

    // --- what the check finds ---------------------------------------------------

    /**
     * Every fragment file this instance ships is registered and clean. That the whole suite boots at all is
     * the stronger half of this assertion: tests run in `unit`, so the check is **strict**, and a malformed
     * fragment anywhere would stop every test in the repository rather than just this one.
     */
    "every registered fragment file is present and free of syntax problems" {
        val cxt = Startup.mkTestBootCxt("fragCheck", "fragCheckTest")
        val results = service(cxt).checkFragments(cxt)
        results.isNotEmpty() shouldBe true
        results.all { it.found } shouldBe true
        results.all { it.issues.isEmpty() } shouldBe true
        // The files with no UI-config to name them are covered too -- `errors` is the one you would otherwise
        // discover was broken during an incident.
        results.map { it.fileId }.contains(FRAG.errors) shouldBe true
    }

    "a declared file that is not there is reported as absent rather than clean" {
        val cxt = Startup.mkTestBootCxt("fragMissing", "fragMissingTest")
        val results = service(cxt).checkFragments(cxt, only = "no-such-fragment-file")
        results.size shouldBe 1
        results[0].found shouldBe false
        // Distinct from a clean file: both have no issues, only one of them exists.
        results[0].issues.isEmpty() shouldBe true
    }

    // --- the endpoint -----------------------------------------------------------

    "an operator can check the fragments of a running instance" {
        val cxt = Startup.mkTestBootCxt("fragEndpoint", "fragEndpointTest")
        val operator = TestUser.create(cxt, "frag-op@example.com", level = ROLE.operator)

        val items = operator.getItems("/operator/fragments/check")
        items.isNotEmpty() shouldBe true
        items.all { it[FCHK.found] == true } shouldBe true
        items.all { (it[FCHK.issueCount] as? Number)?.toInt() == 0 } shouldBe true

        // Narrowed to one file, so an author fixing a single fragment can ask about just that one.
        val one = operator.getItems("/operator/fragments/check", mapOf(FCHK.fileId to FRAG.errors))
        one.size shouldBe 1
        one[0][FCHK.fileId] shouldBe FRAG.errors
    }

    /** It reports copy internals and file positions, so it sits behind the operator gate like the other diagnostics. */
    "an ordinary user cannot reach the fragment check" {
        val cxt = Startup.mkTestBootCxt("fragGate", "fragGateTest")
        val plain = TestUser.create(cxt, "frag-plain@example.com")
        plain.expectError(EXC.notAuthorized, "/operator/fragments/check")
    }
})
