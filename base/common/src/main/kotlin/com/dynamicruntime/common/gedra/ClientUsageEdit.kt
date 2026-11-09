package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's listing columns, on the client page (issue #1491): the catalog the page draws -- the client's columns,
 * each with where it is stored or why it cannot be changed here, and the form traits a column may read with their
 * fields -- and the save and removal behind `POST /clientAdmin/client/usages/save` and `.../remove`.
 *
 * ### Where a column is stored
 *
 * The server decides, so the page needs no entry stamps. A new column goes into the stored configuration holding the
 * client's definition: a column is too small for a configuration of its own. A change is made where the column is
 * stored, as a trait's edit is. A column the client runs but does not store -- declared in source code, or in the
 * client it is built on -- is not changed here, and the catalog says why.
 *
 * A column is keyed by its trait and its heading (issue #1490), so a heading renamed is a removal and an addition in
 * one patch: the entry is replaced where it stands. Its trait is fixed once saved, and so is its column id
 * ([ClientTraitUsage.columnId]), which the server makes from the heading when the trait already has a column -- the
 * column's search parameters and sort keep their names whatever its heading becomes.
 *
 * ### The deployment's defaults
 *
 * A client with no columns of its own shows the deployment's (`GedraConfigCollector.usagesFor`), and one of its own
 * replaces them all. So the first save or removal makes the defaults the client's own -- those whose trait it
 * supports -- with the change made, rather than leaving a client that added one column without the ones it had.
 * Removing a client's last column of its own brings the defaults back.
 *
 * ### How it takes effect
 *
 * As every editor's save on the client's page does ([ClientStoredEdit]): live, or a draft for a client with a sandbox.
 * The write's trial judges the columns as the load would -- a value or presentation that does not parse, a kind its
 * field contradicts, two columns whose search parameters clash. A column changes no stored data, so there is no
 * impact to judge.
 */
object ClientUsageEdit {
    /** What a save or a removal did: the configuration it was made in, the column as saved (none for a removal), and how it took effect. */
    class Result(val configName: String, val usage: ClientTraitUsage?, val issues: List<GedraConfigIssue>, val mode: String)

    /** A column's key (issue #1490): its trait, and its heading. */
    private data class ColumnKey(val traitId: String, val label: String)

    private val ClientTraitUsage.key: ColumnKey get() = ColumnKey(traitId, label)

    private fun keyOf(entry: Map<String, Any?>) = ColumnKey(entry[CCT.traitId].toOptStr().orEmpty(), entry[CCT.label].toOptStr().orEmpty())

    /** A column the client stores: the configuration holding it, latest revision, and its entry. */
    private class StoredColumn(val row: GedraConfigRow, val entry: Map<String, Any?>) {
        val usage: ClientTraitUsage get() = usageFromRuleMap(entry)
    }

    /** Every column `bound`'s client stores, latest revisions, drafts included, in the order its configurations list them. */
    private fun storedColumns(bound: KdrCxt): List<StoredColumn> = GedraConfigService.get(bound).listConfigs(bound)
        .flatMap { row -> row.entriesBySlot()[CCT.usageDef].orEmpty().map { StoredColumn(row, it) } }

    /** [client]'s own columns as it runs them, each with the configuration declaring it; empty when it shows the defaults. */
    private fun ownRunning(cxt: KdrCxt, client: String): List<Pair<ClientTraitUsage, GedraConfig>> =
        SchemaCollector.get(cxt)?.gedraConfigs?.configs.orEmpty()
            .filter { it.gedraId.client == client }
            .flatMap { config -> config.usages.map { it to config } }

    /** The deployment's default columns: what a client with none of its own shows. */
    private fun defaultColumns(cxt: KdrCxt): List<ClientTraitUsage> =
        SchemaCollector.get(cxt)?.gedraConfigs?.usagesFor(GID.globalClient).orEmpty()

    /** The form traits [client] supports, which a column may read: each its id with its declaration. */
    private fun formTraits(cxt: KdrCxt, client: String): List<GedraTrait> {
        val def = ClientService.get(cxt).known(client) ?: return emptyList()
        val collector = SchemaCollector.get(cxt) ?: return emptyList()
        val schema = SchemaService.get(cxt)
        return supportedTraits(collector.gedraConfigs, client, def, schema.overlaidTypesFor(client), schema.droppedTypesFor(client))
            .filter { GedraDataType.formDoc in it.appliesTo }
    }

    /** The catalog for [client]'s page -- see the class note -- as the catalog endpoint answers it. */
    fun catalog(cxt: KdrCxt, client: String): Map<String, Any?> {
        // Named for a sandbox (issue #1358), the parent's stored configuration is what its editors change.
        val owner = sandboxParentOf(client) ?: client
        val view = ClientStoredEdit.editedView(cxt, client)
        val schema = SchemaService.get(cxt)
        val store = schema.storeFor(view)
        val bound = cxt.mkSubContext("usageCatalog", owner)
        val stored = storedColumns(bound)
        val storedByKey = stored.associateBy { keyOf(it.entry) }
        val own = ownRunning(cxt, view)
        val defaults = own.isEmpty() && stored.isEmpty()
        val running = if (defaults) defaultColumns(cxt).map { it to null } else own
        val runningKeys = running.map { it.first.key }.toSet()
        val notRunning = stored.filter { keyOf(it.entry) !in runningKeys }
        val usages = running.map { it.first } + notRunning.map { it.usage }
        val columns = resolveColumns(usages, store.types)
        val rows = columns.mapIndexed { i, column ->
            val declared = running.getOrNull(i)?.second
            val holder = storedByKey[column.usage.key]
            buildMap {
                putAll(column.usage.toRuleMap())
                put(CUS.readsAs, column.kind.name)
                holder?.let { put(CUS.configName, it.row.configId.baseId) }
                if (i >= running.size) put(CUS.notRunning, true)
                if (holder == null && !defaults) put(CUS.notEditable, notEditable(declared))
            }
        }
        return buildMap {
            put(CLD.client, client)
            put(CUS.columns, rows)
            put(CUS.defaults, defaults)
            ClientStoredEdit.definitionHolder(bound)?.let { put(CUS.newColumnsIn, it.configId.baseId) }
            put(CUS.traits, formTraits(cxt, view).map { traitInfo(schema, store, it) })
            // The listing as a whole (issue #1599): how it draws the workflows, as the edited view runs it, and where stored.
            put(CUS.workflowLayout, schema.listingDefFor(view).workflowLayout.name)
            ClientListingEdit.holder(bound)?.let { put(CUS.layoutConfigName, it.configId.baseId) }
        }
    }

    /** Why a column the client runs, declared by [config], cannot be changed here: it is not in the client's storage. */
    private fun notEditable(config: GedraConfig?): String = when {
        config?.inheritedFrom != null -> "It comes from the client this one is built on, '${config.inheritedFrom}'."
        config != null && !config.isStored -> "It is declared in code, where it is edited."
        else -> "Its configuration's latest revision no longer holds it; it is edited in the bundle editor."
    }

    /** A trait for the catalog: its id, what it is for, and the fields a column of it may show. */
    private fun traitInfo(schema: SchemaService, store: KdrSchemaStore, trait: GedraTrait): Map<String, Any?> = buildMap {
        put(CCT.traitId, trait.traitId)
        (schema.schemaStore.servedDefs[trait.typeName] ?: store.defs[trait.typeName]).toJsonMapOrEmpty()[SCH.description]
            .toOptStr()?.let { put(CUS.description, it) }
        put(CUS.fields, fieldsOf(store, trait.traitId))
    }

    /**
     * The fields of trait [traitId] a column may show, as [store] declares them: those a lone `${field}` reads as text,
     * a number, a date, or a yes-or-no ([fieldSearchShape]). Each labeled as a person knows it: its layout's label,
     * unless that is a template, else its title, else its key in words.
     */
    private fun fieldsOf(store: KdrSchemaStore, traitId: String): List<Map<String, Any?>> {
        val data = formTraitData(store.types, traitId) ?: return emptyList()
        val type = data.valueType
        val layout = (data.refName ?: type.name)?.let { store.layouts[it] }
        return type.properties.mapNotNull { (key, prop) ->
            val shape = fieldSearchShape(prop.valueType) ?: return@mapNotNull null
            buildMap {
                put(CUS.field, key)
                put(CUS.label, fieldLabelOf(layout, key, prop))
                put(CUS.readsAs, shape.kind.name)
                if (shape.choices.isNotEmpty()) put(CUS.hasChoices, true)
            }
        }
    }

    /**
     * Saves [wanted] for [client]: a new column when [previousLabel] is null, else a change of its trait's column headed
     * [previousLabel], where it is stored -- see the class note. [wanted]'s column id is ignored: the server keeps a
     * column's, and makes a new one's. [expected] is how the caller told its user the save would take effect.
     */
    fun save(cxt: KdrCxt, client: String, previousLabel: String?, wanted: ClientTraitUsage, expected: String? = null): Result {
        // From a sandbox, its parent's: the sandbox holds no configuration, and a save is a draft of the parent's (#1358).
        val owner = sandboxParentOf(client) ?: client
        val label = wanted.label.trim()
        val display = wanted.display.trim()
        if (label.isEmpty()) throw KdrException.mkInput("A column needs a heading.")
        if (display.isEmpty()) throw KdrException.mkInput("Column '$label' needs a value: a field, or an expression.")
        val target = ClientStoredEdit.target(cxt, client, "usageSave", expected)
        val bound = target.bound
        val view = target.readsAs
        val stored = storedColumns(bound)
        val own = ownRunning(cxt, view)
        val seeds = seedsIfDefaults(cxt, view, stored, own)
        val previous = previousLabel?.let { ColumnKey(wanted.traitId, it) }
        val traits = formTraits(cxt, view)
        if (previous == null && traits.none { it.traitId == wanted.traitId }) {
            throw KdrException.mkInput("Client '$owner' has no form trait '${wanted.traitId}' for a column to read.")
        }
        // The columns as they stand, by key, wherever they are declared -- the stored first, as the next edit finds them:
        // what a new key may not repeat.
        val existing = seeds ?: (stored.map { it.usage } + own.map { it.first })
        val before = previous?.let { key -> existing.firstOrNull { it.key == key } }
        if (previous != null && before == null) {
            throw KdrException("Client '$owner' has no column '${previous.label}' of trait '${previous.traitId}'.", code = EXC.notFound)
        }
        val key = ColumnKey(wanted.traitId, label)
        if (key != previous && existing.any { it.key == key }) {
            throw KdrException.mkInput("Trait '${wanted.traitId}' already has a column headed '$label'.")
        }
        val holder = when {
            seeds != null || previous == null -> ClientStoredEdit.definitionHolder(bound)
                ?: throw KdrException.mkInput("Client '$owner' has no stored definition to keep a column in.")
            else -> stored.firstOrNull { keyOf(it.entry) == previous }?.row
                ?: throw KdrException.mkInput(
                    "Column '${previous.label}' cannot be changed here. " +
                        notEditable(own.firstOrNull { it.first.key == previous }?.second),
                )
        }
        // A column's id is fixed once saved; a new one needs its own when its trait's id already names a column.
        val columnId = if (before != null) before.columnId else {
            val keys = existing.map { it.columnKey }.toSet()
            if (wanted.traitId in keys) keyFromLabel(label, keys + traits.map { it.traitId }, "column") else null
        }
        val presentation = wanted.presentation?.trim()?.ifBlank { null }
        val usage = ClientTraitUsage(wanted.traitId, label, display, wanted.kind, wanted.substring, presentation, columnId)
        if (before != null && usage.toRuleMap() == before.toRuleMap()) {
            throw KdrException.mkInput("Column '$label' is already so; nothing to save.")
        }
        val entry = usage.toRuleMap()
        val result = write(cxt, target, holder, "a change to its listing columns") { current ->
            when {
                seeds != null -> {
                    if (current.isNotEmpty()) throw changedMeanwhile(owner)
                    seeds.map { if (it.key == previous) entry else it.toRuleMap() } + listOfNotNull(entry.takeIf { previous == null })
                }
                previous == null -> {
                    if (current.any { keyOf(it) == key }) throw changedMeanwhile(owner)
                    current + entry
                }
                else -> {
                    if (current.none { keyOf(it) == previous }) throw changedMeanwhile(owner)
                    current.map { if (keyOf(it) == previous) entry else it }
                }
            }
        }
        return Result(result.configName, usage, result.issues, result.mode)
    }

    /**
     * Removes [client]'s column of trait [traitId] headed [label], where it is stored -- see the class note. A client
     * showing the defaults makes the others its own; one removing its last column of its own shows the defaults again.
     */
    fun remove(cxt: KdrCxt, client: String, traitId: String, label: String, expected: String? = null): Result {
        val owner = sandboxParentOf(client) ?: client
        val target = ClientStoredEdit.target(cxt, client, "usageRemove", expected)
        val bound = target.bound
        val stored = storedColumns(bound)
        val own = ownRunning(cxt, target.readsAs)
        val seeds = seedsIfDefaults(cxt, target.readsAs, stored, own)
        val key = ColumnKey(traitId, label)
        val holder = if (seeds != null) {
            if (seeds.none { it.key == key }) {
                throw KdrException("Client '$owner' has no column '$label' of trait '$traitId'.", code = EXC.notFound)
            }
            if (seeds.size == 1) {
                throw KdrException.mkInput(
                    "Column '$label' is the deployment's default, shown while client '$owner' has no column of its own. " +
                        "Add one of its own, and this one can then be removed.",
                )
            }
            ClientStoredEdit.definitionHolder(bound)
                ?: throw KdrException.mkInput("Client '$owner' has no stored definition to keep its columns in.")
        } else {
            stored.firstOrNull { keyOf(it.entry) == key }?.row ?: run {
                val declared = own.firstOrNull { it.first.key == key }
                    ?: throw KdrException("Client '$owner' has no column '$label' of trait '$traitId'.", code = EXC.notFound)
                throw KdrException.mkInput("Column '$label' cannot be removed here. ${notEditable(declared.second)}")
            }
        }
        val result = write(cxt, target, holder, "a change to its listing columns") { current ->
            if (seeds != null) {
                if (current.isNotEmpty()) throw changedMeanwhile(owner)
                seeds.filter { it.key != key }.map { it.toRuleMap() }
            } else {
                if (current.none { keyOf(it) == key }) throw changedMeanwhile(owner)
                current.filter { keyOf(it) != key }
            }
        }
        return Result(result.configName, null, result.issues, result.mode)
    }

    /**
     * The deployment's default columns [view] would make its own -- those over a trait it supports -- when it shows them,
     * having none of its own, stored or declared; null when it has its own. See the class note.
     */
    private fun seedsIfDefaults(
        cxt: KdrCxt, view: String, stored: List<StoredColumn>, own: List<Pair<ClientTraitUsage, GedraConfig>>,
    ): List<ClientTraitUsage>? {
        if (stored.isNotEmpty() || own.isNotEmpty()) return null
        val supported = formTraits(cxt, view).map { it.traitId }.toSet()
        return defaultColumns(cxt).filter { it.traitId in supported }
    }

    private fun changedMeanwhile(owner: String) = KdrException(
        "Client '$owner''s listing columns were changed meanwhile; read them again and make the change there.",
        code = EXC.conflict,
    )

    /** What [write] made: the configuration, and how it took effect. */
    private class Written(val configName: String, val issues: List<GedraConfigIssue>, val mode: String)

    /**
     * Rewrites [holder]'s columns as [change] leaves them -- inside `patchConfigs`, over the revision as it stands under
     * the lock, so a column another administrator added a moment ago is kept or the change refused -- and makes that take
     * effect; a refused publish puts the columns back. [what] names the change in the draft rule's refusal.
     */
    private fun write(
        cxt: KdrCxt,
        target: ClientStoredEdit.EditTarget,
        holder: GedraConfigRow,
        what: String,
        change: (List<Map<String, Any?>>) -> List<Map<String, Any?>>,
    ): Written {
        ClientStoredEdit.requireNoForeignDraft(target, holder, what)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        fun patch(row: GedraConfigRow, to: (List<Map<String, Any?>>) -> List<Map<String, Any?>>) = svc.patchConfigs(bound, trial = true) {
            edit(row.configId) { slots ->
                val now = to(slots[CCT.usageDef].orEmpty())
                if (now.isEmpty()) slots - CCT.usageDef else slots + (CCT.usageDef to now)
            }
        }.single()
        val before = holder.entriesBySlot()[CCT.usageDef].orEmpty()
        val written = patch(holder, change)
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { patch(written) { before } },
            restorePublished = holder.isPublished,
            recalculateForms = false,
        )
        return Written(written.configId.baseId, reload.issues, target.mode)
    }
}
