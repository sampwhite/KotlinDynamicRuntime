package com.dynamicruntime.script.deploy

import java.io.File

/**
 * A lightweight node to deploy to (issue #1243): where it is, who runs the application there, and the settings it
 * runs with -- read from a versioned target file, `deploy/targets/<name>.properties`. No secrets are ever in one;
 * the node keeps those itself (`<baseDir>/private/secrets.properties`), and nothing here reads or writes them.
 *
 * The node is the one `docs/deploy/lightweight-node.md` sets up: the application under [baseDir], a `current` link
 * into `releases/`, its settings in `kdr.env`, and a systemd user service named [service].
 */
data class DeployTarget(
    /** What the target is called: its file's name without `.properties`. */
    val name: String,
    val host: String,
    /** The user that runs the application on the node, and that SSH signs in as. */
    val user: String,
    /** The application's directory on the node, as an absolute path. */
    val baseDir: String,
    /** The systemd user service that runs the application. */
    val service: String,
    /** The address people use, checked after a deploy. */
    val publicUrl: String,
    /** How many releases to keep, the running one always among them. */
    val keepReleases: Int,
    /**
     * The name the build is made under (issue #1273), passed to Gradle as `-PkdrBuild=<name>`, or null to build as a
     * developer does. A workspace's `settings.gradle.kts` reads it to leave out what a deploy should not carry: its
     * own configuration (`customConfig`), its scratch projects.
     */
    val buildProfile: String?,
    /** The node's settings, written as `kdr.env` in this order; empty leaves the node's own file alone. */
    val env: Map<String, String>,
) {
    /** What `ssh` and `rsync` are pointed at. */
    val sshDestination: String get() = "$user@$host"

    val releasesDir: String get() = "$baseDir/releases"
    val currentLink: String get() = "$baseDir/current"
    val envFile: String get() = "$baseDir/kdr.env"

    /** The port the application listens on, on the node: its `KDR_PORT`, else the application's default. */
    val port: Int get() = env["KDR_PORT"]?.toIntOrNull() ?: DPL.defaultPort

    companion object {
        /** The keys a target file must have; every other key is `env.<VAR>`, [DPL.keepReleasesKey] or [DPL.buildProfileKey]. */
        private val required = listOf("host", "user", "baseDir", "service", "publicUrl")

        /** What a build profile may be: a plain name, since it goes onto a Gradle command line. */
        private val profilePattern = Regex("[A-Za-z][A-Za-z0-9_-]*")

        /**
         * Reads a target file's [text], named [name]. A properties file in its simplest form -- `key=value` per
         * line, `#` comments, blank lines -- read by hand so that the `env.` entries keep the order they are written
         * in, which is the order `kdr.env` gets them in. Refuses, naming what is wrong, a line that is not
         * `key=value`, a missing or unknown key, a key given twice, and a [baseDir] that is not absolute.
         */
        fun parse(name: String, text: String): DeployTarget {
            val values = LinkedHashMap<String, String>()
            val env = LinkedHashMap<String, String>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val eq = line.indexOf('=')
                require(eq > 0) { "Target '$name', line ${index + 1}: expected key=value, found '$line'." }
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.startsWith(DPL.envPrefix)) {
                    val variable = key.removePrefix(DPL.envPrefix)
                    require(variable.isNotEmpty()) { "Target '$name', line ${index + 1}: '${DPL.envPrefix}' names no variable." }
                    require(env.put(variable, value) == null) { "Target '$name': $variable is set twice." }
                } else {
                    require(key in required || key == DPL.keepReleasesKey || key == DPL.buildProfileKey) {
                        "Target '$name', line ${index + 1}: unknown key '$key'. A setting for the node is '${DPL.envPrefix}<VARIABLE>'."
                    }
                    require(values.put(key, value) == null) { "Target '$name': '$key' is set twice." }
                }
            }
            val missing = required.filter { values[it].isNullOrEmpty() }
            require(missing.isEmpty()) { "Target '$name' is missing ${missing.joinToString(", ")}." }
            val baseDir = values.getValue("baseDir").trimEnd('/')
            require(baseDir.startsWith("/")) { "Target '$name': baseDir must be an absolute path, not '$baseDir'." }
            val keep = values[DPL.keepReleasesKey]?.let {
                requireNotNull(it.toIntOrNull()?.takeIf { n -> n >= 2 }) {
                    "Target '$name': ${DPL.keepReleasesKey} must be a whole number of at least 2 (the running release and one to roll back to)."
                }
            } ?: DPL.defaultKeepReleases
            val profile = values[DPL.buildProfileKey]?.ifEmpty { null }?.also {
                require(profilePattern.matches(it)) {
                    "Target '$name': ${DPL.buildProfileKey} must be a plain name (letters, digits, '-', '_'), not '$it'."
                }
            }
            return DeployTarget(
                name, values.getValue("host"), values.getValue("user"), baseDir, values.getValue("service"),
                values.getValue("publicUrl").trimEnd('/'), keep, profile, env,
            )
        }

        /**
         * The target [nameOrPath] names: a path to a target file, or the name of one in the repository's
         * `deploy/targets/` under [repoDir]. A relative path is read against [callerDir], where the command was run
         * from: the wrapper starts Java in the workspace directory, so the process's own directory is not it. A target
         * file outside the repository keeps a node's description out of version control altogether.
         */
        fun load(repoDir: File, nameOrPath: String, callerDir: File = File(".")): DeployTarget {
            val asPath = File(nameOrPath).let { if (it.isAbsolute) it else File(callerDir, nameOrPath) }
            val file = if (asPath.isFile) asPath else File(repoDir, "${DPL.targetsDir}/$nameOrPath.properties")
            require(file.isFile) {
                val known = File(repoDir, DPL.targetsDir).listFiles { f -> f.name.endsWith(".properties") }
                    ?.map { it.name.removeSuffix(".properties") }?.sorted().orEmpty()
                "No target '$nameOrPath': neither a file nor one of ${DPL.targetsDir}/ (${known.joinToString(", ").ifEmpty { "none" }})."
            }
            return parse(file.name.removeSuffix(".properties"), file.readText())
        }
    }
}

/**
 * The deploy command's names and defaults (issue #1243). Not `DEPLOY`: beside the object [Deploy] that compiles to
 * `DEPLOY.class` next to `Deploy.class`, which a case-insensitive filesystem (macOS's) holds as one file, so one
 * class silently replaces the other.
 */
@Suppress("ConstPropertyName")
object DPL {
    /** Where the repository keeps its target files. */
    const val targetsDir = "deploy/targets"

    /** A target file's key for a node setting is this plus the variable's name. */
    const val envPrefix = "env."

    const val keepReleasesKey = "keepReleases"
    const val defaultKeepReleases = 3

    /** A target file's key naming its build (issue #1273): see [DeployTarget.buildProfile]. */
    const val buildProfileKey = "buildProfile"

    /** The Gradle property a build profile is passed as, which a workspace's `settings.gradle.kts` reads. */
    const val buildProperty = "kdrBuild"

    /** The application's own default port, when a target sets no `KDR_PORT`. */
    const val defaultPort = 7070

    /** The distribution `:launch:installDist` writes, relative to the repository: the release. */
    const val distributionDir = "launch/build/install/launch"

    /** How long a deploy waits for the application to answer on the node, then through the public address. */
    const val nodeHealthSeconds = 120
    const val publicHealthSeconds = 30
}
