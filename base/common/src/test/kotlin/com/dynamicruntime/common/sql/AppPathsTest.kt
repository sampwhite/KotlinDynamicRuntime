package com.dynamicruntime.common.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * Verifies the workspace-directory walk-up: a launch whose working directory is an interior to the workspace
 * (as an IDE run often is) still resolves the workspace directory by finding the ancestor holding
 * `settings.gradle.kts`.
 */
class AppPathsTest : StringSpec({

    "findWorkspaceDir walks up to the ancestor containing settings.gradle.kts" {
        val root = Files.createTempDirectory("kdrWorkspace").toFile()
        try {
            File(root, AppPaths.settingsFileName).writeText("// test settings\n")
            val nested = File(root, "KotlinDynamicRuntime/base/common").apply { mkdirs() }
            AppPaths.findWorkspaceDir(nested)?.canonicalFile shouldBe root.canonicalFile
        } finally {
            root.deleteRecursively()
        }
    }

    "findWorkspaceDir returns null when no settings file is found upward" {
        val root = Files.createTempDirectory("kdrNoWorkspace").toFile()
        try {
            val nested = File(root, "a/b").apply { mkdirs() }
            AppPaths.findWorkspaceDir(nested) shouldBe null
        } finally {
            root.deleteRecursively()
        }
    }
})
