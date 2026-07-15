package com.dynamicruntime.common.content

/**
 * Path markers for the static-content requests the frontend builds. In the KMP kernel so the frontend forms
 * the same URLs the backend serves. Both live under the static context root and take a `fileId:buildId` whose
 * build id is a cache-busting content hash (see the content servers in `base:common`).
 */
@Suppress("ConstPropertyName")
object CMK {
    /** A Markdown *fragment* file, returned as a `namespace -> (key -> value)` map of addressable snippets:
     *  `/<staticRoot>/<appId>/md/<fileId:buildId>`. */
    const val md = "md"

    /** A whole Markdown *document*, returned verbatim as text to be rendered as a page:
     *  `/<staticRoot>/<appId>/doc/<docId:buildId>`. */
    const val doc = "doc"
}
