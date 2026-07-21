package com.dynamicruntime.common.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/** Covers the default-environment-variables file loading used by the pre-boot config (real environment wins). */
class KdrInstanceConfigTest : StringSpec({

    "readDefaultEnvVars keeps only keys not already defined in the environment" {
        val file = Files.createTempFile("kdr-default-env", ".properties").toFile()
        try {
            file.writeText("KDR_ENV=dev\nKDR_DB_NAME=featureX\nKDR_LOG_LEVEL=info\n")
            // Pretend KDR_ENV is already set in the real environment; the file value must not win for it.
            val env = mapOf("KDR_ENV" to "prod")
            val defaults = KdrInstanceConfig.readDefaultEnvVars(file) { env[it] }
            defaults shouldContainExactly mapOf("KDR_DB_NAME" to "featureX", "KDR_LOG_LEVEL" to "info")
        } finally {
            file.delete()
        }
    }

    "readDefaultEnvVars returns empty when the file is absent" {
        KdrInstanceConfig.readDefaultEnvVars(File("no-such-file.properties")) { null } shouldBe emptyMap()
    }

    // --- isTestInstance: the env var, the unit environment, or inMemoryOnly each makes it a test instance ----

    fun config(env: String) = KdrInstanceConfig("isTestInstance", env, ENV.liveSource)

    "the unit environment is always a test instance" {
        config(ENV.unit).isTestInstance shouldBe true
    }

    "inMemoryOnly makes a test instance, in any environment" {
        config(ENV.local).apply { put(ACFG.inMemoryOnly, true) }.isTestInstance shouldBe true
        config(ENV.dev).apply { put(ACFG.inMemoryOnly, true) }.isTestInstance shouldBe true
    }

    "the env var makes a test instance when set true" {
        config(ENV.local).apply { put(KdrInstanceConfig.testInstanceEnvVar, "true") }.isTestInstance shouldBe true
    }

    "not a test instance by default (no env var, not unit, not in-memory)" {
        config(ENV.local).isTestInstance shouldBe false
        config(ENV.dev).isTestInstance shouldBe false
    }

    // --- dotted keys are nested-map paths -------------------------------------

    "a dotted key builds and reads nested maps" {
        val config = KdrInstanceConfig.codeTest()
        config.put("node.internalIpAddressFilter", "127.0.0.1")
        config.get("node.internalIpAddressFilter") shouldBe "127.0.0.1"
        // Requesting the intermediate segment returns the sub-map.
        (config.get("node") as Map<*, *>)["internalIpAddressFilter"] shouldBe "127.0.0.1"
    }

    "dotted puts under the same prefix merge rather than clobber" {
        val config = KdrInstanceConfig.codeTest()
        config.put("node.a", 1)
        config.put("node.instance.authConfigKey", "ak")
        config.get("node.a") shouldBe 1
        config.get("node.instance.authConfigKey") shouldBe "ak"
    }

    "a pre-nested map value is readable via a dotted key" {
        val config = KdrInstanceConfig.codeTest()
        config.put("db", mapOf("type" to "postgres", "name" to "kdr"))
        config.get("db.type") shouldBe "postgres"
        config.get("db.name") shouldBe "kdr"
    }

    "a missing or non-map nested path reads as null" {
        val config = KdrInstanceConfig.codeTest()
        config.get("node.internalIpAddressFilter") shouldBe null
        config.put("node.a", 1)
        config.get("node.b.c") shouldBe null // "node" present, deeper segments absent
        config.get("node.a.deeper") shouldBe null // "node.a" is a scalar, not a map
    }

    "a null value removes a nested key without disturbing its siblings" {
        val config = KdrInstanceConfig.codeTest()
        config.put("node.a", 1)
        config.put("node.b", 2)
        config.put("node.a", null)
        config.get("node.a") shouldBe null
        config.get("node.b") shouldBe 2
    }

    "flat keys (services, env vars) are unaffected by dotted handling" {
        val config = KdrInstanceConfig.codeTest()
        config.put("NodeService", "svc")
        config.put("KDR_DB_TYPE", "h2File")
        config.get("NodeService") shouldBe "svc"
        config.getEnvVar("KDR_DB_TYPE") shouldBe "h2File"
    }
})
