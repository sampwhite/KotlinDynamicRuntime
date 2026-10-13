package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.filestore.FNAME
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.sql.SqlDbBuilder
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.webasset.WebAssetCheck
import com.dynamicruntime.common.webasset.WebImageKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Instant

/** Environment variables of the local files a file ref may name (issue #1154). */
object LFENV {
    val dir = EnvVarDef(
        "KDR_LOCAL_FILES_DIR", group = ENVGRP.fileStore, defaultDoc = "`localFiles` in the workspace directory",
        description = "Where the local files a file ref may name are kept: each directory directly under it is a " +
            "**root**, by its name, and a ref names a root and a path under it. A relative path is taken from the " +
            "workspace directory; an absolute one is taken as written, so the files need not be in the workspace. " +
            "Nothing creates it: until a deployment puts a root there, there are none. It may not be inside one of " +
            "the places that hold the deployment's own data, nor contain one. A unit test is given none unless it " +
            "names one, as an absolute path: it never defaults into the workspace.",
    )

    val roots = EnvVarDef(
        "KDR_LOCAL_FILE_ROOTS", group = ENVGRP.fileStore, defaultDoc = "none",
        description = "Roots of local files placed somewhere other than under KDR_LOCAL_FILES_DIR, as " +
            "`name=location` separated by commas: `brand=acmeRepo/images,shared=/srv/kdr/imagery`. How a source " +
            "repository's own artwork is a root without being copied. Each location is resolved as " +
            "KDR_LOCAL_FILES_DIR is, and held to the same rule about where it may be; a name declared here takes the " +
            "place of a directory of that name under KDR_LOCAL_FILES_DIR.",
    )
}

/** A local file read as a file ref's: its bytes, the kind of image they were found to be, and when it last changed. */
class LocalFile(val bytes: ByteArray, val kind: WebImageKind, val modifiedAt: Instant, val name: String)

/**
 * What reading a local file came to: the [file], or -- for the operator and nobody else -- [reason], which says why
 * there is none. A reason is about where the file is and what the disk said of it; it never repeats what a file holds.
 */
class LocalRead(val file: LocalFile?, val reason: String?)

/**
 * Where a local file was found, short of reading it: its [real] path, its [size] and when it last changed -- or
 * [reason], which says why it may not be read. Every rule about *which* file has been kept by the time there is one
 * of these; what the file holds has not been looked at.
 */
class LocalAt(val real: Path?, val size: Long, val modifiedMs: Long, val reason: String?)

/**
 * The directories a file ref may read a local file from, and the reading (issue #1154).
 *
 * **A ref names a root and a path under it, and reaches nothing else.** A path in configuration is a string naming
 * a file on the server, and stored configuration is written through the API, so what one can reach is held on
 * every side, here:
 *
 * - **A root is declared by the deployment**, never by the configuration: a directory directly under [location]
 *   (`KDR_LOCAL_FILES_DIR`), by its name, or one [placed] elsewhere by `KDR_LOCAL_FILE_ROOTS`.
 * - **A root is nowhere near the deployment's own data** ([reserved]): judged by its real path, it is neither
 *   inside one of those places nor a directory that contains one. The second half rules out the workspace
 *   directory itself, anything above it and `/` -- a root is somewhere files were put, not somewhere everything is.
 *   Asked each time a root is used, not only at startup, since a root may be a link somebody repoints.
 * - **A root under the location is named exactly as its directory is**, case and all: a name that finds the
 *   directory only because the filesystem ignores case would be a root on one machine and none on the next.
 * - **A path is plain**: a file-store key's rules ([FNAME.keyProblem]), with no segment beginning with a dot.
 * - **The file's real path is exactly the root's real path plus the path.** That refuses a symbolic link anywhere
 *   below the root, and a path differing from the file's name only by case, which resolves on a filesystem that
 *   ignores case and not on one that does not.
 * - **A regular file, of bounded size, that is an image.** The size is asked before the file is read, and the bytes
 *   are sniffed ([WebAssetCheck.sniff]) as an upload's are: a file that does not pass as one of the images a ref
 *   may be is never returned. (A raster is told by how it begins, so this keeps out everything that is not at
 *   least dressed as a picture -- a text file, a key, a database -- which is what a path in configuration could
 *   otherwise be pointed at.)
 */
class LocalFileRoots(
    /** Where roots are looked for by name; null where there is none -- a unit test that named no location. */
    val location: File?,
    /** The roots placed elsewhere, by name. */
    val placed: Map<String, File>,
    /** The real paths of the places that hold the deployment's own data. */
    val reserved: List<Path>,
    /** Why there is no [location] although nobody asked for none: the default is somewhere it may not be. */
    val locationNote: String? = null,
) {
    /**
     * The real path of the root [name], or null with [why] said when there is none here: no such root, or a
     * directory that may not be one.
     */
    private fun rootDir(name: String, why: (String) -> Unit): Path? {
        FREF.rootProblem(name)?.let {
            why("the root's name is no name")
            return null
        }
        // Under the location, by the name the directory really has: `isDirectory` alone would find `brand` when
        // asked for `Brand`, where the filesystem ignores case.
        val dir = placed[name] ?: location?.takeIf { it.list()?.contains(name) == true }?.let { File(it, name) }
        if (dir == null || !dir.isDirectory) {
            why("there is no root '$name' on this machine")
            return null
        }
        val real = try {
            dir.toPath().toRealPath()
        } catch (_: IOException) {
            why("the root '$name' could not be resolved")
            return null
        }
        placeProblem(real, reserved)?.let {
            why("the root '$name' $it")
            return null
        }
        return real
    }

    /** Whether there is a root [name] here that a ref may read from. */
    fun hasRoot(name: String): Boolean = rootDir(name) {} != null

    /**
     * Where the file at [path] under the root [root] is, held to every rule of the class note about *which* file a
     * ref may name, and not read: its content is [read]'s to judge.
     */
    fun locate(root: String, path: String): LocalAt {
        fun none(reason: String?) = LocalAt(null, 0, 0, reason)
        var reason: String? = null
        val rootReal = rootDir(root) { reason = it } ?: return none(reason)
        pathProblem(path)?.let { return none("the path is not one a local file may have ($it)") }
        val named = rootReal.resolve(path)
        return try {
            val real = named.toRealPath()
            when {
                real != named -> none("the path is reached through a link, or differs from the file's own name by case")
                !Files.isRegularFile(real) -> none("it is not a file")
                Files.size(real) > FREF.maxSize -> none(tooLarge)
                else -> LocalAt(real, Files.size(real), Files.getLastModifiedTime(real).toMillis(), null)
            }
        } catch (_: NoSuchFileException) {
            none("there is no such file")
        } catch (_: IOException) {
            none(unreadable)
        }
    }

    /** The file at [path] under the root [root], held to every rule of the class note. */
    fun read(root: String, path: String): LocalRead {
        val at = locate(root, path)
        val real = at.real ?: return LocalRead(null, at.reason)
        return try {
            val bytes = Files.readAllBytes(real)
            val kind = WebAssetCheck.sniff(bytes)
            when {
                // Asked again of what was read: a file can grow between being measured and being read.
                bytes.size > FREF.maxSize -> LocalRead(null, tooLarge)
                kind == null -> LocalRead(null, "it is not an image a file ref may be")
                else -> LocalRead(LocalFile(bytes, kind, Instant.fromEpochMilliseconds(at.modifiedMs), real.fileName.toString()), null)
            }
        } catch (_: NoSuchFileException) {
            LocalRead(null, "there is no such file")
        } catch (_: IOException) {
            LocalRead(null, unreadable)
        }
    }

    /**
     * Where local files are looked for and what was found there, in words: for the startup line. A location or a
     * root that is not there is no fault, and this is where it is said.
     */
    fun describe(): String {
        val under = when {
            locationNote != null -> locationNote
            location == null -> "no location is named"
            !location.isDirectory -> "'${location.path}' is not there, so it holds no roots"
            else -> {
                val names = location.listFiles().orEmpty()
                    .filter { it.isDirectory && FREF.rootProblem(it.name) == null && it.name !in placed }.map { it.name }.sorted()
                "'${location.path}' holds ${if (names.isEmpty()) "no roots" else "the roots ${names.joinToString()}"}"
            }
        }
        val elsewhere = placed.entries.joinToString("") { (name, dir) ->
            "; the root '$name' is at '${dir.path}'${if (dir.isDirectory) "" else ", which is not there"}"
        }
        return under + elsewhere
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** What the location is called when none is named: beside `fileStore` and `h2Database` in the workspace. */
        const val defaultDirName = "localFiles"

        /** Where the workspace's secrets are, should [SecretsUtil.secretsPath] ever name a bare file. */
        const val privateDirName = "private"

        /** Where a node's logs are written, by its logging configuration's default. */
        const val logsDirName = "logs"

        /**
         * Where the sample component keeps its files -- `SampleFileStore.storeDirName`, which this module cannot see; a
         * test of the sample's holds the two to one value.
         */
        const val sampleFilesDirName = "sampleFiles"

        private const val unreadable = "it could not be read"

        private val tooLarge = "it is larger than ${byteSizeText(FREF.maxSize.toLong())}"

        /**
         * The workspace's own directories no root may be in, or contain: its secrets, its database, its logs, the
         * sample's files -- each where it is by default. A deployment that keeps one somewhere else (an H2 file its
         * database configuration places, a log directory its logging does) keeps its roots clear of it itself.
         */
        val reservedWorkspaceDirs = listOf(File(SecretsUtil.secretsPath).parent ?: privateDirName, SqlDbBuilder.h2DirName, logsDirName, sampleFilesDirName)

        /**
         * The roots this node reads local files from, as its environment declares them. **Refuses to start** over a
         * declaration that is malformed or names a place a root may not be, in every environment: this is a fence,
         * not a preference, and a node that started without it would be one whose configuration could read what
         * the fence was for. A location or a root that simply is not there is no fault -- it is logged by whoever
         * calls this, and each ref under it is a configuration problem of its own.
         */
        fun resolve(cxt: KdrCxt): LocalFileRoots {
            val unit = cxt.instanceConfig.env == ENV.unit
            val reserved = reservedPlaces(cxt)
            val named = cxt.getEnvVar(LFENV.dir)?.trim()?.takeIf { it.isNotEmpty() }
            // No default in a unit test: the default is in the workspace, which is no test's to read.
            var note: String? = null
            val location = when {
                named != null -> placed(LFENV.dir.name, named, unit, reserved)
                unit -> null
                else -> {
                    // The default is nobody's declaration, so where it may not be a location it is simply not one:
                    // a deployment whose file store is the workspace itself, say, named no local files and is
                    // not refused a start over them.
                    val byDefault = AppPaths.resolve(defaultDirName)
                    placeProblem(realOf(byDefault), reserved)?.let {
                        note = "the default '${byDefault.path}' is not used: it $it. Name a location with ${LFENV.dir.name}"
                    }
                    byDefault.takeIf { note == null }
                }
            }
            val roots = LinkedHashMap<String, File>()
            for (declared in cxt.getEnvVar(LFENV.roots).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val name = declared.substringBefore('=', "").trim()
                val where = declared.substringAfter('=', "").trim()
                if (name.isEmpty() || where.isEmpty()) {
                    throw configError("${LFENV.roots.name} holds '$declared', which is not `name=location`.")
                }
                FREF.rootProblem(name)?.let { throw configError("${LFENV.roots.name} declares a root whose name is no name: $it") }
                if (name in roots) throw configError("${LFENV.roots.name} declares the root '$name' twice.")
                roots[name] = placed("${LFENV.roots.name} (root '$name')", where, unit, reserved)
            }
            return LocalFileRoots(location, roots, reserved, note)
        }

        /** [declared] as the directory it names, having checked it may be a location or a root; [what] names the declaration in a refusal. */
        private fun placed(what: String, declared: String, unit: Boolean, reserved: List<Path>): File {
            val asWritten = File(declared)
            if (!asWritten.isAbsolute) {
                if (unit) {
                    throw configError("$what is '$declared', a relative path, which would be read in the workspace: a unit test names an absolute one.")
                }
                val segments = declared.split('/', '\\')
                if (segments.any { it == ".." || it.startsWith(".") }) {
                    throw configError("$what is '$declared': a path from the workspace may not hold '..' or a name beginning with a dot.")
                }
            }
            val dir = if (asWritten.isAbsolute) asWritten else AppPaths.resolve(declared)
            placeProblem(realOf(dir), reserved)?.let { throw configError("$what is '$declared', which $it.") }
            return dir
        }

        /**
         * What stops the directory at [real] holding local files, or null: it is inside one of the [reserved]
         * places, or is a directory that contains one.
         */
        fun placeProblem(real: Path, reserved: List<Path>): String? {
            reserved.firstOrNull { real.startsWith(it) }?.let { return "is inside '$it', which holds the deployment's own data" }
            reserved.firstOrNull { it.startsWith(real) }?.let { return "contains '$it', which holds the deployment's own data" }
            return null
        }

        /**
         * The real paths of the places that hold the deployment's own data: [reservedWorkspaceDirs] in [workspace],
         * and the file store's directory. **Each by its real path**, as a root is judged by its own: a workspace
         * whose `logs` is a link to `/var/log/kdr` keeps its logs there, and that is the place to stay out of.
         */
        fun reservedPlaces(cxt: KdrCxt, workspace: File = AppPaths.workspaceDir()): List<Path> =
            // The last as a list of one: a `Path` is itself an `Iterable` of its own segments, and `+` would add those.
            reservedWorkspaceDirs.map { realOf(File(workspace, it)) } + listOf(realOf(FileStoreService.localDirOf(cxt)))

        /**
         * [file]'s real path -- links resolved -- or, for one that is not there, the real path of the nearest
         * directory above it that is, with the rest of its name beneath: so a place that does not exist yet is
         * still compared as the place it will be.
         */
        fun realOf(file: File): Path {
            val absolute = file.absoluteFile.toPath().normalize()
            var existing: Path? = absolute
            while (existing != null && !Files.exists(existing)) existing = existing.parent
            val base = existing ?: return absolute
            val real = try {
                base.toRealPath()
            } catch (_: IOException) {
                base
            }
            return real.resolve(base.relativize(absolute)).normalize()
        }

        /** What is wrong with [path] as a local file's, or null: a file-store key's rules, and no name beginning with a dot. */
        fun pathProblem(path: String): String? =
            FNAME.keyProblem(path) ?: "a name in it begins with a dot".takeIf { path.split('/').any { it.startsWith(".") } }

        private fun configError(message: String): KdrException =
            KdrException("Refusing to start: $message", null, EXC.internalError, SRC.config, ACT.code)
    }
}
