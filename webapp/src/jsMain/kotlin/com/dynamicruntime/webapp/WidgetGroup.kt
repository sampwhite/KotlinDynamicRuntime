package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.FragmentResolver
import com.dynamicruntime.common.util.resolveFragment
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async

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
    /** The group's copy: the first file its config names. */
    val fragment: FragmentRef,
    /** Boolean policy flags. */
    val features: Map<String, Any?>,
    /** Non-flag tuning values (numbers, strings); empty for a group that has none. */
    val settings: Map<String, Any?>,
    val state: Map<String, Any?>,
    /**
     * Every file the config names, [fragment] first. Only the shell's names more (issue #1337): the files of the pages
     * with no UI-config of their own, which they read through it ([HomeConfig.pageFragments]).
     */
    val fragments: List<FragmentRef> = listOf(fragment),
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

    /**
     * A [FragmentResolver] over this copy, for evaluating a `@t("namespace.key")` pull against it (issue #505).
     * It resolves through the kernel's [resolveFragment] -- the same rule the boot checker validates references
     * with -- so a reference the checker blessed resolves here, and one it flagged resolves to nothing.
     *
     * This is a *frontend-pass* resolver: it sees one file (this delivered copy), so a `@t` key is the
     * two-part `namespace.key`. Which file a string resolves against is named by the content element that
     * carries the string, not by the string itself.
     */
    fun fragmentResolver(): FragmentResolver = FragmentResolver { key -> byNamespace.resolveFragment(key) }

    companion object {
        /** The copy of a group whose fragments have not arrived (or failed): every lookup falls back. */
        val empty = Copy(emptyMap())
    }
}

/**
 * Whether [other] is the same answer as this one (issue #1313): the same fragment, features, settings, and state. A
 * re-read that answers the same can then keep the typed config it built last time, so setting it into state changes
 * nothing and nothing re-renders -- which is what makes re-reading on every refresh generation cheap in fact as well
 * as in intent.
 */
fun UiConfig.answersSameAs(other: UiConfig?): Boolean =
    other != null &&
        fragments.map { it.fileId to it.buildId } == other.fragments.map { it.fileId to it.buildId } &&
        features == other.features && settings == other.settings && state == other.state

/** GETs a widget-group's UI-config. Cheap and meant to be re-fetched on navigation. */
suspend fun fetchUiConfig(path: String): UiConfig {
    val results = Http.getApi(path)[EP.results].toJsonMapOrEmpty()
    val fragments = results[UIC.fragments].toJsonListOrEmpty().map {
        val fragment = it.toJsonMapOrEmpty()
        FragmentRef(fileId = fragment[UIC.fileId] as? String ?: "", buildId = fragment[UIC.buildId] as? String ?: "")
    }
    return UiConfig(
        fragment = fragments.firstOrNull() ?: FragmentRef("", ""),
        features = results[UIC.features].toJsonMapOrEmpty(),
        settings = results[UIC.settings].toJsonMapOrEmpty(),
        state = results[UIC.state].toJsonMapOrEmpty(),
        fragments = fragments.ifEmpty { listOf(FragmentRef("", "")) },
    )
}

/**
 * GETs the Markdown fragment file [ref] names, as the group's [Copy] -- once per name (issue #1313). The build id
 * hashes the file's merged content, which the backend serves as immutable, so a name names one copy for good: every
 * group re-reading on a refresh, and the app bar beside the page, share one request and one [Copy] instance. A
 * failed read is not kept, so a stale name's 404 is asked again by whoever names it next.
 */
suspend fun fetchCopy(ref: FragmentRef): Copy = copyByName.getOrPut("${ref.fileId}:${ref.buildId}") {
    copyScope.async {
        Copy(
            Http.getFragments(ref.fileId, ref.buildId).mapValues { (_, namespace) ->
                namespace.toJsonMapOrEmpty().mapValues { (_, value) -> value?.toString() ?: "" }
            },
        )
    }
}.let { read ->
    try {
        read.await()
    } catch (e: Throwable) {
        copyByName.remove("${ref.fileId}:${ref.buildId}")
        throw e
    }
}

/** Shared copy reads, outliving any one caller so a caller's cancellation does not cancel another's read. */
private val copyScope = MainScope()

/** The copy read for each `fileId:buildId`, bounded since a long-lived tab sees each deploy's names in turn. */
private val copyByName = BoundedMap<String, Deferred<Copy>>(32)

/** A map that forgets its oldest entry past [capacity], for content kept by an immutable name. */
internal class BoundedMap<K, V>(private val capacity: Int) {
    private val entries = LinkedHashMap<K, V>()

    fun getOrPut(key: K, make: () -> V): V = entries[key] ?: make().also { value ->
        entries[key] = value
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun remove(key: K) { entries.remove(key) }

    val size: Int get() = entries.size
}

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
