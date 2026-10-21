package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.isVariableName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's own trait as the traits editor sees it (issue #1118): a name, a description, and fields of a fixed set
 * of kinds -- what a person designs, rather than the schema it becomes. In the kernel because both sides run it: the
 * editor draws its preview from [TraitDesign.toDataSchema], and the backend writes exactly that.
 *
 * ### Keys never change
 *
 * A trait's id, a field's key and a choice's value are generated from the first label they are saved with
 * ([withKeys]) and never change after; labels change freely. There is no "rename key" -- that would be removing a
 * field and adding another, and the impact report shows the data left behind. So a design names its fields by key,
 * and a field with no key yet is a new one.
 *
 * ### What a design owns, and what it leaves
 *
 * A design owns each field's type, choices, description and whether the data requires it, and the trait's heading
 * and each field's label in the layout. Everything else in the stored schema -- a field's other keywords, a layout's
 * hints, errors, and form-level strings, which Design View writes -- is carried over by [TraitDesign.toDataSchema],
 * so the two editors do not undo each other. A field removed takes its layout entry with it.
 *
 * A trait whose fields are not all of the editor's kinds -- a nested object, a `$ref`, a date and time, an open
 * list -- is not editable here ([traitDesignOf] says why); it was written by hand, and is edited as it was written.
 */

/** The kinds of field the traits editor offers (issue #1118). Nested objects and arrays wait. */
@Suppress("EnumEntryName")
enum class TraitFieldKind {
    /** One line of text. */
    text,

    /** Text over several lines: a plain string the layout marks `multiline`. */
    longText,

    /** Any number. */
    number,

    /** A whole number. */
    integer,

    /** Yes or no. */
    yesNo,

    /** A day, with no time. */
    date,

    /** One of a closed list of choices. */
    choice,

    /** Any of a closed list of choices. */
    choices,
    ;

    /** Whether a field of this kind has a list of choices. */
    val hasChoices: Boolean get() = this == choice || this == choices

    /** Whether a field may move between this kind and [other] once saved: only between the two kinds of text. */
    fun keepsDataAs(other: TraitFieldKind): Boolean = this == other || (isText && other.isText)

    private val isText: Boolean get() = this == text || this == longText
}

/** One choice of a choice field: its [value], generated from its first label and fixed after; its [label]. */
class TraitChoiceDesign(val value: String?, val label: String)

/** One field of a [TraitDesign]: its [key], generated from its first label and fixed after; null for a new one. */
class TraitFieldDesign(
    val key: String?,
    val label: String,
    val kind: TraitFieldKind,
    val description: String? = null,
    /** Whether the data itself requires it -- every entry of the trait, whoever writes it. Off unless asked for. */
    val required: Boolean = false,
    val choices: List<TraitChoiceDesign> = emptyList(),
)

/** A client's own trait as the editor designs it; see the file note. [traitId] is null for a trait not yet saved. */
class TraitDesign(
    val traitId: String?,
    val label: String,
    val description: String?,
    val fields: List<TraitFieldDesign>,
) {
    /** The design on the wire, as [traitDesignFromInfo] reads it. */
    fun toInfo(): Map<String, Any?> = buildMap {
        traitId?.let { put(CIT.traitId, it) }
        put(CIT.label, label)
        description?.let { put(CIT.description, it) }
        put(CIT.fields, fields.map { f ->
            buildMap {
                f.key?.let { put(CIT.key, it) }
                put(CIT.label, f.label)
                put(CIT.kind, f.kind.name)
                f.description?.let { put(CIT.description, it) }
                if (f.required) put(CIT.required, true)
                if (f.kind.hasChoices) {
                    put(CIT.choices, f.choices.map { c -> buildMap { c.value?.let { put(CIT.value, it) }; put(CIT.label, c.label) } })
                }
            }
        })
    }

    /**
     * The trait's data schema: [stored] -- the schema as it stands, null for a new trait -- with what the design owns
     * written over it and everything else carried (see the file note). Every field must have its key ([withKeys]).
     */
    fun toDataSchema(stored: Map<String, Any?>? = null): Map<String, Any?> {
        val storedProps = stored?.get(SCH.properties).toJsonMapOrEmpty()
        val props = LinkedHashMap<String, Any?>()
        for (f in fields) {
            val key = f.key ?: throw IllegalStateException("Field '${f.label}' has no key; give the design its keys first.")
            val prop = LinkedHashMap(storedProps[key].toJsonMapOrEmpty())
            prop.keys.removeAll(designOwnedKeys)
            when (f.kind) {
                TraitFieldKind.text, TraitFieldKind.longText -> prop[SCH.type] = SCT.string
                TraitFieldKind.number -> prop[SCH.type] = SCT.number
                TraitFieldKind.integer -> prop[SCH.type] = SCT.integer
                TraitFieldKind.yesNo -> prop[SCH.type] = SCT.boolean
                TraitFieldKind.date -> {
                    prop[SCH.type] = SCT.string
                    prop[SCH.format] = SFMT.date
                }
                TraitFieldKind.choice -> {
                    prop[SCH.type] = SCT.string
                    prop[SCH.options] = optionsOf(f)
                }
                TraitFieldKind.choices -> {
                    prop[SCH.type] = SCT.array
                    prop[SCH.uniqueItems] = true
                    prop[SCH.items] = mapOf(SCH.type to SCT.string, SCH.options to optionsOf(f))
                }
            }
            f.description?.takeIf { it.isNotBlank() }?.let { prop[SCH.description] = it }
            props[key] = prop
        }
        val out = LinkedHashMap(stored.orEmpty())
        out[SCH.type] = SCT.kObject
        out[SCH.properties] = props
        val required = fields.filter { it.required }.mapNotNull { it.key }
        if (required.isEmpty()) out.remove(SCH.required) else out[SCH.required] = required
        out[SCH.layout] = layoutOver(stored?.get(SCH.layout).toJsonMapOrEmpty())
        return out
    }

    /** The `g-layout` block: [stored] with the heading, and each field's label and long-text mark, written over. */
    private fun layoutOver(stored: Map<String, Any?>): Map<String, Any?> {
        val storedEntries = stored[SL.schemaFields].toJsonListOfMaps().associateBy { it[SL.field].toOptStr() }
        val entries = fields.map { f ->
            val entry = LinkedHashMap(storedEntries[f.key].orEmpty())
            entry[SL.field] = f.key
            entry[SL.label] = f.label
            if (f.kind == TraitFieldKind.longText) entry[SL.multiline] = true else entry.remove(SL.multiline)
            // A form's restated choices may name only choices there are.
            if (f.kind.hasChoices) {
                val values = f.choices.mapNotNull { it.value }.toSet()
                entry[SL.choices]?.toJsonListOfMaps()?.filter { it[SL.value].toOptStr() in values }?.let {
                    if (it.isEmpty()) entry.remove(SL.choices) else entry[SL.choices] = it
                }
            } else {
                entry.remove(SL.choices)
            }
            entry
        }
        val out = LinkedHashMap(stored)
        out[SL.label] = label
        out[SL.schemaFields] = entries
        return out
    }

    private fun optionsOf(f: TraitFieldDesign): List<Map<String, Any?>> =
        f.choices.map { mapOf(SCH.value to it.value, SCH.label to it.label) }

    private companion object {
        /** The property keywords a design writes, so a kind's leftovers do not survive a change of kind. */
        val designOwnedKeys = setOf(
            SCH.type, SCH.format, SCH.options, SCH.items, SCH.uniqueItems, SCH.description, SCH.title,
        )
    }
}

/** A design read off the wire ([TraitDesign.toInfo]); refuses an unknown kind. */
fun traitDesignFromInfo(raw: Map<String, Any?>): TraitDesign = TraitDesign(
    traitId = raw[CIT.traitId].toOptStr()?.trim()?.ifEmpty { null },
    label = raw[CIT.label].toOptStr()?.trim().orEmpty(),
    description = raw[CIT.description].toOptStr()?.trim()?.ifEmpty { null },
    fields = raw[CIT.fields].toJsonListOfMaps().map { f ->
        val kindName = f[CIT.kind].toOptStr()
        TraitFieldDesign(
            key = f[CIT.key].toOptStr()?.trim()?.ifEmpty { null },
            label = f[CIT.label].toOptStr()?.trim().orEmpty(),
            kind = TraitFieldKind.entries.firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException("'$kindName' is not a kind of field; the kinds are ${TraitFieldKind.entries.joinToString(", ")}."),
            description = f[CIT.description].toOptStr()?.trim()?.ifEmpty { null },
            required = f[CIT.required] == true,
            choices = f[CIT.choices].toJsonListOfMaps().map { c ->
                TraitChoiceDesign(c[CIT.value].toOptStr()?.trim()?.ifEmpty { null }, c[CIT.label].toOptStr()?.trim().orEmpty())
            },
        )
    },
)

/**
 * A key made from [label]: its letters and digits as one camel-case word (`Do you smoke?` -> `doYouSmoke`), at most
 * [maxKeyLength] long, [fallback] when the label has none or would start with a digit -- and, when [taken] holds it,
 * the first of `<key>2`, `<key>3`, ... that it does not. A trait id, a field key, and a choice value are all made
 * this way, once.
 */
fun keyFromLabel(label: String, taken: Set<String>, fallback: String): String {
    val words = label.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    var base = words.mapIndexed { i, w ->
        val lower = w.lowercase()
        if (i == 0) lower else lower.replaceFirstChar { it.uppercase() }
    }.joinToString("").take(maxKeyLength)
    if (base.isEmpty()) base = fallback
    if (base.first().isDigit()) base = fallback + base.replaceFirstChar { it.uppercase() }
    if (base !in taken) return base
    var n = 2
    while ("$base$n" in taken) n++
    return "$base$n"
}

/** The longest key [keyFromLabel] makes, before any number it adds. */
const val maxKeyLength = 40

/**
 * [design] with a key for everything that has none yet (see the file note): the trait's id, unique against
 * [takenTraitIds] -- the client's other traits -- each new field's key, unique within the trait, and each new choice's
 * value, unique within its field. What has a key keeps it.
 */
fun withKeys(design: TraitDesign, takenTraitIds: Set<String>): TraitDesign {
    val traitId = design.traitId ?: keyFromLabel(design.label, takenTraitIds, "trait")
    val keys = design.fields.mapNotNull { it.key }.toMutableSet()
    val fields = design.fields.map { f ->
        val key = f.key ?: keyFromLabel(f.label, keys, "field").also { keys.add(it) }
        val values = f.choices.mapNotNull { it.value }.toMutableSet()
        val choices = f.choices.map { c ->
            if (c.value != null) c else TraitChoiceDesign(keyFromLabel(c.label, values, "choice").also { values.add(it) }, c.label)
        }
        TraitFieldDesign(key, f.label, f.kind, f.description, f.required, choices)
    }
    return TraitDesign(traitId, design.label, design.description, fields)
}

/**
 * What is wrong with [design], in words, or nothing: a name, at least one field, a label on every field and choice,
 * keys well formed and unique, choices for a choice field, and none for another. Against [before] -- the design as
 * stored, null for a new trait -- a kept field keeps its kind (text and long text excepted: they hold the same data)
 * and the trait keeps its id. Asked of a design that has its keys ([withKeys]).
 */
fun traitDesignProblems(design: TraitDesign, before: TraitDesign? = null): List<String> {
    val out = mutableListOf<String>()
    if (design.label.isBlank()) out.add("The trait needs a name.")
    if (design.fields.isEmpty()) out.add("The trait needs at least one field.")
    design.traitId?.let { if (!it.isVariableName()) out.add("'$it' is not a trait id: letters, digits, and underscores, starting with a letter.") }
    if (before != null && design.traitId != before.traitId) out.add("A trait's id never changes; this one is '${before.traitId}'.")
    val seen = HashSet<String>()
    val beforeKinds = before?.fields?.associate { it.key to it.kind }.orEmpty()
    for (f in design.fields) {
        val name = f.label.ifBlank { f.key ?: "a new field" }
        if (f.label.isBlank()) out.add("Every field needs a label.")
        val key = f.key
        if (key != null) {
            if (!key.isVariableName()) out.add("'$key' is not a field key: letters, digits, and underscores, starting with a letter.")
            if (!seen.add(key)) out.add("The trait has two fields '$key'.")
            beforeKinds[key]?.let { was ->
                if (!was.keepsDataAs(f.kind)) {
                    out.add("Field '$name' is ${kindText(was)}, and a field's kind never changes; remove it and add another.")
                }
            }
        }
        if (f.kind.hasChoices) {
            if (f.choices.isEmpty()) out.add("Field '$name' needs at least one choice.")
            if (f.choices.any { it.label.isBlank() }) out.add("Every choice of field '$name' needs a label.")
            val values = f.choices.mapNotNull { it.value }
            if (values.size != values.toSet().size) out.add("Field '$name' has a choice twice.")
        } else if (f.choices.isNotEmpty()) {
            out.add("Field '$name' is ${kindText(f.kind)}, which has no choices.")
        }
    }
    return out.distinct()
}

/** A kind of field in words: "text", "long text", "a number", ... */
fun kindText(kind: TraitFieldKind): String = when (kind) {
    TraitFieldKind.text -> "text"
    TraitFieldKind.longText -> "long text"
    TraitFieldKind.number -> "a number"
    TraitFieldKind.integer -> "a whole number"
    TraitFieldKind.yesNo -> "yes or no"
    TraitFieldKind.date -> "a date"
    TraitFieldKind.choice -> "a choice"
    TraitFieldKind.choices -> "several choices"
}

/** What reading a stored trait as a design gave: the [design], or why it is not one the editor can edit. */
class TraitDesignRead(val design: TraitDesign?, val notEditable: String?)

/**
 * The stored trait [traitId] -- its [description] and its data schema [dataSchema], inline -- as a design, or why
 * the editor cannot edit it: a field not of its kinds (see the file note). Labels come from the layout, else the
 * schema's `title`, else the key.
 */
fun traitDesignOf(traitId: String, description: String?, dataSchema: Map<String, Any?>): TraitDesignRead {
    fun no(why: String) = TraitDesignRead(null, why)
    if (dataSchema.containsKey(SCH.dRef)) return no("Its data is a shared type, which is edited where it is declared.")
    if (dataSchema[SCH.type] != SCT.kObject) return no("Its data is not an object of fields.")
    val layout = dataSchema[SCH.layout].toJsonMapOrEmpty()
    val entries = layout[SL.schemaFields].toJsonListOfMaps().associateBy { it[SL.field].toOptStr() }
    val required = dataSchema[SCH.required].toJsonListOfStrings().toSet()
    val fields = mutableListOf<TraitFieldDesign>()
    for ((key, raw) in dataSchema[SCH.properties].toJsonMapOrEmpty()) {
        val prop = raw.toJsonMapOrEmpty()
        val entry = entries[key].orEmpty()
        val kind = fieldKindOf(prop, multiline = entry[SL.multiline] == true)
            ?: return no("Its field '$key' is not one of the kinds this editor offers.")
        val choices = when (kind) {
            TraitFieldKind.choice -> prop[SCH.options]
            TraitFieldKind.choices -> prop[SCH.items].toJsonMapOrEmpty()[SCH.options]
            else -> null
        }.toJsonListOfMaps().map { TraitChoiceDesign(it[SCH.value].toOptStr(), it[SCH.label].toOptStr() ?: it[SCH.value].toOptStr().orEmpty()) }
        fields.add(
            TraitFieldDesign(
                key = key,
                label = entry[SL.label].toOptStr() ?: prop[SCH.title].toOptStr() ?: humanizeFieldName(key),
                kind = kind,
                description = prop[SCH.description].toOptStr(),
                required = key in required,
                choices = choices,
            ),
        )
    }
    val label = layout[SL.label].toOptStr() ?: humanizeFieldName(traitId)
    return TraitDesignRead(TraitDesign(traitId, label, description, fields), null)
}

/** The kind of the stored field [prop], or null when it is none of the editor's. */
private fun fieldKindOf(prop: Map<String, Any?>, multiline: Boolean): TraitFieldKind? {
    if (prop.containsKey(SCH.dRef) || prop[SCH.openOptions] == true) return null
    return when (prop[SCH.type]) {
        SCT.string -> when {
            prop.containsKey(SCH.options) -> TraitFieldKind.choice
            prop[SCH.format] == SFMT.date -> TraitFieldKind.date
            prop.containsKey(SCH.format) -> null
            multiline -> TraitFieldKind.longText
            else -> TraitFieldKind.text
        }
        SCT.number -> TraitFieldKind.number
        SCT.integer -> TraitFieldKind.integer
        SCT.boolean -> TraitFieldKind.yesNo
        SCT.array -> {
            val items = prop[SCH.items].toJsonMapOrEmpty()
            if (items[SCH.type] == SCT.string && items.containsKey(SCH.options) && items[SCH.openOptions] != true) {
                TraitFieldKind.choices
            } else {
                null
            }
        }
        else -> null
    }
}
