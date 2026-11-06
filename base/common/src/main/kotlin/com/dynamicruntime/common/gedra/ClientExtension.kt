package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.uiblock.UiBlockSource

/** Client extension's constants (issue #945). */
@Suppress("ConstPropertyName")
object CEXT {
    /** What a clone's name starts with, so a listing can tell a copy from the client's own by name alone. */
    const val clonePrefix = "from_"
}

/**
 * How a client that **extends** another (`ClientDef.extendsFromClientId`) is built (issue #945): the other client's
 * source configuration is cloned under the extending client's id, and the extending client's own configuration is
 * applied over it.
 *
 * ### Clone, then overlay
 *
 * Each of the template's **source** configs becomes a copy filed under the extending client, marked with
 * [GedraConfig.inheritedFrom]. A stored config of the template is never cloned: what a client is built on changes
 * with a deployment, not with a database write. The copy is a config of the client's like any other, so every per-
 * client reader -- the trait registry, the schema variant, cfacts, workflows, trait usages, the copy and interface
 * merges -- sees it with no knowledge of extension, and an entry the client stores against a template trait
 * resolves against the client. That is why it is a clone rather than a reference.
 *
 * ### The merge rules
 *
 * Whatever the client defines itself replaces the template's definition **of the same id, whole**, so the built
 * client holds each definition once. The redefinition is taken out of the copy rather than layered over it, by the keys
 * `ConfigKeys` states -- the same rule a stored entry marked to stand over the client's code is cut by (issue #1634):
 *
 * - **Traits** by trait id, **types** by qualified name, **cfacts** by name, **workflows** by workflow id,
 *   **reports** by report id (issue #979).
 * - A **creation or core details** workflow of the client's own replaces the template's of that kind, whatever its id
 *   -- a client has one answer to how a form is created or how its core details are kept, which is the rule a client
 *   already has against the global workflows.
 * - **Trait usages** (listing columns) as a set: a client that says how it presents its forms says all of it, as it
 *   does against the global defaults.
 * - **Copy and interface** are not taken out but layered: the copy's fragment and UiBlock overlays rank below the
 *   client's own (`overlayPrecedence`), so a fragment merges key by key -- two levels, string values -- and a
 *   UiBlock by its keyed merge. Those are the only merges that look inside a definition.
 * - **State and config traits** are not cloned: both are global, and a client's would be dropped anyway.
 * - The template's **client definition** is not cloned: the client defines itself. It takes defaults from the
 *   template's instead -- see [mergeDef].
 *
 * Nothing deeper is merged. A workflow's tasks are the clearest case: a task list has an order, and placing a new
 * task relative to its neighbors is not built (the declared overlay merge, issue #985, leaves anchors open), so a
 * client that changes a template workflow restates it.
 *
 * Two of the client's own configs defining one id are still the collision they always were -- the merge removes
 * a template definition, never one of the client's.
 *
 * ### The namespace stays the template's
 *
 * The copy keeps the template's namespace, so no type is renamed and a `$ref` inside the template's types still
 * resolves. The template keeps owning it: the copy claims it for the template, as a sandbox's copies claim theirs
 * for the parent, so the client cannot author into it.
 */
object ClientExtension {
    /**
     * The copies of [templateConfigs] -- [template]'s source configs -- that [client] runs, given [own], the configs
     * of its own it now holds. [origin] is the origin of the config defining the client: a copy is judged as the
     * client's configuration is, since what the client defines is what decides the copy's contents.
     */
    fun clones(
        client: String,
        template: String,
        templateConfigs: List<GedraConfig>,
        own: List<GedraConfig>,
        origin: GedraConfigOrigin,
    ): List<GedraConfig> {
        // What the client defines itself, by the keys one definition stands over another's (`ConfigKeys`), the rule a
        // stored entry marked to stand over code is cut by too (issue #1634).
        val ownKeys = ConfigKeys.of(own).withoutFileRefs()
        // By what the child declares in **source** only (issue #1170): a stored ref of the same id meets the template's
        // in the registry, where what is stored overrides what is shipped -- so a stored override the load cannot serve
        // (malformed, naming a source this node does not read) reveals the template's ref, as it reveals a client's own
        // shipped one. One past the limit does not: the id is past it, whichever declaration holds it. Two source
        // declarations of one id are a duplicate, so the child's source refs still replace the template's here.
        val ownFileRefs = own.filter { !it.isStored }.flatMap { c -> c.fileRefs.map { it.refId } }.toSet()
        // A default for a workflow the child declares itself is not the template's to give (issue #1386), and one the
        // child's source declares replaces the template's.
        val ownParamDefaults = ownKeys.workflows.keys + own.flatMap { it.workflowParams.keys }
        val keys = ownKeys + ConfigKeys(fileRefs = ownFileRefs)
        return templateConfigs.map { config ->
            val cut = config.cutBy(keys)
            // Neither the template's own definition nor state and config traits are cloned (see the class note).
            cut.copy(
                gedraId = GedraId.of(config.gedraId.kind, client, cloneName(template, config.name)),
                stateTraits = emptyMap(),
                configTraits = emptyMap(),
                client = null,
                customers = emptyList(),
                fragments = config.fragments.map { it.inheritedBy(client, template) },
                uiBlocks = config.uiBlocks.map { it.inheritedBy(client, template) },
                workflowParams = cut.workflowParams.filterKeys { it !in ownParamDefaults },
                origin = origin,
                inheritedFrom = template,
            )
        }
    }

    /**
     * [child]'s definition built on [template]'s: the child's own fields stand, and where it is silent the template
     * fills in. A missing `webResourcesId`, session lifetime or refresh interval (issue #1308) is the template's;
     * `includedTraits` and `userLabels` are the template's
     * followed by the child's, each entry once -- the template's traits are the child's too, by the clone, so what
     * the template takes as it stands the child does. Nothing else is inherited: the environments, the usage type and
     * the audience say what *this* client is, and routing and the sandbox are never shared.
     */
    fun mergeDef(child: ClientDef, template: ClientDef): ClientDef = child.copy(
        webResourcesId = child.webResourcesId ?: template.webResourcesId,
        sessionHours = child.sessionHours ?: template.sessionHours,
        refreshIntervalSeconds = child.refreshIntervalSeconds ?: template.refreshIntervalSeconds,
        includedTraits = (template.includedTraits + child.includedTraits).distinct(),
        userLabels = (template.userLabels + child.userLabels).distinct(),
    )

    /**
     * What [template] supplies of [child]'s definition by [mergeDef] (issue #1621), by [CLD] key, so a page can say
     * which values in force are the template's: each of `webResourcesId`, the session lifetime and the refresh
     * interval that [child] leaves unset and [template] sets, and the template's `includedTraits` and `userLabels`
     * that [child] does not name itself. [child] is the client's own definition as [mergeDef] was handed it.
     *
     * Only what [running] -- the definition the client runs once the checks are done -- still holds is the template's:
     * a check that drops a filled-in value (a session lifetime past the deployment's limit, a package no component
     * ships, an entry naming no trait) leaves nothing of the template's to show. `ClientCheck` records it at the merge
     * (`ClientCheckResult.templateFills`), so the two cannot disagree.
     */
    fun templateFills(child: ClientDef, template: ClientDef, running: ClientDef = mergeDef(child, template)): Map<String, Any?> =
        buildMap {
            fun scalar(key: String, own: Any?, theirs: Any?, runs: Any?) {
                if (own == null && theirs != null && runs == theirs) put(key, theirs)
            }
            scalar(CLD.webResourcesId, child.webResourcesId, template.webResourcesId, running.webResourcesId)
            scalar(CLD.sessionHours, child.sessionHours, template.sessionHours, running.sessionHours)
            scalar(
                CLD.refreshIntervalSeconds, child.refreshIntervalSeconds, template.refreshIntervalSeconds,
                running.refreshIntervalSeconds,
            )
            template.includedTraits.filter { it !in child.includedTraits && it in running.includedTraits }.ifEmpty { null }
                ?.let { put(CLD.includedTraits, it) }
            template.userLabels.filter { it !in child.userLabels && it in running.userLabels }.ifEmpty { null }
                ?.let { put(CLD.userLabels, it) }
        }

    /**
     * The name a clone of [template]'s config [name] is filed under in the extending client: prefixed, so a client
     * config of the same name -- two clients built from one template often name theirs alike -- never collides with
     * the copy. Base-id characters only (`[A-Za-z0-9_]`), as every config name is.
     */
    fun cloneName(template: String, name: String): String = "${CEXT.clonePrefix}${template}_$name"


    private fun FragmentSource.inheritedBy(client: String, template: String): FragmentSource =
        refiled(client = this.client?.let { client }, inheritedFrom = template)

    private fun UiBlockSource.inheritedBy(client: String, template: String): UiBlockSource =
        refiled(client = this.client?.let { client }, inheritedFrom = template)
}
