package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.storedConfigCheckMode
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.common.webasset.WebAssetCheck
import java.util.concurrent.ConcurrentHashMap

/** Logger for a client's file refs (issue #1153). */
object LogFileRef : KdrLogger("fileRef")

/**
 * One file ref as a client's registry holds it: what its configuration declared, the client whose bytes it names,
 * and whether the load found those bytes missing.
 */
class FileRef(
    val def: FileRefDef,
    /** The client the bytes are kept under: the one whose stored configuration holds the entry -- a sandbox's parent. */
    val owner: String,
    /** Whether the file store held no such file when the client loaded. Advice for a listing: serving asks the store. */
    val missing: Boolean,
) {
    val refId: String get() = def.refId

    /** The hash of the ref's bytes. Never null for a ref a registry kept. */
    val hash: String get() = def.hash.orEmpty()

    /** The ref by name and content, as a listing hands it out and a static address carries it. */
    val name: String get() = FREF.versionedName(refId, hash)
}

/** The file refs one client's people are served, by id. */
class FileRefRegistry(val refs: Map<String, FileRef>) {
    companion object {
        val empty = FileRefRegistry(emptyMap())
    }
}

/**
 * A client's file refs at run time (issue #1153): the named files its stored configuration holds, each a pointer --
 * by the hash of its bytes -- to a file in the file store.
 *
 * **Configuration, unlike a client's web assets.** A ref is an entry of a config bundle (`kdr:fileRefDef`), so it
 * is drafted, previewed and published with the copy that names it. This holds no rule of its own for any of that:
 * a registry is built from the configurations the collector holds for a client, and the collector already holds
 * what each client consumes -- the published revisions for a client that runs only those, the latest for its
 * sandbox. So a sandbox previews a ref its parent has only drafted, and the parent serves the one it published.
 *
 * **Bytes are kept by their hash**, in [bucket] at `clients/<owner>/refs/<hash>`, where the owner is the client whose
 * stored configuration holds the entry. An object is written once and never rewritten, so no upload can change the
 * bytes a published revision names; two refs with the same bytes share one object; and removing a ref leaves its
 * object, which older revisions still name.
 *
 * **Built when a client loads** ([checkInit], [reloadClient]) and judged in the trial a configuration write runs
 * ([trialClient]), each problem through [reportConfigProblem] and each costing only the ref it is about.
 *
 * **Served under the static root**, at `/<appId>/ref/<refId:hash>` ([serve]), for the *caller's* client, by the
 * rule every file served by its content follows ([HashedFileServing]). A file ref is published material: the
 * static root asks for no sign-in.
 */
class FileRefService : ServiceInitializer, ContentServer {
    override val serviceName: String = FileRefService.serviceName

    /** Each client's registry, for the clients that have refs. Volatile: a reload swaps it. */
    @Volatile
    var registries: Map<String, FileRefRegistry> = emptyMap()
        private set

    /**
     * The metadata of every file a load found, by owner and hash. Kept for the node's life: an object is never
     * rewritten, so what was found once need not be asked for again -- which is what makes a reload cost only the
     * refs that are new. A file that was *not* found is not remembered, and is asked for again by the next load.
     */
    private val found = ConcurrentHashMap<String, FileMeta>()

    private var isInit = false

    /** Builds every client's registry, and registers this with the dispatcher as a content server (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        if (isInit) return
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.checkInit ran with no schema collector.")
        FileStoreService.get(cxt).checkInit(cxt)
        val configs = collector.gedraConfigs.configs
        val issues = mutableListOf<GedraConfigIssue>()
        registries = configs.filter { it.fileRefs.isNotEmpty() }.map { it.gedraId.client }.distinct()
            .associateWith { build(cxt, configs, it, issues) }
            .filterValues { it.refs.isNotEmpty() }
        RequestService.get(cxt).addContentServer(this)
        isInit = true
    }

    /** Rebuilds [client]'s registry off the current collector and swaps it in: a step of the client's reload. */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val rebuilt = build(cxt, collector.gedraConfigs.configs, client, mutableListOf())
        registries = if (rebuilt.refs.isEmpty()) registries - client else registries + (client to rebuilt)
    }

    /**
     * A trial of [client]'s candidate refs over a trial's [scratch] collector: judged as a load judges them, with
     * every problem going to the trial's capture and nothing swapped in. So a write whose ref names bytes that are
     * not in the store is refused as a write, rather than accepted and served as not found.
     */
    fun trialClient(cxt: KdrCxt, scratch: SchemaCollector, client: String) {
        build(cxt, scratch.gedraConfigs.configs, client, mutableListOf())
    }

    /** The refs [client]'s people are served; none, for a client that has none. */
    fun forClient(client: String): FileRefRegistry = registries[client] ?: FileRefRegistry.empty

    /**
     * [client]'s registry from the [configs] filed under it, each ref judged and each problem reported
     * ([reportConfigProblem]) and added to [issues]. The first configuration to declare an id keeps it.
     *
     * A ref is **dropped** when it could not be served at all -- an id that is no name, a source this node does not
     * read, a hash that is not one, an id already taken, a ref in a configuration that may not hold one. It is
     * **kept, marked missing**, when it is well formed and its bytes are not in the store: the entry is not wrong,
     * and the file may yet arrive.
     */
    private fun build(cxt: KdrCxt, configs: List<GedraConfig>, client: String, issues: MutableList<GedraConfigIssue>): FileRefRegistry {
        val owner = ownerOf(client)
        // Asking the store is the stored check's to switch off, with every other check of stored configuration.
        val checksStore = storedConfigCheckMode(cxt) != BootCheckMode.off
        val kept = LinkedHashMap<String, FileRef>()
        for (config in configs) {
            if (config.gedraId.client != client) continue
            for (def in config.fileRefs) {
                val problem = defProblem(config, def, kept.keys)
                if (problem != null) {
                    reportConfigProblem(cxt, config.issue(problem, "Dropping the file ref; the rest of the configuration loads.", GCEL.fileRef, def.refId), issues)
                    continue
                }
                val hash = def.hash.orEmpty()
                val missing = checksStore && isMissing(cxt, owner, hash)
                if (missing) {
                    reportConfigProblem(
                        cxt,
                        config.issue(
                            "File ref '${def.refId}' of client '$client' names a file that is not in the file store ($hash).",
                            "Keeping the ref; it is answered as not found until its file is there.",
                            GCEL.fileRef, def.refId,
                        ),
                        issues,
                    )
                }
                kept[def.refId] = FileRef(def, owner, missing)
            }
        }
        return FileRefRegistry(kept)
    }

    /** What stops [def], declared by [config], being a ref at all, or null; [taken] are the ids already kept. */
    private fun defProblem(config: GedraConfig, def: FileRefDef, taken: Set<String>): String? {
        val client = config.gedraId.client
        FREF.refIdProblem(def.refId)?.let { return "Config '${config.name}' of client '$client' declares a file ref whose id is no name: $it" }
        val ref = "File ref '${def.refId}' of client '$client' (config '${config.name}')"
        return when {
            client == GID.globalClient ->
                "$ref is declared by a global configuration. A file ref is a client's own: there is no client whose files a global one would be."
            def.source != FREF.sourceStore ->
                "$ref has the source '${def.source}', which this node does not read; the source it reads is '${FREF.sourceStore}'."
            !def.stored ->
                "$ref is declared in source code. A ref names bytes in one deployment's file store, and source " +
                    "configuration runs on every machine, so only stored configuration may hold one."
            !FREF.isHash(def.hash.orEmpty()) ->
                "$ref has the hash '${def.hash.orEmpty()}', which is not one: a SHA-256 is ${FREF.hashLength} lowercase hex characters."
            def.refId in taken ->
                "$ref is declared more than once among the client's configurations. The first is kept."
            else -> null
        }
    }

    /**
     * Whether the store holds no file for [hash] under [owner]. A store that cannot be asked is not a missing file
     * and not a fault of the configuration: it is logged, and the ref is taken as it stands.
     */
    private fun isMissing(cxt: KdrCxt, owner: String, hash: String): Boolean =
        try {
            metaOf(cxt, owner, hash) == null
        } catch (e: KdrException) {
            LogFileRef.warn(cxt) { "Could not ask the file store for the file '$hash' of client '$owner'; taking its ref as it stands. ${e.message}" }
            false
        }

    /** The file store's record of the file [hash] under [owner], or null when it holds none; remembered once found. */
    fun metaOf(cxt: KdrCxt, owner: String, hash: String): FileMeta? {
        val memoKey = "$owner/$hash"
        found[memoKey]?.let { return it }
        return FileStoreService.get(cxt).meta(cxt, bucket, keyOf(owner, hash))?.also { found[memoKey] = it }
    }

    /**
     * The file [ref] names, content and metadata, or null when the store holds none.
     *
     * Two things are held to here, on every read, that an entry cannot be trusted for: the file is **the one the
     * ref names** -- its recorded hash is the ref's, which a key by content makes true unless something wrote the
     * key directly -- and it is **a kind of file a ref may be**, by the type the checked upload recorded. Anything
     * else is no file, said in the log.
     */
    fun read(cxt: KdrCxt, ref: FileRef): StoredFile? {
        val file = FileStoreService.get(cxt).get(cxt, bucket, keyOf(ref.owner, ref.hash)) ?: return null
        if (file.meta.hash != ref.hash || WebAssetCheck.declaredKind(file.meta.mimeType) == null) {
            LogFileRef.warn(cxt) {
                "The file kept for ref '${ref.refId}' of client '${ref.owner}' is not what the ref names (hash " +
                    "${file.meta.hash}, type ${file.meta.mimeType}); answering as though there were none."
            }
            return null
        }
        return file
    }

    /**
     * The client whose registry an administrator of [client] is shown and previews from: its sandbox's where it has
     * one -- the sandbox runs the latest revision, so that is where a draft is -- and otherwise its own.
     */
    fun shownAs(cxt: KdrCxt, client: String): String {
        val owner = ownerOf(client)
        val sandbox = sandboxOf(owner)
        return if (ClientService.get(cxt).isPresent(sandbox)) sandbox else owner
    }

    /** [client], having checked that this node knows it; a 404 otherwise. What every call that names a client goes through. */
    fun knownClient(cxt: KdrCxt, client: String): String {
        if (ClientService.get(cxt).known(client) == null) {
            throw KdrException("This node knows no client '$client'.", code = EXC.notFound)
        }
        return client
    }

    /**
     * Serves `/<appId>/ref/<refId:hash>` under the static root: that ref of the **caller's** client, whatever hash
     * the address carries. The hash decides only how the answer may be kept ([HashedFileServing]).
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        val address = HashedFileServing.addressOf(handler, CMK.ref) ?: return false
        val file = forClient(cxt.client).refs[address.name]?.let { read(cxt, it) }
        HashedFileServing.send(handler, address, file, "No file ref '${address.name}'.")
        return true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "FileRefService"

        /** The file-store bucket a client's file refs are kept in: the one its web assets are, under a prefix of their own. */
        const val bucket = ClientWebAssetService.bucket

        /** The client a ref's bytes are kept under for [client]: its own, or for a sandbox its parent's. */
        fun ownerOf(client: String): String = sandboxParentOf(client) ?: client

        /** Where the file [hash] of [owner]'s is kept in the [bucket]: by its content, so it is written once. */
        fun keyOf(owner: String, hash: String): String = "clients/$owner/refs/$hash"

        fun get(cxt: KdrCxt): FileRefService = cxt.instanceConfig.get(serviceName) as? FileRefService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** Null on a node that keeps no files -- an edge. */
        fun getOrNull(cxt: KdrCxt): FileRefService? = cxt.instanceConfig.get(serviceName) as? FileRefService
    }
}
