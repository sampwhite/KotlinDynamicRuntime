package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.content.CMK

/**
 * A client's own web assets (issue #1130): the images a client's administrator uploads to stand in for the
 * deployment's -- its tab icon and its brand mark. Each is a **slot**, a fixed name with one image in it or none.
 *
 * Here in the kernel because the two sides have to agree on all of it: the backend serves a slot at the address
 * the frontend builds ([staticPath]), under the name the shell config hands it ([versionedName]), and the
 * Clients page reads the listing by these keys.
 */
@Suppress("ConstPropertyName")
object WAS {
    /** The tab icon: what a browser shows on the tab and in a bookmark. */
    const val favicon = "favicon"

    /** The brand mark: the logo in the app bar and the home page's hero. */
    const val brandMark = "brandMark"

    /** Every slot, in the order a page lists them. */
    val slots = listOf(favicon, brandMark)

    // --- Wire names: the inputs of the endpoints, and the fields of a slot as the listing answers it.

    /** The client whose assets these are; the caller's own when absent. */
    const val client = "client"

    /** Which slot: [favicon] or [brandMark]. */
    const val slot = "slot"

    /** An upload's file part, and in a listed slot the metadata of the image it holds (absent when it holds none). */
    const val file = "file"

    /** What a person calls the slot ("Favicon"). */
    const val label = "label"

    /** A slot's image by name and content, `<slot>:<hash>` ([versionedName]); absent for an empty slot. */
    const val name = "name"

    /** The most bytes a slot's image may be. */
    const val maxSize = "maxSize"

    /** The content types a slot accepts, for a file picker to offer. */
    const val mimeTypes = "mimeTypes"

    /** The kinds of image a slot accepts as a person names them ("PNG", "SVG"), for a page to say. */
    const val kinds = "kinds"

    /**
     * On the named download: the hash of the image the caller expects, from the listing. It makes the address
     * name the image and not merely the slot, so the answer may be kept -- and a replaced image is a new address.
     */
    const val hash = "hash"

    /** Who uploaded a slot's image, as they were called then; kept with the file, since a user id is no name. */
    const val uploadedByName = "uploadedByName"

    /** The schema type of one listed slot. */
    const val slotTypeName = "WebAssetSlot"

    /** The schema namespace of the endpoints and their types. */
    const val namespace = "kdr.clientWeb"

    /** Separates a slot from the hash of its content in a [versionedName], as it does a file from its build id. */
    const val nameSep = CMK.nameSep

    /**
     * A slot's image named by its content: `favicon:<hash>`. The whole of what the shell config says about a set
     * slot -- the frontend puts it in a URL as it is ([staticPath]) and never takes it apart, so the grammar is
     * this function's alone.
     */
    fun versionedName(slot: String, hash: String): String = "$slot$nameSep$hash"

    /**
     * Where a slot's image is served, under the static context root: `/<appId>/web/<slot:hash>`. [appId] is the
     * frontend's own and is not read by the backend, which serves the **caller's** client's image whatever the
     * address says -- whose image a caller sees is never something the caller can choose.
     */
    fun staticPath(appId: String, versionedName: String): String = "/$appId/${CMK.web}/$versionedName"
}
