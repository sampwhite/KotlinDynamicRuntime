package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * What the administrators' editors of a client's presentation share (issues #918, #919): the stored config their
 * edits land in when no other holds the thing edited, the rule against writing into somebody's draft, and the
 * publish-reload-undo that makes an edit live. The copy editor ([ClientCopyEdit]) and the menu editor
 * ([ClientMenuEdit]) each decide *which* config holds their key or item and how to patch it; this is the rest.
 */
@Suppress("ConstPropertyName")
object ClientStoredEdit {
    /**
     * Where an editor's save of [client]'s presentation lands, and how it takes effect (issue #930).
     *
     * - **The configuration's owner.** A Shadow Sandbox holds no configuration, so a save named for one lands in its
     *   parent's, made in [bound] -- bound to the parent and acting as the person's user there ([SandboxEdits]).
     * - **Live or draft, by whether the owner has a sandbox.** Without one, a save publishes and goes live at once
     *   (#918's behavior). With one -- from the parent or the sandbox alike -- a save writes the parent's editable
     *   revision and reloads, so the sandbox shows it, and publishing is the explicit step.
     */
    class EditTarget(
        /** The context the configuration is read and written in, bound to [client]. */
        val bound: KdrCxt,
        /** The client whose configuration takes the save: the one named, or a sandbox's parent. */
        val client: String,
        /** Whether a save stays a draft ([EDM.draft]) rather than publishing ([EDM.live]). */
        val draft: Boolean,
    ) {
        /** The result's [CPY.mode]. */
        val mode: String get() = if (draft) EDM.draft else EDM.live

        /** The client whose people read a saved value: the owner when live, its sandbox for a draft. */
        val readsAs: String get() = if (draft) sandboxOf(client) else client
    }

    /**
     * Where a save of [client]'s presentation lands -- see [EditTarget]; [what] names the editor's sub context.
     *
     * **Held to what the caller told its user** (issues #1176, #1191). Whether a save is a draft is decided here, at
     * the request, by whether the client has a sandbox *now*; a page told its user which before they acted, from what
     * it last read, and a sandbox can be removed in between. [expected] is what the page said ([EDM]), and a save
     * expected to be a draft that would be live is refused with a 409, nothing changed: a change the person meant to
     * look at first must not reach the client's people. The other way -- told live, saved as a draft -- goes ahead,
     * since nothing reaches anyone, and the result's mode says which it was. Null asks nothing (a script, an older
     * page).
     *
     * A save naming a sandbox this node no longer carries is refused the same way, whatever was expected: its
     * parent's sandbox was removed, so a "draft" of the parent's configuration could be live at the parent's next load.
     */
    fun target(cxt: KdrCxt, client: String, what: String, expected: String? = null): EditTarget {
        if (isSandboxClient(client) && !ClientService.get(cxt).isPresent(client)) {
            throw KdrException(
                "'$client' is a sandbox this node no longer carries: its parent's sandbox has been removed. Nothing was changed.",
                code = EXC.conflict,
            )
        }
        val target = SandboxEdits.parentCxt(cxt, client)?.let { EditTarget(it, it.client, draft = true) }
            ?: EditTarget(cxt.mkSubContext(what, client), client, ClientService.get(cxt).known(client)?.sandbox == true)
        if (expected == EDM.draft && !target.draft) {
            throw KdrException(
                "This would take effect at once for client '${target.client}', not as a draft: it has no sandbox now. " +
                    "Nothing was changed.",
                code = EXC.conflict,
            )
        }
        return target
    }

    /**
     * Makes a save take effect: published and live ([publishAndReload]), or -- a draft -- the owner reloaded, which
     * rebuilds its sandbox from the latest revision while a published-only owner keeps running what it published. A
     * draft is not judged by [impact]: nothing reaches the owner's data until it is published, and publishing judges it.
     */
    fun takeEffect(
        cxt: KdrCxt,
        target: EditTarget,
        written: GedraConfigRow,
        undo: () -> Unit,
        restorePublished: Boolean = false,
        impact: ImpactGate = ImpactGate.unchecked,
    ): ConfigReloadResult =
        if (target.draft) SandboxEdits.reloadParent(cxt, target.client)
        else publishAndReload(cxt, target.bound, target.client, written, undo, restorePublished, impact)

    /** The stored config of the bound client's that holds its definition (`clientDef`), or null when none does. */
    fun definitionHolder(bound: KdrCxt): GedraConfigRow? =
        GedraConfigService.get(bound).listConfigs(bound).firstOrNull { it.entriesBySlot()[CCT.clientDef]?.isNotEmpty() == true }

    /**
     * Patches [row]'s client definition entry as [edit] leaves it -- inside `patchConfig`, over the revision as it
     * stands under the lock, so a field another administrator changed a moment ago is kept. The definition editors'
     * shared patch (the sandbox flag, #932; the presentation fields, #1026).
     */
    fun patchDefinition(bound: KdrCxt, row: GedraConfigRow, edit: (LinkedHashMap<String, Any?>) -> Unit): GedraConfigRow =
        GedraConfigService.get(bound).patchConfig(bound, row.configId, trial = true) { current ->
            val out = LinkedHashMap(current)
            val defs = current[CCT.clientDef].orEmpty()
            val def = LinkedHashMap(defs.firstOrNull().toJsonMapOrEmpty())
            edit(def)
            out[CCT.clientDef] = listOf(def) + defs.drop(1)
            out
        }

    /**
     * Puts an editor's patched [entry] back into [slot] of [out]: in place of the one at [at], or appended when there
     * was none -- or, when the patch left it [emptied], removed, so a config does not carry an empty overlay. A slot
     * left with no [entries] is dropped too. The shared tail of both editors' patches, run inside `patchConfig`.
     */
    fun storeEntry(
        out: MutableMap<String, List<Map<String, Any?>>>,
        slot: String,
        entries: MutableList<Map<String, Any?>>,
        at: Int,
        entry: Map<String, Any?>,
        emptied: Boolean,
    ) {
        when {
            emptied && at >= 0 -> entries.removeAt(at)
            at >= 0 -> entries[at] = entry
            else -> entries.add(entry)
        }
        if (entries.isEmpty()) out.remove(slot) else out[slot] = entries
    }

    /** The editors' own config of [client]'s ([CPY.copyConfigName]), or null when none has been created yet. */
    fun editConfig(bound: KdrCxt, client: String): GedraConfigRow? =
        GedraConfigService.get(bound).readLatest(bound, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))

    /** Creates the editors' own config for [client] with what [build] declares, trial-checked, and returns the row. */
    fun createEditConfig(bound: KdrCxt, client: String, build: GedraConfigBuilder.() -> Unit): GedraConfigRow {
        val config = gedraConfig(bound, CPY.copyConfigName, clientNamespace(client), client, build = build)
        return GedraConfigService.get(bound).writeConfig(bound, config, trial = true)
    }

    /**
     * The editors' own config of [client]'s, created **empty** when there is none -- and safely against somebody
     * else creating it at the same moment (issue #1153). The creation is a create, refused under the client's lock
     * when the config is already there, and that refusal is answered by reading what the other caller made. So an
     * editor that goes on to *patch* its entry in, under the lock and over the revision as it then stands, cannot
     * write over an entry made a moment earlier -- which [createEditConfig] with content can: two first edits each
     * see no config, and the second's authoritative write drops the first's entry after it was acknowledged.
     */
    fun ensureEditConfig(bound: KdrCxt, client: String): GedraConfigRow {
        editConfig(bound, client)?.let { return it }
        val empty = gedraConfig(bound, CPY.copyConfigName, clientNamespace(client), client) {}
        return try {
            GedraConfigService.get(bound).writeConfig(bound, empty, trial = true, mustBeNew = true)
        } catch (e: KdrException) {
            if (e.code != EXC.conflict) throw e
            editConfig(bound, client) ?: throw e
        }
    }

    /**
     * Refuses to write into a config that has unpublished changes of its own -- an edit publishes the config it lands
     * in, and would take somebody's half-finished draft live with it -- unless it is the editors' own config, whose
     * only drafts are the editors' (a publish that was refused). [what] names the edit in the refusal. Not asked of a
     * [EditTarget.draft] save, which publishes nothing.
     */
    fun requireNoForeignDraft(target: EditTarget, holder: GedraConfigRow, what: String) {
        if (target.draft || holder.isPublished || holder.configId.baseId == CPY.copyConfigName) return
        throw KdrException.mkInput(
            "Configuration '${holder.configId.baseId}' of client '${holder.client}' has unpublished changes, which " +
                "$what would publish with it. Publish or revert that configuration first.",
        )
    }

    /**
     * Publishes [written]'s config, reloads [client] on this node and announces it to peers, returning the reload.
     * A publish the trial refuses runs [undo] -- the change put back as it was -- before the refusal is rethrown, so
     * the change is not left in a draft that the next edit would publish. The undo is itself a further unpublished
     * revision, equal to the published one; with [restorePublished] -- for a config that was published before the
     * edit -- it is published too, so the config is as it was rather than a draft the next edit is refused for.
     *
     * With [impact] at [ImpactGate.refuse], a save that would affect the data the client already stores is refused
     * with the impact report, and undone the same way (issue #1040). It is judged before the publish whatever the
     * client's tier, since the save is what makes the change live ([ConfigImpact.requireNone]'s `asSave`).
     *
     * A [PublishCheck] the instance carries runs where the publish does, so a test can refuse one (issue #1179).
     */
    fun publishAndReload(
        cxt: KdrCxt,
        bound: KdrCxt,
        client: String,
        written: GedraConfigRow,
        undo: () -> Unit,
        restorePublished: Boolean = false,
        impact: ImpactGate = ImpactGate.unchecked,
    ): ConfigReloadResult {
        try {
            if (impact == ImpactGate.refuse) ConfigImpact.requireNone(bound, client, written, asSave = true)
            (bound.instanceConfig.get(publishCheckConfigKey) as? PublishCheck)?.check(bound, client, written)
            GedraConfigService.get(bound).publish(bound, written.configId, trial = true)
        } catch (e: KdrException) {
            undo()
            // Best effort: the undone content is what the client already runs, so its publish should pass; if it does
            // not, the refusal to report is the edit's, and the config is left a draft -- said in the log, since the
            // next edit will be refused for it.
            if (restorePublished) {
                try {
                    GedraConfigService.get(bound).publish(bound, written.configId, trial = true)
                } catch (restore: KdrException) {
                    LogGedra.warn(cxt) {
                        "Could not republish '${written.configId.baseId}' of client '$client' after undoing a refused " +
                            "edit; it is left with unpublished changes: ${restore.message}"
                    }
                }
            }
            throw e
        }
        val reload = GedraConfigReload.reloadClient(cxt, client)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
        return reload
    }

    /**
     * A refusal a test puts where [publishAndReload] publishes (issue #1179), handed what is about to be published.
     * For a client that runs its latest configuration a publish is not trialled, so a change that passed the write's
     * trial is otherwise never refused there -- and an editor's undo, which only a refused publish runs, is never
     * run. A [KdrException] it throws is the publish's refusal: the undo runs and the caller is told it. Anything else
     * escapes as the publish's own faults do, undoing nothing. Nothing but a test sets one, in the instance config
     * under [publishCheckConfigKey]; the publish itself is unchanged.
     */
    fun interface PublishCheck {
        fun check(bound: KdrCxt, client: String, written: GedraConfigRow)
    }

    /** The instance-config key a test hands in a [PublishCheck] under. */
    const val publishCheckConfigKey = "clientEditPublishCheck"
}
