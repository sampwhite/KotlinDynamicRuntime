package com.dynamicruntime.common.content

/**
 * The pages a **copy note** may name (issue #1799): where a person sees a key a client is likely to reword, by the
 * name the person knows the page by. A closed list, so a note says something specific and the keys of one page can be
 * listed together. A note starts with one of these, or with [none].
 *
 * Theme: #CUSTX.
 */
@Suppress("ConstPropertyName")
object CPAGE {
    /** The bar across the top of every page: the brand mark, the wordmark, and the menu. */
    const val appBar = "App bar"

    /** The browser tab's title, for the client's signed-in people. */
    const val browserTab = "Browser tab"

    /** The client's home page. */
    const val home = "Home"

    /** The forms list ("Forms" or "My forms" in the menu). */
    const val forms = "Forms"

    /** A workflow's page for one form: its steps, and the form's parts each asks for. */
    const val workflow = "Workflow"

    /** A form's fields, wherever a form is drawn. */
    const val form = "Form"

    /** The Reports page. */
    const val reports = "Reports"

    /** A mail the client's people are sent. */
    const val mail = "Mail"

    /** Not a page: the note's key is not one a client rewords (a system message, or a deployment's own words). */
    const val none = "none"

    val pages: List<String> = listOf(appBar, browserTab, home, forms, workflow, form, reports, mail)
}

/**
 * The copy notes of one fragment file (issue #1799): for each key a client is likely to reword, where a person sees it,
 * and for the rest, that it is not one. A note is a fragment comment starting `/-where `, written directly
 * before the key or namespace it is about:
 *
 * ```
 * /-where Home: under the title, for everyone. The paragraphs introducing the site. -/
 * # +intro
 * ```
 *
 * The text is `<page>: <place>`, the page one of [CPAGE.pages], or `none: <why>`. A namespace's note may only be
 * `none`, and holds for each of its keys that has no note of its own; locations are per key, since two keys of one
 * namespace are seldom in one place.
 *
 * Being a comment, a note is invisible to [parseMarkdownFragments][com.dynamicruntime.common.util.parseMarkdownFragments]:
 * the values read exactly as they would without it. [parseFragmentNotes][com.dynamicruntime.common.util.parseFragmentNotes]
 * reads the notes.
 *
 * Theme: #CUSTX.
 */
class FragmentNotes(
    /** Each namespace's own note, by namespace: always a `none`. */
    val byNamespace: Map<String, String>,
    /** Each key's own note, by namespace and key. */
    val byKey: Map<String, Map<String, String>>,
) {
    /** The note that holds for a key, its own or its namespace's; null when it has none (it is unclassified). */
    fun noteFor(namespace: String, key: String): String? = byKey[namespace]?.get(key) ?: byNamespace[namespace]

    /** Where a person sees a key: its note, unless that says it is not one a client rewords; null for both. */
    fun whereOf(namespace: String, key: String): String? = noteFor(namespace, key)?.takeUnless { isNotReworded(it) }

    companion object {
        /** What opens a note: `/-where `, with no space between the comment's mark and the word. */
        const val notePrefix = "where "

        val empty = FragmentNotes(emptyMap(), emptyMap())

        /** Whether [note] says its key is not one a client rewords. */
        fun isNotReworded(note: String): Boolean = pageOf(note) == CPAGE.none

        /** The page a note names: its text before the first colon. */
        fun pageOf(note: String): String = note.substringBefore(':').trim()
    }
}
