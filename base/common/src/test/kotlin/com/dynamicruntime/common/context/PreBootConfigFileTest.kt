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

    // --- which file (issue #802) -------------------------------------------------------------------------

    /** An environment holding only [KdrInstanceConfig.defaultsFileEnvVar] set to [value] (or nothing). */
    fun envWith(value: String?): (String) -> String? =
        { name -> if (name == KdrInstanceConfig.defaultsFileEnvVar.name) value else null }

    "unset, the workspace's own defaults file is read, as it always was" {
        val dir = workspace("KDR_PORT=7099")
        val config = inWorkspace(dir) { KdrInstanceConfig.preBootLoadConfig(getEnv = envWith(null)) }
        config.get("KDR_PORT") shouldBe "7099"
    }

    /**
     * The point of the variable: a process that must not inherit the workspace's choices -- an agent's server
     * in a developer's workspace -- reads nothing, including a value added to the file after it last looked.
     */
    "none reads no file at all, and says so" {
        val dir = workspace("KDR_PORT=7099", "KDR_MAIL_TRANSMIT_ADMIN_DOMAIN=true")
        val config = inWorkspace(dir) { KdrInstanceConfig.preBootLoadConfig(getEnv = envWith("none")) }
        config.get("KDR_PORT") shouldBe null
        config.get("KDR_MAIL_TRANSMIT_ADMIN_DOMAIN") shouldBe null
        KdrInstanceConfig.lastLoadReport shouldContain "no defaults file read"
        KdrInstanceConfig.lastLoadReport shouldContain KdrInstanceConfig.defaultsFileEnvVar.name
    }

    "a path reads that file instead, relative to the workspace or absolute" {
        val dir = workspace("KDR_PORT=7099")
        File(dir, "agent.properties").writeText("KDR_PORT=7075\n")
        val relative = inWorkspace(dir) { KdrInstanceConfig.preBootLoadConfig(getEnv = envWith("agent.properties")) }
        relative.get("KDR_PORT") shouldBe "7075"
        val absolute = inWorkspace(dir) {
            KdrInstanceConfig.preBootLoadConfig(getEnv = envWith(File(dir, "agent.properties").absolutePath))
        }
        absolute.get("KDR_PORT") shouldBe "7075"
        KdrInstanceConfig.lastLoadReport shouldContain "agent.properties"
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
