package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.operator.FSB
import com.dynamicruntime.common.util.byteSizeText
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val fileStoreScope = MainScope()

/**
 * The operator's file-store browser (issue #1297): with no bucket in the hash, the store's buckets and what the
 * store is; with one, the folder the hash names -- a breadcrumb back up, the folders under it as links, and its
 * files with their records and a download each. The bucket and folder ride the hash ([HP.bucket], [HP.folder]), so
 * Back steps out through the folders and a folder is linkable. The endpoints are the authority: a caller without the
 * operator role sees their refusal here, as on the other operator pages.
 */
val OperatorFileStorePage = FC<Props> {
    val params = hashParams()
    val bucket = params[HP.bucket]
    val folder = params[HP.folder].orEmpty()
    var buckets by useState<Pair<List<BucketView>, StoreView?>?>(null)
    var listing by useState<ListingView?>(null)
    var error by useState<DisplayError?>(null)
    val generation = useRefreshGeneration()
    val now = useRelativeClock()
    // A superseded response -- a slower folder answering after the next was opened -- must not overwrite the newer.
    val latest = useRef(0)

    useEffect(generation, bucket, folder) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        error = null
        fileStoreScope.launch {
            try {
                if (bucket == null) {
                    val loaded = FileStoreApi.buckets()
                    if (latest.current == token) buckets = loaded
                } else {
                    val loaded = FileStoreApi.listing(bucket, folder)
                    if (latest.current == token) listing = loaded
                }
            } catch (e: Throwable) {
                if (latest.current == token) error = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        backToListing(HMENU.pageOperator)
        h1 { +"File store" }
        if (bucket == null) {
            p { className = ClassName("subtitle"); +"The buckets the file store holds, each drilled folder by folder." }
            val loaded = buckets
            when {
                error != null -> errorText("Couldn't list the buckets.", error!!)
                loaded == null -> p { className = ClassName("subtitle"); +"Loading…" }
                else -> {
                    loaded.second?.let { store -> p { +"This node keeps files in a ${store.provider} store: ${store.location}." } }
                    div {
                        className = ClassName("op-table-scroll")
                        table {
                            className = ClassName("op-table")
                            thead { tr { th { +"Bucket" }; th { +"Holds" } } }
                            tbody {
                                loaded.first.forEach { b ->
                                    tr {
                                        key = b.name.unsafeCast<Key>()
                                        td { a { href = fileStoreHref(b.name); +b.name } }
                                        td { +b.description }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // The breadcrumb: the bucket list, the bucket's top, then each folder of the one open.
            p {
                className = ClassName("subtitle")
                a { href = fileStoreHref(); +"Buckets" }
                +" › "
                if (folder.isEmpty()) +bucket else a { href = fileStoreHref(bucket); +bucket }
                breadcrumbs(folder).forEach { (segment, prefix) ->
                    +" › "
                    if (prefix == folder) +segment else a { href = fileStoreHref(bucket, prefix); +segment }
                }
            }
            val shown = listing?.takeIf { it.bucket == bucket && it.prefix == folder }
            when {
                error != null -> errorText("Couldn't list the folder.", error!!)
                shown == null -> p { className = ClassName("subtitle"); +"Loading…" }
                shown.folders.isEmpty() && shown.files.isEmpty() -> p { className = ClassName("subtitle"); +"Nothing here." }
                else -> {
                    div {
                        className = ClassName("op-table-scroll")
                        table {
                            className = ClassName("op-table")
                            thead {
                                tr {
                                    th { +"Name" }; th { +"Type" }; th { +"Size" }; th { +"Stored" }; th { +"For" }; th { +"" }
                                }
                            }
                            tbody {
                                shown.folders.forEach { sub ->
                                    tr {
                                        key = sub.unsafeCast<Key>()
                                        td { a { href = fileStoreHref(bucket, folder + sub); +sub } }
                                        td { +"folder" }; td {}; td {}; td {}; td {}
                                    }
                                }
                                shown.files.forEach { f ->
                                    tr {
                                        key = f.key.unsafeCast<Key>()
                                        td {
                                            +f.name
                                            fileStateText(f.state)?.let { said -> span { className = ClassName("subtitle"); +" — $said" } }
                                        }
                                        td { +(f.file?.mimeType ?: "—") }
                                        td { +(f.file?.let { byteSizeText(it.size) } ?: "—") }
                                        td { RelativeTime { iso = f.file?.uploadedAt ?: f.modifiedAt; this.now = now } }
                                        td { +storedByText(f) }
                                        td {
                                            if (f.state != FSB.stateNoMeta) {
                                                a {
                                                    href = fileStoreDownloadUrl(bucket, f.key)
                                                    onClick = { e -> e.preventDefault(); startDownload(fileStoreDownloadUrl(bucket, f.key)) }
                                                    +"Download"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (shown.more) p { className = ClassName("subtitle"); +"This folder holds more than is shown." }
                }
            }
        }
    }
}
