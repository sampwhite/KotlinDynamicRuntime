package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.report.ReportDefSchema
import com.dynamicruntime.common.gedra.workflow.WfDefSchema
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.childPath
import com.dynamicruntime.common.schema.inputFailuresException
import com.dynamicruntime.common.schema.offContractKeyFailures
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.checkTemplateSyntax
import com.dynamicruntime.common.util.toOptStr

/*
 * The gate a client configuration **write** passes before it is reassembled (issues #1051, #1052).
 *
 * The endpoints that write a client's configuration are the outward-facing ones -- a client's own administrators
 * write through them -- so each slot entry a write brings is held to the shape `coreConfigTraits` declares for its
 * slot, and a fault is refused by name. The reassembly that follows reads leniently, as the load of a stored row
 * must: a missing field becomes an empty one, a flag that is not a boolean becomes false, a kind that is no kind is
 * dropped. That is how a typo in a write used to become a different configuration than the one written.
 */

/**
 * Each config slot's declared shape, compiled (issue #1052): the data type of `coreConfigTraits`' entry for the slot,
 * and the fields its entries are keyed by. The declarations are the storage vocabulary; this is what makes them
 * something a write is held to, rather than a description beside the code that reads the slots.
 *
 * Compiled beside the definition schemas the slots refer to -- the client's written form, the workflow's, the
 * report's -- as those are compiled on their own, so the gate needs no booted schema store.
 */
object ConfigSlotShapes {
    private class Compiled(val dataTypes: Map<String, SchType>, val primaryKeys: Map<String, List<String>>)

    // Parsed once and kept, as the definition schemas are: it is a constant of the runtime.
    private var compiled: Compiled? = null

    private fun compiled(cxt: KdrCxtBase): Compiled = compiled ?: run {
        val traits = coreConfigTraits(cxt)
        val referenced = parseSchemaTypes(WfDefSchema.defs(cxt) + ReportDefSchema.defs(cxt) + ClientDefSchema.defs(cxt))
        val types = parseSchemaTypes(traits.defs, existingTypes = referenced)
        Compiled(
            traits.configTraits.mapValues { (_, trait) -> types.getValue(trait.typeName).properties.getValue(GE.data).valueType },
            traits.configTraits.mapValues { it.value.primaryKey },
        ).also { compiled = it }
    }

    /** The shape of one entry's data in [slot], or null for a slot nothing declares. */
    fun dataType(cxt: KdrCxtBase, slot: String): SchType? = compiled(cxt).dataTypes[slot]

    /** The fields an entry of [slot] is keyed by; empty for a single-instance slot, and for one nothing declares. */
    fun primaryKey(cxt: KdrCxtBase, slot: String): List<String> = compiled(cxt).primaryKeys[slot].orEmpty()
}

/**
 * What is wrong with the slots of a configuration about to be **written**, each failure located by its slot, its
 * entry and the path within it (`kdr:clientDef.enabledEnvironments[0]`, `kdr:cfactDef[ready].group`) -- empty when
 * nothing is. An entry is named by its **key** rather than its place, so a failure reads the same wherever the
 * entry sits; one with no key is named by place, counted from 0 as an array's items are (`[#1]` is the second),
 * and its missing key is among its failures.
 *
 * **Only what the write changes is judged.** [stored] is the configuration's revision as it stands, when it has
 * one: an entry the write carries unchanged -- the same key, the same content -- is not judged again, and neither
 * is a key the stored revision already holds twice. So a fault a stored configuration already has does not refuse
 * an unrelated write to it, the rule the trial reload keeps for the same reason (issue #843), and a shape tightened
 * by a later release strands nobody: a write is refused for what it brings.
 *
 * **Every slot is held to its declared shape** ([ConfigSlotShapes]): its keys and no others, each of its type.
 * Beyond the shape:
 * - **The client definition** is single-instance and read by [readClientDef], which also asks the one rule its
 *   class keeps (issue #1051).
 * - **A usage rule's `display`** must be a template that parses: it is evaluated per row with its failures
 *   swallowed, so a malformed one blanks its column for every row and says nothing.
 * - **A type declared twice** (`kdr:schemaDef`) is refused: the second would silently replace the first. No other
 *   slot is held to one entry a key, because elsewhere a repeat is either legal -- two overlays of one fragment
 *   file or one UiBlock are two layers -- or refused already, by the reassembly or the trial, as what it is.
 *
 * **Two things are left to the trial reload that follows**, which judges them in the document they belong to and
 * by what the client already has:
 * - **A schema body** (`schema`, `dataSchema`) is held to its **shape** and is not parsed here
 *   ([SchOpts.schemaDocumentsUnparsed]): every keyword in it whose value is not of the keyword's shape, every
 *   keyword this layer refuses and every misspelled one of ours is a failure at its own path
 *   (`kdr:schemaDef[Tally].schema.properties.cost.type`), all of them at once (issue #1056,
 *   `SchMetaSchema.structureFailures`). What it means -- its references, what it narrows -- is the trial's:
 *   parsed on its own it would be refused for naming a sibling type of the same client, or for being an
 *   alteration of a global type.
 * - **A report's `definition`** is only required to be an object. One that does not read is kept as stored and
 *   reported (`GedraConfig.unreadReports`), so a configuration may already hold one; the trial refuses a new one.
 */
fun configSlotFailures(
    cxt: KdrCxtBase,
    entriesBySlot: Map<String, List<Map<String, Any?>>>,
    stored: Map<String, List<Map<String, Any?>>>? = null,
): List<SchFailure> = entriesBySlot.flatMap { (slot, entries) -> slotFailures(cxt, slot, entries, stored?.get(slot).orEmpty()) }

private fun slotFailures(
    cxt: KdrCxtBase,
    slot: String,
    entries: List<Map<String, Any?>>,
    stored: List<Map<String, Any?>>,
): List<SchFailure> {
    // A slot nothing declares is not this gate's: the bundle write refuses it by name, and a stored row holding one
    // is reported by the load.
    val type = ConfigSlotShapes.dataType(cxt, slot) ?: return emptyList()
    val pk = ConfigSlotShapes.primaryKey(cxt, slot)
    if (pk.isEmpty()) {
        // Single-instance: the reassembly reads the first entry and no other, so a second would be silently lost.
        if (entries.size > 1 && stored.size <= 1) {
            return listOf(SchFailure(slot, SchFailCode.badValue, "holds ${entries.size} entries; this slot holds one."))
        }
        val entry = entries.firstOrNull() ?: return emptyList()
        if (entry == stored.firstOrNull()) return emptyList()
        return entryFailures(cxt, slot, type, entry).map { it.under(slot) }
    }

    fun keyOf(entry: Map<String, Any?>): String? =
        pk.map { entry[it].toOptStr()?.ifEmpty { null } ?: return null }.joinToString(",")
    val storedByKey = stored.groupBy { keyOf(it) }
    val seen = HashSet<String>()
    val out = mutableListOf<SchFailure>()
    entries.forEachIndexed { i, entry ->
        val key = keyOf(entry)
        val at = "$slot[${key ?: "#$i"}]"
        val held = if (key == null) emptyList() else storedByKey[key].orEmpty()
        // A second type under one name: the reassembly would keep the later and say nothing of the earlier. Only
        // there -- see the function's note -- and not for a name the stored revision already holds twice.
        if (slot == CCT.schemaDef && key != null && !seen.add(key) && held.size <= 1) {
            out.add(SchFailure(at, SchFailCode.badValue, "is the second type named '$key'; it would silently replace the first."))
        }
        if (entry in held) return@forEachIndexed
        out.addAll(entryFailures(cxt, slot, type, entry).map { it.under(at) })
    }
    return out
}

/** This failure as seen from [parent]: its path beneath it, or [parent] itself for a failure of the whole entry. */
private fun SchFailure.under(parent: String): SchFailure = copy(path = if (path.isEmpty()) parent else childPath(parent, path))

/** What is wrong with one [entry] of [slot], by path within it. */
private fun entryFailures(cxt: KdrCxtBase, slot: String, type: SchType, entry: Map<String, Any?>): List<SchFailure> {
    if (slot == CCT.clientDef) return readClientDef(cxt, entry).failures
    val shape = validate(type, entry, unparsedBodies).filterNot { leftToTheTrial(slot, it) }
    val beyond = if (slot == CCT.usageDef) displayFailures(entry[CCT.display]) else emptyList()
    // A key a request may carry off-contract is not one a stored entry may: it would be accepted and then gone.
    return shape + offContractKeyFailures(entry) + beyond
}

/** How the gate validates an entry: a schema body is taken as the object it is, its parse being the trial's. */
private val unparsedBodies = SchOpts(schemaDocumentsUnparsed = true)

/**
 * Whether [failure] is what the gate leaves to the trial reload of a report (see [configSlotFailures]): anything
 * *inside* its definition. That the definition is there, and is an object, stays the gate's.
 */
private fun leftToTheTrial(slot: String, failure: SchFailure): Boolean =
    slot == CCT.reportDef && (failure.path.startsWith("${CCT.definition}.") || failure.path.startsWith("${CCT.definition}["))

/** What is wrong with a usage rule's `display` as a template -- one failure per defect, so they are fixed in one pass. */
private fun displayFailures(display: Any?): List<SchFailure> = (display as? String)?.checkTemplateSyntax().orEmpty().map {
    SchFailure(CCT.display, SchFailCode.badValue, "This is not a template that parses: ${it.message}")
}

/** How many failures a refusal's message names; all of them are in its structured `failures`. */
private const val failuresNamedInMessage = 10

/**
 * Throws the write's refusal -- a 400 carrying [configSlotFailures] -- when the slots of configuration [name] have
 * any that [stored], its revision as it stands, does not already have.
 */
fun requireWritableSlots(
    cxt: KdrCxtBase,
    name: String,
    entriesBySlot: Map<String, List<Map<String, Any?>>>,
    stored: Map<String, List<Map<String, Any?>>>? = null,
) {
    val failures = configSlotFailures(cxt, entriesBySlot, stored)
    if (failures.isEmpty()) return
    // Each message is a sentence of its own, so they are set side by side rather than joined by punctuation. The
    // message names the first few and how many more there are: every one of them travels structured, and a
    // write of several hundred malformed entries should not make a message of several hundred sentences.
    val named = failures.take(failuresNamedInMessage).joinToString(" ") { "${it.path}: ${it.message}" }
    val more = (failures.size - failuresNamedInMessage).takeIf { it > 0 }?.let { " And $it more." }.orEmpty()
    throw inputFailuresException("Configuration '$name' cannot be written: $named$more", failures)
}
