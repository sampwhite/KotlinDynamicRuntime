package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the picker's debounced user search. */
private val userPickerScope = MainScope()

/** Debounce before a user-search fetch, so a fast typist makes one call, not many. */
private const val userPickerDebounceMs = 200
private fun setPickerTimer(block: () -> Unit, delayMs: Int): Int = js("setTimeout(block, delayMs)") as Int
private fun clearPickerTimer(id: Int) {
    js("clearTimeout(id)")
}

/** The antd options for the user picker: each user's value is their email/id ref (the one the backend resolves),
 *  its label the name-username-email pick label the scope bar uses, so every user picker reads alike. */
private fun userMatchOptions(users: List<AdminUser>): Array<dynamic> = users.map { u ->
    val o: dynamic = js("({})")
    o.value = u.primaryId
    o.label = userPickLabel(u.name, u.username, u.primaryId)
    o
}.toTypedArray()

external interface FormUserPickerProps : Props {
    /** The chosen user, or null when the box is cleared or edited away from a pick. */
    var onPick: (AdminUser?) -> Unit

    /** The label beside the box; defaults to "For user". */
    var label: String?
}

/**
 * A type-ahead picker of a user to act for (issue #727): search-as-you-type over the users the caller
 * administers -- their own client, for a client administrator, since the search endpoint is client-scoped --
 * reporting the chosen user to `onPick`, and null when the box is cleared or edited away from a pick.
 *
 * Self-contained: it owns its query and its matches, so a page keeps only the chosen user. Extracted from the
 * dedicated `allClients` create-for-user page so the plain create, the friendly workflow create, and that page
 * all pick a user the same way (issue #727). The look is the forms scope bar's, so the surfaces read alike.
 *
 * The **value** an option carries is `primaryId` (the email or, for an id-only account, the id), which is what
 * the create endpoints resolve; the label is the friendly pick label. Editing the box after a pick abandons it
 * (a pick sets the text to the user's label, so any other text means none is chosen -- issue #715 review), so a
 * caller is never left submitting for a user the box no longer shows.
 */
val FormUserPicker = FC<FormUserPickerProps> { props ->
    var query by useState("")
    var matches by useState<List<AdminUser>>(emptyList())
    // The label of the current pick, to tell an edit (which abandons it) from the pick itself.
    var pickedLabel by useState<String?>(null)
    val searchTimer = useRef<Int>(null)

    // Debounce a user search on the typed term; a short term is not worth a fetch.
    useEffect(query) {
        searchTimer.current?.let { clearPickerTimer(it) }
        searchTimer.current = null
        val term = query.trim()
        if (term.length < 2) {
            matches = emptyList()
        } else {
            searchTimer.current = setPickerTimer({
                userPickerScope.launch {
                    matches = runCatching {
                        AdminApi.searchUsers(UserSearchQuery(anyText = term), limit = maxUserSuggestions).users
                    }.getOrDefault(emptyList())
                }
            }, userPickerDebounceMs)
        }
    }

    div {
        className = ClassName("row forms-scope")
        span {
            className = ClassName("forms-scope-label")
            +(props.label ?: "For user")
        }
        AutoComplete {
            placeholder = "a name, email, or id"
            value = query
            options = userMatchOptions(matches)
            allowClear = true
            // The backend already narrowed the fetched matches to the term; show them all.
            filterOption = false
            // Enter must not commit a merely-matching suggestion; a user is chosen by clicking or arrowing.
            defaultActiveFirstOption = false
            style = js("({ width: 360 })")
            onChange = { v ->
                val text = (v as? String) ?: ""
                query = text
                // Editing or clearing the box abandons the current pick: a pick set the text to the user's
                // label, so any other text means no user is chosen.
                if (pickedLabel != null && text != pickedLabel) {
                    pickedLabel = null
                    props.onPick(null)
                }
            }
            onSelect = { v ->
                val ref = (v as? String) ?: ""
                matches.firstOrNull { it.primaryId == ref }?.let { chosen ->
                    val label = userPickLabel(chosen.name, chosen.username, chosen.primaryId)
                    pickedLabel = label
                    query = label
                    props.onPick(chosen)
                }
            }
        }
    }
}
