package com.dynamicruntime.script

import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.sql.DbEnv
import com.dynamicruntime.common.sql.DbType
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.sql.SqlDbBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.Properties
import kotlin.system.exitProcess

/**
 * The clever half of the idempotent installer (the dumb half is `bin/kdr-install`, which sniffs for a JDK and
 * copies a minimal `settings.gradle.kts` so this can run). Launched via `kdr-run` with the deployment root as
 * the working directory and the repo path as its one argument. It brings the deployment's per-machine,
 * non-versioned configuration up to date -- and will grow over time (installing databases, OpenSearch, ...).
 *
 * Everything here is idempotent: it checks before it acts, prompts before anything a user might not want, and
 * does nothing when already configured.
 */

/** Minimum Gradle heap the Kotlin/JS webapp build needs, from `examples/gradle.properties.example`. */
private const val minGradleXmxGb = 4

fun main(args: Array<String>) {
    val workDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val repoDir = File(args.firstOrNull() ?: File(workDir, "KotlinDynamicRuntime").path).absoluteFile
    val examples = File(repoDir, "examples")
    if (!examples.isDirectory) {
        System.err.println("install: examples directory not found at $examples")
        exitProcess(1)
    }
    println("kdr-install: checking the deployment configuration in $workDir")
    ensureGradleProperties(workDir, examples)
    syncSettingsWithExample(workDir, examples)
    syncGradleWrapper(workDir, repoDir)
    ensureBinOnPath(repoDir)
    ensurePostgres(workDir)
    println("kdr-install: done.")
}

// --- gradle.properties ------------------------------------------------------------------------------------

/**
 * Ensures `gradle.properties` exists with the JVM heap the webapp build needs. Missing file -> copy the
 * example; present but no `org.gradle.jvmargs` -> add it silently; present but under-provisioned -> warn only.
 */
private fun ensureGradleProperties(workDir: File, examples: File) {
    val gp = File(workDir, "gradle.properties")
    val example = File(examples, "gradle.properties.example")
    if (!gp.exists()) {
        gp.writeText(stripExampleHeader(example.readText()))
        println("gradle.properties: created from the example.")
        return
    }
    val liveText = gp.readText()
    val exampleText = example.readText()

    // Add each heap property the live file lacks -- only the missing ones, so an already-present setting is
    // never duplicated -- each carrying its explanatory comment over from the example. Done silently.
    val heapKeys = listOf("org.gradle.jvmargs", "kotlin.daemon.jvmargs")
    val missing = heapKeys.filterNot { key -> liveText.lineSequence().any { it.trimStart().startsWith(key) } }
    if (missing.isNotEmpty()) {
        val blocks = missing.joinToString("\n\n") { propertyBlock(exampleText, it) }
        val lead = if (liveText.isEmpty() || liveText.endsWith("\n")) "\n" else "\n\n"
        gp.appendText(lead + blocks + "\n")
    }

    // Warn (only) when an already-present org.gradle.jvmargs is under-provisioned for the webapp build.
    val jvmLine = liveText.lineSequence().firstOrNull { it.trimStart().startsWith("org.gradle.jvmargs") }
    val xmxGb = jvmLine?.let { parseXmxGb(it) }
    if (xmxGb != null && xmxGb < minGradleXmxGb) {
        println("WARNING: gradle.properties sets org.gradle.jvmargs to '${jvmLine.substringAfter('=').trim()}',")
        println("         but the webapp's Kotlin/JS build needs at least -Xmx${minGradleXmxGb}g. Consider raising it.")
    }
}

/**
 * The example's declaration of [key] -- the property line plus the contiguous `#` comment lines directly
 * above it -- so a property added to a live file arrives self-documented. Empty if the example lacks the key.
 */
private fun propertyBlock(exampleText: String, key: String): String {
    val lines = exampleText.lines()
    val idx = lines.indexOfFirst { it.trimStart().startsWith(key) }
    if (idx < 0) {
        return ""
    }
    var start = idx
    while (start > 0 && lines[start - 1].trimStart().startsWith("#")) {
        start--
    }
    return lines.subList(start, idx + 1).joinToString("\n")
}

/** Extracts the `-Xmx` value from a jvmargs line as gigabytes, or null if absent/unparseable. */
private fun parseXmxGb(jvmLine: String): Double? {
    val m = Regex("""-Xmx(\d+)([gGmMkK])""").find(jvmLine) ?: return null
    val n = m.groupValues[1].toLong()
    return when (m.groupValues[2].lowercase()) {
        "g" -> n.toDouble()
        "m" -> n / 1024.0
        "k" -> n / 1024.0 / 1024.0
        else -> null
    }
}

// --- settings.gradle.kts ----------------------------------------------------------------------------------

private data class Include(val path: String, val projectDir: String?)

/**
 * Offers to add projects the example `settings.gradle.kts` includes that the live one does not. A project that
 * is present in the live settings but *commented out* is treated as present -- leaving it out was a deliberate
 * choice -- so only genuinely absent projects are offered, and only with the user's consent.
 */
private fun syncSettingsWithExample(workDir: File, examples: File) {
    val settings = File(workDir, "settings.gradle.kts")
    val example = File(examples, "settings.gradle.kts.example")
    if (!settings.isFile || !example.isFile) {
        return
    }
    val wanted = parseIncludes(example.readText(), includeCommented = false)
    val present = parseIncludes(settings.readText(), includeCommented = true).map { it.path }.toSet()
    val newProjects = wanted.filter { it.path !in present }
    if (newProjects.isEmpty()) {
        return
    }
    println("The example settings includes projects your settings.gradle.kts does not:")
    newProjects.forEach { println("  - ${it.path}") }
    print("Add them to settings.gradle.kts? [y/N] ")
    if (!readYes()) {
        println("Left settings.gradle.kts unchanged.")
        return
    }
    val block = buildString {
        append("\n// Added by kdr-install to match the example.\n")
        newProjects.forEach { inc ->
            append("include(\"${inc.path}\")\n")
            inc.projectDir?.let { append("project(\":${inc.path}\").projectDir = file(\"$it\")\n") }
        }
    }
    settings.appendText(block)
    println("Added ${newProjects.size} project(s) to settings.gradle.kts.")
}

/**
 * The `include(...)` entries in a settings file, each paired with its `projectDir` if one is declared.
 * Commented-out includes are returned only when [includeCommented] is set (used to detect deliberate opt-outs).
 */
private fun parseIncludes(text: String, includeCommented: Boolean): List<Include> {
    val includeRe = Regex("""^\s*(//\s*)?include\(\s*"([^"]+)"\s*\)""")
    val dirRe = Regex("""project\(\s*":([^"]+)"\s*\)\.projectDir\s*=\s*file\(\s*"([^"]+)"\s*\)""")
    val dirs = dirRe.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    return text.lineSequence().mapNotNull { line ->
        val m = includeRe.find(line) ?: return@mapNotNull null
        val commented = m.groupValues[1].isNotBlank()
        if (commented && !includeCommented) {
            return@mapNotNull null
        }
        Include(m.groupValues[2], dirs[m.groupValues[2]])
    }.toList()
}

// --- Gradle wrapper ---------------------------------------------------------------------------------------

/**
 * Keeps the parent (build root) Gradle wrapper in alignment with the repo's. The repo ships the canonical wrapper;
 * if the parent's `gradle-wrapper.properties` names a different Gradle version (its `distributionUrl`), offers
 * to copy the repo's `gradlew` and `gradle/wrapper` files over. (The copy takes effect on the next build --
 * the one that ran this installer already used the parent's current wrapper.)
 */
private fun syncGradleWrapper(workDir: File, repoDir: File) {
    val liveProps = File(workDir, "gradle/wrapper/gradle-wrapper.properties")
    val repoProps = File(repoDir, "gradle/wrapper/gradle-wrapper.properties")
    if (!liveProps.isFile || !repoProps.isFile) {
        return
    }
    val liveUrl = distributionUrl(liveProps)
    val repoUrl = distributionUrl(repoProps)
    if (liveUrl == null || repoUrl == null || liveUrl == repoUrl) {
        return
    }
    println("Your Gradle wrapper (${gradleVersion(liveUrl)}) differs from the repository's (${gradleVersion(repoUrl)}).")
    print("Update your wrapper to match the repository? [y/N] ")
    if (!readYes()) {
        println("Left the Gradle wrapper unchanged.")
        return
    }
    File(repoDir, "gradlew").copyTo(File(workDir, "gradlew"), overwrite = true).setExecutable(true)
    File(repoDir, "gradle/wrapper/gradle-wrapper.jar")
        .copyTo(File(workDir, "gradle/wrapper/gradle-wrapper.jar"), overwrite = true)
    repoProps.copyTo(liveProps, overwrite = true)
    println("Updated the Gradle wrapper to ${gradleVersion(repoUrl)}.")
}

/** The `distributionUrl` value from a `gradle-wrapper.properties` file, or null if absent. */
private fun distributionUrl(props: File): String? =
    props.readLines().firstOrNull { it.trimStart().startsWith("distributionUrl") }?.substringAfter('=')?.trim()

/** The Gradle version encoded in a `distributionUrl` (e.g. `...gradle-9.6.0-bin.zip` -> `9.6.0`), else the URL. */
private fun gradleVersion(url: String): String = Regex("""gradle-([\d.]+)""").find(url)?.groupValues?.get(1) ?: url

// --- PATH / shell rc --------------------------------------------------------------------------------------

/**
 * Offers to put this repo's `bin` on the user's PATH (via their shell rc file). If a `KotlinDynamicRuntime`
 * bin is already on PATH -- even from a different checkout or worktree -- it does nothing, so a second checkout
 * never fights the first.
 */
private fun ensureBinOnPath(repoDir: File) {
    val binDir = File(repoDir, "bin").absoluteFile.path
    val path = System.getenv("PATH").orEmpty()
    if (path.split(File.pathSeparatorChar).any { it.contains("KotlinDynamicRuntime") && it.trimEnd('/').endsWith("bin") }) {
        return // a KotlinDynamicRuntime bin is already on PATH; leave the user's setup alone.
    }
    val line = $$"export PATH=\"$PATH:$$binDir\""
    val rc = shellRcFile()
    if (rc == null) {
        println("This repo's bin is not on your PATH. Add it manually (shell config not recognized):")
        println("  $line")
        return
    }
    println("This repo's bin directory is not on your PATH.")
    println("I can append this line to ${rc.path}:")
    println("  $line")
    print("Add it? [y/N] ")
    if (!readYes()) {
        println("Left ${rc.name} unchanged.")
        return
    }
    rc.appendText("\n# KotlinDynamicRuntime bin (kdr-* command-line scripts)\n$line\n")
    println("Added. Refresh PATH in your current shell with:  source ${rc.path}")
}

/** The shell rc file to edit for PATH, per the current shell and OS, or null if it can't be determined. */
private fun shellRcFile(): File? {
    val home = File(System.getProperty("user.home"))
    val shell = System.getenv("SHELL").orEmpty().substringAfterLast('/')
    val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
    return when (shell) {
        "zsh" -> File(home, ".zshrc")
        "bash" -> File(home, if (isMac) ".bash_profile" else ".bashrc")
        else -> null
    }
}

/** Reads a yes/no answer; a bare Enter, EOF, or non-interactive stdin all mean "no". */
private fun readYes(): Boolean = readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")

/**
 * Drops the leading "EXAMPLE ... TEMPLATE, copy and rename me" fenced comment block from an example file, so
 * the live copy does not carry template instructions. The fence is a comment line of `=` characters; the
 * block runs from the first such line to the next. Files without that header are returned unchanged.
 */
private fun stripExampleHeader(text: String): String {
    val lines = text.lines()
    val isFence = { l: String -> l.trimStart('/', '#', ' ').startsWith("=====") }
    if (lines.isEmpty() || !isFence(lines[0])) {
        return text
    }
    val close = lines.drop(1).indexOfFirst(isFence)
    if (close < 0) {
        return text
    }
    return lines.drop(close + 2).dropWhile { it.isBlank() }.joinToString("\n") + "\n"
}

// --- PostgreSQL setup -------------------------------------------------------------------------------------

/** The Homebrew formula used to install PostgreSQL on macOS. The user can edit it in the generated script. */
private const val postgresBrewFormula = "postgresql@16"

/**
 * Offers to set up a local PostgreSQL database when one is not yet configured. A no-op once the database
 * password secret is present, so a configured (or H2-only) deployment is never re-nagged after setup. There
 * are two paths, chosen by whether PostgreSQL is already running:
 *  - Not running: install it and create the default database and user (macOS directly, Linux via a sudo
 *    script the user runs -- see [runPostgresSetup]);
 *  - Already running: set up this deployment's own database on the shared server (see
 *    [setupAdditionalDatabase]).
 * Either way it verifies by connecting, writes the password into the secrets file, and prints how to select
 * PostgreSQL. Any generated script may carry the password, so it is always deleted before returning.
 *
 * This targets a *local* PostgreSQL; a remote host (via `KDR_DB_HOST`) is left for the operator to configure.
 */
private fun ensurePostgres(workDir: File) {
    val osName = System.getProperty("os.name").orEmpty()
    val isMac = osName.contains("mac", ignoreCase = true)
    val isLinux = osName.contains("linux", ignoreCase = true)
    if (!isMac && !isLinux) {
        return // Automated PostgreSQL setup is only scripted for macOS and Linux.
    }

    // Already configured? The password secret being present means there is nothing to do.
    val secretsFile = File(workDir, SecretsUtil.secretsPath)
    if (!readProperty(secretsFile, SqlDbBuilder.defaultPasswordSecretKey).isNullOrBlank()) {
        return
    }

    // Only handle a local database here; a configured remote host is the operator's responsibility.
    val configuredHost = System.getenv(DbEnv.dbHost)?.substringBefore(':')?.trim()
    if (!configuredHost.isNullOrEmpty() && configuredHost != "localhost" && configuredHost != "127.0.0.1") {
        return
    }

    val port = SqlDbBuilder.defaultPostgresPort
    val dbName = SqlDbBuilder.defaultDbName
    val dbUser = SqlDbBuilder.defaultDbUser
    val running = isListening("localhost", port)

    // PostgreSQL already running: it may be a server shared by several checkouts, so set up this deployment's
    // own database on it (see setupAdditionalDatabase). Otherwise, install PostgreSQL and create the default
    // database and user.
    if (running) {
        setupAdditionalDatabase(isMac, workDir, port, dbName, dbUser)
        return
    }

    println("PostgreSQL does not appear to be running on localhost:$port.")
    print("Install PostgreSQL and set up the '$dbName' database now? [y/N] ")
    if (!readYes()) {
        println("Skipping PostgreSQL setup.")
        return
    }
    val password = readSecret("Enter a password for the '$dbUser' database user: ")
    if (password.isBlank()) {
        println("No password entered; skipping PostgreSQL setup.")
        return
    }

    val ok = runPostgresSetup(isMac, port, dbName, dbUser, password, install = true, createDbSql(dbName, dbUser, password))
    if (!ok) {
        println("PostgreSQL was not set up; leaving the secret unset. Re-run kdr-install to try again.")
        return
    }
    writeProperty(secretsFile, SqlDbBuilder.defaultPasswordSecretKey, password)
    println("Connected successfully; wrote the database password to ${secretsFile.path}.")
    printPostgresEnvGuidance(workDir, dbName)
}

/**
 * Sets up this deployment's own database on an already-running PostgreSQL server — the multiple-checkouts
 * case, where several checkouts share one server (and the default `kdr` user) but each uses its own database
 * selected via `KDR_DB_NAME`. Prompts for the database name and the `kdr` user's password, whether the
 * database is new, and a final confirmation. When creating a new database, it first verifies the credentials
 * against the default `postgres` database (the target does not exist yet), then creates just the database via
 * the superuser path. On success, it records the password and prints the extra `KDR_DB_NAME` to set.
 */
private fun setupAdditionalDatabase(isMac: Boolean, workDir: File, port: Int, defaultDbName: String, dbUser: String) {
    val secretsFile = File(workDir, SecretsUtil.secretsPath)
    println("PostgreSQL is running on localhost:$port, but this deployment has no database password")
    println("configured (no '${SqlDbBuilder.defaultPasswordSecretKey}' in ${secretsFile.path}).")
    println("Several checkouts can share this server, each with its own database.")

    print("Database name for this deployment [$defaultDbName]: ")
    val dbName = readlnOrNull()?.trim().let { if (it.isNullOrEmpty()) defaultDbName else it }
    val password = readSecret("Password for the '$dbUser' database user: ")
    if (password.isBlank()) {
        println("No password entered; skipping PostgreSQL setup.")
        return
    }
    print("Does the '$dbName' database still need to be created? [y/N] ")
    val createNew = readYes()

    println()
    if (createNew) {
        println("Will create the '$dbName' database (owned by '$dbUser') on the running server, then record the password.")
    } else {
        println("Will verify the '$dbName' database with that password, then record it.")
    }
    print("Proceed? [y/N] ")
    if (!readYes()) {
        println("Skipping PostgreSQL setup.")
        return
    }

    if (createNew) {
        // The target database does not exist yet, so verify the user/password against the 'postgres' database.
        if (!canConnect("localhost", port, null, dbUser, password)) {
            println("Could not connect to PostgreSQL as '$dbUser' with that password (checked the 'postgres' database).")
            println("Check the password set when PostgreSQL was first installed, then re-run kdr-install.")
            return
        }
        val ok = runPostgresSetup(isMac, port, dbName, dbUser, password, install = false, createDatabaseSql(dbName, dbUser))
        if (!ok) {
            println("Could not create/verify the '$dbName' database; leaving the secret unset.")
            return
        }
    } else {
        // Existing database: verify the credentials against it directly.
        if (!canConnect("localhost", port, dbName, dbUser, password)) {
            println("Could not connect to the '$dbName' database as '$dbUser' with that password.")
            println("Make sure the database exists and the password is correct, then re-run kdr-install.")
            return
        }
    }
    writeProperty(secretsFile, SqlDbBuilder.defaultPasswordSecretKey, password)
    println("Connected successfully; wrote the database password to ${secretsFile.path}.")
    printPostgresEnvGuidance(workDir, dbName)
}

/** Dispatches OS-specific PostgreSQL setup (running [createSql] as the superuser), macOS directly vs. Linux via sudo. */
private fun runPostgresSetup(
    isMac: Boolean,
    port: Int,
    dbName: String,
    dbUser: String,
    password: String,
    install: Boolean,
    createSql: String,
): Boolean =
    if (isMac) {
        setupMacPostgres(port, dbName, dbUser, password, install, createSql)
    } else {
        setupLinuxPostgres(port, dbName, dbUser, password, install, createSql)
    }

/**
 * Runs the macOS PostgreSQL setup directly (no sudo): installs and starts it via Homebrew when [install] is
 * set, then runs [createSql] by connecting to the `postgres` database as the current (superuser) macOS user.
 * Returns true once a connection to [dbName] with the new credentials succeeds.
 */
private fun setupMacPostgres(
    port: Int,
    dbName: String,
    dbUser: String,
    password: String,
    install: Boolean,
    createSql: String,
): Boolean {
    if (install) {
        if (!commandExists("brew")) {
            println("Homebrew ('brew') was not found. Install it from https://brew.sh and re-run kdr-install.")
            return false
        }
        println("Installing PostgreSQL via Homebrew ($postgresBrewFormula)...")
        if (!runProcess(listOf("brew", "install", postgresBrewFormula))) {
            println("'brew install $postgresBrewFormula' failed.")
            return false
        }
        println("Starting the PostgreSQL service...")
        if (!runProcess(listOf("brew", "services", "start", postgresBrewFormula))) {
            println("'brew services start $postgresBrewFormula' failed.")
            return false
        }
    }
    // A versioned Homebrew formula is keg-only, so reference its binaries by their absolute path when present.
    val bin = brewPrefix(postgresBrewFormula)?.plus("/bin")
    val psql = bin?.let { "$it/psql" } ?: "psql"
    val pgIsReady = bin?.let { "$it/pg_isready" } ?: "pg_isready"
    println("Waiting for PostgreSQL to accept connections...")
    waitForReady(pgIsReady)
    println("Creating the '$dbName' database...")
    if (!runProcess(listOf(psql, "postgres"), stdin = createSql)) {
        println("Could not create the database via psql. If it already exists, drop it and re-run.")
        return false
    }
    return canConnectWithRetries("localhost", port, dbName, dbUser, password)
}

/**
 * Runs the Linux PostgreSQL setup by generating a one-time sudo script and asking the user to run it -- so the
 * root operations (package install, `sudo -u postgres`) are visible and explicitly consented to. The script
 * carries the password, so it is always deleted before this returns. Returns true once a connection with the
 * new credentials succeeds.
 */
private fun setupLinuxPostgres(
    port: Int,
    dbName: String,
    dbUser: String,
    password: String,
    install: Boolean,
    createSql: String,
): Boolean {
    val scriptFile = File.createTempFile("kdr-postgres-setup", ".sh")
    scriptFile.deleteOnExit() // Backstop in case this process is killed before the "finally" below runs.
    try {
        writeSecureExecutable(scriptFile, linuxSetupScript(install, createSql))
        println()
        println("Wrote a setup script to: ${scriptFile.path}")
        println("It needs sudo and may contain a password, so it is deleted when kdr-install exits.")
        println("Review and run it in another terminal:")
        println("  less ${scriptFile.path}      # to inspect it first")
        println("  sudo bash ${scriptFile.path} # to run it")
        println()
        while (true) {
            print("Press Enter once it has completed (or type 'skip' to abort): ")
            if (readlnOrNull()?.trim().equals("skip", ignoreCase = true)) {
                return false
            }
            if (canConnect("localhost", port, dbName, dbUser, password)) {
                return true
            }
            println("Could not connect to '$dbName' yet — if the script is still running, wait a moment and retry.")
        }
    } finally {
        scriptFile.delete()
    }
}

/**
 * The SQL that creates the database role and database (owned by that role). The password is placed in a
 * single-quoted SQL literal with any `'` doubled. Left non-private so it can be unit-tested.
 */
fun createDbSql(dbName: String, dbUser: String, password: String): String {
    val sqlPassword = password.replace("'", "''")
    return "CREATE USER $dbUser WITH ENCRYPTED PASSWORD '$sqlPassword';\n" +
        "CREATE DATABASE $dbName OWNER $dbUser;\n"
}

/** SQL that creates only the database (owned by [dbUser]), for when the role already exists. Non-private for tests. */
fun createDatabaseSql(dbName: String, dbUser: String): String = "CREATE DATABASE $dbName OWNER $dbUser;\n"

/**
 * Builds the Linux PostgreSQL setup script (run by the user under sudo): the install/start section when
 * [install] is set, then [createSql] fed to `sudo -u postgres psql` through a quoted heredoc (so no shell
 * expansion touches any inlined password). Left non-private so it can be unit-tested.
 */
fun linuxSetupScript(install: Boolean, createSql: String): String {
    val sb = StringBuilder()
    sb.append("#!/usr/bin/env bash\n#\n")
    sb.append("# Generated by kdr-install to set up a local PostgreSQL database for this deployment.\n")
    sb.append("# It may contain a database password, so kdr-install deletes it when it exits. Review, then run:\n")
    sb.append("#   sudo bash <this file>\n\n")
    if (install) {
        sb.append("echo \"Installing PostgreSQL...\"\n")
        sb.append("sudo apt update\n")
        sb.append("sudo apt install -y postgresql postgresql-contrib\n\n")
        sb.append("echo \"Starting the PostgreSQL service...\"\n")
        sb.append("sudo systemctl start postgresql\n")
        sb.append("sudo systemctl enable postgresql\n\n")
    }
    sb.append("echo \"Creating the database...\"\n")
    sb.append("sudo -u postgres psql <<'EOF'\n")
    sb.append(createSql)
    sb.append("EOF\n\necho \"Done.\"\n")
    return sb.toString()
}

/** Runs [cmd], streaming its output (unless [quiet]); feeds [stdin] to it when given. True on a zero exit. */
private fun runProcess(cmd: List<String>, stdin: String? = null, quiet: Boolean = false): Boolean =
    try {
        val pb = ProcessBuilder(cmd)
        val sink = if (quiet) ProcessBuilder.Redirect.to(File("/dev/null")) else ProcessBuilder.Redirect.INHERIT
        pb.redirectOutput(sink)
        pb.redirectError(sink)
        if (stdin == null) {
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        }
        val p = pb.start()
        if (stdin != null) {
            p.outputStream.use { it.write(stdin.toByteArray()) }
        }
        p.waitFor() == 0
    } catch (e: Exception) {
        if (!quiet) {
            println("  (could not run ${cmd.firstOrNull()}: ${e.message})")
        }
        false
    }

/** Whether [cmd] is available on the PATH. */
private fun commandExists(cmd: String): Boolean =
    runProcess(listOf("/bin/sh", "-c", "command -v $cmd"), quiet = true)

/** The Homebrew install prefix for [formula] (e.g. `/opt/homebrew/opt/postgresql@16`), or null. */
private fun brewPrefix(formula: String): String? =
    try {
        val p = ProcessBuilder("brew", "--prefix", formula)
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }

/** Waits (up to ~30s) for PostgreSQL to accept connections, using [pgIsReady]. */
private fun waitForReady(pgIsReady: String) {
    repeat(30) {
        if (runProcess(listOf(pgIsReady, "-q"), quiet = true)) {
            return
        }
        Thread.sleep(1000)
    }
}

/** [canConnect] with a few one-second retries, to ride out a service that is still coming up. */
private fun canConnectWithRetries(host: String, port: Int, dbName: String?, user: String, password: String): Boolean {
    repeat(5) {
        if (canConnect(host, port, dbName, user, password)) {
            return true
        }
        Thread.sleep(1000)
    }
    return false
}

/** True if a TCP connection to [host]:[port] can be established quickly (a simple "is PostgreSQL up?" probe). */
private fun isListening(host: String, port: Int): Boolean =
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), 800); true }
    } catch (_: Exception) {
        false
    }

/**
 * True if a PostgreSQL connection succeeds with the given credentials. A null [dbName] connects to the
 * default `postgres` maintenance database, which verifies the user/password without needing a target database
 * to exist yet (used before creating a new database).
 */
private fun canConnect(host: String, port: Int, dbName: String?, user: String, password: String): Boolean =
    try {
        // Force driver registration explicitly, since the fat jar's merged service files may not include it.
        runCatching { Class.forName("org.postgresql.Driver") }
        val db = dbName ?: "postgres"
        DriverManager.getConnection("jdbc:postgresql://$host:$port/$db", user, password).use { true }
    } catch (_: Exception) {
        false
    }

/** Prompts for a secret without echoing when a console is available; falls back to a normal (echoed) read. */
private fun readSecret(prompt: String): String {
    val console = System.console()
    if (console != null) {
        return console.readPassword(prompt)?.concatToString().orEmpty()
    }
    print(prompt)
    return readlnOrNull().orEmpty()
}

/** Reads a single property value from a properties [file], or null if the file or key is absent. */
private fun readProperty(file: File, key: String): String? {
    if (!file.isFile) {
        return null
    }
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(key)
}

/** Sets [key]=[value] in a properties [file] (creating it and its parent), preserving other entries; owner-only. */
private fun writeProperty(file: File, key: String, value: String) {
    file.parentFile?.mkdirs()
    val props = Properties()
    if (file.isFile) {
        file.inputStream().use { props.load(it) }
    }
    props.setProperty(key, value)
    file.outputStream().use { props.store(it, "kdr-install: database secrets") }
    setOwnerOnly(file, executable = false)
}

/** Writes [content] to [file] as an owner-only executable script (perms restricted before the content lands). */
private fun writeSecureExecutable(file: File, content: String) {
    setOwnerOnly(file, executable = true)
    file.writeText(content)
}

/** Restricts [file] to owner-only permissions (rwx for an executable, rw otherwise). Best-effort on non-POSIX. */
private fun setOwnerOnly(file: File, executable: Boolean) {
    try {
        val perms = if (executable) "rwx------" else "rw-------"
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
    } catch (_: Exception) {
        // Non-POSIX filesystem: fall back to the java.io.File permission bits.
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        if (executable) {
            file.setExecutable(false, false); file.setExecutable(true, true)
        }
    }
}

/**
 * Prints how to select this PostgreSQL database via environment variables (shell and IntelliJ), then offers
 * to persist them as startup defaults in the default-environment-variables file. Includes `KDR_DB_NAME` only
 * when [dbName] differs from the default, so a deployment on a shared server picks its own.
 */
private fun printPostgresEnvGuidance(workDir: File, dbName: String) {
    val vars = LinkedHashMap<String, String>()
    vars[DbEnv.dbType] = DbType.postgres.name
    vars[DbEnv.inMemoryOnly] = "false"
    if (dbName != SqlDbBuilder.defaultDbName) {
        vars[DbEnv.dbName] = dbName
    }

    println()
    println("To run against this PostgreSQL database, select it with these environment variables:")
    println("  export ${DbEnv.dbType}=${DbType.postgres.name}")
    println("  export ${DbEnv.inMemoryOnly}=false   # in-memory mode otherwise forces in-memory H2")
    if (dbName != SqlDbBuilder.defaultDbName) {
        println("  export ${DbEnv.dbName}=$dbName        # this deployment's database")
    }
    println("You can also set them in an IntelliJ run configuration's \"Environment variables\" field.")

    val defaultsFile = File(workDir, KdrInstanceConfig.defaultEnvVarsFileName)
    print("Make these the startup defaults (write them to ${defaultsFile.name})? [y/N] ")
    if (readYes()) {
        upsertProperties(defaultsFile, vars)
        println("Wrote the startup defaults to ${defaultsFile.path}.")
    }
}

/**
 * Non-destructively sets each of [entries] in the properties [file] (created if absent): a key already
 * present has its value replaced in place; a new key is appended. All other lines -- comments, blank lines,
 * unrelated keys -- are left undisturbed. Left non-private so it can be unit-tested.
 */
fun upsertProperties(file: File, entries: Map<String, String>) {
    val text = if (file.isFile) file.readText() else ""
    val lines = if (text.isEmpty()) mutableListOf() else text.removeSuffix("\n").split("\n").toMutableList()
    for ((key, value) in entries) {
        val newLine = "$key=$value"
        val idx = lines.indexOfFirst { lineDefinesKey(it, key) }
        if (idx >= 0) {
            lines[idx] = newLine
        } else {
            lines.add(newLine)
        }
    }
    file.parentFile?.mkdirs()
    file.writeText(lines.joinToString("\n") + "\n")
}

/** Whether a `.properties` [line] assigns [key] (matching `key=`/`key:`/`key ` and not a `#`/`!` comment). */
private fun lineDefinesKey(line: String, key: String): Boolean {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("#") || trimmed.startsWith("!") || !trimmed.startsWith(key)) {
        return false
    }
    val rest = trimmed.substring(key.length)
    return rest.isEmpty() || rest[0] == '=' || rest[0] == ':' || rest[0].isWhitespace()
}
