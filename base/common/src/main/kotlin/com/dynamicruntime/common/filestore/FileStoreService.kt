package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.startup.bootCheckMode
import com.dynamicruntime.common.startup.modeOverride
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.truncateToMs
import java.io.File

/** Topic logger for the file store (placed beside the code that owns the `"fileStore"` topic). */
object LogFileStore : KdrLogger("fileStore")

/** Environment variables of the file store. */
object FSENV {
    val type = EnvVarDef(
        "KDR_FILE_STORE_TYPE", group = ENVGRP.fileStore,
        defaultDoc = "`memory` when running in memory only (and in a unit test), else `local`",
        description = "Where files are kept: `memory` (in the JVM, gone when it stops, and bounded by nothing but " +
            "the heap -- for tests and a throwaway node), `local` (a directory on this machine, see " +
            "KDR_FILE_STORE_DIR) or `s3` (S3, or a service that speaks its API -- see the KDR_S3_ variables). " +
            "This is the **default** store; KDR_FILE_STORES declares others. Every node of a deployment must name the " +
            "same stores, and `local` is one store only where the nodes share the directory, on a filesystem that " +
            "honors file locks between them.",
    )

    val dir = EnvVarDef(
        "KDR_FILE_STORE_DIR", group = ENVGRP.fileStore, defaultDoc = "`fileStore` in the workspace directory",
        description = "The directory a `local` file store keeps its files in, each bucket a directory under it. " +
            "A relative path is taken from the workspace directory. A unit test that runs on the `local` store " +
            "must name one: it never defaults into the workspace.",
    )

    val s3Region = EnvVarDef(
        "KDR_S3_REGION", group = ENVGRP.fileStore, defaultDoc = "unset; required for `s3`",
        description = "The region of an `s3` file store's buckets: AWS's (`us-east-1`), or with KDR_S3_ENDPOINT the " +
            "service's own -- for Linode, the first part of the endpoint's hostname (`us-ord-10`). Credentials are " +
            "not configured here: they come from the AWS SDK's default chain -- AWS_ACCESS_KEY_ID and " +
            "AWS_SECRET_ACCESS_KEY in the environment, a profile in `~/.aws/credentials` (AWS_PROFILE to pick one), " +
            "or the machine's role -- so they are never in this application's configuration or its secrets file.",
    )

    val s3BucketPrefix = EnvVarDef(
        "KDR_S3_BUCKET_PREFIX", group = ENVGRP.fileStore, defaultDoc = "empty",
        description = "What this deployment's S3 bucket names begin with: the bucket the application calls " +
            "`web-assets` is the S3 bucket `<prefix>web-assets`. S3 bucket names are shared by all its users, so " +
            "a deployment needs names of its own. The buckets must already exist; the application creates none.",
    )

    val s3Endpoint = EnvVarDef(
        "KDR_S3_ENDPOINT", group = ENVGRP.fileStore, defaultDoc = "unset (AWS itself)",
        description = "The URL of a service other than AWS that speaks the S3 API, for an `s3` file store. Tried " +
            "against Linode (Akamai) Object Storage at the endpoint its access key lists, such as " +
            "`https://us-ord-10.linodeobjects.com` (issue #1271). Addressed by path rather than by a bucket's own " +
            "hostname, and an upload through it carries no trailing checksum, which the AWS SDK otherwise adds and " +
            "S3-compatible services need not accept.",
    )

    val stores = EnvVarDef(
        "KDR_FILE_STORES", group = ENVGRP.fileStore, defaultDoc = "none: the default store alone",
        description = "File stores besides the default one (issue #1282), which KDR_FILE_STORE_TYPE and its " +
            "variables describe: `<store>.<attribute>=<value>` entries separated by commas, such as " +
            "`aws.type=s3,aws.region=us-east-1,aws.prefix=acme-,aws.profile=kdr-aws,disk.type=local,disk.dir=/srv/kdr/files`. " +
            "A name is a lowercase word (`default` is the default store's). Attributes: `type` (`memory`, `local` " +
            "or `s3`); `dir` for `local`, required, taken from the workspace directory when relative; `region` for " +
            "`s3`, required, with `endpoint`, `prefix` (of its bucket names) and `profile` (a profile in the AWS " +
            "SDK's credentials file, which signs it in instead of the default chain) optional. Every declared store " +
            "must serve every bucket, as the default must. A client's files go to the store it is assigned (issue " +
            "#1283; until then, and for a client assigned none, the default), and every other file to the default. " +
            "Every node of a deployment declares the same stores, under the same " +
            "names. A mistake in it refuses the start.",
    )

    val check = EnvVarDef(
        "KDR_FILE_STORE_CHECK", group = ENVGRP.fileStore, defaultDoc = "strict, except warn in production",
        description = "The mode of the boot check that the file store can serve every bucket a component " +
            "declared (`strict`, `warn` or `off`): a directory that cannot be created or written to, an S3 bucket " +
            "that does not exist or cannot be reached. Under `warn` the node starts, and storing or reading a " +
            "file in that bucket fails when it is tried.",
    )
}

/**
 * Where the application keeps files (issue #1129): one service over whichever store the deployment runs on --
 * a directory, S3, or memory -- so the code that stores a file never knows which.
 *
 * **Every call names a bucket and a key.** A bucket is the store's own unit of addressing on every provider
 * ([FileBucketDef]) and must be one a component declared; a key is a path within it that the caller composes
 * ([FNAME.keyProblem]). Both are checked here, so a provider is handed only names that passed.
 *
 * **Every file has metadata beside it** ([FileMeta]), written by [put] from the content it is given and the
 * context it is called in. [meta] and [list] read that metadata and nothing else: asking what a file is never
 * fetches it.
 *
 * **Which store** is `KDR_FILE_STORE_TYPE` ([FSENV.type]). Unset, a node running in memory only keeps its files
 * in memory too, and so does every unit test, whether or not it runs in memory otherwise: neither leaves a file
 * behind, and a developer's throwaway server writes nothing into a workspace that is not its own. Any other
 * node keeps them in a directory. S3 is always asked for by name. A store that *is* named is the one used, in
 * a unit test as anywhere -- which is how a test runs on a directory or a stand-in for S3 -- with two refusals
 * that keep a test off what it did not choose: no directory by default ([FSENV.dir] must name one), and no
 * real S3 ([s3ApiConfigKey]). The variable is read as every other is, so one set in the shell that runs the
 * tests reaches them too.
 *
 * **More than one store** (issue #1282): `KDR_FILE_STORES` ([FSENV.stores]) declares stores besides that default
 * one, by name ([FileStoreSpecs]). A client's files -- every key under [FNAME.clientsFolder] -- are reached through
 * [forOwner], which keeps them in the store the client is assigned; everything else through this service's own calls,
 * which are the default store's and refuse a client's key, so no client's file is ever kept where its client's are
 * not. The boot check holds every store to every declared bucket.
 *
 * **Who may read or write a file is not decided here.** A bucket and a key say nothing about whose a file is,
 * so the endpoint that composes the key is the one that has checked the caller.
 */
class FileStoreService : ServiceInitializer {
    override val serviceName: String = FileStoreService.serviceName

    /** Every store this instance keeps files in, by name: the default one first ([FileStoreSpecs.all]). */
    private var stores: Map<String, FileStore> = emptyMap()

    private var buckets: Map<String, FileBucketDef> = emptyMap()
    private var checked = false
    private lateinit var defaultView: FileStoreView

    /** The default store: where every file is kept that is no client's, and every client's not assigned another. */
    val store: FileStore get() = stores.getValue(FSNM.default)

    /** Which kind of store the default one is, an [FST] value. */
    val providerType: String get() = store.providerType

    /** The names of the stores this instance keeps files in, [FSNM.default] first. */
    fun storeNames(): List<String> = stores.keys.toList()

    /** The store called [name], or null when this instance declares none. */
    fun storeNamed(name: String): FileStore? = stores[name]

    override fun onCreate(cxt: KdrCxt) {
        stores = FileStoreSpecs.all(cxt).associateTo(LinkedHashMap()) { it.name to createStore(cxt, it) }
        buckets = SchemaCollector.get(cxt)?.fileBuckets.orEmpty().associateBy { it.name }
        defaultView = FileStoreView(FSNM.default, store, owner = null)
    }

    private fun createStore(cxt: KdrCxt, spec: FileStoreSpec): FileStore = when (spec.type) {
        FST.memory -> MemoryFileStore()
        FST.local -> LocalFileStore(localDir(cxt, spec))
        FST.s3 -> {
            if (spec.name == FSNM.default) {
                FNAME.bucketPrefixProblem(spec.prefix)?.let { throw configError("${FSENV.s3BucketPrefix} is '${spec.prefix}'; $it.") }
            }
            S3FileStore(s3Api(cxt, spec), spec.prefix)
        }
        else -> throw configError("${FSENV.type} is '${spec.type}'; it must be one of ${FST.all.joinToString()}.")
    }

    private fun localDir(cxt: KdrCxt, spec: FileStoreSpec): File {
        // The default is in the workspace, which is no test's to write in: a test on this store says where. A named
        // store always says where (FileStoreSpecs).
        if (spec.name == FSNM.default && cxt.getEnvVar(FSENV.dir).isNullOrBlank() && cxt.instanceConfig.env == ENV.unit) {
            throw configError(
                "The '${FST.local}' file store has no directory by default in the unit test environment -- name " +
                    "one with ${FSENV.dir}, or use the '${FST.memory}' store.",
            )
        }
        return spec.dir ?: localDirOf(cxt)
    }

    private fun s3Api(cxt: KdrCxt, spec: FileStoreSpec): S3Api {
        val standInKey = s3ApiConfigKeyOf(spec.name)
        (cxt.instanceConfig.get(standInKey) as? S3Api)?.let { return it }
        // The unit tests never reach a network (as `OutboundHttpService` refuses them): a test of the S3
        // provider hands in a stand-in for S3, one for each S3 store, so two stores are kept apart there too.
        if (cxt.instanceConfig.env == ENV.unit) {
            throw configError(
                "The S3 file store '${spec.name}' is not available in the unit test environment -- put a stand-in " +
                    "S3Api in the instance config under '$standInKey', or use the '${FST.memory}' store.",
            )
        }
        val region = spec.region ?: throw configError("${FSENV.type} is '${FST.s3}', which needs ${FSENV.s3Region}.")
        val api = SdkS3Api(region, spec.endpoint, spec.profile)
        InstanceRegistry.registerForShutdown(api)
        return api
    }

    private fun configError(message: String): KdrException = FileStoreSpecs.refusal(message)

    /**
     * Makes every declared bucket ready in every store and records what stops any being so, as the boot check
     * [BCHK.fileStore]: strict outside production, a warning in it ([FSENV.check] overrides). A warning leaves
     * the node serving everything that does not touch that bucket, which is the right trade for a store that
     * is one feature among many; the first use of the bucket then fails saying why.
     */
    override fun checkInit(cxt: KdrCxt) {
        if (checked) return
        checked = true
        val mode = bootCheckMode(cxt, modeOverride(cxt, FSENV.check), prodMode = BootCheckMode.warn)
        LogFileStore.info(cxt) { "${storesText()}, ${buckets.size} bucket(s) declared." }
        if (mode == BootCheckMode.off) {
            // Recorded as off, with nothing asked of the store: a check that did not run is said to be one,
            // rather than missing from the report as though there were none.
            BootCheckRegistry.get(cxt).record(BCHK.fileStore, FSENV.check.name, mode)
            return
        }
        // Each finding names its store once there is more than one, so a report says which of them is wrong.
        val findings = stores.flatMap { (name, store) ->
            buckets.keys.flatMap { store.ensureBucket(cxt, it) }.map { if (stores.size == 1) it else "Store '$name': $it" }
        }
        BootCheckRegistry.get(cxt).record(BCHK.fileStore, FSENV.check.name, mode, findings)
        if (findings.isEmpty()) return
        if (mode == BootCheckMode.strict) {
            val which = if (stores.size == 1) "the ${store.providerType} file store cannot" else "the file stores cannot each"
            throw configError("$which serve every declared bucket. ${findings.joinToString(" ")}")
        }
        LogFileStore.warn(cxt) { "File store problems: ${findings.joinToString(" ")}" }
    }

    /** The stores in words, for the startup line and a script's heading. */
    fun storesText(): String =
        if (stores.size == 1) {
            "File store: ${store.providerType} (${store.location})"
        } else {
            "File stores: " + stores.entries.joinToString { (name, store) -> "$name = ${store.providerType} (${store.location})" }
        }

    /** The buckets this instance serves: every one a component declared. */
    fun declaredBuckets(): List<FileBucketDef> = buckets.values.toList()

    private fun declared(bucket: String): String {
        if (bucket !in buckets) {
            // Sensitive: it lists what this deployment keeps files in, which is for whoever wrote the call.
            throw KdrException(
                "The file-store bucket '$bucket' is not declared. A component declares the buckets it keeps " +
                    "files in (SchemaCollector.addFileBucket); declared here: ${buckets.keys.joinToString().ifEmpty { "none" }}.",
                sensitive = true,
            )
        }
        return bucket
    }

    /**
     * The files of the client [owner] (issue #1282) -- its web assets, its file refs, whatever else is kept under its
     * folder ([FNAME.clientKeyPrefix]) -- in the store it keeps them in: today the default for every client, and the
     * one a client is assigned once clients are (issue #1283). The view takes only keys in that folder, so a client's
     * files are never split between stores.
     */
    fun forOwner(cxt: KdrCxt, owner: String): FileStoreView {
        FNAME.clientKeyPrefix(owner)
        val name = storeNameOf(cxt, owner)
        val store = stores[name] ?: throw KdrException(
            "Client '$owner' keeps its files in the file store '$name', which this node does not have " +
                "(it has ${stores.keys.joinToString()}).",
            null, EXC.notAvailable, SRC.config,
        )
        return FileStoreView(name, store, owner)
    }

    /** The name of the store [owner]'s files are kept in. Every client's is the default until clients are assigned one (#1283). */
    fun storeNameOf(cxt: KdrCxt, owner: String): String = FSNM.default

    /**
     * Stores [content] at [key] in [bucket] of the default store -- a key that is no client's ([forOwner]) -- as
     * [FileStoreView.put] does.
     */
    fun put(cxt: KdrCxt, bucket: String, key: String, content: ContentData, extra: Map<String, Any?> = emptyMap()): FileMeta =
        defaultView.put(cxt, bucket, key, content, extra)

    /** The file at [key] in [bucket] of the default store, as [FileStoreView.get] reads one. */
    fun get(cxt: KdrCxt, bucket: String, key: String): StoredFile? = defaultView.get(cxt, bucket, key)

    /** The metadata of the file at [key] in [bucket] of the default store, without its content. */
    fun meta(cxt: KdrCxt, bucket: String, key: String): FileMeta? = defaultView.meta(cxt, bucket, key)

    /** Removes the file at [key] in [bucket] of the default store, and its metadata; whether there was one. */
    fun delete(cxt: KdrCxt, bucket: String, key: String): Boolean = defaultView.delete(cxt, bucket, key)

    /** The files in [bucket] of the default store under [keyPrefix], as [FileStoreView.list] lists them. */
    fun list(cxt: KdrCxt, bucket: String, keyPrefix: String = "", limit: Int = FNAME.defaultListLimit): List<FileEntry> =
        defaultView.list(cxt, bucket, keyPrefix, limit)

    /**
     * One store's files, as a caller reaches them (issue #1282): the default store's that are no client's, or the
     * client [owner]'s in the store it keeps them in ([forOwner]). Every call names a declared bucket and a key the
     * view takes -- under [owner]'s folder, or, for the default view, outside every client's -- and both are checked
     * here, so a provider is handed only names that passed.
     */
    inner class FileStoreView(
        /** Which of the instance's stores this is: [FSNM.default], or one [FSENV.stores] declares. */
        val storeName: String,
        val store: FileStore,
        /** The client whose files these are, or null for the default store's own. */
        val owner: String?,
    ) {
        private val ownFolder: String? = owner?.let { FNAME.clientKeyPrefix(it) }

        private fun checkedKey(key: String): String {
            FNAME.checkKey(key)
            when {
                ownFolder == null && key.startsWith(FNAME.clientsFolder) -> throw KdrException(
                    "The file-store key '$key' is a client's. A client's files are reached through forOwner, which " +
                        "keeps them in the store the client is assigned.",
                )
                ownFolder != null && !key.startsWith(ownFolder) -> throw KdrException(
                    "The file-store key '$key' is not one of client '$owner''s, which are under '$ownFolder'.",
                )
            }
            return key
        }

        /**
         * Stores [content] at [key] in [bucket], replacing a file already there, and answers the metadata written
         * beside it: the content's type, length and SHA-256, the name it arrived under, the moment, the signed-in
         * user who is acting and the client the context is bound to -- the owner the context was given, as a row's
         * owner is, so code storing a file for a client does it in a context bound to that client; one bound to none
         * records the deployment's own. [extra] is kept with them, for whatever else the caller wants to be able to
         * read back without the file -- JSON values, and nothing secret ([FileMeta]).
         *
         * **What is answered is what a later read gives, on every store**: the metadata as its file holds it. So
         * [extra] is kept as its JSON form -- a copy, which the caller's map going on to change does not touch, and
         * a value that is not JSON's (a date, say) as the text it is written as, in memory as on disk.
         */
        fun put(cxt: KdrCxt, bucket: String, key: String, content: ContentData, extra: Map<String, Any?> = emptyMap()): FileMeta {
            declared(bucket)
            checkedKey(key)
            val bytes = content.contentBytes()
            val meta = FileMeta(
                mimeType = content.mimeType,
                size = bytes.size.toLong(),
                hash = bytes.sha256Hex(),
                // To the precision the metadata file holds, so what is answered here is what a later read gives.
                uploadedAt = cxt.now().truncateToMs(),
                fileName = content.saveAsFilename?.let { ContentData.safeFilename(it) }?.takeIf { it.isNotEmpty() },
                uploadedBy = cxt.userProfile.takeIf { it.isLoggedIn }?.userId,
                client = cxt.client,
                extra = extra,
            ).let { FileMeta.fromFileText(it.toFileText()) }
                ?: throw KdrException("The metadata of the file '$key' in the bucket '$bucket' could not be written out and read back.")
            store.put(cxt, bucket, key, bytes, meta)
            return meta
        }

        /**
         * The file at [key] in [bucket], or null when there is none.
         *
         * **The content returned is the content its metadata describes**: its hash is checked against the one
         * recorded, which costs a pass over the content on every read. They differ for a moment while another
         * caller replaces the file -- its content is the new one before its metadata is ([FileStore]) -- so a
         * mismatch is read again, a few times over [rereadPausesMs], which is what lets a replace leave the file
         * readable throughout. One that persists is a stored file that is damaged, which is refused rather than
         * handed on under metadata that is not its own.
         */
        fun get(cxt: KdrCxt, bucket: String, key: String): StoredFile? {
            declared(bucket)
            checkedKey(key)
            var read = store.get(cxt, bucket, key) ?: return null
            for (pauseMs in rereadPausesMs) {
                if (read.bytes.sha256Hex() == read.meta.hash) return read
                Thread.sleep(pauseMs)
                read = store.get(cxt, bucket, key) ?: return null
            }
            if (read.bytes.sha256Hex() == read.meta.hash) return read
            throw KdrException.mkFileIo(
                "The file '$key' in the bucket '$bucket' does not match its metadata (its content hashes to something " +
                    "other than the recorded ${read.meta.hash}).",
                null, EXC.internalError,
            )
        }

        /** The metadata of the file at [key] in [bucket], without its content; null when there is no such file. */
        fun meta(cxt: KdrCxt, bucket: String, key: String): FileMeta? {
            declared(bucket)
            return store.meta(cxt, bucket, checkedKey(key))
        }

        /** Removes the file at [key] in [bucket], and its metadata; whether there was one. */
        fun delete(cxt: KdrCxt, bucket: String, key: String): Boolean {
            declared(bucket)
            return store.delete(cxt, bucket, checkedKey(key))
        }

        /**
         * The files in [bucket] whose key begins with [keyPrefix], in key order, each with its metadata and none with
         * its content: every file this view takes when [keyPrefix] is empty -- the client's, or the default store's
         * that are no client's.
         *
         * **At most [limit] of them, and refused when there are more.** A listing reads a file's metadata for each
         * file it answers -- a request apiece on S3 -- so it is for the folder of one owner's files, and a caller
         * whose prefix takes in more is told so rather than handed the first few as though they were all.
         */
        fun list(cxt: KdrCxt, bucket: String, keyPrefix: String = "", limit: Int = FNAME.defaultListLimit): List<FileEntry> {
            declared(bucket)
            if (limit < 1) throw KdrException("A file listing's limit must be at least 1, not $limit.", null, EXC.badInput)
            val prefix = FNAME.checkKeyPrefix(keyPrefix).ifEmpty { ownFolder.orEmpty() }
            when {
                ownFolder == null && prefix.startsWith(FNAME.clientsFolder) -> throw KdrException(
                    "The file-store prefix '$prefix' is a client's folder, listed through forOwner.",
                )
                ownFolder != null && !prefix.startsWith(ownFolder) -> throw KdrException(
                    "The file-store prefix '$prefix' is not within client '$owner''s folder, '$ownFolder'.",
                )
            }
            // One more than the limit is asked for, which is how "there are more" is told from "exactly that many".
            // The default store's own listing leaves out every client's files, which it does not take.
            val asked = if (limit == Int.MAX_VALUE) limit else limit + 1
            val found = store.list(cxt, bucket, prefix, asked).filter { ownFolder != null || !it.key.startsWith(FNAME.clientsFolder) }
            if (found.size > limit) {
                throw KdrException(
                    "The bucket '$bucket' holds more than $limit files under '$prefix'. A listing is for one folder of " +
                        "files: narrow the prefix, or ask for a higher limit.",
                    null, EXC.badInput,
                )
            }
            return found
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "FileStoreService"

        /** The `local` store's directory when [FSENV.dir] names none, under the workspace directory. */
        const val defaultDirName = "fileStore"

        /**
         * The directory a `local` file store keeps its files in on this node -- the one [FSENV.dir] names, a
         * relative path taken from the workspace directory, else [defaultDirName] there -- **whether or not that is
         * the store in use**. For whoever has to keep out of it: the deployment's own data is there, or will be.
         */
        fun localDirOf(cxt: KdrCxt): File {
            val named = cxt.getEnvVar(FSENV.dir)?.trim()?.takeIf { it.isNotEmpty() } ?: return AppPaths.resolve(defaultDirName)
            return File(named).takeIf { it.isAbsolute } ?: AppPaths.resolve(named)
        }

        /**
         * Instance-config key under which a test hands in the [S3Api] an `s3` store runs over, in place of the
         * AWS SDK: the only way to an S3 store in the unit environment, where no network is reached.
         */
        const val s3ApiConfigKey = "fileStoreS3Api"

        /**
         * The key a test's stand-in [S3Api] for the store [name] goes under: [s3ApiConfigKey] for the default's. Not
         * dotted, since a dotted key is a path into the instance config and would sit inside the default's.
         */
        fun s3ApiConfigKeyOf(name: String): String = if (name == FSNM.default) s3ApiConfigKey else "$s3ApiConfigKey:$name"

        /**
         * How long [get] waits before each further read of a file whose content does not match its metadata: long
         * enough, in all, for a replace to write its metadata after its content, on S3 as on a disk.
         */
        val rereadPausesMs = listOf(10L, 50L, 250L)

        fun get(cxt: KdrCxt): FileStoreService = cxt.instanceConfig.get(serviceName) as? FileStoreService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** As [get], or null on a node that carries no file store (an edge). */
        fun getOrNull(cxt: KdrCxt): FileStoreService? = cxt.instanceConfig.get(serviceName) as? FileStoreService
    }
}
