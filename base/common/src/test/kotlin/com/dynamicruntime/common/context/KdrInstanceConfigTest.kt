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
})
