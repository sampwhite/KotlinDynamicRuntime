package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.uiblock.UiBlockSource

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
 * client holds each definition once. The redefinition is taken out of the copy rather than layered over it:
 *
 * - **Traits** by trait id, **types** by qualified name, **cfacts** by name, **workflows** by workflow id.
 * - A **creation or survey** workflow of the client's own replaces the template's of that kind, whatever its id --
 *   a client has one answer to how a form is created or surveyed, which is the rule a client already has against
 *   the global workflows.
 * - **Trait usages** (listing columns) as a set: a client that says how it presents its forms says all of it, as it
 *   does against the global defaults.
 * - **Copy and interface** are not taken out but layered: the copy's fragment and UiBlock overlays rank below the
 *   client's own (`overlayPrecedence`), so a fragment merges key by key -- two levels, string values -- and a
 *   UiBlock by its keyed merge. Those are the only merges that look inside a definition.
 * - **State and config traits** are not cloned: both are global, and a client's would be dropped anyway.
 * - The template's **client definition** is not cloned: the client defines itself. It takes defaults from the
 *   template's instead -- see [mergeDef].
 *
 * Nothing deeper is merged. A workflow's tasks waiting on #921's task-id rule is the clearest case: a client that
 * changes a template workflow restates it.
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
        val ownTraits = own.flatMap { it.traits.keys }.toSet()
        val ownTypes = own.flatMap { it.defs.keys }.toSet()
        val ownCfacts = own.flatMap { c -> c.cfacts.map { it.name } }.toSet()
        val ownWorkflows = own.flatMap { it.workflows.values }
        val ownWorkflowIds = ownWorkflows.map { it.workflowId }.toSet()
        val ownSingletonKinds = ownWorkflows.map { it.entry }.filter { it in singletonKinds }.toSet()
        val ownUsages = own.any { it.usages.isNotEmpty() }
        return templateConfigs.map { config ->
            GedraConfig(
                gedraId = GedraId.of(config.gedraId.kind, client, cloneName(template, config.name)),
                namespace = config.namespace,
                traits = config.traits.filterKeys { it !in ownTraits },
                defs = config.defs.filterKeys { it !in ownTypes },
                cfacts = config.cfacts.filter { it.name !in ownCfacts },
                fragments = config.fragments.map { it.inheritedBy(client, template) },
                uiBlocks = config.uiBlocks.map { it.inheritedBy(client, template) },
                workflows = config.workflows.filterValues { it.workflowId !in ownWorkflowIds && it.entry !in ownSingletonKinds },
                usages = if (ownUsages) emptyList() else config.usages,
                origin = origin,
                inheritedFrom = template,
            )
        }
    }

    /**
     * [child]'s definition built on [template]'s: the child's own fields stand, and where it is silent the template
     * fills in. A missing `webResourcesId` is the template's; `includedTraits` and `userLabels` are the template's
     * followed by the child's, each entry once -- the template's traits are the child's too, by the clone, so what
     * the template takes as it stands the child does. Nothing else is inherited: the environments, the usage type and
     * the audience say what *this* client is, and routing and the sandbox are never shared.
     */
    fun mergeDef(child: ClientDef, template: ClientDef): ClientDef = child.copy(
        webResourcesId = child.webResourcesId ?: template.webResourcesId,
        includedTraits = (template.includedTraits + child.includedTraits).distinct(),
        userLabels = (template.userLabels + child.userLabels).distinct(),
    )

    /**
     * The name a clone of [template]'s config [name] is filed under in the extending client: prefixed, so a client
     * config of the same name -- two clients built from one template often name theirs alike -- never collides with
     * the copy. Base-id characters only (`[A-Za-z0-9_]`), as every config name is.
     */
    fun cloneName(template: String, name: String): String = "${clonePrefix}${template}_$name"

    /** What a clone's name starts with, so a listing can tell a copy from the client's own by name alone. */
    @Suppress("ConstPropertyName")
    const val clonePrefix = "from_"

    /** The kinds of workflow a scope has exactly one of (`WorkflowRegistry`'s singleton rule). */
    private val singletonKinds = setOf(WfEntry.creation, WfEntry.survey)

    private fun FragmentSource.inheritedBy(client: String, template: String): FragmentSource = FragmentSource(
        fileId = fileId, isOverlay = isOverlay, client = this.client?.let { client }, origin = origin,
        audience = audience, shownOn = shownOn, shownFor = shownFor, configName = configName, stored = stored,
        inheritedFrom = template, load = load,
    )

    private fun UiBlockSource.inheritedBy(client: String, template: String): UiBlockSource = UiBlockSource(
        blockId = blockId, isOverlay = isOverlay, client = this.client?.let { client }, origin = origin,
        content = content, arrayKeys = arrayKeys, configName = configName, stored = stored, inheritedFrom = template,
    )
}
