package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.rootedNameProblem
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * `state.images` as rooted name to served name (issue #1339): the images components ship, each by the name the static
 * root serves it under, `<name>:<hash>`. An entry that is not that -- a name that is not rooted, a served name that is
 * not the name with a hash -- is left out rather than drawn from, so copy naming it draws its alt text. Pure, covered
 * under `jsNodeTest`.
 */
fun componentImageNamesFrom(raw: Any?): Map<String, String> = raw.toJsonMapOrEmpty().mapNotNull { (name, served) ->
    val prefix = "$name${CMK.nameSep}"
    (served as? String)?.takeIf {
        rootedNameProblem(OwnedNameKind.image, name) == null && it.startsWith(prefix) && FREF.isHash(it.removePrefix(prefix))
    }?.let { name to it }
}.toMap()

/**
 * Where copy naming the image `img:<name>` is drawn from (issue #1339), given the [names] images are served by and how a
 * served name becomes an address: null for an image not among them, which the renderer draws as its alt text.
 */
fun imageAddressFrom(names: Map<String, String>, addressOf: (String) -> String): (String) -> String? =
    { name -> names[name]?.let(addressOf) }
