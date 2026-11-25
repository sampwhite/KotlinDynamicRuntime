package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.cfact.analyzeCFactOrAlways
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.rowFactProblem
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.SchemaTrial
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's form types (issue #1902): the kinds of form it has, and who may set a form to each.
 *
 * **A registry per client**, built from the configurations the client holds as its counters are (`CounterService`): at
 * boot, again on each reload of the client, and over a write's scratch configurations in its trial, so a type that is no
 * good is refused as a write and dropped, with an issue, from a load. A stored type stands over the code's of the same
 * id. A sandbox holds its parent's, copied with its configuration.
 *
 * **What a load forgives costs the least it can.** A trait the client does not have is left out of the type's list. A
 * `setWhen` that does not parse keeps the type and lets nobody set it: dropping the rule would open the type to anyone,
 * and dropping the type would let its forms be changed out of it.
 *
 * What a form may be set to is [FormTypeHook]'s to hold.
 *
 * Theme: #CFGFG.
 */
class FormTypeService : ServiceInitializer {
    override val serviceName: String = FormTypeService.serviceName

    @Volatile
    private var registries: Map<String, Map<String, FormTypeDef>> = emptyMap()

    /** What a client's types are judged by: the traits its forms may hold, and the facts its expressions may name. */
    private class Scope(val traitIds: Set<String>, val cfactNames: Set<String>)

    override fun checkInit(cxt: KdrCxt) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.checkInit ran with no schema collector.")
        val configs = collector.gedraConfigs
        registries = configs.configs.filter { it.formTypes.isNotEmpty() }.map { it.gedraId.client }.distinct()
            .associateWith { build(cxt, configs, it, scopeOf(cxt, it)) }.filterValues { it.isNotEmpty() }
    }

    /**
     * Rebuilds [client]'s form types off the current collector and swaps them in: a step of the client's reload, after
     * its schema's.
     *
     * Themes: #CFGFG, #RELSY.
     */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val rebuilt = build(cxt, collector.gedraConfigs, client, scopeOf(cxt, client))
        registries = if (rebuilt.isEmpty()) registries - client else registries + (client to rebuilt)
    }

    /**
     * [client]'s candidate form types judged over a trial's [scratch] collector: problems to the trial's capture, nothing
     * swapped in. [def] is the client's candidate definition when it would be present, and [schema] its candidate schema.
     */
    fun trialClient(cxt: KdrCxt, scratch: SchemaCollector, client: String, def: ClientDef?, schema: SchemaTrial) {
        val scope = def?.let {
            val overlaid = scratch.clientOverlays[client]?.keys ?: emptySet()
            Scope(formTraitIds(supportedTraits(scratch.gedraConfigs, client, it, overlaid, schema.droppedTypes)), schema.cfactNames)
        }
        build(cxt, scratch.gedraConfigs, client, scope)
    }

    /** [client]'s form types, by id in the order declared; none for a client that has none. */
    fun forClient(client: String): Map<String, FormTypeDef> = registries[client].orEmpty()

    /** Whether whoever [cxt] acts as may set a form of [client] to [type]. A rule that no longer parses is met by nobody. */
    fun maySet(cxt: KdrCxt, client: String, type: FormTypeDef): Boolean {
        if (type.setWhen == null) return true
        val registry = SchemaService.get(cxt).cfactsFor(client)
        return isMet(type, registry, registry.assemble(cxt))
    }

    private fun isMet(type: FormTypeDef, registry: CFactRegistry, facts: Set<String>): Boolean =
        type.setWhen == null || registry.analyze(type.setWhen, evaluateWith = facts).value ?: false

    /**
     * Refuses a form of [client] going from the type [old] (null for none) to [new], written by whoever [cxt] acts as:
     * [FormTypeHook]'s rule, and what a write of several forms asks of each before it stores any.
     */
    fun requireAllowed(cxt: KdrCxt, client: String, old: String?, new: String?) {
        if (old == new) return
        val types = forClient(client)
        types[old]?.takeIf { !it.mayLeave }?.let {
            throw KdrException.mkInput("This form's type, '${it.label}', was settled when it was stored, and does not change.")
        }
        if (new == null) return
        val type = types[new] ?: throw KdrException.mkInput("The client '$client' has no form type '$new'.")
        if (!maySet(cxt, client, type)) {
            throw KdrException("You may not make this form's type '${type.label}'.", code = EXC.notAuthorized)
        }
    }

    /** The choices of a form's type: every type of [client], so a type's label is known wherever a form is shown. */
    fun choices(client: String): List<SchOption> = forClient(client).values.map { SchOption(it.formTypeId, it.label) }

    /**
     * The choices of the type of a form whose type is [current]: those [cxt]'s caller may set, and the form's own -- which
     * is the only one when the form may not leave it, and is offered by its id when it is no longer defined, so that a
     * page does not refuse the value its form already holds.
     */
    fun choicesFor(cxt: KdrCxt, client: String, current: String?): List<SchOption> {
        val types = forClient(client)
        val fixed = types[current]?.takeIf { !it.mayLeave }
        val registry = SchemaService.get(cxt).cfactsFor(client)
        // The caller's facts once, for every type's rule.
        val facts by lazy { registry.assemble(cxt) }
        val undefined = current?.takeIf { it !in types }
        return buildList {
            undefined?.let { add(SchOption(it, it)) }
            for (type in types.values) {
                val offered = type.formTypeId == current || (fixed == null && (type.setWhen == null || isMet(type, registry, facts)))
                if (offered) add(SchOption(type.formTypeId, type.label))
            }
        }
    }

    /** What [client]'s types are judged by on the running node, or null for a client not present here. */
    private fun scopeOf(cxt: KdrCxt, client: String): Scope? {
        val def = ClientService.get(cxt).present(client) ?: return null
        val schema = SchemaService.get(cxt)
        return Scope(formTraitIds(schema.supportedGedraTraitsFor(client, def)), schema.cfactsFor(client).names)
    }

    private fun formTraitIds(traits: List<GedraTrait>): Set<String> =
        traits.filter { GedraDataType.formDoc in it.appliesTo }.map { it.traitId }.toSet()

    /**
     * [client]'s form types, judged as a load judges them, the first declaration of an id kept -- a stored one before the
     * code's. With no [scope] (a client not present), what a type names is not judged.
     */
    private fun build(cxt: KdrCxt, configs: GedraConfigCollector, client: String, scope: Scope?): Map<String, FormTypeDef> {
        val owner = configOwnerOf(client)
        val issues = mutableListOf<GedraConfigIssue>()
        val kept = LinkedHashMap<String, FormTypeDef>()
        fun report(config: GedraConfig, def: FormTypeDef, problem: String, degradedTo: String) =
            reportConfigProblem(cxt, config.issue(problem, degradedTo, GCEL.formType, def.formTypeId), issues)

        // In an order of their own, as counters are: a load and a trial read a client's configurations in different
        // orders, and which declaration of an id is kept must come out the same in both.
        val holding = configs.configs.filter { it.gedraId.client == client && it.formTypes.isNotEmpty() }
            .sortedWith(compareBy({ config -> if (config.formTypes.any { it.stored }) 0 else 1 }, { it.gedraId.fullId }))
        for (config in holding) {
            for (def in config.formTypes) {
                val holder = kept[def.formTypeId]
                if (holder != null && holder.stored && !def.stored) continue
                val problem = defProblem(config, def, owner, holder != null)
                if (problem != null) {
                    report(config, def, problem, "Leaving the form type out; the rest of the configuration loads.")
                    continue
                }
                val named = "Form type '${def.formTypeId}' of client '$owner' (config '${config.name}')"
                setWhenProblem(def, scope)?.let {
                    report(
                        config, def, "$named has a setWhen that $it",
                        "Keeping the form type; nobody may set a form to it until the rule is fixed.",
                    )
                }
                kept[def.formTypeId] = def.withTraits(
                    def.traits.filterIndexed { i, ref ->
                        val fault = traitProblem(def, i, ref, scope) ?: return@filterIndexed true
                        report(config, def, "$named $fault", "Leaving the trait out of the form type's list.")
                        false
                    },
                )
            }
        }
        return kept
    }

    /** What stops [def], declared by [config], from being a form type, or null; [taken] is whether its id is already kept. */
    private fun defProblem(config: GedraConfig, def: FormTypeDef, owner: String, taken: Boolean): String? {
        if (!FTYPE.idRegex.matches(def.formTypeId)) {
            return "Config '${config.name}' of client '$owner' declares a form type whose id '${def.formTypeId}' is not one: " +
                "${FTYPE.idRule}."
        }
        val named = "Form type '${def.formTypeId}' of client '$owner' (config '${config.name}')"
        return when {
            config.gedraId.client == GID.globalClient ->
                "$named is declared by a global configuration. A form type is a client's own."
            def.label.isBlank() -> "$named has no label."
            taken -> "$named is declared more than once among the client's configurations. The first is kept."
            else -> null
        }
    }

    /** What is wrong with [def]'s `setWhen`, as the rest of a sentence, or null. */
    private fun setWhenProblem(def: FormTypeDef, scope: Scope?): String? {
        val rule = def.setWhen ?: return null
        val unparsed = scope?.let { analyzeCFactOrAlways(rule, it.cfactNames).problems.firstOrNull() }
        return unparsed?.let { "does not parse: ${it.message}" } ?: rowFactProblem(rule)
    }

    /** What is wrong with [ref], the [index]th trait [def] lists, as the rest of a sentence about the type, or null. */
    private fun traitProblem(def: FormTypeDef, index: Int, ref: WfTraitRef, scope: Scope?): String? = when {
        def.traits.indexOfFirst { it.traitId == ref.traitId } < index -> "lists the trait '${ref.traitId}' twice."
        ref.traitId == GT.formType -> "lists the trait '${ref.traitId}', which is where a form says its type."
        scope != null && ref.traitId !in scope.traitIds ->
            "lists the trait '${ref.traitId}', which the client's forms cannot hold."
        else -> null
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "FormTypeService"

        fun get(cxt: KdrCxt): FormTypeService =
            getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.")

        /** The service, or null on a node that keeps no gedras (an edge). */
        fun getOrNull(cxt: KdrCxt): FormTypeService? = cxt.instanceConfig.get(serviceName) as? FormTypeService
    }
}

/**
 * The choices of a form's type (issue #1902): each of the client's form types. Which a caller may set is [FormTypeHook]'s
 * to hold, and a page drawing a form narrows the list to those (`resolveWorkflowView`).
 *
 * A module of its own, registered on every kind of node: the trait naming this source is a core trait, which a node with
 * no forms (an edge) has too, where the list is empty.
 */
fun formTypeSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.formType") {
    // `c.client` is the client whose schema is served, which an administrator of every client may name.
    optionsProvider(FTYPE.optionsSource) { c, _ -> FormTypeService.getOrNull(c)?.choices(c.client).orEmpty() }
}

/** The form type [entries] say the form is of, or null when they say none (issue #1902). */
fun formTypeOf(entries: List<Map<String, Any?>>): String? =
    entries.firstOrNull { it[GE.traitId].toOptStr() == GT.formType }?.get(GE.data).toJsonMapOrEmpty()[GT.formTypeField].toOptStr()

/**
 * Holds a form's type to its client's form types (issue #1902), on every write of a form's data -- a create, a patch, an
 * import -- inside the write's transaction, so a refusal rolls the whole write back. A sourced choice list takes no part
 * in validation, so this is what makes the choices bind. (An import of several forms also asks before it stores any,
 * since each is a transaction of its own.)
 *
 * - **Unchanged** from what was stored: not judged, even when the type is no longer defined, since removing a type from
 *   the configuration must not make its forms unwritable.
 * - **Changed or removed**: refused when the type it leaves is fixed ([FormTypeDef.mayLeave]). One no longer defined may
 *   be left.
 * - **Set or changed to a type**: refused when the client defines no such type, or the writer does not meet its
 *   [FormTypeDef.setWhen].
 *
 * The traits the form holds are not touched, whatever its type becomes.
 */
object FormTypeHook : GedraWriteHook {
    override fun afterWrite(cxt: KdrCxt, write: GedraWriteContext) {
        val old = formTypeOf(write.priorEntries)
        val new = formTypeOf(write.row.entries)
        if (old != new) FormTypeService.get(cxt).requireAllowed(cxt, write.row.client, old, new)
    }
}
