package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.formTraitName
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientStoredEdit
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraTrait
import com.dynamicruntime.common.gedra.fieldLabelOf
import com.dynamicruntime.common.gedra.formTraitData
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.keyFromLabel
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.searchKeywordsOf
import com.dynamicruntime.common.gedra.workflow.labelsCtaFilter
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Making and removing a report on the Reports page (issue #1492): what the page offers, the report paths it may pick
 * from, the create behind `POST /clientAdmin/report/create`, and the removal behind `.../remove`. **One-way**: a report
 * made here is not edited here afterwards. It is an ordinary stored report, so it is changed in the bundle editor, as
 * JSON, and removed there or here.
 *
 * ### The page does not build paths
 *
 * The catalog lists every path a report may read for the client -- the form's own facts, each form trait's fields,
 * each workflow's standing and approvals -- each **bound** against the client as the configuration load binds a report
 * ([ReportService.scopeFor], [bindReportPath]), so the page offers only what will be accepted, with the kind and the
 * multiplicity the binding found. A keyed trait's fields are listed reading every entry (`[*]`); naming one entry
 * instead, by its key, is the create's to write ([KeySelector.Positional]), from the key values the page sends.
 *
 * ### Where it is stored
 *
 * In the client's `reports` configuration ([ClientCreate.starterReportsConfig], where a client created from data has its
 * starter report), made when the client has none. The id is made from the name and never changes; one the client
 * already has is refused rather than numbered, since a second report of the same name is more likely a mistake than a
 * wish. Then as every editor's save: published and reloaded, or a draft for a client with a sandbox -- whose page leads
 * to the sandbox's to make one ([offer]). The write's trial binds the report as the load will, so a mistake is refused
 * in the server's words.
 */
@Suppress("ConstPropertyName")
object ReportEdit {
    /** What a create or a removal did: the configuration the report is or was in, its id, and how it took effect. */
    class Result(val configName: String, val reportId: String, val issues: List<GedraConfigIssue>, val mode: String)

    /**
     * What the Reports page offers [client]'s administrator, as the reports listing's summary carries it: whether a
     * report may be made here ([RCR.canCreate]) and whether it would be a draft ([RCR.createsDraft]), or the sandbox
     * it is made from instead ([RCR.createFrom]), as the bundle editor leads a client with one to its sandbox. Nothing
     * for the global scope, or a client that takes no stored configuration on this node.
     */
    fun offer(cxt: KdrCxt, client: String): Map<String, Any?> {
        val owner = sandboxParentOf(client) ?: client
        val clients = ClientService.get(cxt)
        val def = clients.known(owner)
        val none = mapOf(RCR.canCreate to false)
        if (client == GID.globalClient || def == null || GedraConfigService.get(cxt).isStaticHere(cxt, owner)) return none
        if (isSandboxClient(client)) return mapOf(RCR.canCreate to clients.isPresent(client), RCR.createsDraft to true)
        if (!def.sandbox) return mapOf(RCR.canCreate to true, RCR.createsDraft to false)
        val sandbox = sandboxOf(client)
        return if (clients.isPresent(sandbox)) none + (RCR.createFrom to sandbox) else none
    }

    /** The report paths for [client]'s page, grouped by where the value comes from -- see the class note. */
    fun catalog(cxt: KdrCxt, client: String): Map<String, Any?> {
        val view = ClientStoredEdit.editedView(cxt, client)
        val scope = ReportService.get(cxt).scopeFor(cxt, view)
        val store = SchemaService.get(cxt).storeFor(view)
        // The search keywords each normal workflow's calls to action may carry (issue #1600): what its paths offer, and
        // whether the form's own "calls to action" are worth offering at all.
        val keywords = scope.workflows.values.filter { it.entry == WfEntry.normal }.associate { it.workflowId to searchKeywordsOf(cxt, it) }
        val groups = buildList {
            add(group(ReportPathGroupKind.form, null, "The form", formPaths(scope, keywords.values.any { it.isNotEmpty() })))
            for (trait in scope.formTraits.values) {
                val paths = traitPaths(scope, store, trait)
                // Named as every page names a trait (issue #1520): its heading, its title, its id.
                if (paths.isNotEmpty()) add(group(ReportPathGroupKind.trait, trait.traitId, formTraitName(cxt, view, trait.traitId), paths))
            }
            for (wf in scope.workflows.values.filter { it.entry == WfEntry.normal }) {
                val paths = workflowPaths(scope, wf, keywords[wf.workflowId].orEmpty().isNotEmpty())
                val label = plainLabel(wf.label, wf.workflowId)
                if (paths.isNotEmpty()) add(group(ReportPathGroupKind.workflow, wf.workflowId, label, paths))
            }
        }
        return mapOf(RRUN.client to client, RCR.groups to groups)
    }

    private fun group(source: ReportPathGroupKind, sourceId: String?, label: String, paths: List<Map<String, Any?>>): Map<String, Any?> =
        buildMap {
            put(RCR.source, source.name)
            sourceId?.let { put(RCR.sourceId, it) }
            put(RRUN.label, label)
            put(RCR.paths, paths)
        }

    /** [text] as a path the page may offer, labeled [label] -- or nothing, when it does not bind in [scope]. */
    private fun offered(
        scope: ReportScope,
        text: String,
        label: String,
        keyFields: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?>? {
        val bound = bindReportPath(text, scope).valueOrNull() ?: return null
        return buildMap {
            put(RRUN.path, bound.path.canonicalText)
            put(RRUN.label, label)
            put(RRUN.kind, bound.kind.name)
            put(RRUN.multiValued, bound.multiValued)
            if (keyFields.isNotEmpty()) put(RCR.keyFields, keyFields)
        }
    }

    /**
     * The form's own facts: when it was made and changed, its status, and its owner -- and, when the client's workflows'
     * calls to action carry search keywords ([withKeywords], issue #1600), which ones the form's do.
     */
    private fun formPaths(scope: ReportScope, withKeywords: Boolean): List<Map<String, Any?>> = listOfNotNull(
        offered(scope, MetaPath(RMETA.createdAt).canonicalText, "Created"),
        offered(scope, MetaPath(RMETA.updatedAt).canonicalText, "Updated"),
        offered(scope, MetaPath(RMETA.formStatus).canonicalText, "Status"),
        if (withKeywords) offered(scope, MetaPath(RMETA.ctaKeywords).canonicalText, "Calls to action") else null,
        offered(scope, UserPath(RUSR.email).canonicalText, "Owner's email"),
        offered(scope, UserPath(RUSR.name).canonicalText, "Owner's name"),
    )

    /** A label from configuration, unless it is a template or blank; else [id] in words. */
    private fun plainLabel(label: String?, id: String): String =
        label?.trim()?.takeIf { it.isNotEmpty() && "%{" !in it && $$"${" !in it } ?: humanizeFieldName(id.substringAfter(':'))

    /**
     * [trait]'s fields a column may show, down through nested objects `maxNesting` deep: each that binds. A keyed
     * trait's read every entry, and carry the key fields a column naming one entry gives values for.
     */
    private fun traitPaths(scope: ReportScope, store: KdrSchemaStore, trait: GedraTrait): List<Map<String, Any?>> {
        val data = formTraitData(store.types, trait.traitId) ?: return emptyList()
        val layout = layoutOf(store, data)
        // The trait's key is its data type's `g-primaryKey`, as this client's schema compiles it (issue #1521).
        val key = data.valueType.primaryKey
        val keyed = key.isNotEmpty()
        val keyFields = key.map { field ->
            buildMap {
                put(RCR.field, field)
                put(RRUN.label, propertiesOf(data.valueType)[field]?.let { fieldLabelOf(layout, field, it) } ?: humanizeFieldName(field))
                put(RRUN.kind, (walkReportFields(data.valueType, listOf(field)).valueOrNull()?.kind ?: ReportKind.string).name)
            }
        }
        val out = ArrayList<Map<String, Any?>>()
        fun walk(type: SchType, typeLayout: SchLayout?, prefix: List<String>, labelPrefix: String, depth: Int) {
            for ((key, prop) in propertiesOf(type)) {
                val label = labelPrefix + fieldLabelOf(typeLayout, key, prop)
                val inner = prop.valueType
                if (inner.jsonType == SCT.kObject && inner.properties.isNotEmpty()) {
                    if (depth < maxNesting) walk(inner, layoutOf(store, prop), prefix + key, "$label: ", depth + 1)
                    continue
                }
                val text = FormPath(trait.traitId, if (keyed) KeySelector.All else null, prefix + key).canonicalText
                offered(scope, text, label, if (keyed) keyFields else emptyList())?.let { out.add(it) }
            }
        }
        walk(data.valueType, layout, emptyList(), "", 1)
        return out
    }

    /** How deep a trait's nested objects are offered. */
    private const val maxNesting = 3

    /** The layout labeling the fields of [prop]'s type, by its name. */
    private fun layoutOf(store: KdrSchemaStore, prop: SchProperty): SchLayout? =
        (prop.refName ?: prop.valueType.name)?.let { store.layouts[it] }

    /** [type]'s properties, and for a union every branch's, the first declaration of a name kept. */
    private fun propertiesOf(type: SchType): Map<String, SchProperty> {
        val out = LinkedHashMap(type.properties)
        type.variants?.let { v ->
            (v.branches + listOfNotNull(v.defaultBranch)).forEach { b -> b.properties.forEach { (k, p) -> out.putIfAbsent(k, p) } }
        }
        return out
    }

    /**
     * What a report says about [wf] on a form: where the form stands, and each approval task's approval -- and, for a
     * workflow whose calls to action carry search keywords ([withKeywords], issue #1600), its next task and that task's
     * keyword, and whom it waits on when one of its CTA filters may say ([labelsCtaFilter]).
     */
    private fun workflowPaths(scope: ReportScope, wf: WfDef, withKeywords: Boolean): List<Map<String, Any?>> = buildList {
        offered(scope, WorkflowPath(wf.workflowId, RWF.category).canonicalText, "Where it stands")?.let { add(it) }
        if (withKeywords) {
            offered(scope, WorkflowPath(wf.workflowId, RWF.ctaTask).canonicalText, "Next task")?.let { add(it) }
            offered(scope, WorkflowPath(wf.workflowId, RWF.ctaKeyword).canonicalText, "Call to action")?.let { add(it) }
        }
        if (labelsCtaFilter(wf)) {
            offered(scope, WorkflowPath(wf.workflowId, RWF.ctaFilterLabel).canonicalText, "Waiting on")?.let { add(it) }
        }
        for (task in wf.tasks.filter { it.approval != null }) {
            val taskLabel = plainLabel(task.label, task.id)
            offered(scope, WorkflowPath(wf.workflowId, RWF.approved, task.id).canonicalText, "$taskLabel: approved")?.let { add(it) }
            offered(scope, WorkflowPath(wf.workflowId, RWF.approvedAt, task.id).canonicalText, "$taskLabel: approved on")?.let { add(it) }
        }
    }

    /**
     * Makes the report [request] describes for [client] -- see the class note. [expected] is how the caller told its
     * user the create would take effect.
     */
    fun create(cxt: KdrCxt, client: String, request: Map<String, Any?>, expected: String? = null): Result {
        val owner = sandboxParentOf(client) ?: client
        val label = request[RDEF.label].toOptStr()?.trim().orEmpty()
        if (label.isEmpty()) throw KdrException.mkInput("A report needs a name.")
        val asked = request[RDEF.columns].toJsonListOfMaps()
        if (asked.isEmpty()) throw KdrException.mkInput("A report needs at least one column.")
        val target = ClientStoredEdit.target(cxt, client, "reportCreate", expected)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        val reportId = keyFromLabel(label, emptySet(), "report")
        val stored = svc.listConfigs(bound)
            .flatMap { row -> row.entriesBySlot()[CCT.reportDef].orEmpty().mapNotNull { it[CCT.reportId].toOptStr() } }
        if (reportId in stored || ReportService.get(cxt).forClient(target.readsAs).report(reportId) != null) {
            throw KdrException.mkInput("Client '$owner' already has a report '$reportId'. Give this one another name.")
        }
        val report = reportOf(reportId, label, request, asked)
        // Checked here first, so a mistake is said of the column it is in; the write's trial checks it again, with the rest.
        when (val checked = bindReport(report, ReportService.get(cxt).scopeFor(cxt, target.readsAs))) {
            is Parsed.Failed -> throw KdrException.mkInput("Report '$label' cannot be made: ${checked.problems.first().message}")
            is Parsed.Ok -> {}
        }
        val holderId = GedraId.of(GedraConfigType.configDoc, owner, ClientCreate.starterReportsConfig)
        val holder = svc.readLatest(bound, holderId)
        holder?.let { ClientStoredEdit.requireNoForeignDraft(target, it, "a new report") }
        val entry = linkedMapOf(CCT.reportId to reportId, CCT.definition to report.toJsonMap())
        fun patch(change: (List<Map<String, Any?>>) -> List<Map<String, Any?>>) = svc.patchConfigs(bound, trial = true) {
            edit(holderId) { slots ->
                val now = change(slots[CCT.reportDef].orEmpty())
                if (now.isEmpty()) slots - CCT.reportDef else slots + (CCT.reportDef to now)
            }
        }.single()
        val written = patch { current ->
            if (current.any { it[CCT.reportId] == reportId }) {
                throw KdrException(
                    "Client '$owner' was given a report '$reportId' meanwhile. Give this one another name.", code = EXC.conflict,
                )
            }
            current + entry
        }
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = {
                val undone = patch { current -> current.filter { it[CCT.reportId] != reportId } }
                // A configuration this create made goes with it, so it is not left an empty draft the next create is refused for.
                if (holder == null) undone.updatedAt?.let { svc.deleteConfig(bound, undone.configId, it) }
            },
            restorePublished = holder?.isPublished == true,
            recalculateForms = false,
        )
        return Result(written.configId.baseId, reportId, reload.issues, target.mode)
    }

    /**
     * Removes [client]'s report [reportId] from the stored configuration holding it, wherever that is: one made here, or
     * written in the bundle editor. One the client runs but does not store -- declared in source code, or copied from
     * the client it is built on -- is refused, saying where it is. Then as [create] takes effect: published and
     * reloaded, a refused publish putting the report back, or a draft for a client with a sandbox. The report's
     * snapshots are kept, listable under its id, as they are for a report removed in the bundle editor.
     */
    fun remove(cxt: KdrCxt, client: String, reportId: String, expected: String? = null): Result {
        val owner = sandboxParentOf(client) ?: client
        val target = ClientStoredEdit.target(cxt, client, "reportRemove", expected)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        val holder = svc.listConfigs(bound).firstOrNull { row ->
            row.entriesBySlot()[CCT.reportDef].orEmpty().any { it[CCT.reportId] == reportId }
        }
        if (holder == null) {
            val declared = ReportService.get(cxt).forClient(target.readsAs).report(reportId)
                ?: throw KdrException("Client '$owner' has no report '$reportId'.", code = EXC.notFound)
            val where = when {
                declared.fromTemplate -> "it comes from the client this one is built on, '${declared.bundle.inheritedFrom}'"
                !declared.bundle.isStored -> "it is declared in code, where it is removed"
                else -> "its configuration's latest revision no longer holds it"
            }
            throw KdrException.mkInput("Report '$reportId' cannot be removed here: $where.")
        }
        ClientStoredEdit.requireNoForeignDraft(target, holder, "removing a report")
        fun patch(change: (List<Map<String, Any?>>) -> List<Map<String, Any?>>) = svc.patchConfigs(bound, trial = true) {
            edit(holder.configId) { slots ->
                val now = change(slots[CCT.reportDef].orEmpty())
                if (now.isEmpty()) slots - CCT.reportDef else slots + (CCT.reportDef to now)
            }
        }.single()
        val before = holder.entriesBySlot()[CCT.reportDef].orEmpty()
        val written = patch { current ->
            if (current.none { it[CCT.reportId] == reportId }) {
                throw KdrException("Client '$owner''s report '$reportId' was removed meanwhile.", code = EXC.conflict)
            }
            current.filter { it[CCT.reportId] != reportId }
        }
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { patch { before } },
            restorePublished = holder.isPublished,
            recalculateForms = false,
        )
        return Result(written.configId.baseId, reportId, reload.issues, target.mode)
    }

    /**
     * The report [request] asks for, its columns from [asked]: each column's id made from its heading, a keyed path
     * naming one entry when the column gives key values, and the report's grouping, required columns, and chart's
     * measure from the columns that say so. Whether the chart can be drawn is the binding's to say (issue #1773).
     */
    private fun reportOf(reportId: String, label: String, request: Map<String, Any?>, asked: List<Map<String, Any?>>): ClientReport {
        val ids = HashSet<String>()
        val groupBy = ArrayList<String>()
        val excludeEmpty = ArrayList<String>()
        val charted = ArrayList<String>()
        val columns = asked.mapIndexed { i, c ->
            val heading = c[RDEF.label].toOptStr()?.trim().orEmpty()
            if (heading.isEmpty()) throw KdrException.mkInput("Column ${i + 1} needs a heading.")
            val path = c[RDEF.path].toOptStr()?.trim().orEmpty()
            if (path.isEmpty()) throw KdrException.mkInput("Column '$heading' needs a value to show.")
            val key = c[RCR.key].toJsonListOrEmpty().map { it.toOptStr()?.trim().orEmpty() }
            val columnId = keyFromLabel(heading, ids, "column").also { ids.add(it) }
            if (c[RCR.groupedBy] == true) groupBy.add(columnId)
            if (c[RCR.mustHaveValue] == true) excludeEmpty.add(columnId)
            if (c[RCR.charted] == true) charted.add(columnId)
            ReportColumn(
                columnId, heading, if (key.isEmpty()) path else oneEntry(heading, path, key),
                combine = combineOf(heading, RDEF.combine, c[RDEF.combine]),
                rollup = combineOf(heading, RDEF.rollup, c[RDEF.rollup]),
            )
        }
        return ClientReport(
            reportId, label, columns,
            description = request[RDEF.description].toOptStr()?.trim()?.ifEmpty { null },
            groupBy = groupBy, excludeEmpty = excludeEmpty, history = request[RDEF.history] == true,
            chart = chartOf(request[RDEF.chart], charted),
        )
    }

    /**
     * The chart a create asks for: of [kind], measuring the one column marked [RCR.charted], else the count of forms.
     * Open only so a test reaches the refusals the endpoint's own validation keeps a request from (a kind it does not
     * list).
     */
    @KdrPrivate
    fun chartOf(kind: Any?, charted: List<String>): ReportChart? {
        val name = kind.toOptStr()?.ifEmpty { null }
        if (name == null) {
            if (charted.isNotEmpty()) {
                throw KdrException.mkInput("Column '${charted.first()}' is what a chart measures, but no chart is asked for.")
            }
            return null
        }
        val chartKind = ReportChartKind.entries.firstOrNull { it.name == name }
            ?: throw KdrException.mkInput("There is no chart '$name'; choose ${ReportChartKind.entries.joinToString(" or ") { it.name }}.")
        if (charted.size > 1) throw KdrException.mkInput("A chart measures one column, and ${charted.size} are marked.")
        return ReportChart(chartKind, charted.firstOrNull())
    }

    /** [path], a keyed trait's path reading every entry, made to read the one entry [key] names. */
    private fun oneEntry(heading: String, path: String, key: List<String>): String {
        val parsed = parseReportPath(path).valueOrNull()
        if (parsed !is FormPath || parsed.selector !is KeySelector.All) {
            throw KdrException.mkInput("Column '$heading' gives a key, but '$path' does not read a keyed trait's every entry.")
        }
        if (key.any { it.isEmpty() }) throw KdrException.mkInput("Column '$heading' needs a value for each field of the key.")
        return FormPath(parsed.traitId, KeySelector.Positional(key), parsed.fields, parsed.envField).canonicalText
    }

    private fun combineOf(heading: String, what: String, value: Any?): ReportCombine? {
        val name = value.toOptStr()?.ifEmpty { null } ?: return null
        return ReportCombine.entries.firstOrNull { it.name == name }
            ?: throw KdrException.mkInput("Column '$heading' has no $what '$name'.")
    }
}
