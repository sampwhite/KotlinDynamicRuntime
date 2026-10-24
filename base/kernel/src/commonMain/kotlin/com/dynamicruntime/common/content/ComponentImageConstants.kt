package com.dynamicruntime.common.content

import com.dynamicruntime.common.fileref.FREF

/**
 * Names for the images a component ships in its resources (issue #1339): declared by the component, checked at boot,
 * served under the static root, and named from copy as `![alt](img:<name>)` (`MDR.imageScheme`). In the kernel so the
 * frontend forms the addresses the backend serves.
 */
@Suppress("ConstPropertyName")
object IMG {
    /** The most bytes a component image may hold: a file ref's limit (2 MB). */
    const val maxSize = FREF.maxSize

    /** An image named by its content: `sample:diagram:<hash>`. The frontend puts it in a URL as it is. */
    fun versionedName(name: String, hash: String): String = "$name${CMK.nameSep}$hash"

    /**
     * Where an image is served, under the static context root: `/<appId>/img/<name:hash>`. [appId] is the frontend's
     * own and is not read by the backend: a component image is the same for every caller.
     */
    fun staticPath(appId: String, versionedName: String): String = "/$appId/${CMK.img}/$versionedName"
}
