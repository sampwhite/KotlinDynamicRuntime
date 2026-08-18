package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.util.toOptStr

/**
 * Standard workflow vocabulary. Each name matches its value; a `const val` in an UPPER-CASE object per the
 * house rule, referenced qualified (`WF.advisor`).
 *
 * These are defaults, not the only allowed values. A transition's [WfTransition.by] is any role string a
 * definition chooses, and a state's [WfState.holder] is any of the closed [WfHolder] set; [WF.advisor] is the
 * capability the reference flow gates its advisor transitions on, named here so the engine, the tests and
 * (later) a definition all spell it the same way.
 */
@Suppress("ConstPropertyName")
object WF {
    /**
     * The reference advisor capability. Off the privilege ladder, the same shape as [ROLE.allClients]: a
     * deployment grants it to name who *may act as an advisor*, distinct from which workflow they are assigned
     * (`gedra-workflow.md`). A deployment may name its own instead; this is the shared default the sample uses.
     */
    const val advisor = "advisor"
}

/**
 * Who holds a workflow while it sits in a state -- whose action is expected next. A closed operational set, so
 * an enum (the guide's rule for state machines), not a role string: a *holder* is a fact about the state,
 * while the *role* that may take a transition is [WfTransition.by] and is open.
 */
@Suppress("EnumEntryName")
enum class WfHolder {
    /** The workflow is with its owning user -- they fill it in, they submit it. */
    user,

    /** The workflow is with an advisor -- they review it, they approve or return it. */
    advisor,

    /**
     * Nobody: a terminal state, or one advanced only by a process. "Nobody" is enforced, not decorative --
     * a state held by no one yields no assignment, and the transition gate **fails closed** on a null
     * assignment, so no person can advance a workflow out of a `none`-held state. A process path that needs
     * to will have to say so explicitly rather than slipping through an unheld gate.
     */
    none,
}

/**
 * The status of one task within a workflow run (`gedra-workflow.md`, the reopenable task set). A closed set,
 * so an enum. It is **derived**, never stored on its own: [WfEngine.taskStatus] computes it from the entries a
 * workflow holds and the set of tasks the advisor has reopened.
 */
@Suppress("EnumEntryName")
enum class WfTaskStatus {
    /** The task's required traits are not all filled in yet. */
    pending,

    /** The task's required traits are filled and it has not been sent back. */
    complete,

    /** The advisor sent this task back for changes; it is what a targeted "request changes" flags. */
    changesRequested,
}

/**
 * What kind of thing a workflow is assigned to -- who may act on it. A closed set, so an enum.
 *
 * The three cover "a group of users or users belonging to a particular role" and the single-person case: a
 * whole **role** (anyone holding it), a named **group** of users, or one specific **user** (a claim).
 */
@Suppress("EnumEntryName")
enum class WfAssigneeKind { role, group, user }

/** The keys of a stored [WfAssignment] under [com.dynamicruntime.common.gedra.GD.wfAssignment]. */
@Suppress("ConstPropertyName")
object WFA {
    const val kind = "kind"
    const val value = "value"
}

/**
 * Who may act on a workflow right now: a [kind] and the [value] naming it -- a role name, a group id, or a
 * user id as text. This is what replaces a single `assigneeId`, so that the actor can be a role or a group and
 * not only one person (`gedra-workflow.md`).
 *
 * It is matched against a [WfActor] -- the caller's roles, groups and user id -- by [matches], and stored in
 * the workflow's `data` map as `{kind, value}`. A role match runs through [RoleLadder.satisfies], the same
 * predicate the section gate and a transition's [WfTransition.by] use, so "assigned to the advisor role" reads
 * roles the one way the whole system does.
 */
class WfAssignment(val kind: WfAssigneeKind, val value: String) {
    /** Whether [actor] is one of the people this assignment names. */
    fun matches(actor: WfActor): Boolean = when (kind) {
        WfAssigneeKind.role -> RoleLadder.satisfies(actor.roles, value)
        WfAssigneeKind.group -> value in actor.groups
        WfAssigneeKind.user -> actor.userId?.toString() == value
    }

    /** The stored form: `{kind, value}` under `data.wfAssignment`. */
    fun toMap(): Map<String, Any?> = mapOf(WFA.kind to kind.name, WFA.value to value)

    companion object {
        fun ofRole(role: String): WfAssignment = WfAssignment(WfAssigneeKind.role, role)
        fun ofGroup(group: String): WfAssignment = WfAssignment(WfAssigneeKind.group, group)
        fun ofUser(userId: Long): WfAssignment = WfAssignment(WfAssigneeKind.user, userId.toString())

        /**
         * Reads an assignment back from its stored `{kind, value}` map, or null when it cannot. Values are
         * **coerced** ([toOptStr]), not cast: a user id that round-tripped through JSON or SQL as a number
         * must read back as the claim it is, because the caller treats an unreadable assignment as a fault --
         * a parse failure on a security field must never quietly widen who may act.
         */
        fun fromMap(stored: Any?): WfAssignment? {
            val map = stored as? Map<*, *> ?: return null
            val kind = map[WFA.kind].toOptStr()?.let { name -> WfAssigneeKind.entries.firstOrNull { it.name == name } }
                ?: return null
            val value = map[WFA.value].toOptStr() ?: return null
            return WfAssignment(kind, value)
        }
    }
}

/**
 * The caller, reduced to what an assignment is matched against: the roles they hold, the groups they belong
 * to, and their user id. Built at the edge (from `KdrCxt`) and passed into the pure engine, so the kernel
 * stays free of what a "group" happens to mean in a given deployment.
 */
class WfActor(val roles: Set<String>, val userId: Long?, val groups: Set<String> = emptySet())

/**
 * One state a workflow can be in: a name, who holds it, and whether it is where a run starts or ends.
 *
 * [holder] is what the handoff is *about* -- moving between a `user`-held and an `advisor`-held state is the
 * pass from person to person (`gedra-workflow.md`). It is also what tells the engine whose id to stamp as the
 * assignee when a transition lands here.
 */
class WfState(
    val name: String,
    /** Whose turn it is while the workflow sits here. */
    val holder: WfHolder,
    /** Whether a freshly created workflow starts here. Exactly one state per definition is initial. */
    val initial: Boolean = false,
    /** Whether the workflow is finished here -- no transition leaves a terminal state. */
    val terminal: Boolean = false,
)

/**
 * One **task** -- a *step* of the workflow. A task is a unit of editing: the traits its page may touch, and
 * the subset of those that must be filled for the task to be complete (`gedra-patch.md`: "a task declares the
 * traits and key ranges in scope for the task edit").
 *
 * Tasks are what a targeted "request changes" reopens: the advisor sends specific tasks back, not the whole
 * workflow. They are also what a completeness gate is expressed in terms of -- a submit requires its tasks
 * *complete*, and completeness is per task.
 */
class WfTask(
    /** Stable id, unique within the definition; what a transition names to require or reopen it. */
    val id: String,
    /**
     * The traits that must be filled for this task to count as [WfTaskStatus.complete] -- the soft-validation
     * requirement, which lives here rather than in the trait's schema (the trait marks its answers optional;
     * the *workflow* says they are needed). Empty means the task has nothing to complete.
     */
    val requiredTraitIds: List<String> = emptyList(),
    /**
     * The traits this task's page may edit. A superset of (or equal to) [requiredTraitIds]. Consumed when a
     * patch is scoped to a task (`gedra-workflow.md` phase 3) and when the UI renders the task's page (phase
     * 4); phase 1 defines it but does not yet read it, since completeness runs off [requiredTraitIds].
     */
    val editableTraitIds: List<String> = requiredTraitIds,
)

/**
 * One transition: a name, the state it leaves, the state it enters, and the role that may take it. What gates
 * or side-effects it carries is expressed in terms of **tasks**:
 *
 * - [requiresTasks] -- task ids that must be complete before the transition may be taken. This is soft
 *   validation (`gedra-patch.md`): it stops the advance, it never fails the edit that preceded it, and a
 *   refusal reports *which tasks* are unfinished.
 * - [reopensTasks] -- whether this is a "request changes" transition. When true, the caller names the tasks to
 *   send back (at least one), and the engine marks them [WfTaskStatus.changesRequested]. Such a transition
 *   must land in a `user`-held state -- reopening a task is precisely handing it back to the user.
 *
 * [by] is a role string checked with [RoleLadder.satisfies], so a ladder role admits anything at or above it
 * and a capability (like [WF.advisor]) demands exact membership -- the identical rule the section gate uses.
 */
class WfTransition(
    val name: String,
    val from: String,
    val to: String,
    /** The role a caller must satisfy to take this transition. */
    val by: String,
    /** Task ids that must be complete for this transition to be allowed; empty for an ungated transition. */
    val requiresTasks: List<String> = emptyList(),
    /** Whether taking this transition reopens caller-named tasks (a targeted "request changes"). */
    val reopensTasks: Boolean = false,
)

/**
 * A workflow definition: its states, its transitions, and its tasks, compiled into a small state machine the
 * engine interprets (`gedra-workflow.md`). Pure model in `base/kernel`, so the frontend can later run the
 * identical machine the backend enforces.
 *
 * The definition validates itself on construction: exactly one initial state, every transition naming states
 * and tasks that exist, and every task-reopening transition landing in a user-held state. A definition that
 * cannot be a coherent machine is a programming error, caught where it is written.
 */
class WfDefinition(
    /** The definition's id (its base id in a [com.dynamicruntime.common.gedra.GedraId]); names the machine. */
    val workflowId: String,
    states: List<WfState>,
    transitions: List<WfTransition>,
    tasks: List<WfTask> = emptyList(),
    /**
     * The role that holds an [WfHolder.advisor]-held state by default: when a workflow lands in such a state
     * with no narrower assignment, it belongs to everyone with this role (the pool an advisor queue reads).
     * Defaults to [WF.advisor]; a deployment naming its own advisor capability sets it here.
     */
    val advisorRole: String = WF.advisor,
) {
    /** States by name. */
    val statesByName: Map<String, WfState> = states.associateBy { it.name }

    /** Transitions by name. */
    val transitionsByName: Map<String, WfTransition> = transitions.associateBy { it.name }

    /** Tasks by id -- the steps. */
    val tasksById: Map<String, WfTask> = tasks.associateBy { it.id }

    /** Where a freshly created workflow starts. */
    val initialState: WfState

    init {
        if (statesByName.size != states.size) {
            throw KdrException("Workflow '$workflowId' has two states with the same name.")
        }
        if (transitionsByName.size != transitions.size) {
            throw KdrException("Workflow '$workflowId' has two transitions with the same name.")
        }
        if (tasksById.size != tasks.size) {
            throw KdrException("Workflow '$workflowId' has two tasks with the same id.")
        }
        val initials = states.filter { it.initial }
        initialState = when (initials.size) {
            1 -> initials.single()
            0 -> throw KdrException("Workflow '$workflowId' names no initial state.")
            else -> throw KdrException(
                "Workflow '$workflowId' names ${initials.size} initial states; exactly one is allowed.",
            )
        }
        for (t in transitions) {
            statesByName[t.from]
                ?: throw KdrException("Transition '${t.name}' of '$workflowId' leaves unknown state '${t.from}'.")
            statesByName[t.to]
                ?: throw KdrException("Transition '${t.name}' of '$workflowId' enters unknown state '${t.to}'.")
            if (statesByName.getValue(t.from).terminal) {
                throw KdrException("Transition '${t.name}' of '$workflowId' leaves terminal state '${t.from}'.")
            }
            for (taskId in t.requiresTasks) {
                tasksById[taskId] ?: throw KdrException(
                    "Transition '${t.name}' of '$workflowId' requires unknown task '$taskId'.",
                )
            }
            // Reopening a task is handing it back to the user, so such a transition must land in a user-held
            // state; validating it here means the machine cannot be authored to reopen a task into review.
            if (t.reopensTasks && statesByName.getValue(t.to).holder != WfHolder.user) {
                throw KdrException(
                    "Transition '${t.name}' of '$workflowId' reopens tasks but does not land in a user-held state.",
                )
            }
        }
    }

    /** The state named, or null. */
    fun state(name: String): WfState? = statesByName[name]

    /** The transition named, or null. */
    fun transition(name: String): WfTransition? = transitionsByName[name]

    /** The task named, or null. */
    fun task(id: String): WfTask? = tasksById[id]

    /** Every transition that leaves [stateName], regardless of who may take it. */
    fun transitionsFrom(stateName: String): List<WfTransition> =
        transitionsByName.values.filter { it.from == stateName }

    /**
     * The transitions [actor] may take from [stateName], given the workflow's current [assignment] -- **both**
     * gates the transition operation enforces: the transition's role, and a match against who holds the
     * workflow. This is what a UI asks to render its buttons, and because it applies the same two predicates
     * the enforcement does, "see" and "do" cannot disagree: a claimed workflow shows no buttons to the
     * advisor who holds the role but not the claim.
     *
     * A null [assignment] means nobody holds the workflow (an ownerless or process-held state), and nobody
     * may act -- the same fail-closed reading the enforcement applies.
     */
    fun availableTransitions(stateName: String, actor: WfActor, assignment: WfAssignment?): List<WfTransition> {
        if (assignment == null || !assignment.matches(actor)) {
            return emptyList()
        }
        return transitionsFrom(stateName).filter { RoleLadder.satisfies(actor.roles, it.by) }
    }

    /**
     * Who a workflow that has just landed in [stateName] belongs to, when nothing has claimed it more
     * narrowly: the **owner** for a user-held state (their own thing to finish), the **advisor role** for an
     * advisor-held state (the pool), and **nobody** for a terminal or process-held state. A caller may pass an
     * explicit assignment to a transition to narrow the advisor case to one person or a group; this is the
     * default it overrides.
     */
    fun defaultAssignment(stateName: String, ownerUserId: Long?): WfAssignment? =
        when (statesByName[stateName]?.holder) {
            // A user-held state belongs to the owner. An absent owner -- null, or the system user's 0, which
            // is what a null userId column reads back as -- yields NO assignment rather than ofUser(0), and
            // the gate fails closed on null: an ownerless workflow is stuck, never everybody's.
            WfHolder.user -> ownerUserId?.takeIf { it != 0L }?.let { WfAssignment.ofUser(it) }
            WfHolder.advisor -> WfAssignment.ofRole(advisorRole)
            WfHolder.none, null -> null
        }
}

/**
 * The stateless part of the engine: reading completeness and task status off the entries a workflow holds and
 * the tasks its advisor has reopened.
 *
 * Kept apart from [WfDefinition] and pure -- it takes the entries as plain maps -- so both the backend
 * (against stored entries) and, later, the frontend (against a draft the user is still editing) run the same
 * completeness check.
 */
object WfEngine {
    /**
     * The [requiredTraitIds] that are **not** satisfied by [entries]: a required trait is satisfied when an
     * entry of that trait is **present** with a non-null [GE.data] -- whatever shape that data takes.
     *
     * Presence, deliberately not content: a trait whose payload is legitimately an empty map (all-optional
     * fields), an array, or a scalar must be able to satisfy a gate, or the submit is blocked forever with no
     * action that can unblock it. The other side of the same coin -- a present-but-blank answer counting as
     * complete -- is accepted on purpose: completeness is the system's check for *presence*, and judging
     * *content* is what the advisor's review exists for (`gedra-workflow.md`, the soft-validation seam).
     */
    fun missingTraits(requiredTraitIds: List<String>, entries: List<Map<String, Any?>>): List<String> {
        val satisfied = entries.mapNotNull { entry ->
            if (entry[GE.data] != null) entry[GE.traitId].toOptStr() else null
        }.toSet()
        return requiredTraitIds.filter { it !in satisfied }
    }

    /** Whether [task]'s required traits are all present in [entries] (see [missingTraits] for the rule). */
    fun taskComplete(task: WfTask, entries: List<Map<String, Any?>>): Boolean =
        missingTraits(task.requiredTraitIds, entries).isEmpty()

    /**
     * The status of [task] given the [entries] the workflow holds and the ids of tasks the advisor has
     * [reopened]. A reopened task is [WfTaskStatus.changesRequested] whatever its data says -- the advisor's
     * ask is not a data-absence and the system cannot judge it addressed; that is what resubmission and a
     * fresh review are for.
     */
    fun taskStatus(task: WfTask, entries: List<Map<String, Any?>>, reopened: Set<String>): WfTaskStatus = when {
        task.id in reopened -> WfTaskStatus.changesRequested
        taskComplete(task, entries) -> WfTaskStatus.complete
        else -> WfTaskStatus.pending
    }

    /**
     * Which of the [taskIds] a transition [requiresTasks][WfTransition.requiresTasks] are not complete, in the
     * order given -- the "what is unfinished" a refused submit reports. Completeness here is data presence
     * only; a reopened-but-filled task does not block a resubmit (the advisor re-judges it), so this does not
     * consult the reopened set.
     */
    fun incompleteTasks(
        taskIds: List<String>,
        definition: WfDefinition,
        entries: List<Map<String, Any?>>,
    ): List<String> = taskIds.filter { id ->
        val task = definition.task(id) ?: return@filter true // an unknown required task can never be complete
        !taskComplete(task, entries)
    }
}
