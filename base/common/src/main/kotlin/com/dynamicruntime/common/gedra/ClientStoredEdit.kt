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
     * So is a save for a client whose definition asks for a sandbox this node does not carry -- one whose own load
     * failed (issue #1192): it would be a draft no sandbox shows, and the page, which lists what the client runs,
     * would lose sight of it.
     */
    fun target(cxt: KdrCxt, client: String, what: String, expected: String? = null): EditTarget {
        if (expected != null && expected != EDM.live && expected != EDM.draft) {
            throw KdrException.mkInput("'$expected' is not how a save takes effect: it is '${EDM.live}' or '${EDM.draft}'.")
        }
        if (isSandboxClient(client) && !ClientService.get(cxt).isPresent(client)) {
            throw notAsExpected("'$client' is a sandbox this node no longer carries: its parent's sandbox has been removed. Nothing was changed.")
        }
        val target = SandboxEdits.parentCxt(cxt, client)?.let { EditTarget(it, it.client, draft = true) }
            ?: EditTarget(cxt.mkSubContext(what, client), client, ClientService.get(cxt).known(client)?.sandbox == true)
        if (target.draft && !ClientService.get(cxt).isPresent(sandboxOf(target.client))) {
            throw notAsExpected(
                "Client '${target.client}' asks for a sandbox, which this node does not carry -- its issues say why -- so " +
                    "a save would be a draft no sandbox shows. Nothing was changed. Mend what its sandbox's issues name, " +
                    "or remove the sandbox (its Sandbox section, which publishes at once), and saves work again.",
            )
        }
        if (expected == EDM.draft && !target.draft) {
            throw notAsExpected(
                "This would take effect at once for client '${target.client}', not as a draft: it has no sandbox now. " +
                    "Nothing was changed.",
            )
        }
        return target
    }

    /**
     * The client whose running configuration shows [client]'s edits: for a client with a sandbox, the sandbox -- which
     * runs its latest configuration, drafts included, so what an editor lists is what its next edit finds -- and for a
     * sandbox, itself (issue #1358), asked before a sandbox's sandbox's id is formed, which is no id. What the editors'
     * catalogs read (workflows, listing columns, and the report paths, issue #1492).
     */
    fun editedView(cxt: KdrCxt, client: String): String =
        if (sandboxParentOf(client) != null) client
        else sandboxOf(client).takeIf { ClientService.get(cxt).present(it) != null } ?: client

    /** [target]'s refusal of a save that would not take effect as its caller said: a 409 carrying [EDM.notAsExpected]. */
    private fun notAsExpected(message: String): KdrException =
        KdrException(message, code = EXC.conflict).also { it.extraData[KdrException.errorCodeKey] = EDM.notAsExpected }

    /**
     * Makes a save take effect: published and live ([publishAndReload]), or -- a draft -- the owner reloaded, which
     * rebuilds its sandbox from the latest revision while a published-only owner keeps running what it published. A
     * draft is not judged by [impact]: nothing reaches the owner's data until it is published, and publishing judges it.
     *
     * Then the forms the change reaches are brought up to date when the client is small enough (issue #1741,
     * [autoRecalculateFormStates]): the client's own, or for a draft its sandbox's, which is what a draft changes. An
     * editor whose saves cannot change any form's state -- copy, the menu, file refs, the listing and its columns,
     * reports -- passes false for [recalculateForms], and its result says nothing of form states.
     */
    fun takeEffect(
        cxt: KdrCxt,
        target: EditTarget,
        written: GedraConfigRow,
        undo: () -> Unit,
        restorePublished: Boolean = false,
        impact: ImpactGate = ImpactGate.unchecked,
        recalculateForms: Boolean = true,
    ): ConfigReloadResult {
        val reload = if (target.draft) SandboxEdits.reloadParent(cxt, target.client)
        else publishAndReload(cxt, target.bound, target.client, written, undo, restorePublished, impact)
        if (recalculateForms) autoRecalculateFormStates(cxt, target.readsAs)
        return reload
    }

    /**
     * A dry run's [takeEffect] (issue #1764), for [written], the revision the dry run built and did not store. A live
     * save publishes, so a dry run of one is held to what its publish checks ([checkAsPublished]); a draft's is not,
     * since nothing reaches the client until it is published, and publishing checks it then. Answers the problems the
     * client's configuration has now, for the result's [CPY.issues]: a change that passed its trial would add none.
     */
    fun dryRunEffect(
        cxt: KdrCxt,
        target: EditTarget,
        written: GedraConfigRow,
        impact: ImpactGate = ImpactGate.unchecked,
    ): List<GedraConfigIssue> {
        if (!target.draft) checkAsPublished(target.bound, target.client, written, impact)
        return ClientConfigIssues.get(cxt).issuesFor(target.client)
    }

    /**
     * An editor's save made to take effect ([takeEffect]), or -- a [dryRun], which stored nothing -- checked as it would be
     * ([dryRunEffect]) (issue #1766): the one call after an editor's write, so a dry run cannot reach the publish or the
     * reload. Answers the issues the result reports. [undo] runs only for a real save whose publish is refused.
     */
    fun settle(
        cxt: KdrCxt,
        target: EditTarget,
        written: GedraConfigRow,
        dryRun: Boolean,
        undo: () -> Unit,
        restorePublished: Boolean = false,
        impact: ImpactGate = ImpactGate.unchecked,
        recalculateForms: Boolean = true,
    ): List<GedraConfigIssue> =
        if (dryRun) dryRunEffect(cxt, target, written, impact)
        else takeEffect(cxt, target, written, undo, restorePublished, impact, recalculateForms).issues

    /**
     * What [publishAndReload] checks before publishing [written], run on the revision in hand (issue #1764): the
     * [impact] gate, the instance's [PublishCheck], and -- for a client that runs only what it publishes -- the
     * publish's trial, which for one that runs its latest has nothing to judge that the write's trial did not.
     */
    fun checkAsPublished(bound: KdrCxt, client: String, written: GedraConfigRow, impact: ImpactGate) {
        checkBeforePublish(bound, client, written, impact)
        if (GedraConfigService.get(bound).publishedOnly(bound, client)) {
            GedraConfigTrial.requireClean(
                bound, client, listOf(written), published = true, "Configuration '${written.configId.baseId}' would not be published",
            )
        }
    }

    /** The checks [publishAndReload] runs before it publishes [written]: the [impact] gate, and a [PublishCheck]. */
    private fun checkBeforePublish(bound: KdrCxt, client: String, written: GedraConfigRow, impact: ImpactGate) {
        if (impact == ImpactGate.refuse) ConfigImpact.requireNone(bound, client, listOf(written), asSave = true)
        (bound.instanceConfig.get(publishCheckConfigKey) as? PublishCheck)?.check(bound, client, written)
    }

    /**
     * For a [target] that saved a draft, what publishing [written] would do to the stored data of the client it is
     * the draft of (issues #935, #1119): run against the live client, so the person working in the sandbox sees it
     * at the save, for information -- publishing still asks. Null for a live save, or when nothing would be affected.
     */
    fun draftImpact(target: EditTarget, written: GedraConfigRow): ImpactReport? =
        if (!target.draft) null else ConfigImpact.reportFor(target.bound, target.client, listOf(written), anyTier = true).takeIf { it.blocks }

    /**
     * The stored config of the bound client's that holds its definition (`clientDef`), or null when none does. An overlay
     * of a definition in code (issue #1677) is not one: a client defined in code has none stored.
     */
    fun definitionHolder(bound: KdrCxt): GedraConfigRow? =
        GedraConfigService.get(bound).listConfigs(bound).firstOrNull { row ->
            row.entriesBySlot()[CCT.clientDef].orEmpty().any { !it.isCodeOverlay }
        }

    /**
     * Patches [row]'s client definition entry as [edit] leaves it -- inside `patchConfigs`, over the revision as it
     * stands under the lock, so a field another administrator changed a moment ago is kept. The definition editors'
     * shared patch (the sandbox flag, #932; the presentation fields, #1026).
     */
    fun patchDefinition(
        bound: KdrCxt,
        row: GedraConfigRow,
        dryRun: Boolean = false,
        edit: (LinkedHashMap<String, Any?>) -> Unit,
    ): GedraConfigRow =
        patch(bound, dryRun) { edit(row.configId) { current ->
            val out = LinkedHashMap(current)
            val defs = current[CCT.clientDef].orEmpty()
            val def = LinkedHashMap(defs.firstOrNull().toJsonMapOrEmpty())
            edit(def)
            out[CCT.clientDef] = listOf(def) + defs.drop(1)
            out
        } }.single()

    /**
     * An editor's write of the bound client's configuration, trial-checked: stored, or -- a [dryRun] (issue #1764) -- built
     * and judged as the save would be, every rule and the trial reload included, and not stored. A dry run's caller
     * then calls [dryRunEffect] in place of [takeEffect]: nothing was written to take effect.
     */
    fun patch(bound: KdrCxt, dryRun: Boolean, block: ConfigPatches.() -> Unit): List<GedraConfigRow> {
        val svc = GedraConfigService.get(bound)
        return if (dryRun) svc.dryRunPatchConfigs(bound, block) else svc.patchConfigs(bound, trial = true, block)
    }

    /**
     * Puts an editor's patched [entry] back into [slot] of [out]: in place of the one at [at], or appended when there
     * was none -- or, when the patch left it [emptied], removed, so a config does not carry an empty overlay. A slot
     * left with no [entries] is dropped too. The shared tail of both editors' patches, run inside `patchConfigs`.
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
            checkBeforePublish(bound, client, written, impact)
            GedraConfigService.get(bound).publish(bound, listOf(written.configId), trial = true)
        } catch (e: KdrException) {
            undo()
            // Best effort: the undone content is what the client already runs, so its publish should pass; if it does
            // not, the refusal to report is the edit's, and the config is left a draft -- said in the log, since the
            // next edit will be refused for it.
            if (restorePublished) {
                try {
                    GedraConfigService.get(bound).publish(bound, listOf(written.configId), trial = true)
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
     * For a client that runs its latest configuration a publish is not trialed, so a change that passed the write's
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
