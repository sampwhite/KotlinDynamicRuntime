package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.AKEY
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName

private val apiKeysPageScope = MainScope()

/** How many keys the page asks for at once: more than a client is likely to hold, and the count says when it is not. */
private const val apiKeysPageLimit = 500

/** The page's status filter: every status, or one of them. Empty for every one. */
private const val allStatuses = ""

/**
 * Every API key of the users the caller administers (issue #1402), soonest to expire first: who each acts as (linked to
 * the Users page), what it is for, and whether it still works, to be extended or revoked from here. A key is made from
 * its user's page in the Users editor, or by its owner on Profile, since a key belongs to someone.
 */
val ApiKeysPage = FC<Props> {
    val generation = useRefreshGeneration()
    var status by useState(allStatuses)
    var page by useState<ApiKeyPage?>(null)
    var loadError by useState<DisplayError?>(null)
    var reread by useState(0)

    useEffect(generation, status, reread) {
        apiKeysPageScope.launch {
            try {
                page = ApiKeysApi.listAdministered(status.ifEmpty { null }, apiKeysPageLimit)
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"API keys" }
        p {
            className = ClassName("subtitle")
            +(
                "The keys of the users you administer. A key acts as its user, so make one from that user's page under " +
                    "Users, or its owner makes one on their profile."
            )
        }
        choiceRow("Status") {
            Select {
                value = status
                options = (listOf(allStatuses) + AKEY.statuses).map { s ->
                    val o: dynamic = js("({})")
                    o.label = if (s == allStatuses) "Any" else apiKeyStatusText(s)
                    o.value = s
                    o
                }.toTypedArray()
                style = js("({ minWidth: 160 })")
                onChange = { v -> status = v as String }
            }
        }
        loadError?.let { errorText("Couldn't read the keys.", it) }
        val loaded = page
        when {
            loaded == null && loadError == null -> p { className = ClassName("subtitle"); +"Loading…" }
            loaded != null && loaded.keys.isEmpty() -> p { className = ClassName("subtitle"); +"No keys." }
            loaded != null -> {
                p {
                    className = ClassName("subtitle")
                    +(
                        if (loaded.hasMore) {
                            "Showing ${loaded.keys.size} of ${loaded.numAvailable}; narrow by status to see the rest."
                        } else {
                            "${loaded.keys.size} ${if (loaded.keys.size == 1) "key" else "keys"}."
                        }
                    )
                }
                ApiKeysTable {
                    keys = loaded.keys
                    limits = loaded.limits
                    asAdmin = true
                    showOwner = true
                    onChanged = { reread += 1 }
                }
            }
        }
    }
}
