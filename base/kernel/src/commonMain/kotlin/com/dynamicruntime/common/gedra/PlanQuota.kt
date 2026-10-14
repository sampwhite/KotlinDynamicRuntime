package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/**
 * The plans a client may be on (issue #1109). A plan limits **content, never features**: how many forms, users,
 * workflows, and schemas a client holds, never what it may do. A customer carries its plan
 * ([CustomerDef.plan], [unlimited] when it names none); a self-serve client ([isSelfServeClient]) is on [free]; a
 * platform or test client, with no customer, is on none and holds what it likes.
 */
@Suppress("ConstPropertyName")
object PLAN {
    const val free = "free"
    const val unlimited = "unlimited"

    /** Every plan, in the order a chooser offers them. */
    val names: List<String> = listOf(free, unlimited)

    /** What the free plan holds: what keeps an advertisement's infrastructure cost bounded. */
    val freeQuotas = PlanQuotas(forms = 100, users = 20, workflows = 4, tasksPerWorkflow = 2, schemas = 10, attributesPerSchema = 10)

    /** [plan]'s quotas: [freeQuotas] for [free] -- and for a name that is no plan, the least -- none for [unlimited]. */
    fun quotasOf(plan: String): PlanQuotas = when (plan) {
        unlimited -> PlanQuotas.none
        else -> freeQuotas
    }
}

/** The measures a plan limits (issue #1109), as their wire names. Each name matches its value. */
@Suppress("ConstPropertyName")
object QUOTA {
    const val forms = "forms"
    const val users = "users"
    const val workflows = "workflows"
    const val tasksPerWorkflow = "tasksPerWorkflow"
    const val schemas = "schemas"
    const val attributesPerSchema = "attributesPerSchema"

    // --- a client's usage, as the overview carries it ---
    const val plan = "plan"
    const val measures = "measures"
    const val measure = "measure"
    const val used = "used"
    const val limit = "limit"
}

/**
 * What a plan allows a client to hold (issue #1109); a null measure is unlimited. A sandbox holds its own forms and
 * users under its parent's plan, counted apart from the parent's.
 */
class PlanQuotas(
    val forms: Int? = null,
    val users: Int? = null,
    val workflows: Int? = null,
    val tasksPerWorkflow: Int? = null,
    /** The types a client declares as its own: a trait's data type, or a type declared directly. */
    val schemas: Int? = null,
    /** The attributes one of those types declares (see [schemaAttributeCount]). */
    val attributesPerSchema: Int? = null,
) {
    /** Whether any measure is limited. */
    val isLimited: Boolean
        get() = listOf(forms, users, workflows, tasksPerWorkflow, schemas, attributesPerSchema).any { it != null }

    companion object {
        val none = PlanQuotas()
    }
}

/** One measure of a client's usage against its plan (issue #1109): how much it holds, of how much it may. */
data class QuotaMeasure(val measure: String, val used: Int, val limit: Int)

/**
 * A client's usage against its plan (issue #1109), for the Clients page: the plan's name, and each counted measure
 * its plan limits -- forms, users, workflows, schemas. The per-item measures (tasks per workflow, attributes per
 * schema) are said by a refusal, where one is met, rather than here.
 */
data class PlanUsage(val plan: String, val measures: List<QuotaMeasure>) {
    fun toInfo(): Map<String, Any?> = mapOf(
        QUOTA.plan to plan,
        QUOTA.measures to measures.map { mapOf(QUOTA.measure to it.measure, QUOTA.used to it.used, QUOTA.limit to it.limit) },
    )

    companion object {
        /** The mirror of [toInfo]; null for a map with no plan. */
        fun fromInfo(info: Map<String, Any?>?): PlanUsage? {
            val plan = info?.get(QUOTA.plan).toOptStr() ?: return null
            val measures = info?.get(QUOTA.measures).toJsonListOfMaps().mapNotNull { m ->
                val measure = m[QUOTA.measure].toOptStr() ?: return@mapNotNull null
                QuotaMeasure(measure, (m[QUOTA.used] as? Number)?.toInt() ?: 0, (m[QUOTA.limit] as? Number)?.toInt() ?: 0)
            }
            return PlanUsage(plan, measures)
        }
    }
}

/**
 * What a client's own configuration holds that a plan counts (issue #1109): its workflows, each with how many tasks,
 * and its own types, each with how many attributes.
 */
class ConfigUsage(
    /** Each of the client's workflows, by id, with its number of tasks. */
    val tasksByWorkflow: Map<String, Int>,
    /** Each of the client's own types, by qualified name, with its [schemaAttributeCount]. */
    val attributesBySchema: Map<String, Int>,
) {
    val workflows: Int get() = tasksByWorkflow.size
    val schemas: Int get() = attributesBySchema.size

    companion object {
        val empty = ConfigUsage(emptyMap(), emptyMap())

        /**
         * The usage [configs] -- one client's own configs -- add up to, beside the global document's type names
         * [globalTypes]. A config copied from a template is not the client's own declaration and is left out; so are
         * the entry types a trait generates, and a type of the global document's name, which a client declares only to
         * narrow it -- an alteration takes away, and holds nothing of its own. A `g-extends` type's body is its
         * delta, so it counts only what it adds.
         */
        fun of(configs: List<GedraConfig>, globalTypes: Set<String>): ConfigUsage {
            val own = configs.filter { it.inheritedFrom == null }
            val tasks = LinkedHashMap<String, Int>()
            val attributes = LinkedHashMap<String, Int>()
            for (config in own) {
                config.workflows.values.forEach { tasks[it.workflowId] = it.tasks.size }
                val generated = config.traits.values.map { it.typeName }.toSet()
                for ((name, body) in config.defs) {
                    if (name in generated || name in globalTypes) continue
                    attributes[name] = schemaAttributeCount(body as? Map<*, *> ?: emptyMap<String, Any?>())
                }
            }
            return ConfigUsage(tasks, attributes)
        }
    }
}

/**
 * How many attributes a type's [body] declares (issue #1109): each property counts one, and a property that is an
 * inline object -- or an array of inline objects -- adds what it declares, at any depth. A property that is only a
 * `$ref` counts one, and what the type it names declares is that type's, not this one's.
 *
 * Fields declared in a composition (`allOf`, `anyOf`, `oneOf`), a conditional branch (`then`, `else`), or an inline
 * `additionalProperties` object are the type's too, so a client cannot hold fields there uncounted. Counted as
 * distinct field paths: a branch restating a field the type already declares, to narrow it, adds nothing.
 */
fun schemaAttributeCount(body: Map<*, *>): Int = attributePaths(body, "").size

private fun attributePaths(body: Map<*, *>, prefix: String): Set<String> {
    val out = LinkedHashSet<String>()
    (body[SCH.properties] as? Map<*, *>)?.forEach { (name, property) ->
        val p = property as? Map<*, *> ?: return@forEach
        val path = "$prefix$name"
        out.add(path)
        if (SCH.dRef in p) return@forEach
        val nested = if (p[SCH.type] == SCT.array) (p[SCH.items] as? Map<*, *>)?.takeIf { SCH.dRef !in it } else p
        nested?.let { out.addAll(attributePaths(it, "$path.")) }
    }
    for (key in listOf(SCH.allOf, SCH.anyOf, SCH.oneOf)) {
        (body[key] as? List<*>)?.forEach { branch -> (branch as? Map<*, *>)?.let { out.addAll(attributePaths(it, prefix)) } }
    }
    for (key in listOf(SCH.kThen, SCH.kElse)) (body[key] as? Map<*, *>)?.let { out.addAll(attributePaths(it, prefix)) }
    (body[SCH.additionalProperties] as? Map<*, *>)?.takeIf { SCH.dRef !in it }?.let { out.addAll(attributePaths(it, "$prefix*.")) }
    return out
}
