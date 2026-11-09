package com.dynamicruntime.common.user

import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * The wire names of what a user label does (issue #1658): a [UserLabelDetail] per label a client suggests, each with
 * the [UserLabelUse]s its workflows make of it. In the kernel so the Users editor reads the backend's answer, and words
 * it, with the code that wrote it.
 */
@Suppress("ConstPropertyName")
object ULU {
    /** On the label-suggestions read: a [UserLabelDetail] per suggested label, in the client's order. */
    const val details = "details"

    const val label = "label"
    const val uses = "uses"

    /** The client's own sentence about the label (issue #1660), from its definition's `userLabelNotes`. */
    const val note = "note"

    /** How many enabled users of the client carry the label, as the caller sees them. */
    const val carriers = "carriers"

    const val kind = "kind"
    const val workflowId = "workflowId"
    const val workflowLabel = "workflowLabel"
    const val taskId = "taskId"
    const val taskLabel = "taskLabel"

    /** The fact a task's `userHasLabel` function emits for a viewer carrying the label. */
    const val cfact = "cfact"
}

/**
 * What a client's workflow does with a user label (issue #1658). A label grants nothing itself; these are the ways a
 * workflow gives it meaning, worked out from the configuration and never stored.
 */
@Suppress("EnumEntryName")
enum class UserLabelUseKind {
    /** A task's `userHasLabel` emits a fact its approval rule names: a holder may approve the task. */
    approves,

    /** A task's `userHasLabel` emits a fact its rule for who may save it names: a holder may save the task. */
    saves,

    /** A task's `userHasLabel` emits a fact that only the task's display or other rules test. */
    tests,

    /** The workflow is restricted to the holders of some labels, this one among them, and its administrators. */
    sees,
}

/**
 * One thing a client's workflow does with a user label (issue #1658): the workflow, and for a task's use the task and
 * the fact the task's `userHasLabel` function emits. The labels are as the client's page shows them, fragment pulls
 * resolved.
 */
class UserLabelUse(
    val kind: UserLabelUseKind,
    val workflowId: String,
    val workflowLabel: String,
    val taskId: String? = null,
    val taskLabel: String? = null,
    val cfact: String? = null,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = buildMap {
        put(ULU.kind, kind.name)
        put(ULU.workflowId, workflowId)
        put(ULU.workflowLabel, workflowLabel)
        taskId?.let { put(ULU.taskId, it) }
        taskLabel?.let { put(ULU.taskLabel, it) }
        cfact?.let { put(ULU.cfact, it) }
    }

    /** The task as a sentence names it, quoted; its id when it has no label. */
    val taskName: String get() = "“${taskLabel ?: taskId ?: ""}”"

    /** The workflow as a sentence names it, quoted. */
    val workflowName: String get() = "“$workflowLabel”"

    /**
     * What a holder of the label can do, as a sentence: "Approves “Approve the plan” in “Plan the logistics”". The one
     * wording, so the Users editor and any warning say the same thing.
     */
    val sentence: String
        get() = when (kind) {
            UserLabelUseKind.approves -> "Approves $taskName in $workflowName"
            UserLabelUseKind.saves -> "Saves $taskName in $workflowName"
            UserLabelUseKind.tests -> "Changes what $taskName in $workflowName shows"
            UserLabelUseKind.sees -> "Sees $workflowName, which is restricted to the holders of its labels"
        }

    /**
     * What somebody without the label cannot do, as a verb phrase completing "can't …": "approve “Approve the plan” in
     * “Plan the logistics”". Null for [UserLabelUseKind.tests], which withholds nothing a person would miss.
     */
    val withheld: String?
        get() = when (kind) {
            UserLabelUseKind.approves -> "approve $taskName in $workflowName"
            UserLabelUseKind.saves -> "save $taskName in $workflowName"
            UserLabelUseKind.sees -> "see $workflowName"
            UserLabelUseKind.tests -> null
        }

    companion object {
        /** A use read off the wire; a kind that is none is null, so a newer backend's kind is skipped, not fatal. */
        fun fromJson(m: Map<String, Any?>): UserLabelUse? {
            val kind = UserLabelUseKind.entries.firstOrNull { it.name == m[ULU.kind].toOptStr() } ?: return null
            return UserLabelUse(
                kind = kind,
                workflowId = m[ULU.workflowId].toOptStr() ?: "",
                workflowLabel = m[ULU.workflowLabel].toOptStr() ?: m[ULU.workflowId].toOptStr() ?: "",
                taskId = m[ULU.taskId].toOptStr(),
                taskLabel = m[ULU.taskLabel].toOptStr(),
                cfact = m[ULU.cfact].toOptStr(),
            )
        }
    }
}

/**
 * A label a client suggests (issue #1658), with what its workflows do with it ([uses], empty when none names it), how
 * many of the client's enabled users carry it ([carriers]), counted among those the caller sees, and the client's own
 * sentence about it ([note], issue #1660), when it wrote one.
 */
class UserLabelDetail(
    val label: String,
    val uses: List<UserLabelUse>,
    val carriers: Int,
    val note: String? = null,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = buildMap {
        put(ULU.label, label)
        note?.let { put(ULU.note, it) }
        put(ULU.uses, uses.map { it.toJsonMap() })
        put(ULU.carriers, carriers)
    }

    companion object {
        fun fromJson(m: Map<String, Any?>): UserLabelDetail = UserLabelDetail(
            label = m[ULU.label].toOptStr() ?: "",
            uses = m[ULU.uses].toJsonListOfMaps().mapNotNull { UserLabelUse.fromJson(it) },
            carriers = (m[ULU.carriers].toOptLong() ?: 0L).toInt(),
            note = m[ULU.note].toOptStr(),
        )

        /** The details on a label-suggestions answer ([ULU.details]); empty from a backend that sends none. */
        fun listFrom(answer: Map<String, Any?>): List<UserLabelDetail> = answer[ULU.details].toJsonListOfMaps().map { fromJson(it) }
    }
}

/** [items] as a sentence lists them: "a", "a or b", "a, b, or c". */
fun joinedWithOr(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} or ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", or " + items.last()
}

/**
 * What goes undone for want of a label nobody carries (issue #1661), a sentence per such label: "Nobody carries
 * “reviewer”, so nobody can approve “Approve the plan” in “Plan the logistics”." A restricted workflow is still seen by
 * the client's administrators, which the sentence says. A label whose uses withhold nothing, or that somebody carries,
 * says nothing. The count is of the users the caller sees, so an administrator confined to an organization is told
 * about their own. A notice, never a refusal: a new client starts with nobody labeled.
 */
fun nobodyCarriesWarnings(details: List<UserLabelDetail>): List<String> = details.mapNotNull { d ->
    if (d.carriers > 0) return@mapNotNull null
    val acts = d.uses.filter { it.kind == UserLabelUseKind.approves || it.kind == UserLabelUseKind.saves }
        .mapNotNull { it.withheld }.distinct()
    val sees = d.uses.filter { it.kind == UserLabelUseKind.sees }.mapNotNull { it.withheld }.distinct()
    val parts = listOfNotNull(
        acts.takeIf { it.isNotEmpty() }?.let { "nobody can ${joinedWithOr(it)}" },
        sees.takeIf { it.isNotEmpty() }?.let { "only the client's administrators can ${joinedWithOr(it)}" },
    )
    if (parts.isEmpty()) null else "Nobody carries “${d.label}”, so ${parts.joinToString(", and ")}."
}
