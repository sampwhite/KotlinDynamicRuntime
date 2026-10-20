package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.operator.FSB
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The operator's file-store browser (issue #1297), the pure half: what the endpoints answer read into views, the
 * addresses the page links to, and the words for a file's state. Covered under `jsNodeTest`; the components are in
 * `OperatorFileStorePage.kt`.
 */

/** One bucket of the store, as the buckets listing answers it. */
class BucketView(val name: String, val description: String)

/** What the store is, from the buckets listing's summary. */
class StoreView(val provider: String, val location: String)

/**
 * One file of a folder: its [key], its [name], the store's record of it when it holds one ([file], with the uploader
 * and the client beside, which the shared record leaves out), when any of it last changed, and its [state]
 * (`FSB.stateOk` / `stateNoMeta` / `stateNoContent`).
 */
class BrowseFileView(
    val key: String,
    val name: String,
    val file: WebAssetFile?,
    val uploadedBy: Long?,
    val client: String?,
    val modifiedAt: String,
    val state: String,
)

/** What is directly under one folder of a bucket: an empty [prefix] is the top of the bucket. */
class ListingView(val bucket: String, val prefix: String, val folders: List<String>, val files: List<BrowseFileView>, val more: Boolean)

/** The buckets and the store, from the buckets listing's envelope. */
fun parseBuckets(envelope: Map<String, Any?>): Pair<List<BucketView>, StoreView?> {
    val buckets = envelope[EP.items].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
        .map { BucketView(it[FSB.name].toOptStr().orEmpty(), it[FSB.description].toOptStr().orEmpty()) }
    val summary = envelope[EP.summary].toJsonMapOrEmpty()
    val store = summary[FSB.provider].toOptStr()?.let { StoreView(it, summary[FSB.location].toOptStr().orEmpty()) }
    return buckets to store
}

/** A folder's listing, from the listing endpoint's results. */
fun parseListing(results: Map<String, Any?>): ListingView = ListingView(
    bucket = results[FSB.bucket].toOptStr().orEmpty(),
    prefix = results[FSB.prefix].toOptStr().orEmpty(),
    folders = results[FSB.folders].toJsonListOrEmpty().mapNotNull { it.toOptStr() },
    files = results[FSB.files].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }.map { row ->
        val meta = row[FSB.meta].toJsonMapOrEmpty()
        BrowseFileView(
            key = row[FSB.key].toOptStr().orEmpty(),
            name = row[FSB.name].toOptStr().orEmpty(),
            file = storedFileFrom(row[FSB.meta]),
            uploadedBy = (meta[FMETA.uploadedBy] as? Number)?.toLong(),
            client = meta[FMETA.client].toOptStr(),
            modifiedAt = row[FSB.modifiedAt].toOptStr().orEmpty(),
            state = row[FSB.state].toOptStr() ?: FSB.stateOk,
        )
    },
    more = results[FSB.more] == true,
)

/**
 * The way to each folder above the one open, as the page's breadcrumb draws it: the bucket's top first, then each
 * folder of [prefix] with the prefix that opens it -- `clients/acme/` is `clients/` then `clients/acme/`.
 */
fun breadcrumbs(prefix: String): List<Pair<String, String>> {
    val crumbs = mutableListOf<Pair<String, String>>()
    var sofar = ""
    for (segment in prefix.split('/').filter { it.isNotEmpty() }) {
        sofar += "$segment/"
        crumbs.add(segment to sofar)
    }
    return crumbs
}

/** The page's address for [bucket]'s folder [prefix]; the bucket list with no bucket. */
fun fileStoreHref(bucket: String? = null, prefix: String = ""): String = hashHref(
    buildList {
        add(HP.page to HMENU.pageFileStore)
        if (bucket != null) add(HP.bucket to bucket)
        if (prefix.isNotEmpty()) add(HP.folder to prefix)
    },
)

/** The address the file at [key] in [bucket] is downloaded from. */
fun fileStoreDownloadUrl(bucket: String, key: String): String =
    Http.apiUrl(OPS.fileStoreDownloadPath, mapOf(FSB.bucket to bucket, FSB.key to key))

/** A file's [state] in words; nothing for a whole file, which needs none. */
fun fileStateText(state: String): String? = when (state) {
    FSB.stateNoMeta -> "No record: a write that stopped part-way. It cannot be downloaded."
    FSB.stateNoContent -> "No content: its record answers for a file that is not there."
    else -> null
}

/** Who stored a file, in a word: the client it was stored for, the user's id, or neither. */
fun storedByText(file: BrowseFileView): String = listOfNotNull(file.client, file.uploadedBy?.let { "user $it" }).joinToString(", ").ifEmpty { "—" }

/** The fetches, each the envelope read into its view. */
object FileStoreApi {
    suspend fun buckets(): Pair<List<BucketView>, StoreView?> = parseBuckets(Http.getApi(OPS.fileStoreBucketsPath))

    suspend fun listing(bucket: String, prefix: String): ListingView = parseListing(
        Http.getApi(OPS.fileStoreListPath + queryString(mapOf(FSB.bucket to bucket, FSB.prefix to prefix.ifEmpty { null })))[EP.results].toJsonMapOrEmpty(),
    )
}
