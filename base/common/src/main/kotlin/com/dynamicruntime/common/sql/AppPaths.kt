package com.dynamicruntime.common.sql

import java.io.File

/**
 * Resolves filesystem paths against the **KDR workspace** — the composition root that contains the
 * KotlinDynamicRuntime repository (and possibly sibling repos), the per-deployment `settings.gradle.kts`,
 * the deployment-config code, and the runtime data. "Workspace" is the whole entity; the *workspace
 * directory* is where it lives on disk, and is what this resolves. Deployment-relative resources such as the
 * H2 data file and the secrets file live under it (e.g. `h2Database/dbData.dat`, `private/secrets.properties`).
 *
 * The workspace directory is resolved in priority order:
 *  1. The `kdr.workspaceDir` system property (overridable in tests / special launches),
 *  2. The `KDR_WORKSPACE_DIR` environment variable — set once in a shell, it consistently controls both the
 *     `bin/` scripts and the launched JVM (useful with multiple checkouts of the same repository),
 *  3. The nearest ancestor of the working directory that contains a [settingsFileName] — because a launch
 *     (notably from an IDE) often has a working directory *interior* to the workspace, and there is no
 *     top-level config file to anchor on (deployment config is Kotlin in `customConfig`),
 *  4. Failing all that, the working directory itself.
 *
 * The walk-up mirrors dn's `ConfigLoadUtil.findConfigFile`, hunting a bounded number of parents; keying on
 * `settings.gradle.kts` mirrors how the `bin/` scripts find the same directory.
 */
@Suppress("ConstPropertyName")
object AppPaths {
    const val workspaceDirProperty = "kdr.workspaceDir"
    const val workspaceDirEnvVar = "KDR_WORKSPACE_DIR"

    /** The per-deployment Gradle settings file that marks the workspace directory. */
    const val settingsFileName = "settings.gradle.kts"

    /** How many parent directories to hunt before giving up. */
    const val maxParentWalk = 8

    fun workspaceDir(): File {
        System.getProperty(workspaceDirProperty)?.let { return File(it) }
        System.getenv(workspaceDirEnvVar)?.let { return File(it) }
        return findWorkspaceDir() ?: File(System.getProperty("user.dir"))
    }

    /**
     * Walks up from [startDir] (default: the working directory) looking for the nearest ancestor that
     * contains a [settingsFileName], returning that directory, or null if none is found within
     * [maxParentWalk] parents / before the filesystem root.
     */
    fun findWorkspaceDir(startDir: File = File(System.getProperty("user.dir"))): File? {
        var dir: File? = startDir.absoluteFile
        var i = 0
        while (dir != null && i < maxParentWalk) {
            if (File(dir, settingsFileName).isFile) {
                return dir
            }
            dir = dir.parentFile
            i++
        }
        return null
    }

    /** Resolves [relativePath] against the [workspaceDir]. */
    fun resolve(relativePath: String): File = File(workspaceDir(), relativePath)
}
