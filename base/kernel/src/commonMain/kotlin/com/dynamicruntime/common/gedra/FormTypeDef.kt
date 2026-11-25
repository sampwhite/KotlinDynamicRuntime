package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/**
 * The names a form type is made of (issue #1902): the fields of a `kdr:formTypeDef` entry. In the kernel so the frontend
 * reads a type by the names the backend writes. Each name matches its value.
 */
@Suppress("ConstPropertyName")
object FTYPE {
    /** The type's id within its client: what a form's [GT.formType] entry holds. */
    const val formTypeId = "formTypeId"

    /** What the type is called, in the choices of a form's type. */
    const val label = "label"

    /** What a form of the type is, in the administrator's words. */
    const val description = "description"

    /** The traits a form of the type collects, each as a task names one. */
    const val traits = "traits"

    /** Who may set a form to the type: a cfact expression over the facts of whoever is writing. Absent, whoever may write the form. */
    const val setWhen = "setWhen"

    /** Whether a form of the type may be changed to another, or lose its type. */
    const val mayLeave = "mayLeave"

    /** What a form type's id looks like. */
    const val idPattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$"
    val idRegex: Regex = Regex(idPattern)

    /** [idPattern] in words, for a refusal. */
    const val idRule = "letters, digits, and underscores, starting with a letter, at most 64 characters"

    /** The sourced choice list of a form's type: the client's form types. */
    const val optionsSource = "kdr:formTypes"
}

/**
 * One form type a configuration declares (issue #1902), as its `kdr:formTypeDef` entry holds it: what forms of one kind
 * are called, the [traits] they collect, who may set a form to it ([setWhen]), and whether a form may leave it
 * ([mayLeave]).
 *
 * **It selects traits, and not strictly.** Every trait stays valid on any form whatever its type, so a form may hold a
 * trait its type does not list, or lack one it does: a type changes no schema, and what it requires is a task's to ask
 * for. [traits] may be empty, for a type that only classifies.
 *
 * [configName] and [stored] are a stamp, not content: which configuration declared it and whether it was read from the
 * database. [extra] is whatever else a stored entry held -- a later release's fields -- written back as it was.
 */
class FormTypeDef(
    val formTypeId: String,
    val label: String,
    val description: String? = null,
    val traits: List<WfTraitRef> = emptyList(),
    val setWhen: String? = null,
    val mayLeave: Boolean = false,
    val configName: String? = null,
    val stored: Boolean = false,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** This type as its stored entry: what was authored and what was carried, and none of the stamp. */
    fun toEntry(): Map<String, Any?> = buildMap {
        put(FTYPE.formTypeId, formTypeId)
        put(FTYPE.label, label)
        description?.let { put(FTYPE.description, it) }
        if (traits.isNotEmpty()) {
            put(FTYPE.traits, traits.map { linkedMapOf(WFD.traitId to it.traitId, WFD.required to it.required) })
        }
        setWhen?.let { put(FTYPE.setWhen, it) }
        if (mayLeave) put(FTYPE.mayLeave, true)
        putAll(extra)
    }

    /** This type with only [kept] of its traits. */
    fun withTraits(kept: List<WfTraitRef>): FormTypeDef =
        FormTypeDef(formTypeId, label, description, kept, setWhen, mayLeave, configName, stored, extra)

    companion object {
        /**
         * The type [entry] declares. **Forgiving**, as a counter's is: an entry with a bad field is still a type -- one
         * the load reports -- since refusing it here would cost its whole configuration.
         */
        fun fromEntry(entry: Map<String, Any?>, configName: String?, stored: Boolean): FormTypeDef = FormTypeDef(
            formTypeId = entry[FTYPE.formTypeId].toOptStr().orEmpty(),
            label = entry[FTYPE.label].toOptStr().orEmpty(),
            description = entry[FTYPE.description].toOptStr(),
            traits = entry[FTYPE.traits].toJsonListOfMaps()
                .map { WfTraitRef(it[WFD.traitId].toOptStr().orEmpty(), it[WFD.required] as? Boolean ?: true) },
            setWhen = entry[FTYPE.setWhen].toOptStr(),
            mayLeave = entry[FTYPE.mayLeave] == true,
            configName = configName,
            stored = stored,
            extra = entry.filterKeys { it !in readFields },
        )

        /** The fields of an entry this release reads; anything else is [extra]. */
        private val readFields =
            setOf(FTYPE.formTypeId, FTYPE.label, FTYPE.description, FTYPE.traits, FTYPE.setWhen, FTYPE.mayLeave)
    }
}

/** Declares a form type's traits, in the order a form collects them; see [GedraConfigBuilder.formType]. */
class FormTypeBuilder {
    internal val traits: MutableList<WfTraitRef> = mutableListOf()

    /** A trait a form of the type collects, and whether an entry of it is [required]. */
    fun trait(traitId: String, required: Boolean = true) {
        traits.add(WfTraitRef(traitId, required))
    }
}
