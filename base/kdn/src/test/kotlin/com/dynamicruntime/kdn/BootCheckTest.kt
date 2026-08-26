package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.sql.DbEnv
import com.dynamicruntime.common.sql.SqlSchemaDrift
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.allowOverride
import com.dynamicruntime.common.startup.bootCheckMode
import com.dynamicruntime.common.startup.modeOverride
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The boot-check registry and the one operator endpoint that reads it (issue #303).
 *
 * The assertions worth reading are the ones about a **clean** check. That a broken thing is reported is the
 * obvious half and fails loudly; that a check which ran and found nothing is still *present* is the half the
 * report exists for, and it fails silently — a node where the check never executed and a node where it
 * executed and was happy look identical the moment the registry only records problems.
 */
class BootCheckTest : StringSpec({

    fun cxtIn(env: String, name: String): KdrCxt =
        KdrCxt("bootCheck", KdrInstanceConfig(name, env, ENV.liveSource))

    // --- the mode, which is per check and used to be written twice ---------------------------------------

    "each check keeps its own answer for production, and the same one everywhere else" {
        // The pair the registry has to express rather than flatten. A broken fragment is a defect on the side,
        // so production serves; drift that makes every insert fail is the node unable to do its job, so
        // production refuses. Outside production both are strict, which is the part that was duplicated.
        val prod = cxtIn(ENV.prod, "bootCheckProd")
        bootCheckMode(prod, null, prodMode = BootCheckMode.warn) shouldBe BootCheckMode.warn
        bootCheckMode(prod, null, prodMode = BootCheckMode.strict) shouldBe BootCheckMode.strict

        val dev = cxtIn(ENV.dev, "bootCheckDev")
        bootCheckMode(dev, null, prodMode = BootCheckMode.warn) shouldBe BootCheckMode.strict
        bootCheckMode(dev, null, prodMode = BootCheckMode.strict) shouldBe BootCheckMode.strict
    }

    "a mode word overrides the environment, in either direction" {
        val prod = cxtIn(ENV.prod, "bootCheckWord")
        prod.instanceConfig.put(FRAG.checkEnvVar, BootCheckMode.strict.name)
        bootCheckMode(prod, modeOverride(prod, FRAG.checkEnvVar), BootCheckMode.warn) shouldBe BootCheckMode.strict

        val dev = cxtIn(ENV.dev, "bootCheckWordDev")
        dev.instanceConfig.put(FRAG.checkEnvVar, BootCheckMode.off.name)
        bootCheckMode(dev, modeOverride(dev, FRAG.checkEnvVar), BootCheckMode.warn) shouldBe BootCheckMode.off
    }

    "an unrecognized mode word leaves the environment default rather than guessing" {
        val dev = cxtIn(ENV.dev, "bootCheckJunk")
        dev.instanceConfig.put(FRAG.checkEnvVar, "sometimes")
        modeOverride(dev, FRAG.checkEnvVar).shouldBeNull()
        bootCheckMode(dev, modeOverride(dev, FRAG.checkEnvVar), BootCheckMode.warn) shouldBe BootCheckMode.strict
    }

    "an allow-flag reads as permission, not as a mode word" {
        // `KDR_ALLOW_SCHEMA_DRIFT=true` says *allow*, which is `warn`. Spelling it as a mode word would be a
        // worse name for the same thing, so the two spellings coexist and only the default is shared.
        val prod = cxtIn(ENV.prod, "bootCheckAllow")
        prod.instanceConfig.put(DbEnv.allowSchemaDrift, "true")
        SqlSchemaDrift.driftMode(prod) shouldBe BootCheckMode.warn

        prod.instanceConfig.put(DbEnv.allowSchemaDrift, "false")
        SqlSchemaDrift.driftMode(prod) shouldBe BootCheckMode.strict
        // Unset is the check's own production policy, which for drift is to refuse.
        SqlSchemaDrift.driftMode(cxtIn(ENV.prod, "bootCheckAllowUnset")) shouldBe BootCheckMode.strict
    }

    // --- the registry -----------------------------------------------------------------------------------

    "findings accumulate under one name, because a check does not always run once" {
        // Schema drift is checked per table, so its entry is built across many calls rather than written once.
        val cxt = cxtIn(ENV.dev, "bootCheckAccumulate")
        val registry = BootCheckRegistry.get(cxt)
        registry.record("demo", "KDR_DEMO", BootCheckMode.warn)
        registry.record("demo", "KDR_DEMO", BootCheckMode.warn, listOf("first"))
        registry.record("demo", "KDR_DEMO", BootCheckMode.warn, listOf("second"))

        val demo = registry.results().single()
        demo.name shouldBe "demo"
        demo.findings shouldBe listOf("first", "second")
    }

    "the registry is one per instance, whoever asks for it first" {
        // Created on demand rather than by a startup service: drift runs while tables are reconciled, which is
        // earlier than any service ordering would guarantee a registry existed.
        val cxt = cxtIn(ENV.dev, "bootCheckShared")
        BootCheckRegistry.get(cxt).record("demo", "KDR_DEMO", BootCheckMode.warn, listOf("seen"))
        BootCheckRegistry.get(cxt).results().single().findings shouldBe listOf("seen")
    }

    // --- the endpoint -----------------------------------------------------------------------------------

    val cxt = Startup.mkTestBootCxt("bootChecks", "bootChecksTest")

    "a booted node reports the checks that ran, clean ones included" {
        val opal = TestUser.create(cxt, "boot-ops@example.com", level = ROLE.operator)
        val checks = opal.getItems("/operator/boot/checks")
        val names = checks.mapNotNull { it[BCHK.name].toOptStr() }

        // All three members of the paradigm are present on an ordinary boot -- which is the count that made
        // the registry worth building: three hand-rolled copies of the same asymmetry.
        names shouldContain BCHK.fragments
        names shouldContain BCHK.schemaDrift
        names shouldContain BCHK.gedraConfig
        // ...and this instance is healthy, so each says so by carrying no findings. An empty `findings` is a
        // claim ("checked, nothing wrong"); an absent entry would be a different claim entirely.
        for (check in checks) {
            check[BCHK.findings].toJsonListOfStrings().shouldBeEmpty()
        }
    }

    "each check reports the mode it resolved to and the variable that overrides it" {
        val opal = TestUser.create(cxt, "boot-ops2@example.com", level = ROLE.operator)
        val byName = opal.getItems("/operator/boot/checks").associateBy { it[BCHK.name].toOptStr() }

        // A unit instance is not production, so both are strict here -- which is what makes the *envVar* the
        // useful half of the report: it is how an operator changes the answer without reading the source.
        byName.getValue(BCHK.fragments)[BCHK.mode] shouldBe BootCheckMode.strict.name
        byName.getValue(BCHK.fragments)[BCHK.envVar] shouldBe FRAG.checkEnvVar
        byName.getValue(BCHK.schemaDrift)[BCHK.envVar] shouldBe DbEnv.allowSchemaDrift
        byName.getValue(BCHK.gedraConfig)[BCHK.envVar] shouldBe GCFG.checkEnvVar
    }

    "a degraded node says so, which is the whole reason the endpoint exists" {
        // The case a production node reaches and a boot log cannot report: something was let through, and an
        // operator arriving later has no other way to find out. Recorded directly because a *real* finding
        // needs a broken deployment to manufacture, and what is under test here is that the endpoint reads
        // the live registry rather than a snapshot taken when the schema was built.
        //
        // Its **own instance**, because writing a finding into a registry is exactly the kind of thing the
        // blocks above would then have to run before. They do today, by declaration order, and depending on
        // that would make reordering them a silent failure rather than a compile error.
        val degraded = Startup.mkTestBootCxt("bootChecksDegraded", "bootChecksDegradedTest")
        BootCheckRegistry.get(degraded).record(
            BCHK.fragments,
            FRAG.checkEnvVar,
            BootCheckMode.warn,
            listOf("'demo' is declared but absent"),
        )
        val opal = TestUser.create(degraded, "boot-ops3@example.com", level = ROLE.operator)
        val fragments = opal.getItems("/operator/boot/checks")
            .single { it[BCHK.name].toOptStr() == BCHK.fragments }

        fragments[BCHK.mode] shouldBe BootCheckMode.warn.name
        fragments[BCHK.findings].toJsonListOfStrings() shouldContain "'demo' is declared but absent"
    }

    "the report is behind the operator gate" {
        // It names environment variables, table internals and file positions -- the same reasoning that puts
        // `/operator/db/tables` and `/operator/fragments/check` here.
        TestUser.create(cxt, "boot-user@example.com").expectError(EXC.notAuthorized, "/operator/boot/checks")
    }
})
