package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import react.ChildrenBuilder
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.p

/** "Unpublished drafts"' sizes (issue #1638). */
@Suppress("ConstPropertyName")
object DRAFT {
    /** How many configurations the card names before it only counts the rest. */
    const val shownNames = 3
}

/**
 * The client's configurations with unpublished changes (issue #1638): whose they are ([client], the sandbox's parent
 * from inside it), how many, their names, and whether the read was made from inside the sandbox.
 */
class DraftsRead(val client: String, val count: Int, val names: List<String>, val inSandbox: Boolean)

/**
 * `/clientAdmin/config/drafts`' answer as a [DraftsRead] (issue #1638), or null when it names no client. The count is
 * the answer's, else how many names it lists. Pure, and covered under `jsNodeTest`.
 */
fun parseDraftsRead(item: Map<String, Any?>): DraftsRead? {
    val client = item[CFEP.client].toOptStr() ?: return null
    val names = item[CFEP.names].toJsonListOfStrings()
    return DraftsRead(client, (item[CFEP.count] as? Number)?.toInt() ?: names.size, names, item[CFEP.inSandbox] == true)
}

/**
 * Where the drafts are published (issue #1638): the Clients page's publishing panel for the client -- the sandbox's
 * own page from inside it, where the person is, as the workflow assistant's drafts link. Pure.
 */
fun draftsPublishRoute(read: DraftsRead): List<Pair<String, String>> = listOf(
    HP.page to HMENU.pageClients,
    HP.client to if (read.inSandbox) sandboxOf(read.client) else read.client,
    HP.section to panelPublishing,
)

/**
 * The client's unpublished drafts, for Home's card (issue #1638), or null while they are read, when they cannot be, or
 * when no card in [cards] asks for them -- then nothing is read ([useCardRead], which says what [decidedAt] is). Read
 * again on every refresh, so a publish made elsewhere takes the card away.
 */
fun useUnpublishedDrafts(cards: List<HomeCard>, decidedAt: Int): DraftsRead? =
    useCardRead(cards.any { it.kind == HCARD.kindUnpublishedDrafts }, decidedAt, null, "the unpublished drafts") {
        parseDraftsRead(Http.getApi(CFEP.drafts)[EP.item].toJsonMapOrEmpty())
    }

/**
 * The sentence the "Unpublished drafts" card opens with (issue #1638): how many configurations have unpublished
 * changes, worded as the Clients page's banner is. Pure, and covered under `jsNodeTest`.
 */
fun draftsCountText(count: Int): String =
    if (count == 1) "1 configuration has unpublished changes:" else "$count configurations have unpublished changes:"

/** The "Unpublished drafts" card's body (issue #1638): how many, the first few by name, and where to publish them. */
fun ChildrenBuilder.draftsCardBody(read: DraftsRead) {
    p {
        +"${draftsCountText(read.count)} "
        read.names.take(DRAFT.shownNames).forEachIndexed { i, name ->
            if (i > 0) +", "
            code { +name }
        }
        val more = read.count - minOf(read.names.size, DRAFT.shownNames)
        if (more > 0) +", and $more more"
        +"."
    }
    a {
        href = routeHref(draftsPublishRoute(read))
        +"Review and publish"
    }
}
