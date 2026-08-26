package com.dynamicruntime.common.context

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * The one declared variable this test reads. Declared at **file scope**, not inside the spec body, so it is
 * constructed exactly once per classload (in the file initializer) and therefore registers exactly once --
 * independent of Kotest's isolation mode. Were it a spec-level `val`, an isolation mode that re-instantiates
 * the spec (InstancePerTest/PerLeaf) would re-run its initializer and the second construction of the same name
 * would throw "declared more than once" from a field initializer, failing spec init rather than a test.
 */
private val sample = EnvVarDef(
    "KDR_ENV_DEF_TEST", group = ENVGRP.application, defaultDoc = "unset", description = "A test variable.",
)

/**
 * The declaration model (issue #371): an [EnvVarDef] resolves its value the way [KdrInstanceConfig.getEnvVar]
 * always did -- config over the process environment, role-prefixed name first under a boot role -- and
 * declaring one registers it, so the reference is complete by construction. A duplicate name is refused,
 * because two spellings of one variable is the drift this replaces.
 */
class EnvVarDefTest : StringSpec({

    fun config(bootRole: String? = null) = KdrInstanceConfig("envDefTest", ENV.local, ENV.liveSource, bootRole)

    "a value in the instance config is read through the def, and unset reads null" {
        config().apply { put(sample.name, "hello") }.getEnvVar(sample) shouldBe "hello"
        config().getEnvVar(sample) shouldBe null
    }

    "getEnvBool parses loosely and falls through to null on an unrecognized value" {
        config().apply { put(sample.name, "yes") }.getEnvBool(sample) shouldBe true
        config().apply { put(sample.name, "0") }.getEnvBool(sample) shouldBe false
        config().apply { put(sample.name, "maybe") }.getEnvBool(sample) shouldBe null
    }

    "under a boot role the role-prefixed value wins, with the plain name as fallback" {
        config("edge").apply {
            put(sample.name, "plain")
            put("KDR_EDGE_ENV_DEF_TEST", "role")
        }.getEnvVar(sample) shouldBe "role"

        config("edge").apply { put(sample.name, "plain") }.getEnvVar(sample) shouldBe "plain"
    }

    "declaring a def registers it, discoverable by name and in the index" {
        EnvVarRegistry.forName(sample.name) shouldBe sample
        EnvVarRegistry.all() shouldContain sample
    }

    "resolveEnvVar reports the value, the exact key that supplied it, and the source" {
        val fromConfig = config().apply { put(sample.name, "v") }.resolveEnvVar(sample)
        fromConfig.value shouldBe "v"
        fromConfig.matchedName shouldBe sample.name
        fromConfig.source shouldBe EVSRC.config

        val unset = config().resolveEnvVar(sample)
        unset.value shouldBe null
        unset.matchedName shouldBe null
        unset.source shouldBe EVSRC.unset

        // An explicitly empty value is a set value, distinct from unset: resolution reports the raw truth (the
        // variable IS present), and it is the operator view -- not this layer -- that says most reads treat
        // empty as unset. Collapsing empty to null here would hide that a variable is set at all.
        val empty = config().apply { put(sample.name, "") }.resolveEnvVar(sample)
        empty.value shouldBe ""
        empty.source shouldBe EVSRC.config

        // Under a boot role the role-prefixed key is the one reported, not the plain name -- which is the fact
        // an operator needs to see when an edge and an app disagree about the same variable.
        val role = config("edge").apply { put("KDR_EDGE_ENV_DEF_TEST", "r") }.resolveEnvVar(sample)
        role.value shouldBe "r"
        role.matchedName shouldBe "KDR_EDGE_ENV_DEF_TEST"
        role.source shouldBe EVSRC.config
    }

    "declaring a second variable with the same name is refused" {
        // Built from `sample.name`, not the literal: referencing `sample` forces its (lazy, file-class)
        // registration to exist first, so this test cannot run against an empty registry in any order.
        shouldThrow<KdrException> {
            EnvVarDef(sample.name, group = ENVGRP.application, defaultDoc = "x", description = "dup")
        }
    }
})
