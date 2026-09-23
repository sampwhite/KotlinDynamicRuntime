package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.toOptStr

/**
 * The `prefillFromOwner` function's name, its initialization-data fields, and the owner attributes it may read
 * (issue #679). The attribute set is the vocabulary a handler exposes about the form's owner; naming it here
 * lets the initialization-data schema boot-check a `userAttribute` against it.
 */
@Suppress("ConstPropertyName")
object PFO {
    /** The `fn` discriminator this function is declared under. */
    const val fn = "prefillFromOwner"

    /** Which owner attribute to read; one of [ownerAttributes]. */
    const val userAttribute = "userAttribute"

    /** The trait whose field the value defaults. */
    const val targetTrait = "targetTrait"

    /** A dotted path into [targetTrait]'s data the value defaults (e.g. `fullName`, or `address.city`). */
    const val targetValuePath = "targetValuePath"

    // --- the owner attributes a prefill may read (the same keys a handler builds the owner map with) ---
    const val publicName = "publicName"
    const val name = "name"

    /** The owner's primary identifier -- their email (`AuthUserRow.primaryId`), issue #710/#711. */
    const val email = "email"

    /** The attributes a `prefillFromOwner` may name, and a handler supplies. */
    val ownerAttributes: List<String> = listOf(publicName, name, email)

    /** The schema namespace and type name for this function's initialization data. */
    const val namespace = "wffnpfo"
    const val initDataType = "PrefillFromOwner"
}

/**
 * The first `prefillData` executor (issue #679): read the form owner's [userAttribute] (e.g. `publicName`) and
 * supply it as the default for [targetTrait]'s [targetValuePath] field. The design example -- a trait that asks
 * for the participant's name, defaulted from the owner's public name.
 *
 * Definition is data, computation is Kotlin: this class is the computation, built from validated initialization
 * data by [PrefillFromOwnerCreation]. An owner with no such attribute (or a task whose target field is already
 * filled) supplies nothing -- a default is an offer, not an override.
 */
class PrefillFromOwnerFn(
    override val priority: Int,
    private val userAttribute: String,
    private val targetTrait: String,
    private val targetValuePath: String,
) : PrefillDataFn {
    override val fn: String = PFO.fn

    override fun prefill(cxt: KdrCxt, params: PrefillDataParams) {
        params.supply(targetTrait, targetValuePath, params.ownerAttributes[userAttribute])
    }
}

/**
 * Builds a [PrefillFromOwnerFn] from a usage's initialization data (issue #679), validating and coercing it
 * against [initDataType] and throwing -- which the resolution pass turns into a config problem -- when it does
 * not conform. Registered in `CommonComponent` through `SchemaCollector.addWorkflowFunction`.
 */
object PrefillFromOwnerCreation : WfFunctionCreation {
    override val fn: String = PFO.fn
    override val event: WfEventType = WfEventType.prefillData

    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction {
        val m = validatedInitData(initDataType(cxt), usage, PFO.fn)
        return PrefillFromOwnerFn(
            priority = usage.priority,
            userAttribute = m[PFO.userAttribute].toOptStr() ?: "",
            targetTrait = m[PFO.targetTrait].toOptStr() ?: "",
            targetValuePath = m[PFO.targetValuePath].toOptStr() ?: "",
        )
    }

    /** The trait this function defaults into, for the admission check that the client supports it. */
    override fun referencedTraits(usage: WfFunctionUsage): Set<String> =
        usage.initData[PFO.targetTrait].toOptStr()?.let { setOf(it) } ?: emptySet()

    // Parsed once and kept: the schema is a constant of the runtime, matching how WfDefSchema keeps its own.
    private var parsed: SchType? = null

    private fun initDataType(cxt: KdrCxt): SchType =
        parsed ?: parseSchemaTypes(
            schemaDefs(cxt, PFO.namespace) {
                type(PFO.initDataType) {
                    type = SCT.kObject
                    description = "Default a trait field from an attribute of the form's owner."
                    property(WFD.fn, "The function discriminator; always '${PFO.fn}' here.", required = true)
                    property(WFD.priority, "Run order within the task's prefillData list.") { type = SCT.integer }
                    property(PFO.userAttribute, "Which owner attribute to read.", required = true) {
                        for (attr in PFO.ownerAttributes) option(attr)
                    }
                    property(PFO.targetTrait, "The trait whose field the value defaults.", required = true)
                    property(PFO.targetValuePath, "A dotted path into the target trait's data the value defaults.", required = true)
                }
            },
        ).getValue("${PFO.namespace}.${PFO.initDataType}").also { parsed = it }
}

/**
 * Authors a [PFO.fn] usage's initialization data as JSON (issue #679), used inside a
 * `task(...) { function(prefillFromOwner { ... }) }` block. It emits the same shape [PrefillFromOwnerCreation]
 * parses, so a code-built and a stored usage are indistinguishable downstream.
 */
class PrefillFromOwnerBuilder {
    /** Which owner attribute to read; one of [PFO.ownerAttributes]. */
    var userAttribute: String = PFO.publicName

    /** The trait whose field the value defaults. */
    var targetTrait: String = ""

    /** The field of the target trait's data the value defaults. */
    var targetValuePath: String = ""

    /** Run order within the task's prefillData list; unset leaves it at the default. */
    var priority: Int? = null

    /** The usage's initialization data, ready for `function(...)`. */
    fun build(): Map<String, Any?> = buildMap {
        put(WFD.fn, PFO.fn)
        priority?.let { put(WFD.priority, it) }
        put(PFO.userAttribute, userAttribute)
        put(PFO.targetTrait, targetTrait)
        put(PFO.targetValuePath, targetValuePath)
    }
}

/** Builds [PFO.fn] initialization data with [PrefillFromOwnerBuilder]; see it. */
fun prefillFromOwner(build: PrefillFromOwnerBuilder.() -> Unit): Map<String, Any?> =
    PrefillFromOwnerBuilder().apply(build).build()
