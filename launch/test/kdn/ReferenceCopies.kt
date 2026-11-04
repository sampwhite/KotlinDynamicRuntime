package kdn

import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.EnvVarRegistry
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.coreDocs
import com.dynamicruntime.common.operator.envVarCatalogMarkdown
import java.io.File
import java.util.zip.ZipFile

/**
 * The committed copies of the reference documents (issues #1603, #1607). The repository holds each one in the file at
 * its source path, between two marker lines, so it can be read and searched without a build or a server, and a change
 * to a reference shows in a diff. The file's own text outside the markers -- where the document comes from, where it is
 * served -- is written by hand.
 *
 * Two kinds: the documents a running server generates ([com.dynamicruntime.common.content.MarkdownDocDef.generate]),
 * and the environment variables, which no server can list whole (its operator view shows those it has read) and so are
 * made here, from every declaration in the repository ([declaredEnvVars]). That is why this lives in `launch`: its
 * classpath is the only one holding every module.
 *
 * `./gradlew :launch:refDocsDump` rewrites the copies ([main]), and `refDocsTest` ([ReferenceCopiesTest], part of
 * `check`) fails while one differs from what its generator makes now. Both live with the tests, so nothing here ships.
 */
@Suppress("ConstPropertyName")
object GENREF {
    const val dumpCommand = "./gradlew :launch:refDocsDump"

    /** How the line opening a generated region begins; the rest of the line is the dump's to write. */
    const val beginPrefix = "<!-- kdr-generated: begin"
    const val beginLine = "$beginPrefix -- copied from its generator by `$dumpCommand`; edit the generator, not this -->"
    const val endLine = "<!-- kdr-generated: end -->"

    /** The environment variables' copy, which no server serves. */
    const val envVarsPath = "docs/reference/environment-variables.md"

    /** How a class file names [EnvVarDef] when it refers to it. */
    const val envVarDefInternalName = "com/dynamicruntime/common/context/EnvVarDef"
}

/** One committed copy: the repository file holding it, and what makes its text. */
class ReferenceCopy(val sourcePath: String, val generate: () -> String)

/** Every committed copy: the documents a server generates, then the environment variables. */
fun referenceCopies(repoRoot: File): List<ReferenceCopy> =
    coreDocs().mapNotNull { doc -> doc.generate?.let { ReferenceCopy(doc.sourcePath, it) } } +
        ReferenceCopy(GENREF.envVarsPath) { envVarCatalogMarkdown(declaredEnvVars(repoRoot)) }

/**
 * Every environment variable the repository declares. A declaration registers itself ([EnvVarRegistry]) when the
 * object holding it is initialized, which on a running node happens as code paths are taken; here every class of the
 * repository's modules that refers to [EnvVarDef] is initialized first, so none is missed. Only classes on this
 * classpath from under [repoRoot] count -- a workspace's injected configuration is not the runtime's -- and not this
 * module's tests.
 */
fun declaredEnvVars(repoRoot: File): List<EnvVarDef> {
    val root = repoRoot.canonicalPath + File.separator
    val ownTests = File(ReferenceCopy::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile
    val entries = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() }
        .map { File(it).canonicalFile }
        .filter { it.path.startsWith(root) && it != ownTests && it.exists() }
    val loader = ReferenceCopy::class.java.classLoader
    for (name in entries.flatMap { classesReferringToEnvVarDef(it) }) {
        Class.forName(name, true, loader)
    }
    return EnvVarRegistry.all()
}

/** The classes in [entry], a directory of classes or a jar, whose class file names [EnvVarDef]. */
private fun classesReferringToEnvVarDef(entry: File): List<String> {
    fun refers(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).contains(GENREF.envVarDefInternalName)
    fun className(path: String) = path.removeSuffix(".class").replace('/', '.')
    fun isClass(path: String) = path.endsWith(".class") && !path.startsWith("META-INF/") && !path.endsWith("module-info.class")
    return if (entry.isDirectory) {
        entry.walk().filter { it.isFile && isClass(it.relativeTo(entry).invariantSeparatorsPath) && refers(it.readBytes()) }
            .map { className(it.relativeTo(entry).invariantSeparatorsPath) }.toList()
    } else {
        ZipFile(entry).use { zip ->
            zip.entries().asSequence().filter { isClass(it.name) }
                .filter { e -> zip.getInputStream(e).use { refers(it.readBytes()) } }
                .map { className(it.name) }.toList()
        }
    }
}

/**
 * [fileText] with its generated region holding [generated], the rest of it unchanged. Refused when the file has no
 * region, or more than one, or an end before its begin: the markers are written by hand, once, where the document
 * goes, and a file whose markers are wrong is not one to guess about. [where] names the file in the message.
 */
fun withGeneratedRegion(fileText: String, generated: String, where: String): String {
    val lines = fileText.lines()
    val begins = lines.indices.filter { lines[it].startsWith(GENREF.beginPrefix) }
    val ends = lines.indices.filter { lines[it] == GENREF.endLine }
    val begin = begins.singleOrNull()
    val end = ends.singleOrNull()
    if (begin == null || end == null || end < begin) {
        throw KdrException(
            "$where needs exactly one generated region: a line beginning '${GENREF.beginPrefix}' and, after it, " +
                "the line '${GENREF.endLine}'.",
        )
    }
    val region = listOf(GENREF.beginLine) + generated.trimEnd('\n').lines() + GENREF.endLine
    return (lines.subList(0, begin) + region + lines.subList(end + 1, lines.size)).joinToString("\n")
}

/**
 * Rewrites each copy under the repository root named by the one argument, leaving a file whose copy is current
 * untouched (so its timestamp says when the reference last changed), and lists what it rewrote.
 */
fun main(args: Array<String>) {
    val repoRoot = File(args.singleOrNull() ?: throw KdrException("Name the repository root, and nothing else.")).canonicalFile
    val copies = referenceCopies(repoRoot)
    var rewritten = 0
    for (copy in copies) {
        val file = File(repoRoot, copy.sourcePath)
        if (!file.isFile) {
            throw KdrException("${copy.sourcePath} does not exist: a reference document's file holds its copy, so write " +
                "it first, with the markers where the copy goes.")
        }
        val text = file.readText()
        val next = withGeneratedRegion(text, copy.generate(), copy.sourcePath)
        if (next != text) {
            file.writeText(next)
            rewritten++
            println("Rewrote ${copy.sourcePath}")
        }
    }
    println("Reference documents: ${copies.size}, rewritten: $rewritten.")
}
