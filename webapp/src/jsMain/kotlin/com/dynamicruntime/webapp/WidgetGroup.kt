package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * What every widget-group needs from the backend, in one place: its UI-config envelope and its copy.
 *
 * Each group still owns a *typed* config of its own ([AuthConfig], [ProfileConfig], [HomeConfig]) -- the
 * features and state are group-specific and that is the point of the per-group endpoint model. What is not
 * group-specific is the plumbing underneath: unwrapping `{fragments, features, state}` and turning a fragment
 * file into a copy lookup were written three times, identically, which is what this replaces.
 */

/** A Markdown fragment file plus the build id that busts its cache, as a UI-config names it. */
class FragmentRef(val fileId: String, val buildId: String)

/**
 * A UI-config response, unwrapped but not yet interpreted: the group reads [features], [settings] and [state]
 * with its own keys and builds its own typed config from them.
 */
class UiConfig(
    /** The group's copy. Configs name one fragment file today; [UIC.fragments] is a list against more later. */
    val fragment: FragmentRef,
    /** Boolean policy flags. */
    val features: Map<String, Any?>,
    /** Non-flag tuning values (numbers, strings); empty for a group that has none. */
    val settings: Map<String, Any?>,
    val state: Map<String, Any?>,
)

/**
 * A widget-group's copy: the `namespace -> key -> value` map of a fragment file, behind the two lookups the
 * groups actually make.
 */
class Copy(private val byNamespace: Map<String, Map<String, String>>) {
    /**
     * The value at [ns].[key], or [dflt] when the fragment file does not carry it -- so a page renders before
     * its copy arrives, and a key added to the code before the file still shows something sensible.
     */
    fun t(ns: String, key: String, dflt: String): String = opt(ns, key) ?: dflt

    /** The value at [ns].[key], or null -- for copy whose *absence* means "render nothing here". */
    fun opt(ns: String, key: String): String? = byNamespace[ns]?.get(key)

    companion object {
        /** The copy of a group whose fragments have not arrived (or failed): every lookup falls back. */
        val empty = Copy(emptyMap())
    }
}

/** GETs a widget-group's UI-config. Cheap and meant to be re-fetched on navigation. */
suspend fun fetchUiConfig(path: String): UiConfig {
    val results = Http.getApi(path)[EP.results].toJsonMapOrEmpty()
    val fragment = results[UIC.fragments].toJsonListOrEmpty().firstOrNull().toJsonMapOrEmpty()
    return UiConfig(
        fragment = FragmentRef(
            fileId = fragment[UIC.fileId] as? String ?: "",
            buildId = fragment[UIC.buildId] as? String ?: "",
        ),
        features = results[UIC.features].toJsonMapOrEmpty(),
        settings = results[UIC.settings].toJsonMapOrEmpty(),
        state = results[UIC.state].toJsonMapOrEmpty(),
    )
}

/** GETs the Markdown fragment file [ref] names, as the group's [Copy]. */
suspend fun fetchCopy(ref: FragmentRef): Copy = Copy(
    Http.getFragments(ref.fileId, ref.buildId).mapValues { (_, namespace) ->
        namespace.toJsonMapOrEmpty().mapValues { (_, value) -> value?.toString() ?: "" }
    },
)

/**
 * [fetchCopy] with **stale-ref recovery** (issue #469). A fragment ref is per-caller and per-build, so a
 * rolling deploy leaves every open browser holding one no node recognizes; that 404s -- the ordinary case, not
 * an edge one. On a stale 404 ([isStaleFragment]) this re-fetches the UI-config for a fresh ref (via [freshRef])
 * and retries **once**. Never a loop: a file genuinely removed from a deployment 404s forever, and a retry loop
 * on a page-load path is a far worse bug than the blank it was meant to fix.
 *
 * Lives here, in the shared helper, rather than in `AppBar` and `Home` separately -- they handled failure two
 * different ways, which is how one bug became two (see the issue). A non-stale failure, or a stale one whose
 * retry also fails, propagates for the caller to render as [ShellBrand.Failed] / its error boundary.
 */
suspend fun fetchCopyWithRetry(ref: FragmentRef, freshRef: suspend () -> FragmentRef?): Copy =
    try {
        fetchCopy(ref)
    } catch (e: ApiError) {
        if (isStaleFragment(e.status)) {
            val fresh = freshRef() ?: throw e
            // If the config hands back the same ref, the 404 is not staleness -- the file is genuinely gone, and
            // it 404s forever. Re-fetching it would just fail again, once per refresh generation; give up now.
            if (fresh.fileId == ref.fileId && fresh.buildId == ref.buildId) throw e
            fetchCopy(fresh)
        } else {
            throw e
        }
    }
