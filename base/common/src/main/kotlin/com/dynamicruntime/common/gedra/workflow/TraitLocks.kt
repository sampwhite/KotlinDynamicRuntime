package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraGuardedWrite
import com.dynamicruntime.common.gedra.GedraWriteGuard
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.util.humanizeFieldName

/**
 * Which of a form's traits are **locked for this caller** (issue #857), from the form's stored state and the caller --
 * computed on every read and every write, never stored, so nothing has to be written to lift a lock.
 *
 * A lock ([WfLock]) of a normal workflow is **active** on a form while the form is engaged with the workflow, the
 * workflow is within its lifetime, and the lock's condition holds over the form's facts (its merged cfacts) and the
 * workflow's own (its stored cfacts and the singletons it contributes). An active lock **holds for** a caller who may
 * not save its `writableVia` task -- issue #856's rule, judged by the same [WorkflowTaskJudge] the task's view and save
 * use -- so the task that owns the trait and the lock on it cannot disagree about who may write it. A caller the lock
 * holds for **may override** it when its `overrideWhen` matches their request facts.
 */
object TraitLocks {
    /** A lock holding for the caller: the workflow it belongs to, the lock, and whether the caller may override it. */
    class Held(val declared: WfDeclared, val lock: WfLock, val canOverride: Boolean)

    /**
     * The locks that hold for [cxt]'s caller on a form of [client] with [entries] and [states], narrowed to
     * [traitIds] when given. Empty when the form is engaged with no workflow that locks anything.
     */
    fun heldFor(
        cxt: KdrCxt,
        client: String,
        entries: List<Map<String, Any?>>,
        states: List<Map<String, Any?>>,
        traitIds: Set<String>? = null,
    ): List<Held> {
        val registry = WorkflowService.get(cxt).forClient(client)
        val engaged = engagedWorkflowIds(states)
        val locking = engaged.mapNotNull { id ->
            WorkflowPhases.live(cxt, registry, id)?.takeIf { d ->
                d.def.entry == WfEntry.normal && d.def.locks.any { traitIds == null || it.traitId in traitIds }
            }
        }
        if (locking.isEmpty()) return emptyList()
        val cfacts = SchemaService.get(cxt).cfactsFor(client)
        val requestFacts = cfacts.assemble(cxt)
        val formFacts = states.filter { it[GE.traitId].toOptStr() == GT.cfacts }
            .flatMap { it[GE.data].toJsonMapOrEmpty()[GT.facts].toJsonListOrEmpty() }
            .mapNotNull { it.toOptStr() }.toSet()
        return locking.flatMap { declared ->
            val workflowId = declared.def.workflowId
            val entry = previousEntry(states, workflowId)
            val lockFacts = formFacts +
                entry[WFS.cfacts].toJsonListOrEmpty().mapNotNull { it.toOptStr() } +
                entry[WFS.singletonCfacts].toJsonListOrEmpty().mapNotNull { it.toOptStr() }
            val judge by lazy {
                WorkflowTaskJudge(
                    cxt, client, declared, taskEntriesOf(declared, entries), WorkflowApprovals.of(states, workflowId),
                    requestFacts,
                )
            }
            declared.def.locks
                .filter { traitIds == null || it.traitId in traitIds }
                .filter { cfacts.parse(it.whenExpr).matches(lockFacts) }
                .filter { lock -> declared.def.task(lock.writableVia)?.let { !judge.maySave(it) } ?: false }
                .map { lock ->
                    Held(declared, lock, canOverride = lock.overrideWhen?.let { cfacts.parse(it).matches(requestFacts) } ?: false)
                }
        }
    }

    /**
     * [held] as the pages read it (issue #857): per locked trait, the workflow locking it -- id and label, in
     * [client]'s wording -- and whether this caller may override it. A trait two workflows lock is listed once per
     * lock, since an override has to be allowed by each.
     */
    fun describe(cxt: KdrCxt, client: String, held: List<Held>): List<Map<String, Any?>> {
        if (held.isEmpty()) return emptyList()
        val resolve = copyResolver(cxt, client)
        return held.map {
            linkedMapOf<String, Any?>(
                WFD.traitId to it.lock.traitId,
                // Named as the server's refusals name it, so the page and the refusal beside it cannot disagree.
                WVF.traitName to traitName(cxt, client, it.lock.traitId),
                WFD.workflowId to it.declared.def.workflowId,
                WFD.label to workflowLabel(it.declared.def, resolve),
                WVF.canOverride to it.canOverride,
            )
        }
    }

    /**
     * A trait's name as the pages head it, for a message: its data type's title in [client]'s schema, else its id
     * humanized -- "Acme site audit", not `acmeSiteAudit`.
     */
    fun traitName(cxt: KdrCxt, client: String, traitId: String): String {
        val union = SchemaService.get(cxt).storeFor(client)
            .types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]
        val title = union?.variants?.byValue?.get(traitId)?.properties?.get(GE.data)?.valueType?.title
        return title?.takeIf { it.isNotBlank() } ?: humanizeFieldName(traitId)
    }

    /** A workflow's name, in [client]'s wording, for saying which workflow holds a lock. */
    fun workflowLabel(cxt: KdrCxt, client: String, held: Held): String =
        workflowLabel(held.declared.def, copyResolver(cxt, client))
}

/**
 * The trait-lock guard (issue #857): refuses a patch that changes a trait locked for the writer, unless the writer
 * asked to override (a non-blank reason) and may override every lock the edit touches -- and then records the
 * override on each workflow's engagement trail, with the write. Registered as a [GedraWriteGuard], so it runs under
 * the form's lock on every edit path: the raw editor and patch endpoint, and the survey's and a workflow's saves.
 * It also refuses deleting a form holding a trait locked for the deleter -- with no override, since a deletion leaves
 * nothing for the trail to be read on.
 */
object TraitLockGuard : GedraWriteGuard {
    override fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)? {
        // Cheap first: most edits touch no trait any workflow of this client locks, and then there is nothing to read.
        val registry = WorkflowService.get(cxt).forClient(write.row.client)
        val lockedSomewhere = registry.workflows.values.any { d -> d.def.locks.any { it.traitId in write.changedTraits } }
        if (!lockedSomewhere) return null
        val client = write.row.client
        val held = TraitLocks.heldFor(cxt, client, write.row.entries, write.states, write.changedTraits)
        if (held.isEmpty()) return null
        // The messages name traits and workflows as the pages do -- never by id -- in the wording the raw editor shares.
        fun names(locks: List<TraitLocks.Held>) = locks.map {
            LockNames(TraitLocks.traitName(cxt, client, it.lock.traitId), TraitLocks.workflowLabel(cxt, client, it))
        }
        if (write.deletesGedra) {
            throw KdrException(TraitLockCopy.deleteRefused(names(held)), code = EXC.conflict)
        }
        val reason = write.overrideReason
        if (reason == null) {
            val overridable = held.all { it.canOverride }
            throw KdrException(
                TraitLockCopy.changeRefused(names(held)) +
                    if (overridable) " " + TraitLockCopy.overrideOffer(held.size) else "",
                code = EXC.conflict,
            )
        }
        if (reason.isBlank()) {
            throw KdrException.mkInput("Overriding a lock needs a reason, recorded with the change.")
        }
        held.filterNot { it.canOverride }.takeIf { it.isNotEmpty() }?.let { refused ->
            throw KdrException(TraitLockCopy.overrideRefused(names(refused)), code = EXC.conflict)
        }
        // Permitted: the override is recorded on each locking workflow's trail, with the write.
        val at = cxt.instanceNow()
        val by = cxt.userProfile.userId
        val byWorkflow = held.groupBy { it.declared.def.workflowId }
        return { current ->
            byWorkflow.entries.fold(current) { acc, (workflowId, locks) ->
                val event = linkedMapOf<String, Any?>(
                    WFS.kind to WFS.lockOverriddenEvent,
                    WFS.at to at,
                    WFS.by to by,
                    WFS.note to "${locks.joinToString(", ") { it.lock.traitId }}: ${reason.trim()}",
                )
                WorkflowEngagement.withEvent(acc, workflowId, event)
            }
        }
    }
}
