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

    /** A client's own web asset (issue #1130) -- its tab icon, its brand mark -- returned as the image itself:
     *  `/<staticRoot>/<appId>/web/<slot:hash>`. Always the **caller's** client's; see `WAS.staticPath`. */
    const val web = "web"

    /** One of a client's file refs (issue #1153) -- a named file its configuration holds -- returned as the file
     *  itself: `/<staticRoot>/<appId>/ref/<refId:hash>`. Always the **caller's** client's; see `FREF.staticPath`. */
    const val ref = "ref"

    /**
     * Separates a name from the hash of its content in a static address -- a fragment file from its build id, a web
     * asset's slot or a file ref's id from its hash. One constant, so the side that builds an address and the side
     * that parses it cannot come to split it differently.
     */
    const val nameSep = ':'
}
