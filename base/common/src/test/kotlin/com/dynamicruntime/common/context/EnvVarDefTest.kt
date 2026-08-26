package com.dynamicruntime.common.context

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * The declaration model (issue #371): an [EnvVarDef] resolves its value the way [KdrInstanceConfig.getEnvVar]
 * always did -- config over the process environment, role-prefixed name first under a boot role -- and
 * declaring one registers it, so the reference is complete by construction. A duplicate name is refused,
 * because two spellings of one variable is the drift this replaces.
 */
class EnvVarDefTest : StringSpec({

    fun config(bootRole: String? = null) = KdrInstanceConfig("envDefTest", ENV.local, ENV.liveSource, bootRole)

    // Declared once, at spec construction -- which registers it.
    val sample = EnvVarDef(
        "KDR_ENV_DEF_TEST", group = ENVGRP.application, defaultDoc = "unset", description = "A test variable.",
    )

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

    "declaring a second variable with the same name is refused" {
        shouldThrow<KdrException> {
            EnvVarDef("KDR_ENV_DEF_TEST", group = ENVGRP.application, defaultDoc = "x", description = "dup")
        }
    }
})
