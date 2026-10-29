package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes

/**
 * The **client configuration reference** (issue #1476): what a client's configuration is made of, slot by slot, and
 * what a write or a patch of it must send -- the rules a schema cannot state, such as what a delete carries, beside the
 * fields each slot's entries hold. Served as a Markdown document on the Documents page, and linked from the config
 * write and patch endpoints and from the bundle editor.
 *
 * Made from the slot shapes the write gate is held to ([ConfigSlotShapes.served], from `coreConfigTraits`), so a field
 * added to a slot, or to a type a slot refers to, reaches the page with nothing to remember. Depends on nothing but
 * code, as a generated document must: its build id is a hash of this text.
 */
fun clientConfigurationMarkdown(): String {
    val shapes = ConfigSlotShapes.served(KdrCxt.mkSimpleCxt("configReference"))
    val types = parseSchemaTypes(shapes.defs)
    return ConfigReferenceWriter(shapes, types).write()
}

/** Writes [clientConfigurationMarkdown]: the prose, then each slot, then each named type the slots reach. */
private class ConfigReferenceWriter(private val shapes: ServedSlotShapes, private val types: Map<String, SchType>) {
    private val sb = StringBuilder()

    /** Each slot's data type, by slot, in declaration order. */
    private val slotData: Map<String, SchType> = shapes.entryTypes.mapValues { (_, entryType) ->
        types.getValue(entryType).properties.getValue(GE.data).valueType
    }

    /**
     * The named types a slot refers to, documented in a section each, in the order they are first met. A slot's own
     * data type is drawn in the slot's section instead -- unless it is a type of its own (the client definition's).
     */
    private val sectioned = LinkedHashMap<String, SchType>()

    fun write(): String {
        for (data in slotData.values) {
            if (isSlotDataType(data)) data.properties.values.forEach { collect(it.valueType) } else collect(data)
        }
        intro()
        writing()
        patching()
        checked()
        examples()
        slots()
        typeSections()
        return sb.toString()
    }

    private fun line(text: String = "") {
        sb.append(text).append('\n')
    }

    private fun intro() {
        line("# A client's configuration")
        line()
        line(
            "A client's configuration says what the client is and what it does: the traits that give its forms' data " +
                "their shape, the workflows its forms move through, its reports, and more. This page says how that " +
                "configuration is stored, lists every kind of thing it holds, and says what a write or an edit of one " +
                "must send. It is made from the shapes a write is held to, so it says what the server accepts.",
        )
        bundlesAndSlots()
        line()
        line("## The endpoints")
        line()
        line("The bundle editor shows a client's bundles as they are stored, and edits them through these endpoints.")
        line()
        line("| Endpoint | What it does |")
        line("|---|---|")
        line("| `GET ${CFEP.bundle}` | One bundle's latest revision, every slot. |")
        line("| `GET ${CFEP.slotEntries}` | One bundle's entries, each with its slot and its own accounting. |")
        line("| `POST ${CFEP.bundleWrite}` | Writes a whole bundle. |")
        line("| `POST ${CFEP.bundlePatch}` | Edits entries of one or more bundles, together. |")
        line()
        line(
            "Each acts on the caller's own client. An administrator of every client (`allClients`) reaches a named " +
                "client's through the same endpoints under `/admin/client/config/`.",
        )
    }

    /**
     * What bundles are for, and how a client's configuration is assembled from them: the part of the page a schema
     * cannot say, ahead of everything that assumes it.
     */
    private fun bundlesAndSlots() {
        line()
        line("## Bundles, slots, and entries")
        line()
        line(
            "A client's configuration is stored in pieces called **bundles**, each with a name, and assembled from " +
                "them: what the client runs is every entry of every one of its bundles, taken together. No entry has to " +
                "be in a particular bundle. Which entries go in which bundle is your choice, made when you write the " +
                "bundles, and you can change it later. You could keep all of a client's traits in one bundle, or give " +
                "each trait a bundle of its own.",
        )
        line()
        line(
            "**A bundle is what you save and what you publish.** Each save of a bundle makes a new revision of it. For " +
                "a client with a sandbox, that revision is a draft: the sandbox runs it at once, and the client's own " +
                "people get it only when the bundle is published. So the way you divide a configuration into bundles is " +
                "the way you can release it. To publish a change to one workflow and nothing else, keep that workflow " +
                "in a bundle of its own. Publishing is done by the client's own people in the app, after trying the changes " +
                "in the sandbox, so a change written through the endpoints below stays a draft until then.",
        )
        line()
        line(
            "**The app often chooses the bundle for you.** Design View edits a workflow in whichever bundle declares " +
                "it, and a workflow made on a client's page is given a new bundle of its own. The bundle editor and the " +
                "endpoints below work on bundles directly.",
        )
        line()
        line(
            "**Within a bundle, entries are sorted into slots,** one slot for each kind of thing: `${CCT.traitDef}` " +
                "holds traits, `${CCT.workflowDef}` holds workflows, and so on. A slot is an array of entries of its one " +
                "kind, each named by its **key**: a trait by its id, a workflow by its id. Most slots hold as many " +
                "entries as you like. [`${CCT.clientDef}`](#${anchor(CCT.clientDef)}), which holds the client's " +
                "definition, holds at most one, since a client has one definition.",
        )
        line()
        line(
            "**A key names one thing in the whole client, not only in its bundle.** A second trait, workflow, report, " +
                "or type under a key that is already used, in the same bundle or another, is refused. Overlays are the " +
                "exception: two `${CCT.fragmentDef}` entries for one fragment file, or two `${CCT.uiBlockDef}` entries " +
                "for one block, are two layers, and both apply.",
        )
        line()
        line(
            "**Entries depend on one another across bundles, so a change is judged against the whole client.** A " +
                "workflow names the traits its forms collect, so it depends on them. Every write and patch is checked " +
                "against the client as it would be with the change in place (see " +
                "[what is checked, and when](#what-is-checked-and-when)), so removing a trait that a workflow still " +
                "collects is refused, even when the two are in different bundles. There are two ways to do it:",
        )
        line()
        line(
            "- Save the workflow's bundle first, without the trait in the workflow, and then remove the trait from its " +
                "own bundle.",
        )
        line(
            "- Make both changes in one [patch](#editing-entries), which edits several bundles and stores them " +
                "together or not at all.",
        )
        line()
        line(
            "Publishing follows the same rule. Publishing a workflow's bundle without the bundle holding a new trait " +
                "it collects is refused, so bundles that were changed together are published together.",
        )
    }

    private fun writing() {
        line()
        line("## Writing a whole bundle")
        line()
        line(
            "`${CFEP.bundleWrite}` takes the bundle's `${CFEP.name}`, its `${CFEP.namespaceField}` (where the types it " +
                "declares are named), and its `${CFEP.slots}`: an object with one array of entries for each slot it " +
                "fills, named as in [the slots](#the-slots) below. An entry is its data alone, as the slot's section " +
                "lists it.",
        )
        line()
        line(
            "A write is the whole bundle. A slot it leaves out is emptied, unless it says `\"${CFEP.impliedDelete}\": " +
                "false`, when a slot it leaves out is kept as it was. It is judged only on what it changes: an entry it " +
                "carries back exactly as stored is not judged again, so a bundle read, edited, and written back is not " +
                "refused for a fault it already had.",
        )
        line()
        line(
            "`\"${CFEP.reloadField}\": true` makes the client run the new revision at once. A client that runs only what " +
                "is published, as a self-serve client does, runs a draft only in its sandbox.",
        )
    }

    private fun patching() {
        line()
        line("## Editing entries")
        line()
        line(
            "`${CFEP.bundlePatch}` edits entries without sending the rest of the bundle. Its `${CFEP.configs}` are the " +
                "bundles it edits, each `{\"${CFEP.name}\": ..., \"${CFEP.edits}\": [...]}`. A bundle not yet written is " +
                "created. Every edit of every bundle is applied together and judged together, so an entry can move from " +
                "one bundle to another: delete it from one, add it to the other.",
        )
        line()
        line("Each edit is an object:")
        line()
        line("| Field | Required | What it is |")
        line("|---|---|---|")
        line("| `${CFEP.slot}` | yes | The slot the entry is in: one of [the slots](#the-slots). |")
        line(
            "| `${GED.action}` | yes | What to do: ${GedraEditAction.entries.joinToString(", ") { "`${it.name}`" }}, " +
                "as the table below says. |",
        )
        line(
            "| `${GE.data}` | yes | The entry's data, or as much of it as the action needs. Only a delete from a slot " +
                "with one entry may leave it out. |",
        )
        line("| `${CFEP.expectUpdatedAt}` | no | The entry's `updatedAt` when the caller read it. |")
        line("| `${CFEP.expectNew}` | no | `true` when the caller read no such entry. |")
        line()
        line(
            "**Which entry an edit means is said by its data:** the entry whose key fields have the values the data " +
                "gives. So every edit's data carries the slot's key fields -- for a slot with one entry, such as " +
                "`${CCT.clientDef}`, there are none -- and an edit cannot change an entry's key. To rename one, delete " +
                "it and add it under the new key.",
        )
        line()
        line("**What each action's data must carry:**")
        line()
        line("| Action | What it does | Its data |")
        line("|---|---|---|")
        line(
            "| `${GedraEditAction.deleteOrNoOp.name}` | Removes the entry, or does nothing if there is none. | The key " +
                "fields, and nothing else is needed. |",
        )
        line(
            "| `${GedraEditAction.addOrMerge.name}` | Changes the fields it gives, keeping the rest; adds the entry if " +
                "there is none. | The key fields and the fields to change. A field it leaves out keeps its stored " +
                "value, so a required field need not be sent again; a new entry must be complete. |",
        )
        line(
            "| `${GedraEditAction.addOrReplace.name}` | Replaces the entry with what it gives; adds it if there is none. " +
                "| The **whole** entry, required fields and all, even when the entry exists: a field it leaves out is " +
                "gone. |",
        )
        line()
        line(
            "**Saying what was read.** An edit may say what its caller saw: `${CFEP.expectUpdatedAt}`, the entry's " +
                "`updatedAt` when read, or `\"${CFEP.expectNew}\": true` when there was no entry. If the entry has " +
                "changed since, or is gone, or now exists, the patch is refused, all of it, with status 409 and " +
                "`errorCode` `${CFEP.staleCode}`. Read again and redo the edit.",
        )
        line()
        line(
            "**Trying it first.** `\"${CFEP.dryRun}\": true` runs the patch -- its checks, its edits, and the trial " +
                "reload -- and answers the bundles as they would be written, with what they would do to the forms the " +
                "client already stores, without storing anything.",
        )
    }

    private fun checked() {
        line()
        line("## What is checked, and when")
        line()
        line(
            "A write or a patch is refused, all of it, with every problem named by where it is: its slot, the " +
                "entry's key, and the path within it, as `${CCT.cfactDef}[ready].group`.",
        )
        line()
        line(
            "1. **Each entry it changes is held to its slot's shape:** the fields the slot's section lists and no " +
                "others, each of its type, and the required ones present. A few rules go beyond the shape: a usage rule's `${CCT.display}` and `${CCT.presentation}` " +
                "must be templates that parse, a file ref names its file the one way its `${CCT.source}` reads, and a " +
                "type may be declared once.",
        )
        line(
            "2. **Then the client is loaded with the change in place, as a trial,** and refused if that finds a " +
                "problem the client did not have. Some things are judged only here, in the configuration they belong " +
                "to:",
        )
        line(
            "   - **A schema body** -- a `${CCT.schemaDef}`'s `${CCT.schema}`, a `${CCT.traitDef}`'s " +
                "`${CCT.dataSchema}` -- is held to the shape of a schema at the first step, and is parsed only here, " +
                "where the client's other types are there for it to name. What a schema may say is the " +
                "[schema dialect](schema-dialect.md).",
        )
        line(
            "   - **Inside a report's `${CCT.definition}`**, and inside the definition of a workflow made from a " +
                "template (`${WFD.fromTemplate}`), which says only what it changes in the template: the first step " +
                "asks only that the definition is an object.",
        )
        line()
        line(
            "**A bundle read in portable form** writes the client's own namespace as `${PORT.namespace}` (`?" +
                "${PORT.portableField}=true` on a read), so it can be written to another client. A write or a patch " +
                "accepts it and resolves it to the client it is written to, everywhere but the " +
                "${PORT.keptSlots.sorted().joinToString(" and ") { "`$it`" }} slots.",
        )
    }

    private fun examples() {
        line()
        line("## Examples")
        line()
        line(
            "Three edits to one bundle, `main`. The first declares a cfact, or replaces its declaration. The second " +
                "changes only a cfact's description, keeping its other fields. The third removes one, by its key.",
        )
        line()
        line("```json")
        line("{")
        line("  \"${CFEP.configs}\": [")
        line("    {")
        line("      \"${CFEP.name}\": \"main\",")
        line("      \"${CFEP.edits}\": [")
        line("        {")
        line("          \"${CFEP.slot}\": \"${CCT.cfactDef}\", \"${GED.action}\": \"${GedraEditAction.addOrReplace.name}\",")
        line(
            "          \"${GE.data}\": {\"${CCT.name}\": \"ready\", \"${CCT.group}\": \"review\", " +
                "\"${CCT.description}\": \"The review is ready.\", \"${CCT.toFrontend}\": true}",
        )
        line("        },")
        line("        {")
        line("          \"${CFEP.slot}\": \"${CCT.cfactDef}\", \"${GED.action}\": \"${GedraEditAction.addOrMerge.name}\",")
        line("          \"${GE.data}\": {\"${CCT.name}\": \"accepted\", \"${CCT.description}\": \"The talk was accepted.\"},")
        line("          \"${CFEP.expectUpdatedAt}\": \"2026-10-29T16:00:00Z\"")
        line("        },")
        line("        {")
        line("          \"${CFEP.slot}\": \"${CCT.cfactDef}\", \"${GED.action}\": \"${GedraEditAction.deleteOrNoOp.name}\",")
        line("          \"${GE.data}\": {\"${CCT.name}\": \"draft\"}")
        line("        }")
        line("      ]")
        line("    }")
        line("  ],")
        line("  \"${CFEP.reloadField}\": true")
        line("}")
        line("```")
        line()
        line(
            "The merge says what its caller read, so it is refused if `accepted` changed after " +
                "`2026-10-29T16:00:00Z`.",
        )
    }

    private fun slots() {
        line()
        line("## The slots")
        line()
        line("| Slot | Keyed by | What an entry is |")
        line("|---|---|---|")
        for ((slot, entryType) in shapes.entryTypes) {
            val key = shapes.keys[slot].orEmpty()
            val keyed = if (key.isEmpty()) "one entry" else key.joinToString(", ") { "`$it`" }
            line("| [`$slot`](#${anchor(slot)}) | $keyed | ${cell(types.getValue(entryType).description)} |")
        }
        for ((slot, data) in slotData) {
            val key = shapes.keys[slot].orEmpty()
            line()
            line("### $slot")
            line()
            types.getValue(shapes.entryTypes.getValue(slot)).description?.let {
                line(it)
                line()
            }
            line(if (key.isEmpty()) "A bundle holds one entry in this slot." else "Keyed by ${key.joinToString(" and ") { "`$it`" }}.")
            line()
            if (isSlotDataType(data)) {
                fieldTable(data)
            } else {
                line("An entry is ${typeText(data)}.")
            }
        }
    }

    private fun typeSections() {
        if (sectioned.isEmpty()) return
        line()
        line("## The types the slots refer to")
        line()
        line(
            "Each is a type the server declares, named as the endpoint catalog names it. A field whose type is one of " +
                "these links to it.",
        )
        for ((name, type) in sectioned) {
            line()
            line("### ${simpleName(name)}")
            line()
            line("`$name`${type.description?.let { ": $it" }.orEmpty()}")
            line()
            val variants = type.variants
            if (variants != null) {
                line("One of these, by its `${variants.discriminator}`:")
                line()
                for (branch in variants.branches) {
                    val selector = branch.properties[variants.discriminator]?.valueType?.constValue
                    line("- ${selector?.let { "`$it`: " }.orEmpty()}${typeText(branch)}")
                }
            } else if (type.properties.isNotEmpty()) {
                fieldTable(type)
            } else {
                line("It is ${typeText(type)}.")
            }
        }
    }

    private fun fieldTable(type: SchType) {
        line("| Field | Type | Required | What it is |")
        line("|---|---|---|---|")
        for ((name, prop) in type.properties) {
            if (prop.valueType.derived) continue
            val required = if (name in type.required) "yes" else ""
            line("| `$name` | ${typeText(prop.valueType)} | $required | ${cell(prop.description ?: prop.valueType.description)} |")
        }
    }

    /** Whether [data] is a slot's own data type, drawn in the slot's section: one the slot declared inline. */
    private fun isSlotDataType(data: SchType): Boolean = data.name?.endsWith(slotDataSuffix) == true

    /** Adds [type], and every named type it reaches, to the types documented in a section each. */
    private fun collect(type: SchType) {
        val named = type.name
        if (named != null && documented(type)) {
            if (named in sectioned) return
            sectioned[named] = type
            type.variants?.branches?.forEach { collect(it) }
        }
        type.properties.values.forEach { collect(it.valueType) }
        type.itemType?.let { collect(it) }
        type.additionalValueType?.let { collect(it) }
    }

    /** Whether a named [type] gets a section: it has fields or branches to list. */
    private fun documented(type: SchType): Boolean = type.properties.isNotEmpty() || type.variants != null

    /** [type] in words, for a table cell or a sentence: a link to its section when it has one. */
    private fun typeText(type: SchType): String {
        val named = type.name
        if (named != null && named in sectioned) return "[${simpleName(named)}](#${anchor(simpleName(named))})"
        if (type.schemaDocument) return "a schema"
        type.constValue?.let { return "`$it`" }
        type.options?.takeIf { !type.openOptions }?.let { options -> return "one of ${options.joinToString(", ") { "`${it.value}`" }}" }
        return when (type.jsonType) {
            SCT.array -> "array of ${type.itemType?.let { typeText(it) } ?: "anything"}"
            SCT.kObject -> type.additionalValueType?.takeIf { type.properties.isEmpty() }?.let { "map of ${typeText(it)}" } ?: "object"
            SCT.string -> if (type.format == SFMT.dateTime) "date-time" else "string"
            else -> type.jsonType ?: "anything"
        }
    }

    private fun simpleName(qualified: String): String = qualified.substringAfterLast('.')

    /**
     * The anchor of a heading reading [text], as the app's renderer and GitHub make one: lowercased, a space a hyphen,
     * and any other character but a letter, a digit, `-`, or `_` dropped -- so `kdr:clientDef` is `kdrclientdef`.
     */
    private fun anchor(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }.replace(' ', '-')

    /** [text] as one table cell: on one line, with a `|` escaped. */
    private fun cell(text: String?): String = text.orEmpty().replace("\n", " ").replace("|", "\\|")
}

/** The suffix of the data type a config trait generates for data declared inline (`TraitDefData`). */
private const val slotDataSuffix = "Data"
