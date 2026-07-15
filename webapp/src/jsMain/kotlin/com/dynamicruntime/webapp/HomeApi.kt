package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.util.jsonMap
import kotlinx.coroutines.await
import kotlin.js.Promise
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOrEmpty

/**
 * The home widget-group's backend calls, all keyed off the shared kernel constants so the frontend never
 * re-hardcodes a path or a JSON key the backend serves:
 *  - The **UI-config** (fetchConfig) -- the construction manifest: which fragment holds the copy, which
 *    layout affordances are on, and which documents to link to;
 *  - The group's **copy** (fetchFragments) -- a Markdown fragment file;
 *  - A linked **document** (fetchDoc) -- whole Markdown, rendered by [Markdown].
 *
 * The two content calls go to the static root and carry a `:buildId`, so they are immutably cacheable.
 */
private const val apiRoot = "/kda"
private const val staticRoot = "/st"

/**
 * The app id the frontend constructs for static content (the application, plus optional account/locale
 * suffixes). Opaque to the backend today -- it ignores it -- but a future backend may vary content by it.
 */
private const val appId = "kdr"

/** Binding to the browser's global `fetch` (named to avoid clashing with any wrapper `fetch`). */
@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/** One navigable Markdown document offered by the home page, already carrying its cache-busting build id. */
class HomeLink(val id: String, val label: String, val docId: String, val buildId: String)

/** Which of the three link presentations this deployment enabled. Independent toggles: any combination. */
class HomeLayout(val topBar: Boolean, val leftBar: Boolean, val inlineLinks: Boolean)

/** The home page's construction manifest: where its copy lives, how to lay it out, and what to link to. */
class HomeConfig(
    val fragmentFileId: String,
    val fragmentBuildId: String,
    val layout: HomeLayout,
    val links: List<HomeLink>,
)

object HomeApi {
    /** GET the home UI-config -- cheap and meant to be re-fetched on navigation. */
    suspend fun fetchConfig(): HomeConfig {
        val results = getJson("$apiRoot${HEP.homeUiConfig}")[EP.results].toJsonMapOrEmpty()
        val fragment = results[UIC.fragments].toJsonListOrEmpty().firstOrNull().toJsonMapOrEmpty()
        val features = results[UIC.features].toJsonMapOrEmpty()
        val links = results[UIC.state].toJsonMapOrEmpty()[HFLD.links].toJsonListOrEmpty().map { raw ->
            val link = raw.toJsonMapOrEmpty()
            HomeLink(
                id = link[HFLD.id] as? String ?: "",
                label = link[HFLD.label] as? String ?: "",
                docId = link[HFLD.docId] as? String ?: "",
                buildId = link[HFLD.buildId] as? String ?: "",
            )
        }
        return HomeConfig(
            fragmentFileId = fragment[UIC.fileId] as? String ?: "",
            fragmentBuildId = fragment[UIC.buildId] as? String ?: "",
            layout = HomeLayout(
                topBar = features[HFEAT.topBar] == true,
                leftBar = features[HFEAT.leftBar] == true,
                inlineLinks = features[HFEAT.inlineLinks] == true,
            ),
            links = links,
        )
    }

    /** GET a Markdown fragment file: the group's copy as `namespace -> (key -> value)`. */
    suspend fun fetchFragments(fileId: String, buildId: String): Map<String, Map<String, String>> {
        val raw = getJson("$staticRoot/$appId/${CMK.md}/$fileId:$buildId")
        return raw.mapValues { (_, namespace) ->
            namespace.toJsonMapOrEmpty().mapValues { (_, value) -> value?.toString() ?: "" }
        }
    }

    /** GET a whole Markdown document, verbatim; the caller renders it. */
    suspend fun fetchDoc(docId: String, buildId: String): String =
        getText("$staticRoot/$appId/${CMK.doc}/$docId:$buildId")

    /** Parses a response with the shared kernel parser, so numbers/values match what the backend wrote. */
    private suspend fun getJson(url: String): Map<String, Any?> = getText(url).jsonMap() ?: emptyMap()

    private suspend fun getText(url: String): String {
        val response = browserFetch(url).await()
        if (!(response.ok as Boolean)) {
            error("GET $url failed with status ${response.status}")
        }
        // `response` is dynamic, so cast to a typed Promise for the coroutines `await` extension.
        return (response.text() as Promise<String>).await()
    }
}


