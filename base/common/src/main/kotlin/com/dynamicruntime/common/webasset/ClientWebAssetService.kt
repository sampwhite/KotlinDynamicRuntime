package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.byteSizeText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Logger for a client's web assets (issue #1130). */
object LogWebAsset : KdrLogger("webAsset")

/** One slot of a client's web assets: its wire name, what a person calls it, and the most bytes its image may be. */
class WebSlotDef(val slot: String, val label: String, val maxSize: Int)

/**
 * A client's own web assets (issue #1130): the tab icon and the brand mark its administrator uploads, which stand
 * in for the deployment's for that client's people.
 *
 * **Kept in the file store**, in the [bucket], at `clients/<client>/web/<slot>` -- keyed by the client's id, which
 * is an identifier and so a key segment as it stands. The metadata the store keeps beside each image (its type, its
 * size, its hash, who uploaded it and when) is the whole of what this knows about one; there is no table.
 *
 * **Not configuration.** An upload is not part of a client's stored configuration, so it has no draft and no
 * publish: it replaces the image for everyone in the client as soon as it is stored. That is why a **sandbox has
 * none of its own** ([ownerOf]) -- it shows its parent's, since it is the parent it previews -- and why changing
 * them is refused there: nothing done in a sandbox may change what the parent's people see.
 *
 * **Served under the static root**, at `/<appId>/web/<slot:hash>` ([serve]), for the *caller's* client. The
 * address names a slot and the hash of an image, never a client: whose image is served is decided from who is
 * signed in, as a fragment's copy is, so a caller cannot ask for another client's by spelling an address.
 *
 * **Other nodes see an upload through the store**, which they share. Each node keeps what a client's slots hold
 * for [memoMs] ([slots]), since the shell config asks on every page load and the answer is a read of the store --
 * a request apiece on S3. So an upload shows at once on the node that took it, which drops what it held, and
 * within that long on the others.
 */
class ClientWebAssetService : ServiceInitializer, ContentServer {
    override val serviceName: String = ClientWebAssetService.serviceName

    /** What a client's slots held, and when this node last looked. */
    private class Memo(val atNanos: Long, val slots: Map<String, FileMeta>)

    private val memo = ConcurrentHashMap<String, Memo>()

    /** Counts this node's uploads and removals, so a read that began before one is not kept after it. */
    private val writes = AtomicLong()

    /** The clock the memo is aged by. A test's to replace; never the instance clock, which a test may travel. */
    var nanoTime: () -> Long = System::nanoTime

    /**
     * Registers this with the dispatcher as a content server (idempotent). The dispatcher is configured in its
     * own `onCreate`, which the whole tier completes first, so it is found ready by the lifecycle, not by asking.
     */
    override fun checkInit(cxt: KdrCxt) {
        RequestService.get(cxt).addContentServer(this)
    }

    /**
     * The images [client]'s slots hold, by slot; a slot holding none is absent. From what this node last read
     * unless that is older than [memoMs] or [fresh] asks for the store's own answer -- which a page that *edits*
     * the slots does, since it has to show what is there and not what was.
     */
    fun slots(cxt: KdrCxt, client: String, fresh: Boolean = false): Map<String, FileMeta> {
        val owner = ownerOf(client)
        if (!fresh) {
            memo[owner]?.takeIf { nanoTime() - it.atNanos < memoMs * nanosPerMs }?.let { return it.slots }
        }
        val before = writes.get()
        val files = FileStoreService.get(cxt)
        val read = slotDefs.mapNotNull { def -> files.meta(cxt, bucket, keyOf(owner, def.slot))?.let { def.slot to it } }.toMap()
        // Kept only when no upload or removal landed while the store was being read: what was read may predate it.
        memo.compute(owner) { _, held -> if (writes.get() == before) Memo(nanoTime(), read) else held }
        return read
    }

    /**
     * [client]'s set slots as the shell config carries them: a slot to its image by name and content
     * ([WAS.versionedName]).
     *
     * **Never fails the shell.** A store that cannot be read costs a client its own images, not its app: the
     * failure is logged and the client shown the deployment's, for [memoMs], so a store that is down is not
     * asked again by every page load.
     */
    fun versionedNames(cxt: KdrCxt, client: String): Map<String, String> {
        val before = writes.get()
        return try {
            slots(cxt, client).mapValues { (slot, meta) -> WAS.versionedName(slot, meta.hash) }
        } catch (e: KdrException) {
            LogWebAsset.warn(cxt) { "Could not read the web assets of client '$client'; serving the deployment's. ${e.message}" }
            // Held to the rule a read that succeeded is ([slots]): an upload that landed on this node while the
            // store was failing has already dropped the memo, and "nothing set" must not be written over that.
            memo.compute(ownerOf(client)) { _, held -> if (writes.get() == before) Memo(nanoTime(), emptyMap()) else held }
            emptyMap()
        }
    }

    /**
     * [client], having checked that this node **knows** it; a 404 otherwise. What every call that *names* a
     * client goes through -- an administrator who sees every client may name any string, and a name nobody
     * declared must be told so by the listing and the download as it is by an upload, rather than be listed as a
     * client that has merely set nothing. The shell and the static address never name one: they ask for the
     * caller's own.
     */
    fun knownClient(cxt: KdrCxt, client: String): String {
        if (ClientService.get(cxt).known(client) == null) {
            throw KdrException("This node knows no client '$client'.", code = EXC.notFound)
        }
        return client
    }

    /** The image in [slot] of [client]'s web assets, content and metadata, or null when the slot holds none. */
    fun file(cxt: KdrCxt, client: String, slot: String): StoredFile? =
        FileStoreService.get(cxt).get(cxt, bucket, keyOf(ownerOf(client), slotDef(slot).slot))

    /**
     * Stores [content] as the image of [slot] of [client]'s web assets, replacing the one there, and answers the
     * metadata kept with it. Refused as bad input when the file is empty, larger than the slot takes, or not the
     * image it was declared to be ([WebAssetCheck]); and for a sandbox ([editableOwner]).
     *
     * Stored under the type the content was found to be, in a context bound to the client -- so the file is
     * recorded as the client's ([FileMeta.client]) whoever uploaded it -- with the uploader's name beside their
     * id, since an id tells an administrator reading the listing nothing.
     */
    fun upload(cxt: KdrCxt, client: String, slot: String, content: ContentData): FileMeta {
        val def = slotDef(slot)
        val owner = editableOwner(cxt, client)
        val bytes = content.contentBytes()
        val what = def.label.lowercase()
        if (bytes.isEmpty()) throw KdrException.mkInput("The file sent as the $what is empty.")
        if (bytes.size > def.maxSize) {
            throw KdrException.mkInput(
                "A $what may be at most ${byteSizeText(def.maxSize.toLong())}; this file is ${byteSizeText(bytes.size.toLong())}.",
            )
        }
        val kind = WebAssetCheck.check(what, content.mimeType, bytes)
        val extra = buildMap<String, Any?> {
            put(WAS.slot, def.slot)
            cxt.userProfile.takeIf { it.isLoggedIn }?.displayName?.let { put(WAS.uploadedByName, it) }
        }
        val meta = FileStoreService.get(cxt).put(
            cxt.mkSubContext("webAsset", owner), bucket, keyOf(owner, def.slot),
            ContentData(bytes, kind.mimeType, saveAsFilename = content.saveAsFilename), extra,
        )
        forget(owner)
        LogWebAsset.info(cxt) { "Client '$owner' now has a ${kind.label} $what of ${bytes.size} bytes (${meta.hash})." }
        return meta
    }

    /** Empties [slot] of [client]'s web assets; whether it held an image. Refused for a sandbox ([editableOwner]). */
    fun remove(cxt: KdrCxt, client: String, slot: String): Boolean {
        val def = slotDef(slot)
        val owner = editableOwner(cxt, client)
        val removed = FileStoreService.get(cxt).delete(cxt, bucket, keyOf(owner, def.slot))
        forget(owner)
        if (removed) LogWebAsset.info(cxt) { "Client '$owner' no longer has a ${def.label.lowercase()} of its own." }
        return removed
    }

    /**
     * Drops what this node held of [owner]'s slots, so its next read is the store's -- and counts as a write, so a
     * read already under way does not put back what it saw. Every upload and removal ends here.
     */
    @KdrPrivate
    fun forget(owner: String) {
        memo.compute(owner) { _, _ ->
            writes.incrementAndGet()
            null
        }
    }

    /**
     * The client whose web assets [client]'s may be **changed** as: [client] itself. A sandbox is refused, since
     * the images it shows are its parent's and a change to them is live for the parent's people; and so is a
     * client this node does not know ([knownClient]), which would otherwise be given files under whatever name
     * was sent.
     */
    private fun editableOwner(cxt: KdrCxt, client: String): String {
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput(
                "'$client' is a sandbox, and shows the web assets of '$parent'. They are not part of the configuration " +
                    "a sandbox previews: a change takes effect for '$parent' at once, so it is made on that client's own page.",
            )
        }
        return knownClient(cxt, client)
    }

    /**
     * Serves `/<appId>/web/<slot:hash>` under the static root: the image in that slot of the **caller's** client's
     * web assets, whatever hash the address carries.
     *
     * The hash decides only how the answer may be cached, by the rule every content-addressed address here
     * follows ([ContentResources.buildId]): one that names exactly the bytes returned is kept for good, and any
     * other -- an image replaced since the page was built, an address somebody typed -- gets the current image
     * under `no-store`, so a shared cache never holds one client's image at an address that does not name it. An
     * empty slot is a `no-store` 404. An SVG goes out with a policy that lets it run nothing, and every answer
     * -- the 404 too, whose words repeat the slot the address named -- with `nosniff`, for a browser sent straight
     * to the address rather than drawing it in a page.
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.static) return false
        // appPath is context-root-stripped: "/<appId>/web/<slot:hash>" -> ["", appId, "web", slotWithHash].
        val segments = handler.appPath.split('/')
        if (segments.size != pathSegments || segments[2] != CMK.web) return false
        val slot = segments[3].substringBefore(WAS.nameSep)
        val suppliedHash = segments[3].substringAfter(WAS.nameSep, "")
        val file = if (slotDefs.none { it.slot == slot }) null else file(cxt, cxt.client, slot)
        if (file == null) {
            handler.setResponseHeader(ContentResources.cacheControlHeader, ContentResources.noStore)
            handler.setResponseHeader(noSniffHeader, noSniff)
            handler.sendStringResponse("No web asset '$slot'.", EXC.notFound, "text/plain")
            return true
        }
        handler.setResponseHeader(
            ContentResources.cacheControlHeader, ContentResources.cacheHeaderFor(suppliedHash == file.meta.hash),
        )
        guardHeaders(file.meta.mimeType).forEach { (header, value) -> handler.setResponseHeader(header, value) }
        handler.sendBytesResponse(file.bytes, EXC.ok, file.meta.mimeType)
        return true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "ClientWebAssetService"

        /** The file-store bucket a client's web assets are kept in. */
        const val bucket = "web-assets"

        /** How long a node goes on what it last read of a client's slots: how soon an upload shows on another node. */
        const val memoMs = 30_000L

        /** Nanoseconds in a millisecond: [memoMs] in the unit [nanoTime] counts. */
        const val nanosPerMs = 1_000_000L
        private const val bytesPerKb = 1024
        private const val pathSegments = 4

        /** Tells a browser to take the declared type as given, and never to guess a page out of an image. */
        const val noSniffHeader = "X-Content-Type-Options"
        const val noSniff = "nosniff"

        const val contentPolicyHeader = "Content-Security-Policy"

        /**
         * What an uploaded SVG may do when a browser opens it as a document: draw itself, with its own inline
         * styles, and nothing else -- no script, no request, no form, and no place in this origin (`sandbox`).
         */
        const val svgContentPolicy = "default-src 'none'; style-src 'unsafe-inline'; sandbox"

        /** The slots, in listing order. A favicon is small by nature; a brand mark may be a photograph. */
        val slotDefs = listOf(
            WebSlotDef(WAS.favicon, "Favicon", 256 * bytesPerKb),
            WebSlotDef(WAS.brandMark, "Brand mark", 1024 * bytesPerKb),
        )

        /** The bucket's declaration, for the component that registers this service to make. */
        fun bucketDef(): FileBucketDef =
            FileBucketDef(bucket, "Each client's own web assets: its tab icon and brand mark, uploaded by its administrator.")

        /** The slot named [slot], or a bad-input refusal naming the ones there are. */
        fun slotDef(slot: String): WebSlotDef = slotDefs.firstOrNull { it.slot == slot }
            ?: throw KdrException.mkInput("No web asset slot '$slot'; the slots are ${slotDefs.joinToString { it.slot }}.")

        /**
         * The client whose web assets [client]'s people see: its own, or for a sandbox its parent's -- a sandbox
         * previews its parent, and has no images of its own to be different by.
         */
        fun ownerOf(client: String): String = sandboxParentOf(client) ?: client

        /** Where [slot] of [owner]'s web assets is kept in the [bucket]. */
        fun keyOf(owner: String, slot: String): String = "clients/$owner/web/$slot"

        /** The headers an image of [mimeType] is sent with beyond its type and caching; see [serve]. */
        fun guardHeaders(mimeType: String): Map<String, String> = buildMap {
            put(noSniffHeader, noSniff)
            if (mimeType == WebAssetCheck.svg.mimeType) put(contentPolicyHeader, svgContentPolicy)
        }

        fun get(cxt: KdrCxt): ClientWebAssetService = cxt.instanceConfig.get(serviceName) as? ClientWebAssetService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** Null on a node that keeps no files -- an edge. */
        fun getOrNull(cxt: KdrCxt): ClientWebAssetService? = cxt.instanceConfig.get(serviceName) as? ClientWebAssetService
    }
}
