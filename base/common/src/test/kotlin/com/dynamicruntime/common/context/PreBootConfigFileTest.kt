package com.dynamicruntime.common.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * Coverage for where `default-environment-variables.properties` is looked for (issue #380).
 *
 * It was opened by a bare relative path, so it was found only when the JVM happened to start in the workspace
 * -- which a Gradle `run` task does not. A deployment's defaults were therefore silently not applied, and the
 * silence is half the defect: an absent file and an empty one produced the same empty map.
 */
class PreBootConfigFileTest : StringSpec({

    /** A throwaway workspace: a settings file to anchor on, and the defaults file beside it. */
    fun workspace(vararg lines: String): File {
        val dir = Files.createTempDirectory("kdrWorkspace").toFile()
        File(dir, AppPaths.settingsFileName).writeText("// test settings\n")
        if (lines.isNotEmpty()) {
            File(dir, KdrInstanceConfig.defaultEnvVarsFileName).writeText(lines.joinToString("\n") + "\n")
        }
        return dir
    }

    fun <T> inWorkspace(dir: File, body: () -> T): T {
        val prev = System.getProperty(AppPaths.workspaceDirProperty)
        System.setProperty(AppPaths.workspaceDirProperty, dir.absolutePath)
        try {
            return body()
        } finally {
            if (prev == null) System.clearProperty(AppPaths.workspaceDirProperty)
            else System.setProperty(AppPaths.workspaceDirProperty, prev)
        }
    }

    /**
     * The bug itself. Resolution is against the **workspace**, so the file is found however deep inside it (or
     * outside it) the process happened to start -- which is the whole point, since a launcher's working
     * directory is not something a deployment controls.
     */
    "the defaults file is found through the workspace, not the working directory" {
        val dir = workspace("KDR_PORT=7099", "KDR_SOMETHING=yes")
        val config = inWorkspace(dir) { KdrInstanceConfig.preBootLoadConfig() }
        config.get("KDR_PORT") shouldBe "7099"
        config.get("KDR_SOMETHING") shouldBe "yes"
    }

    /**
     * The other half. "Applied" and "never seen" used to look identical from outside, which is exactly how a
     * file that was never being read went unnoticed -- so each says which it was, and names the path it looked
     * at, since a wrong path is the failure worth reporting.
     */
    "what happened to the file is reported either way" {
        val present = workspace("KDR_PORT=7099")
        inWorkspace(present) { KdrInstanceConfig.preBootLoadConfig() }
        KdrInstanceConfig.lastLoadReport shouldContain "1 of 1 entries applied"
        KdrInstanceConfig.lastLoadReport shouldContain present.absolutePath

        val empty = workspace() // a workspace with no defaults file at all
        inWorkspace(empty) { KdrInstanceConfig.preBootLoadConfig() }
        KdrInstanceConfig.lastLoadReport shouldContain "no ${KdrInstanceConfig.defaultEnvVarsFileName} found"
        KdrInstanceConfig.lastLoadReport shouldContain empty.absolutePath
    }

    /**
     * The precedence the file has always had, asserted here because the fix moves *where* it is read from and
     * must not move *what wins*: the real environment is authoritative and the file is only a fallback.
     */
    "the real environment still beats the file" {
        val dir = workspace("KDR_PORT=7099")
        val defaults = KdrInstanceConfig.readDefaultEnvVars(
            File(dir, KdrInstanceConfig.defaultEnvVarsFileName),
        ) { name -> if (name == "KDR_PORT") "7001" else null }
        defaults.containsKey("KDR_PORT") shouldBe false
    }
})
