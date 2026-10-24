package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.content.IMGS
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FNAME
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.checkMode
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.configOwnerOf
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.MDR
import com.dynamicruntime.common.util.componentImageNames
import com.dynamicruntime.common.util.imageRefIds
import com.dynamicruntime.common.webasset.ClientWebAssetService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Logger for a client's file refs (issue #1153). */
object LogFileRef : KdrLogger("fileRef")

/** One file ref as a client's registry holds it: what its configuration declared, and the client whose bytes it names. */
class FileRef(
    val def: FileRefDef,
    /** The client the bytes are kept under: the one whose stored configuration holds the entry -- a sandbox's parent. */
    val owner: String,
    /**
     * The template the ref came from, when the client inherits it rather than declares it (issue #1180): a client's
     * own local file is then looked for first, and the template's is its default ([LocalFileSource]).
     */
    val inheritedFrom: String? = null,
) {
    val refId: String get() = def.refId
}

/**
 * The file refs one client's people are served, by id -- and how many well-formed refs it **holds** ([held]), which
 * past the limit is more than it is served, with how many of those a stored configuration holds ([heldStored]): what
 * a client at the limit is told it must take away before it can add one (issue #1169).
 */
class FileRefRegistry(
    val refs: Map<String, FileRef>,
    val held: Int = refs.size,
    val heldStored: Int = refs.values.count { it.def.stored },
) {
    companion object {
        val empty = FileRefRegistry(emptyMap())
    }
}

/**
 * A client's file refs at run time (issues #1153, #1154): the named files its configuration holds, each a pointer to
 * bytes kept somewhere this asks a [FileRefSource] for -- the file store, by the hash of what was uploaded
 * ([StoreFileSource]), or a file on this machine, by a root and a path ([LocalFileSource]).
 *
 * **Configuration, unlike a client's web assets.** A ref is an entry of a config bundle (`kdr:fileRefDef`), so it
 * is drafted, previewed and published with the copy that names it. This holds no rule of its own for any of that:
 * a registry is built from the configurations the collector holds for a client, and the collector already holds
 * what each client consumes -- the published revisions for a client that runs only those, the latest for its
 * sandbox. So a sandbox previews a ref its parent has only drafted, and the parent serves the one it published.
 *
 * **What is stored overrides what is shipped.** Source configuration may declare a ref to a local file, and a ref
 * of the same id in stored configuration takes its place -- as a piece of stored copy takes the place of the
 * source's -- so uploading over a shipped image replaces it, and removing the upload reveals it again.
 *
 * **Built when a client loads** ([checkInit], [reloadClient]) and judged in the trial a configuration write runs
 * ([trialClient]), each problem through [reportConfigProblem] and each costing only the ref it is about. A client
 * is served at most [FREF.maxRefs]: holding more is reported and the rest left out, which is what bounds a load, and
 * is how a write that would take a client over is refused -- by its trial, whichever configuration or call it is.
 *
 * **A problem is worded by the client that owns the configuration**, never by the one loading it. A sandbox loads
 * its parent's drafts, and the trial of the parent's next write has to recognize what the sandbox's load already
 * recorded (`GedraConfigTrial`), or a draft's old problem would refuse every later edit as a new one.
 *
 * **Served under the static root**, at `/<appId>/ref/<refId:hash>` ([serve]), for the *caller's* client, by the
 * rule every file served by its content follows ([HashedFileServing]). A file ref is published material: the
 * static root asks for no sign-in.
 *
 * **Rolling it out.** A node that predates the slot rewrites a configuration without it, so the release that adds
 * file refs is on every node before the first ref is stored (`docs/design/gedra/config-and-data.md`).
 */
class FileRefService : ServiceInitializer, ContentServer {
    override val serviceName: String = FileRefService.serviceName

    /** Each client's registry, for the clients that have refs. Volatile: a reload swaps it. */
    @Volatile
    var registries: Map<String, FileRefRegistry> = emptyMap()
        private set

    /** The file store as a source: there from the start, since it is what an upload writes to. */
    val store = StoreFileSource()

    /** What [servedNames] last answered for a client, and when (issue #1186). */
    private class Served(val atNanos: Long, val names: Map<String, String>)

    /** [servedNames]' answers by client, for [servedMemoMs]; a client's is dropped when it reloads. */
    private val served = ConcurrentHashMap<String, Served>()

    /** Counts the reloads, so an answer worked out while one landed is not kept over it. */
    private val reloads = AtomicLong()

    /** The local files this node reads, once this is set up ([checkInit]); null before, and so "none". */
    @Volatile
    var localRoots: LocalFileRoots? = null
        private set

    /**
     * Whether a client's own administrator may name a file in the root [root] (issue #1164): the deployment opened
     * it to clients. What the write of a client's own local ref is judged by (`GedraConfigService`), and what a
     * client is told it may name from ([clientRoots]).
     */
    fun opensToClients(root: String): Boolean = localRoots?.isOpenToClients(root) == true

    /** The roots open to clients, by name, in order. */
    fun clientRoots(): List<String> = localRoots?.clientRoots.orEmpty().sorted()

    /** The sources a ref may name, by what an entry calls them. Local files join when this is set up ([checkInit]). */
    @Volatile
    var sources: Map<String, FileRefSource> = mapOf(store.source to store)
        private set

    /** The clock the store's memo is aged by ([StoreFileSource.nanoTime]). A test's to replace. */
    var nanoTime: () -> Long
        get() = store.nanoTime
        set(value) {
            store.nanoTime = value
        }

    private var isInit = false

    /** Builds every client's registry, and registers this with the dispatcher as a content server (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        if (isInit) return
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.checkInit ran with no schema collector.")
        FileStoreService.get(cxt).checkInit(cxt)
        // Refuses the start over a declaration that is wrong (see there). One that names nothing yet is only said.
        val roots = LocalFileRoots.resolve(cxt)
        localRoots = roots
        sources = sources + (FREF.sourceLocal to LocalFileSource(roots)) +
            (FREF.sourceClientLocal to LocalFileSource(roots, FREF.sourceClientLocal))
        LogFileRef.info(cxt) { "Local files a file ref may name (${LFENV.dir.name}): ${roots.describe()}." }
        val configs = collector.gedraConfigs.configs
        // A client whose copy names a ref is judged too, refs or none: copy naming one it lacks is its problem.
        registries = configs.filter { it.fileRefs.isNotEmpty() || it.fragments.isNotEmpty() }.map { it.gedraId.client }.distinct()
            .associateWith { build(cxt, configs, it, running = null) }
            .filterValues { it.refs.isNotEmpty() }
        RequestService.get(cxt).addContentServer(this)
        isInit = true
    }

    /**
     * Rebuilds [client]'s registry off the current collector and swaps it in: a step of the client's reload.
     *
     * **A ref this node already serves is not refused for its file having gone** -- it is reported, and kept. A
     * file goes with no configuration changing, so the write that led to this reload could not have been refused
     * for it (`GedraConfigTrial.fileRefBaseline`) and is stored by now: refusing here, where problems are strict,
     * would fail every later save of the client after it was committed. A ref the reload *brings* is held as
     * strictly as ever.
     */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val rebuilt = build(cxt, collector.gedraConfigs.configs, client, running = forClient(client))
        registries = if (rebuilt.refs.isEmpty()) registries - client else registries + (client to rebuilt)
        reloads.incrementAndGet()
        served.remove(client)
    }

    /**
     * A trial of [client]'s candidate refs over a trial's [scratch] collector: judged as a load judges them, with
     * every problem going to the trial's capture and nothing swapped in. So a write whose ref names bytes that are
     * not in the store is refused as a write, rather than accepted and served as not found.
     */
    fun trialClient(cxt: KdrCxt, scratch: SchemaCollector, client: String) {
        build(cxt, scratch.gedraConfigs.configs, client, running = null)
    }

    /**
     * The problems [client]'s refs have **now**, judged again over the configurations this node runs for it:
     * nothing reported, nothing swapped in. What a write's trial is compared with (`GedraConfigTrial`), since
     * whether a file is there can change with no configuration changing.
     */
    fun problemsNow(cxt: KdrCxt, client: String): List<GedraConfigIssue> {
        val collector = SchemaCollector.get(cxt) ?: return emptyList()
        val capture = mutableListOf<GedraConfigIssue>()
        val asking = cxt.mkSubContext("fileRefsNow", client).also { it.locals[GCFG.trialCaptureKey] = capture }
        build(asking, collector.gedraConfigs.configs, client, running = null)
        return capture
    }

    /** The refs [client]'s people are served; none, for a client that has none. */
    fun forClient(client: String): FileRefRegistry = registries[client] ?: FileRefRegistry.empty

    /**
     * The refs [client]'s people are served, as the shell config carries them (issue #1186): each id to its file
     * by name and content, `logo:<hash>` -- what copy naming the image `ref:logo` is drawn from. A ref whose file
     * is not there is left out, so copy naming it draws its alt text.
     *
     * **Kept for [servedMemoMs]**, and dropped when the client reloads -- which every upload, replacement, and
     * removal of a ref does -- since the shell config asks for it on every page a client's people open, and a local
     * ref costs a look at the disk apiece. What only the disk changes (a local file edited in place) is a new name
     * that long later; the old name is answered meanwhile, with the file as it now is and nothing kept of it.
     *
     * **Never fails the shell.** A ref that cannot be asked after is left out and said in the log: a store that is
     * down costs a client its images in copy, not its app.
     */
    fun servedNames(cxt: KdrCxt, client: String): Map<String, String> {
        served[client]?.takeIf { nanoTime() - it.atNanos < servedMemoMs * nanosPerMs }?.let { return it.names }
        val before = reloads.get()
        val names = namesNow(cxt, client)
        if (reloads.get() == before) served[client] = Served(nanoTime(), names)
        return names
    }

    /** [servedNames] worked out: each ref asked of its source ([metaOf]). */
    private fun namesNow(cxt: KdrCxt, client: String): Map<String, String> = buildMap {
        for (ref in forClient(client).refs.values) {
            val meta = try {
                metaOf(cxt, ref)
            } catch (e: KdrException) {
                LogFileRef.warn(cxt) { "Could not ask after the file of ref '${ref.refId}' of client '$client'; leaving it out of the shell. ${e.message}" }
                null
            } ?: continue
            put(ref.refId, FREF.versionedName(ref.refId, meta.hash))
        }
    }

    /**
     * [client]'s registry from the [configs] filed under it, each ref judged and each problem reported
     * ([reportConfigProblem]).
     *
     * **Whose ref an id is.** A client's stored configurations are read before its source ones, and each in the
     * order of their ids. A source ref whose id a stored one keeps is **overridden**, which is no problem and is
     * not reported (see the class note); any other second declaration of an id is, and the first keeps it.
     *
     * A ref is **left out** when it could not be served -- an id that is no name, a source this node does not read,
     * an entry its source cannot read ([FileRefSource.defProblem]), an id already taken, a ref in a configuration
     * that may not hold one. It is **kept** when it is well formed and its file is not there
     * ([FileRefSource.fileProblem]): that is reported, but the entry is not wrong, the file may yet arrive, and
     * serving asks again.
     *
     * **A client is served the first [FREF.maxRefs]** of its well-formed refs, and holding more is one problem, of
     * the client and not of any one ref. Which refs are "the rest" depends on the order the configurations are read
     * in, and a write's trial reads them in another order than a load does -- so a problem naming them would be a
     * new one to every trial, and would refuse even the removal that mends it. As one problem it is refused when a
     * write first takes a client over, and is no obstacle to a client already over taking refs away. It is held by
     * no configuration (issue #1169), since which one holds the first ref changes as refs come and go. (Such a client
     * may still be given more, by an entry typed into its configuration; they are stored and not served.)
     *
     * [running] is the registry a reload is replacing, and null for a boot and a trial: see [reloadClient].
     */
    private fun build(cxt: KdrCxt, configs: List<GedraConfig>, client: String, running: FileRefRegistry?): FileRefRegistry {
        val owner = configOwnerOf(client)
        val issues = mutableListOf<GedraConfigIssue>()
        val kept = LinkedHashMap<String, FileRef>()
        // In an order of their own, not the order they happen to be held in: a load and a write's trial read a
        // client's configurations in different orders, and "the first to declare an id keeps it" has to come out
        // the same in both, or the trial would find the other one the duplicate and call that a new problem.
        val holding = configs.filter { it.gedraId.client == client && it.fileRefs.isNotEmpty() }
            .sortedWith(compareBy({ config -> if (config.fileRefs.any { it.stored }) 0 else 1 }, { it.gedraId.fullId }))
        var beyond = 0
        var beyondStored = 0
        // The ids counted past the limit: a second declaration of one -- what is shipped beneath a stored override,
        // or a template's ref beneath the client's -- is the same ref, past the limit too, and counted once.
        val beyondIds = HashSet<String>()
        for (config in holding) {
            // Asking after a ref's file is the configuration's own check's to switch off, with its other checks.
            val checksFile = config.checkMode(cxt) != BootCheckMode.off
            for (def in config.fileRefs) {
                val holder = kept[def.refId]
                // What the client stored in place of what it was shipped with.
                if (holder != null && holder.def.stored && !def.stored) continue
                val problem = defProblem(config, def, owner, holder != null)
                if (problem != null) {
                    reportConfigProblem(cxt, config.issue(problem, "Leaving the file ref out; the rest of the configuration loads.", GCEL.fileRef, def.refId), issues)
                    continue
                }
                // Past what a client is served: counted, and its file not asked after -- the limit is what bounds that.
                if (kept.size >= FREF.maxRefs) {
                    if (beyondIds.add(def.refId)) {
                        beyond++
                        if (def.stored) beyondStored++
                    }
                    continue
                }
                val ref = FileRef(def, owner, config.inheritedFrom)
                val fileProblem = if (checksFile) sources.getValue(def.source).fileProblem(cxt, ref) else null
                if (fileProblem != null) {
                    reportConfigProblem(
                        cxt,
                        config.issue(
                            "File ref '${def.refId}' of client '$owner' (config '${config.name}') $fileProblem",
                            "Keeping the ref; it is answered as not found until its file is there.",
                            GCEL.fileRef, def.refId,
                        ),
                        issues,
                        // The same ref, naming the same file, as the client is served now: its file has gone.
                        aroseWhileRunning = running?.refs?.get(def.refId)?.def?.pointer == def.pointer,
                    )
                }
                kept[def.refId] = ref
            }
        }
        if (beyond > 0) {
            // The client's, held by no configuration (issue #1169): which configuration held the first of its refs
            // changes as refs come and go -- the first stored one emptied, the first upload over a shipped one -- and
            // a problem named by it would be a new one to the trial of a write that only takes refs away or replaces
            // a file. Judged as stored configuration once the client's own data holds any ref, and as source code
            // while only what it was shipped with does, which is strict where a mistake in source is caught.
            reportConfigProblem(
                cxt,
                GedraConfigIssue(
                    "Client '$owner' has more than ${FREF.maxRefs} file refs, which is as many as a client is served.",
                    "Serving the first ${FREF.maxRefs}; the rest are left out.",
                    client = client,
                    elementKind = GCEL.fileRef,
                    judgedAs = if (holding.any { it.isStored }) GedraConfigOrigin.stored else GedraConfigOrigin.source,
                ),
                issues,
            )
        }
        reportCopyNamingNoRef(cxt, configs, client, owner, kept, issues)
        return FileRefRegistry(kept, held = kept.size + beyond, heldStored = kept.values.count { it.def.stored } + beyondStored)
    }

    /**
     * Reports each image the copy of [client]'s own configurations names by a file ref ([MDR.refScheme],
     * `![alt](ref:logo)`) that is not among the refs [kept] for it (issue #1186): a problem of the copy, as a `$ref`
     * to a type the client does not have is. Judged here, beside the refs, so that one build judges both -- a load, a
     * reload and a write's trial alike -- and a write that adds copy naming a ref, or takes away a ref copy still
     * names, is refused where it is written. The copy is kept: the image is drawn as its alt text.
     *
     * A ref kept and its file missing is not this problem (its own is said above): copy may name it, and it is
     * drawn as its alt text until the file is there. Worded by [owner], as the ref problems are, so a sandbox and
     * its parent say it alike.
     *
     * An image the copy names that no component ships ([MDR.imageScheme], `![alt](img:sample:diagram)`, issue #1339) is
     * the same problem, said the same way: what copy every client shares names is the fragment check's to judge.
     */
    private fun reportCopyNamingNoRef(
        cxt: KdrCxt,
        configs: List<GedraConfig>,
        client: String,
        owner: String,
        kept: Map<String, FileRef>,
        issues: MutableList<GedraConfigIssue>,
    ) {
        val namePrefix = MDR.refScheme + ":"
        val imagePrefix = MDR.imageScheme + ":"
        val images = IMGS.declaredNames(cxt)
        for (config in configs) {
            if (config.gedraId.client != client || config.fragments.isEmpty() || config.checkMode(cxt) == BootCheckMode.off) continue
            for (layer in config.fragments) {
                val content = layer.load() ?: continue
                for ((namespace, keys) in content) {
                    for ((key, value) in keys) {
                        // An image no component ships (issue #1339), judged with the refs: the same copy, the same build.
                        if (value.contains(imagePrefix)) {
                            for (name in value.componentImageNames()) {
                                if (name in images) continue
                                reportConfigProblem(
                                    cxt,
                                    config.issue(
                                        "$namespace.$key (file '${layer.fileId}' of client '$owner', config '${config.name}') " +
                                            "shows the image $imagePrefix$name, which no component ships.",
                                        "Keeping the copy; the image is drawn as its alt text.",
                                        GCEL.fragment, layer.fileId,
                                    ),
                                    issues,
                                )
                            }
                        }
                        // Cheap enough to ask of every value, so the parse is for the few that may name one.
                        if (!value.contains(namePrefix)) continue
                        for (refId in value.imageRefIds()) {
                            if (refId in kept) continue
                            // A target that could be no ref's id -- a placeholder, a hyphen -- is said as such: the
                            // remedy is to the copy, not a ref to add.
                            val why = FREF.refIdProblem(refId)?.let { "which is no file ref's id: $it" }
                                ?: "and the client has no file ref '$refId'"
                            reportConfigProblem(
                                cxt,
                                config.issue(
                                    "$namespace.$key (file '${layer.fileId}' of client '$owner', config '${config.name}') " +
                                        "shows the image $namePrefix$refId, $why.",
                                    "Keeping the copy; the image is drawn as its alt text.",
                                    GCEL.fragment, layer.fileId,
                                ),
                                issues,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * What stops [def], declared by [config], being served, or null; [taken] is whether its id is already kept.
     * Worded by [owner], the client the configuration is stored under, so the same problem reads the same whichever
     * client's load found it.
     */
    private fun defProblem(config: GedraConfig, def: FileRefDef, owner: String, taken: Boolean): String? {
        FREF.refIdProblem(def.refId)?.let { return "Config '${config.name}' of client '$owner' declares a file ref whose id is no name: $it" }
        val ref = "File ref '${def.refId}' of client '$owner' (config '${config.name}')"
        if (config.gedraId.client == GID.globalClient) {
            return "$ref is declared by a global configuration. A file ref is a client's own: there is no client whose files a global one would be."
        }
        val source = sources[def.source]
            ?: return "$ref has the source '${def.source}', which this node does not read; the sources it reads are ${sources.keys.sorted().joinToString { "'$it'" }}."
        source.defProblem(def, ref)?.let { return it }
        return if (taken) "$ref is declared more than once among the client's configurations. The first is kept." else null
    }

    /**
     * What is known of [ref]'s file without its content -- the file store's record of it, or for a local file what
     * reading it found -- or null when there is none to serve.
     */
    fun metaOf(cxt: KdrCxt, ref: FileRef): FileMeta? = sources[ref.def.source]?.meta(cxt, ref)

    /**
     * As [metaOf], but what [read] would find now, without the content ([FileRefSource.currentMeta], issue #1131):
     * what a meta-only read beside the download answers.
     */
    fun currentMetaOf(cxt: KdrCxt, ref: FileRef): FileMeta? = sources[ref.def.source]?.currentMeta(cxt, ref)

    /**
     * Why [ref]'s file is not there to be served, as a load would say it, or null when it is -- or when its source
     * is one this node does not read. For a call that would rather refuse before it writes than have its trial do it.
     */
    fun fileProblem(cxt: KdrCxt, ref: FileRef): String? = sources[ref.def.source]?.fileProblem(cxt, ref)

    /** Drops what the store was last said to hold of the file [hash] under [owner]: an upload has just changed the answer. */
    fun forget(owner: String, hash: String) {
        store.forget(owner, hash)
    }

    /** The file [ref] names, content and what describes it, or null when its source has none to serve ([FileRefSource.read]). */
    fun read(cxt: KdrCxt, ref: FileRef): StoredFile? = sources[ref.def.source]?.read(cxt, ref)

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

        /** How long the shell config's names of a client's refs ([servedNames]) are kept, as a client's web assets are. */
        const val servedMemoMs = 30_000L

        private const val nanosPerMs = 1_000_000L

        /** Where the file [hash] of [owner]'s is kept in the [bucket]: by its content, so it is written once. */
        fun keyOf(owner: String, hash: String): String = "${FNAME.clientKeyPrefix(owner)}refs/$hash"

        fun get(cxt: KdrCxt): FileRefService = cxt.instanceConfig.get(serviceName) as? FileRefService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** Null on a node that keeps no files -- an edge. */
        fun getOrNull(cxt: KdrCxt): FileRefService? = cxt.instanceConfig.get(serviceName) as? FileRefService
    }
}
