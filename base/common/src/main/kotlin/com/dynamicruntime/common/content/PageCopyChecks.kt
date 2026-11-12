package com.dynamicruntime.common.content

import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.page.PGW
import com.dynamicruntime.common.page.PageCheckScope
import com.dynamicruntime.common.page.PageWidgets
import com.dynamicruntime.common.page.pageDocumentProblems
import com.dynamicruntime.common.startup.SchemaService

/** The cfacts of [this] that are sent to the frontend (issue #1792): all a page's condition, judged in the browser, sees. */
fun CFactRegistry.frontendNames(): Set<String> = defs.values.filter { it.toFrontend }.mapTo(LinkedHashSet()) { it.name }

/**
 * Copy every client shares whose widget fences are wrong (issue #1792): one finding per key and problem, said as
 * [pageDocumentProblems] says them. For the fragment check at boot, as [sharedCopyRoleProblems] is for roles; a client's
 * own copy is judged with its configuration ([ClientCopyPageCheck]). A fence's `copy` key is looked for in its file
 * as every client shares it, and its condition is held to the cfacts every client knows and those sent to the frontend.
 */
fun sharedCopyPageProblems(cxt: KdrCxt, sources: List<FragmentSource>): List<String> {
    val shared = sources.filter { it.client == null }
    val withFences = shared.mapNotNull { layer ->
        layer.load()?.takeIf { content -> content.values.any { keys -> keys.values.any { it.contains(PGW.infoString) } } }
            ?.let { layer to it }
    }
    if (withFences.isEmpty()) return emptyList()
    val registry = SchemaService.get(cxt).cfactsFor(null)
    val frontend = registry.frontendNames()
    val merged = HashMap<String, Map<String, Map<String, String>>>()
    val findings = mutableListOf<String>()
    for ((layer, content) in withFences) {
        val file = merged.getOrPut(layer.fileId) {
            mergeFragmentLayers(layer.fileId, shared.filter { it.fileId == layer.fileId }, null).content
        }
        val scope = PageCheckScope(
            PageWidgets.core, shipped = true,
            copyKeyExists = { namespace, key -> file[namespace]?.containsKey(key) == true },
            knownCfacts = registry.names, frontendCfacts = frontend,
        )
        for ((namespace, keys) in content) {
            for ((key, value) in keys) {
                pageDocumentProblems(value, scope).forEach { findings.add("'${layer.fileId}' $namespace.$key: $it") }
            }
        }
    }
    return findings.distinct()
}

/**
 * The check of widget fences in a client's own copy (issue #1792), for one load, reload, or write trial of [client]'s
 * [configs]: judged as a client's copy -- a kind only shipped copy may place is refused. Built once per check of the
 * client and asked of every copy value, it does nothing until a value holds a fence, then works out what it needs once
 * per fragment file:
 *
 *  - a fence's `copy` key is looked for in the file as the client has it -- every layer registered for every client or
 *    for this one (a parent's copy copied to its sandbox, a template's cloned to the client, a source overlay filed under
 *    it) -- and in the client's [configs] being judged, which a write trial has not registered yet;
 *  - its condition is held to the cfacts the client knows (the global ones, and its own as the schema service has them)
 *    plus those its [configs] declare, and of those, the ones sent to the frontend.
 *
 * What is registered may be the client's copy before this write; it only adds names, so a fence is never refused for it.
 */
class ClientCopyPageCheck(private val cxt: KdrCxt, private val client: String, configs: List<GedraConfig>) {
    private val own = configs.filter { it.gedraId.client == client }

    private val facts: Pair<Set<String>, Set<String>> by lazy {
        val registry = SchemaService.get(cxt).cfactsFor(client)
        val declared = own.flatMap { it.cfacts }
        Pair(
            registry.names + declared.map { it.name },
            registry.frontendNames() + declared.filter { it.toFrontend }.map { it.name },
        )
    }

    private val scopes = HashMap<String, PageCheckScope>()

    /** The problems of [value]'s fences, a value of the fragment file [fileId]: none, at no cost, for copy without one. */
    fun problems(fileId: String, value: String): List<String> {
        if (!value.contains(PGW.infoString)) return emptyList()
        return pageDocumentProblems(value, scopes.getOrPut(fileId) { scopeFor(fileId) })
    }

    private fun scopeFor(fileId: String): PageCheckScope {
        val registered = MarkdownFragmentService.registeredFragmentSources(cxt)
            .filter { it.fileId == fileId && (it.client == null || it.client == client) }
        val file = mergeFragmentLayers(fileId, registered, client).content
        val judged = own.flatMap { it.fragments }.filter { it.fileId == fileId }.mapNotNull { it.load() }
        return PageCheckScope(
            PageWidgets.core, shipped = false,
            copyKeyExists = { namespace, key ->
                file[namespace]?.containsKey(key) == true || judged.any { it[namespace]?.containsKey(key) == true }
            },
            knownCfacts = facts.first, frontendCfacts = facts.second,
        )
    }
}
