package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.KdrCxt
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
import com.dynamicruntime.common.gedra.configOwnerOf
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

/** One file ref as a client's registry holds it: what its configuration declared, and the client whose bytes it names. */
class FileRef(
    val def: FileRefDef,
    /** The client the bytes are kept under: the one whose stored configuration holds the entry -- a sandbox's parent. */
    val owner: String,
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
 * ([trialClient]), each problem through [reportConfigProblem] and each costing only the ref it is about. A client
 * is served at most [FREF.maxRefs]: holding more is reported and the rest left out, which is what bounds a load, and
 * is how a write that would take a client over is refused -- by its trial, whichever configuration or call it is.
 *
 * **A problem is worded by the client that owns the configuration**, never by the one loading it. A sandbox loads
 * its parent's drafts, and the trial of the parent's next write has to recognise what the sandbox's load already
 * recorded (`GedraConfigTrial`), or a draft's old problem would refuse every later edit as a new one.
 *
 * **Served under the static root**, at `/<appId>/ref/<refId:hash>` ([serve]), for the *caller's* client, by the
 * rule every file served by its content follows ([HashedFileServing]). A file ref is published material: the
 * static root asks for no sign-in.
 *
 * **Rolling it out.** A node that predates the slot rewrites a configuration without it, so the release that adds
 * file refs is on every node before the first ref is stored (`gedra-config-and-data.md`).
 */
class FileRefService : ServiceInitializer, ContentServer {
    override val serviceName: String = FileRefService.serviceName

    /** Each client's registry, for the clients that have refs. Volatile: a reload swaps it. */
    @Volatile
    var registries: Map<String, FileRefRegistry> = emptyMap()
        private set

    /** What the store answered for one file, and when. */
    private class Asked(val atNanos: Long, val meta: FileMeta?)

    /**
     * What the file store last said of each file, by owner and hash, for [askedMemoMs]. An object is written once
     * and never rewritten, so an answer of "there" stays true unless somebody deletes the object -- which is why it
     * is kept at all, and what makes a reload, a trial and a listing cost only the refs that are new. It is not
     * kept for good: a deleted object would otherwise be reported present by this node until it restarted, while
     * another node said it was missing. "Not there" is kept as long, so a ref whose file is gone is not asked for
     * again by every listing and every write.
     */
    private val asked = ConcurrentHashMap<String, Asked>()

    /** The clock the memo is aged by. A test's to replace; never the instance clock, which a test may travel. */
    var nanoTime: () -> Long = System::nanoTime

    private var isInit = false

    /** Builds every client's registry, and registers this with the dispatcher as a content server (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        if (isInit) return
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.checkInit ran with no schema collector.")
        FileStoreService.get(cxt).checkInit(cxt)
        val configs = collector.gedraConfigs.configs
        registries = configs.filter { it.fileRefs.isNotEmpty() }.map { it.gedraId.client }.distinct()
            .associateWith { build(cxt, configs, it) }
            .filterValues { it.refs.isNotEmpty() }
        RequestService.get(cxt).addContentServer(this)
        isInit = true
    }

    /** Rebuilds [client]'s registry off the current collector and swaps it in: a step of the client's reload. */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val rebuilt = build(cxt, collector.gedraConfigs.configs, client)
        registries = if (rebuilt.refs.isEmpty()) registries - client else registries + (client to rebuilt)
    }

    /**
     * A trial of [client]'s candidate refs over a trial's [scratch] collector: judged as a load judges them, with
     * every problem going to the trial's capture and nothing swapped in. So a write whose ref names bytes that are
     * not in the store is refused as a write, rather than accepted and served as not found.
     */
    fun trialClient(cxt: KdrCxt, scratch: SchemaCollector, client: String) {
        build(cxt, scratch.gedraConfigs.configs, client)
    }

    /** The refs [client]'s people are served; none, for a client that has none. */
    fun forClient(client: String): FileRefRegistry = registries[client] ?: FileRefRegistry.empty

    /**
     * [client]'s registry from the [configs] filed under it, each ref judged and each problem reported
     * ([reportConfigProblem]). Of two configurations declaring one id, the one whose own id sorts first keeps it.
     *
     * A ref is **left out** when it could not be served -- an id that is no name, a source this node does not read,
     * a hash that is not one, an id already taken, a ref in a configuration that may not hold one. It is **kept**
     * when it is well formed and its bytes are not in the store: that is reported, but the entry is not wrong, the
     * file may yet arrive, and serving asks the store itself.
     *
     * **A client is served the first [FREF.maxRefs]** of its well-formed refs, and holding more is one problem, of
     * the client and not of any one ref. Which refs are "the rest" depends on the order the configurations are read
     * in, and a write's trial reads them in another order than a load does -- so a problem naming them would be a
     * new one to every trial, and would refuse even the removal that mends it. As one problem it is refused when a
     * write first takes a client over, and is no obstacle to a client already over taking refs away. (Such a client
     * may still be given more, by an entry typed into its configuration; they are stored and not served.)
     */
    private fun build(cxt: KdrCxt, configs: List<GedraConfig>, client: String): FileRefRegistry {
        val owner = configOwnerOf(client)
        // Asking the store is the stored check's to switch off, with every other check of stored configuration.
        val checksStore = storedConfigCheckMode(cxt) != BootCheckMode.off
        val issues = mutableListOf<GedraConfigIssue>()
        val kept = LinkedHashMap<String, FileRef>()
        // In the order of their ids, not the order they happen to be held in: a load and a write's trial read a
        // client's configurations in different orders, and "the first to declare an id keeps it" has to come out
        // the same in both, or the trial would find the other one the duplicate and call that a new problem.
        val holding = configs.filter { it.gedraId.client == client && it.fileRefs.isNotEmpty() }.sortedBy { it.gedraId.fullId }
        var beyond = 0
        for (config in holding) {
            for (def in config.fileRefs) {
                val problem = defProblem(config, def, owner, kept.keys)
                if (problem != null) {
                    reportConfigProblem(cxt, config.issue(problem, "Leaving the file ref out; the rest of the configuration loads.", GCEL.fileRef, def.refId), issues)
                    continue
                }
                // Past what a client is served: counted, and not asked of the store -- the limit is what bounds that.
                if (kept.size >= FREF.maxRefs) {
                    beyond++
                    continue
                }
                val hash = def.hash.orEmpty()
                if (checksStore && isMissing(cxt, owner, hash)) {
                    reportConfigProblem(
                        cxt,
                        config.issue(
                            "File ref '${def.refId}' of client '$owner' (config '${config.name}') names a file that is not in the file store ($hash).",
                            "Keeping the ref; it is answered as not found until its file is there.",
                            GCEL.fileRef, def.refId,
                        ),
                        issues,
                    )
                }
                kept[def.refId] = FileRef(def, owner)
            }
        }
        if (beyond > 0) {
            // Held by the first of them, as every load and trial orders them, so each says it alike.
            val holder = holding.first()
            reportConfigProblem(
                cxt,
                holder.issue(
                    "Client '$owner' has more than ${FREF.maxRefs} file refs, which is as many as a client is served.",
                    "Serving the first ${FREF.maxRefs}; the rest are left out.",
                    GCEL.fileRef,
                ),
                issues,
            )
        }
        return FileRefRegistry(kept)
    }

    /**
     * What stops [def], declared by [config], being served, or null; [taken] are the ids already kept. Worded by
     * [owner], the client the configuration is stored under, so the same problem reads the same whichever client's
     * load found it.
     */
    private fun defProblem(config: GedraConfig, def: FileRefDef, owner: String, taken: Set<String>): String? {
        FREF.refIdProblem(def.refId)?.let { return "Config '${config.name}' of client '$owner' declares a file ref whose id is no name: $it" }
        val ref = "File ref '${def.refId}' of client '$owner' (config '${config.name}')"
        return when {
            config.gedraId.client == GID.globalClient ->
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

    /**
     * The file store's record of the file [hash] under [owner], or null when it holds none: from what the store
     * last said within [askedMemoMs], else asked again. A record says the store *describes* such a file; whether its
     * bytes are whole is [read]'s to find, on the way out.
     */
    fun metaOf(cxt: KdrCxt, owner: String, hash: String): FileMeta? {
        val memoKey = "$owner/$hash"
        asked[memoKey]?.takeIf { nanoTime() - it.atNanos < askedMemoMs * nanosPerMs }?.let { return it.meta }
        return FileStoreService.get(cxt).meta(cxt, bucket, keyOf(owner, hash)).also { asked[memoKey] = Asked(nanoTime(), it) }
    }

    /** Drops what was last said of the file [hash] under [owner]: an upload has just changed the answer. */
    fun forget(owner: String, hash: String) {
        asked.remove("$owner/$hash")
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
        val owner = configOwnerOf(client)
        val sandbox = sandboxOf(owner)
        return if (ClientService.get(cxt).isPresent(sandbox)) sandbox else owner
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

        /** How long what the store said of a file is gone on: how soon a deleted object is noticed by this node. */
        const val askedMemoMs = 10 * 60_000L

        private const val nanosPerMs = 1_000_000L

        /** Where the file [hash] of [owner]'s is kept in the [bucket]: by its content, so it is written once. */
        fun keyOf(owner: String, hash: String): String = "clients/$owner/refs/$hash"

        fun get(cxt: KdrCxt): FileRefService = cxt.instanceConfig.get(serviceName) as? FileRefService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** Null on a node that keeps no files -- an edge. */
        fun getOrNull(cxt: KdrCxt): FileRefService? = cxt.instanceConfig.get(serviceName) as? FileRefService
    }
}
