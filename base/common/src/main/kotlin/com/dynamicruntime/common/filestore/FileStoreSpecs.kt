package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import java.io.File

/** File Store Attributes: what a store declared in `KDR_FILE_STORES` ([FSENV.stores]) may be given (issue #1282). */
@Suppress("ConstPropertyName")
object FSA {
    /** Which kind of store, an [FST] value. Required. */
    const val type = "type"

    /** A `local` store's directory. Required: a named store has no directory by default. */
    const val dir = "dir"

    /** An `s3` store's region. Required. */
    const val region = "region"

    /** An `s3` store's endpoint, for a service other than AWS; as [FSENV.s3Endpoint]. */
    const val endpoint = "endpoint"

    /** What an `s3` store's bucket names begin with; as [FSENV.s3BucketPrefix]. */
    const val prefix = "prefix"

    /** The profile in the AWS SDK's credentials file an `s3` store signs in with, rather than the SDK's default chain. */
    const val profile = "profile"

    val all = listOf(type, dir, region, endpoint, prefix, profile)

    /** The attributes a store of [type] takes. */
    fun takenBy(type: String): List<String> = when (type) {
        FST.local -> listOf(FSA.type, dir)
        FST.s3 -> listOf(FSA.type, region, endpoint, prefix, profile)
        else -> listOf(FSA.type)
    }

    /** The attributes a store of [type] must be given. */
    fun requiredBy(type: String): List<String> = when (type) {
        FST.local -> listOf(dir)
        FST.s3 -> listOf(region)
        else -> emptyList()
    }
}

/**
 * One file store a deployment keeps files in (issue #1282): the default store, described by `KDR_FILE_STORE_TYPE`
 * and its variables, or one named in `KDR_FILE_STORES`. What it is, not the store itself: [FileStoreService] builds
 * the store from it. [dir] is a `local` store's directory, resolved; [region], [endpoint], [prefix] and [profile] are
 * an `s3` store's.
 */
class FileStoreSpec(
    val name: String,
    val type: String,
    val dir: File? = null,
    val region: String? = null,
    val endpoint: String? = null,
    val prefix: String = "",
    val profile: String? = null,
)

/**
 * The file stores this node declares (issue #1282), read from its environment and nothing else -- **no service is
 * asked**, so what declares a client's store (issue #1283) can know which stores there are before the file store
 * runs. Every mistake in a declaration refuses the start, in every environment, as the variables of the default
 * store do: a store named wrongly would otherwise keep files somewhere nobody chose.
 */
object FileStoreSpecs {
    /** Every store this node declares: the default one first, then those [FSENV.stores] names, in its order. */
    fun all(cxt: KdrCxt): List<FileStoreSpec> {
        val specs = listOf(defaultOf(cxt)) + named(cxt)
        requireApart(specs)
        return specs
    }

    /** The names of the stores this node declares, [FSNM.default] first. */
    fun namesOf(cxt: KdrCxt): List<String> = listOf(FSNM.default) + named(cxt).map { it.name }

    /**
     * The directories `local` stores keep files in on this node: the default store's -- [FileStoreService.localDirOf],
     * **whether or not that store is in use**, since the deployment's own data is there or will be -- and every named
     * `local` store's. For whoever has to keep out of them.
     */
    fun localDirsOf(cxt: KdrCxt): List<File> = listOf(FileStoreService.localDirOf(cxt)) + named(cxt).mapNotNull { it.dir }

    /**
     * The default store, as its own variables describe it. Unset, a node running in memory only keeps its files in
     * memory too, and so does every unit test; any other node keeps them in a directory. What is wrong with it is
     * said when the store is made ([FileStoreService]), in the terms of those variables.
     */
    fun defaultOf(cxt: KdrCxt): FileStoreSpec {
        val config = cxt.instanceConfig
        // A unit test never writes a file to a disk it did not choose, even one that turns `inMemoryOnly` off
        // to share a database between instances.
        val inMemory = config.get(ACFG.inMemoryOnly) == true || config.env == ENV.unit
        val type = cxt.getEnvVar(FSENV.type)?.trim()?.takeIf { it.isNotEmpty() } ?: if (inMemory) FST.memory else FST.local
        return FileStoreSpec(
            FSNM.default, type,
            dir = FileStoreService.localDirOf(cxt).takeIf { type == FST.local },
            region = cxt.getEnvVar(FSENV.s3Region)?.trim()?.takeIf { it.isNotEmpty() },
            endpoint = cxt.getEnvVar(FSENV.s3Endpoint)?.trim()?.takeIf { it.isNotEmpty() },
            prefix = cxt.getEnvVar(FSENV.s3BucketPrefix)?.trim().orEmpty(),
        )
    }

    /**
     * The stores [FSENV.stores] declares, in its order: entries `<store>.<attribute>=<value>` separated by commas, a
     * store's attributes in any order and among other stores'. Refused, naming the entry: one that is not of that
     * form, a store whose name is no name ([FSNM.nameProblem]) or is [FSNM.default], an attribute nobody takes or
     * given twice or with no value, a store with no type, an attribute its type does not take or one it requires
     * missing, and an `s3` prefix that is no bucket-name prefix.
     */
    fun named(cxt: KdrCxt): List<FileStoreSpec> {
        val declared = cxt.getEnvVar(FSENV.stores)?.trim().orEmpty()
        if (declared.isEmpty()) return emptyList()
        val byStore = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (entry in declared.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val left = entry.substringBefore('=', "").trim()
            val value = entry.substringAfter('=', "").trim()
            val name = left.substringBefore('.', "")
            val attribute = left.substringAfter('.', "")
            if (!entry.contains('=') || name.isEmpty() || attribute.isEmpty()) {
                throw refusal("${FSENV.stores.name} holds '$entry', which is not `<store>.<attribute>=<value>`.")
            }
            FSNM.nameProblem(name)?.let { throw refusal("${FSENV.stores.name} declares a store '$name', whose name is no name: $it.") }
            if (name == FSNM.default) {
                throw refusal(
                    "${FSENV.stores.name} declares a store '${FSNM.default}'. That is the store ${FSENV.type.name} and its " +
                        "variables describe; name another.",
                )
            }
            if (attribute !in FSA.all) {
                throw refusal("${FSENV.stores.name} gives the store '$name' an attribute '$attribute'; the attributes are ${FSA.all.joinToString()}.")
            }
            if (value.isEmpty()) throw refusal("${FSENV.stores.name} gives the store '$name' an empty $attribute.")
            val attributes = byStore.getOrPut(name) { LinkedHashMap() }
            if (attribute in attributes) throw refusal("${FSENV.stores.name} gives the store '$name' its $attribute twice.")
            attributes[attribute] = value
        }
        return byStore.map { (name, attributes) -> specOf(cxt, name, attributes) }
    }

    private fun specOf(cxt: KdrCxt, name: String, attributes: Map<String, String>): FileStoreSpec {
        val type = attributes[FSA.type]
            ?: throw refusal("${FSENV.stores.name} declares the store '$name' with no ${FSA.type} (one of ${FST.all.joinToString()}).")
        if (type !in FST.all) {
            throw refusal("${FSENV.stores.name} gives the store '$name' the ${FSA.type} '$type'; it must be one of ${FST.all.joinToString()}.")
        }
        val untaken = attributes.keys - FSA.takenBy(type).toSet()
        if (untaken.isNotEmpty()) {
            throw refusal("${FSENV.stores.name} gives the $type store '$name' ${untaken.joinToString()}, which a $type store does not take.")
        }
        val missing = FSA.requiredBy(type) - attributes.keys
        if (missing.isNotEmpty()) {
            throw refusal("${FSENV.stores.name} declares the $type store '$name' with no ${missing.joinToString()}, which a $type store needs.")
        }
        val prefix = attributes[FSA.prefix].orEmpty()
        FNAME.bucketPrefixProblem(prefix)?.let { throw refusal("${FSENV.stores.name} gives the store '$name' the ${FSA.prefix} '$prefix'; $it.") }
        return FileStoreSpec(
            name, type,
            dir = attributes[FSA.dir]?.let { dirOf(cxt, name, it) },
            region = attributes[FSA.region],
            endpoint = attributes[FSA.endpoint],
            prefix = prefix,
            profile = attributes[FSA.profile],
        )
    }

    /** A named `local` store's [dir]: absolute as written, else taken from the workspace directory -- which no unit test's is. */
    private fun dirOf(cxt: KdrCxt, name: String, dir: String): File {
        val file = File(dir)
        if (file.isAbsolute) return file
        if (cxt.instanceConfig.env == ENV.unit) {
            throw refusal(
                "${FSENV.stores.name} gives the store '$name' the ${FSA.dir} '$dir'. In the unit test environment it must be " +
                    "an absolute path: a test never keeps files in the workspace.",
            )
        }
        return AppPaths.resolve(dir)
    }

    /**
     * Refuses two stores that are one place: `local` stores whose directories are the same or one inside the other
     * (by real path, so a link into one is in it), and `s3` stores with the same endpoint, region and bucket prefix --
     * the same buckets, whatever credentials each signs in with. Each would hold the other's files and say they were
     * its own.
     */
    private fun requireApart(specs: List<FileStoreSpec>) {
        val dirs = specs.mapNotNull { spec -> spec.dir?.takeIf { spec.type == FST.local }?.let { spec to it.canonicalFile.toPath() } }
        for ((i, a) in dirs.withIndex()) {
            for (b in dirs.drop(i + 1)) {
                if (a.second.startsWith(b.second) || b.second.startsWith(a.second)) {
                    throw refusal(
                        "the file stores '${a.first.name}' and '${b.first.name}' are at one place (${a.second} and ${b.second}): " +
                            "each would hold the other's files. Give each a directory of its own.",
                    )
                }
            }
        }
        val buckets = specs.filter { it.type == FST.s3 }.groupBy { Triple(it.endpoint, it.region, it.prefix) }
        buckets.values.firstOrNull { it.size > 1 }?.let { same ->
            throw refusal(
                "the file stores ${same.joinToString { "'${it.name}'" }} are the same buckets (one endpoint, region and " +
                    "bucket prefix): each would hold the other's files. Give each a bucket prefix of its own.",
            )
        }
    }

    /** A declaration that refuses the start, as every mistake in the file store's own variables does. */
    fun refusal(message: String): KdrException =
        KdrException("Refusing to start: $message", null, EXC.internalError, SRC.config, ACT.code)
}
