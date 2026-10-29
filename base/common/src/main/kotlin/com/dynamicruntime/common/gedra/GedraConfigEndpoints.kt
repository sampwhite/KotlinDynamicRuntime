package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.util.getReqNonBlankStr
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.truncateToMs
import com.dynamicruntime.common.util.toOptInstantOrNull

/**
 * The read/write surface over stored client configuration (issue #627): the endpoints a client administrator
 * uses to see and edit their configuration, built on the write service (#633) and the split/reassemble (#613).
 *
 * ### Two views of one row, and why
 *
 * A stored config is one JSON bundle to the person editing it and a set of independently-accounted slot entries
 * underneath (#611). These endpoints show both:
 *
 *  - **Bundle-shaped** ([CFEP.bundle] read, [CFEP.bundleWrite], [CFEP.bundlePublish], [CFEP.bundles] list) --
 *    the same shape a component declares a `GedraConfig` in, split into slot entries on write (via
 *    [reassembleGedraConfig] -> [GedraConfigService.writeConfig]) and reassembled on read, so a caller never
 *    handles the split. This is the surface an editor uses.
 *  - **Entry-level** ([CFEP.slotEntries]) -- the interior, the stored slot entries with their own accounting
 *    (each carries its own `createdAt`/`updatedAt`), so *when and by whom a single entry changed* is inspectable.
 *    Outward these are slots, never traits (issue #1352): that a slot is stored as a config trait is the storage's
 *    business, and the slot rides as [CFEP.slot] where the stored envelope says `traitId`. This is a read view; the matching per-slot **edit** is [CFEP.bundlePatch] (issue #732).
 *
 * ### Authorization: client-scoped admin
 *
 * The endpoints live in the **`clientAdmin`** section (their paths lead with [SECT.clientAdmin]), so they take
 * [com.dynamicruntime.common.http.request.ROLE.admin] confined by scope -- the same gate the client-scoped user
 * administration uses (#466). A configuration belongs to a client, so every endpoint acts within the caller's
 * own client ([KdrCxt.client]): the config id is built from that client and the supplied name, so a caller
 * cannot read or write another client's configuration by naming it.
 *
 * ### What is published
 *
 * The list, the bundle and entry reads, the write, and the patch are `publicApi` (issue #1483): what an AI configuring
 * one client needs. The rest are not -- publishing and what it weighs ([CFEP.bundlePublish], [CFEP.bundleImpact],
 * [CFEP.publishedOnly]), which a client's own people do, and the shortcuts a write or patch already covers
 * ([CFEP.editor], and [CFEP.reload], which a write takes as a flag).
 *
 * ### What is here, and what is elsewhere
 *
 * Reload (#616, [CFEP.reload]) and the protection tier (#617, [CFEP.publishedOnly]) are endpoints here too.
 * Loading a written config at boot (#614) is `GedraConfigLoadService`, and the two-revision cache (#615) is
 * `GedraConfigCache`.
 */
@Suppress("DuplicatedCode")
fun gedraConfigSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, CFEP.namespace) {
    // The slots a configuration is made of (issue #1476): what an edit names, and what a bundle's slots are.
    val slots = coreConfigTraits(cxt).configTraits.keys

    // A config's identity and accounting, without its contents -- what a listing row shows.
    type(CFEP.summaryType) {
        type = SCT.kObject
        description = "One stored configuration: its identity, current version and whether it is published."
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.client, "The owning client.", required = true)
        property(CFEP.version, "The latest revision's version number.", required = true) { type = SCT.integer }
        property(CFEP.published, "Whether the latest revision has been published.", required = true) { type = SCT.boolean }
        property(CFEP.publishedAt, "When the latest revision was published; absent while it is still editable.") { dateTime() }
        property(
            CFEP.publishedVersion,
            "The latest published revision's version (issue #1001) -- what a published-only client runs while the " +
                "latest is a draft; absent when no revision is published.",
        ) { type = SCT.integer }
        property(CFEP.slotCount, "How many slots the latest revision holds entries in (issue #1461).", required = true) {
            type = SCT.integer
        }
        property(CFEP.entryCount, "How many entries the latest revision holds; none, and it may be deleted.", required = true) {
            type = SCT.integer
        }
        property(CFEP.publishedSlotCount, "How many slots the latest published revision holds entries in; absent when none is.") {
            type = SCT.integer
        }
        property(
            CFEP.publishedEntryCount,
            "How many entries the latest published revision holds; absent when none is. Unless none, the configuration is " +
                "not deleted until an emptied revision is published.",
        ) { type = SCT.integer }
        property(CFEP.createdAt, "When this revision was created.") { dateTime() }
        property(CFEP.updatedAt, "When this revision was last written.") { dateTime() }
        configIssuesProperty()
        reloadResultProperty()
    }

    type(IMP.findingType) {
        type = SCT.kObject
        description = "Rows a publish would affect the same way (issue #935): a kind of harm, what it is about, how " +
            "many rows, and a few of them."
        property(
            IMP.kind,
            "What would happen to the rows: ${ImpactKind.traitGone} (their trait would no longer be supported), " +
                "${ImpactKind.dataInvalid} (an entry would no longer validate, so every later edit of the form is " +
                "refused), ${ImpactKind.workflowGone} (a workflow they take part in would no longer exist) or " +
                "${ImpactKind.stateStranded} (their recorded state names a task the workflow would no longer define).",
            required = true,
        )
        property(IMP.traitId, "The trait a trait finding is about.")
        property(IMP.workflowId, "The workflow a workflow finding is about.")
        property(IMP.taskId, "The task a stranded-state finding is about.")
        property(IMP.count, "How many rows; a row counts once however many of its entries are affected.", required = true) {
            type = SCT.integer
        }
        property(IMP.sampleIds, "Up to ${IMP.sampleLimit} of the rows' gedra ids, to go and look at.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    type(IMP.configType) {
        type = SCT.kObject
        description = "One configuration a publish would make live, as an impact report names it (issue #1352)."
        property(IMP.name, "The configuration's name.", required = true)
        property(IMP.version, "The version of its latest revision, the one judged.", required = true) { type = SCT.integer }
    }

    type(IMP.reportType) {
        type = SCT.kObject
        description = "What publishing configurations together would do to the data its client already stores (issue " +
            "#935): only what the publish breaks, so a row with a problem already is not reported. Always empty for " +
            "a client that is not published-only, since publishing changes nothing such a client runs."
        property(IMP.client, "The client whose stored data is judged: the configuration's owner.", required = true)
        property(IMP.configs, "The configurations a publish would make live together, each its name and version.", required = true) {
            type = SCT.array
            items { ref(IMP.configType) }
        }
        property(IMP.scanned, "How many stored rows were examined.", required = true) { type = SCT.integer }
        property(
            IMP.tooLarge,
            "The client stores more rows than a report examines (${ConfigImpact.scanLimitEnvVar.name}), so none " +
                "were: a publish asks for acknowledgement as for a finding.",
            required = true,
        ) { type = SCT.boolean }
        property(IMP.findings, "What the publish would do, one finding per kind and subject.", required = true) {
            type = SCT.array
            items { ref(IMP.findingType) }
        }
    }

    // The whole bundle: a summary plus the config contents, one array of entries per slot -- the shape a
    // component declares and the reassembler consumes. The slot contents are heterogeneous and are validated by
    // rebuilding the `GedraConfig` on write, so `slots` is an open object rather than a spelled-out type.
    type(CFEP.bundleType) {
        type = SCT.kObject
        description = "A whole stored configuration as one bundle: its identity and its contents by slot."
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.namespaceField, "The namespace the configuration's generated types live in.")
        property(CFEP.client, "The owning client.", required = true)
        property(CFEP.version, "The latest revision's version number.", required = true) { type = SCT.integer }
        property(CFEP.published, "Whether the latest revision has been published.", required = true) { type = SCT.boolean }
        property(CFEP.publishedAt, "When the latest revision was published; absent while it is still editable.") { dateTime() }
        property(CFEP.slots, "The configuration's contents, one array of entries per config slot.", required = true) {
            type = SCT.kObject
        }
        property(CFEP.createdAt, "When this revision was created.") { dateTime() }
        property(CFEP.updatedAt, "When this revision was last written.") { dateTime() }
        configIssuesProperty()
        reloadResultProperty()
    }

    // The bundle editor page's one read (issue #1436): each configuration's two revisions, and each slot's shape.
    type(CFEP.editorBundleType) {
        type = SCT.kObject
        description = "One configuration as the bundle editor shows it: its latest revision, and the published one beside it."
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.revisionLatest, "Its latest revision: the editable draft, or the published revision when there is none.", required = true) {
            ref(CFEP.bundleType)
        }
        property(
            CFEP.revisionPublished,
            "Its latest published revision, when the latest is a draft over it; absent when the latest is published, or " +
                "nothing is.",
        ) { ref(CFEP.bundleType) }
        property(
            CFEP.latestStamps,
            "Each entry of its latest revision's `updatedAt`, by slot, in the order the slots hold the entries: what an " +
                "edit says it read (issue #1460).",
            required = true,
        ) { type = SCT.kObject }
    }
    type(CFEP.editorType) {
        type = SCT.kObject
        description = "Everything the bundle editor page draws (issue #1436)."
        property(CFEP.client, "The client whose configuration it is.", required = true)
        property(CFEP.configs, "Every configuration the client stores, most recently written first.", required = true) {
            type = SCT.array
            items { ref(CFEP.editorBundleType) }
        }
        property(CFEP.slotTypes, "Each slot's entry type, by slot; its `data` property is the shape of one entry's data.", required = true) {
            type = SCT.kObject
        }
        property(CFEP.slotKeys, "The fields each slot's entries are keyed by, by slot; empty for a single-instance slot.", required = true) {
            type = SCT.kObject
        }
        property(SCH.dDefs, "The schema types the slot types need.", required = true) { type = SCT.kObject }
    }

    // What a patch or a publish answers (issue #1352): every configuration it wrote, in the order asked, and the reload
    // when one was asked for -- once, since the configurations change together.
    type(CFEP.patchResultType) {
        type = SCT.kObject
        description = "The configurations a patch wrote, together, each as a whole bundle -- or, for a dry run, would write."
        property(CFEP.configs, "Each configuration written, in the order the patch named them.", required = true) {
            type = SCT.array
            items { ref(CFEP.bundleType) }
        }
        property(CFEP.dryRun, "Present and true for a dry run (issue #1434): nothing was stored.") { type = SCT.boolean }
        property(CFEP.impact, "For a dry run: what the patch would do to the data the client already stores.") { ref(IMP.reportType) }
        reloadResultProperty()
    }

    type(CFEP.deleteResultType) {
        type = SCT.kObject
        description = "The configuration a delete took away (issue #1461)."
        property(CFEP.name, "The configuration's name.", required = true)
        property(CFEP.version, "Its latest revision's version, as it stood.", required = true) { type = SCT.integer }
        reloadResultProperty()
    }

    type(CFEP.publishResultType) {
        type = SCT.kObject
        description = "The configurations a publish made published, together."
        property(CFEP.configs, "Each configuration named, in order -- one already published is as it was.", required = true) {
            type = SCT.array
            items { ref(CFEP.summaryType) }
        }
        reloadResultProperty()
    }

    // What a caller sends to write a bundle: identity, contents, and the delete mode. The version, publish
    // state, owning client and timestamps are the server's to decide, so they are absent here rather than
    // `g-derived` on the output type -- the write takes a type of its own, which is also where `impliedDelete`
    // lives, an instruction about the write that has no place in what a configuration *is*.
    type(CFEP.bundleWriteType) {
        type = SCT.kObject
        description = "A configuration to write, as a whole bundle."
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.namespaceField, "The namespace the configuration's generated types live in.", required = true)
        bundleSlotsProperty(slots)
        property(CFEP.impliedDelete, "Whether a slot the bundle omits is dropped (true, the default) or carried forward (false).") {
            type = SCT.boolean
        }
        reloadRequestProperty()
    }

    // One stored slot entry, its envelope carried whole so its accounting is visible -- its slot named as a patch
    // names it (issue #1352).
    type(CFEP.slotEntryType) {
        type = SCT.kObject
        description = "One stored slot entry: the slot it belongs to, its data, and its own accounting."
        property(CFEP.slot, "The slot this entry belongs to (`${CCT.traitDef}`, `${CCT.workflowDef}`, ...).", required = true)
        property(GE.data, "The slot entry's data.", required = true) { type = SCT.kObject }
        property(GE.entryId, "This entry's stable id.", required = true)
        property(GE.source, "How the entry's value arrived.", required = true)
        property(GE.createdAt, "When this slot entry was first written.", required = true) { dateTime() }
        property(GE.updatedAt, "When this slot entry was last changed.", required = true) { dateTime() }
        property(GE.createdBy, "The user who first wrote this slot entry.", required = true) { type = SCT.integer }
        property(GE.updatedBy, "The user who last changed this slot entry.", required = true) { type = SCT.integer }
    }

    listEndpoint(
        CFEP.bundles,
        "Lists the configurations this client carries, each as a summary of its latest revision.",
        publicApi = true,
        outputRef = CFEP.summaryType,
        noLimit = true,
    ) { c, _ -> inConfigScope(c) { cfgBundlesBody(it) } }

    itemEndpoint(
        CFEP.bundle,
        "Fetches one configuration's latest revision as a whole bundle.",
        HttpMethod.GET,
        publicApi = true,
        outputRef = CFEP.bundleType,
        inputFields = {
            field(CFEP.name, "The configuration's name.", required = true)
            revisionField()
            portableField()
        },
    ) { c, request -> inConfigScope(c) { cfgBundleBody(it, request) } }

    itemEndpoint(
        CFEP.editor,
        "Everything the bundle editor page draws, in one read (issue #1436): every configuration the client stores, with " +
            "its latest revision and, when that is a draft, the published revision beside it; and each slot's entry " +
            "type and key fields, with the schema types they need.",
        HttpMethod.GET,
        outputRef = CFEP.editorType,
    ) { c, _ -> inConfigScope(c) { cfgEditorBody(it) } }

    generalEndpoint(
        CFEP.bundleWrite,
        "Writes a configuration from a whole bundle, applying the version/publish and diff-before-stamp rules. What each " +
            "slot's entries hold is in $configReferenceLink.",
        HttpMethod.POST,
        // Its input may be a whole configuration, past the default body limit (issue #1446).
        largeBody = true,
        publicApi = true,
        outputRef = CFEP.bundleType,
        inputRef = CFEP.bundleWriteType,
    ) { c, request -> inConfigScope(c, mutating = true) { cfgWriteBody(it, request) } }

    generalEndpoint(
        CFEP.bundlePatch,
        "Edits the interior slot entries of one or more configurations (issues #732, #1352): per-slot add/replace/" +
            "merge/delete, addressed by slot + primary key, applied over each one's latest revision, all in one " +
            "transaction and judged together -- so an entry can move from one configuration to another. A " +
            "configuration not yet written is created. The rest of each is left as it was. A dry run answers what the " +
            "patch would do without storing it, and an edit saying what its caller read is refused if that has " +
            "changed since (issue #1434). What each slot's entries hold, and what each action's data must carry, is in " +
            "$configReferenceLink.",
        HttpMethod.POST,
        // Its input may be a whole configuration, past the default body limit (issue #1446).
        largeBody = true,
        publicApi = true,
        outputRef = CFEP.patchResultType,
        inputFields = { configPatchInput(slots) },
    ) { c, request -> inConfigScope(c, mutating = true) { cfgPatchBody(it, request) } }

    generalEndpoint(
        CFEP.bundlePublish,
        "Publishes the latest editable revision of one or more configurations, together and judged as one change " +
            "(issue #1352); one already published is left as it is. For a published-only client, refused when " +
            "making them live would affect the data the client already stores (issue #935) -- the refusal's " +
            "errorCode is '${IMP.refusedCode}' and its extraData carries the report under '${IMP.report}' -- unless " +
            "the call acknowledges it.",
        HttpMethod.POST,
        outputRef = CFEP.publishResultType,
        inputFields = {
            namesField("The configurations to publish together.")
            acknowledgeImpactField()
            reloadRequestField()
        },
    ) { c, request -> inConfigScope(c, mutating = true) { cfgPublishBody(it, request) } }

    itemEndpoint(
        CFEP.bundleImpact,
        "What publishing configurations' latest revisions together would do to the data the client already stores " +
            "(issues #935, #1352), without publishing them. Called in a Shadow Sandbox, about its parent's data.",
        HttpMethod.GET,
        outputRef = IMP.reportType,
        inputFields = {
            namesField("The configurations whose publishing together is judged.")
        },
    ) { c, request -> inConfigScope(c) { cfgImpactBody(it, request) } }

    generalEndpoint(
        CFEP.bundleRevert,
        "Reopens a published configuration for editing (issue #734): mints a new editable revision copied from " +
            "the published head, which stays immutable. A no-op when the head is already editable; refused for a " +
            "published-only client.",
        HttpMethod.POST,
        outputRef = CFEP.summaryType,
        inputFields = {
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> inConfigScope(c, mutating = true) { cfgRevertBody(it, request) } }

    generalEndpoint(
        CFEP.bundleDelete,
        deleteDescription,
        HttpMethod.POST,
        outputRef = CFEP.deleteResultType,
        inputFields = { deleteInput() },
    ) { c, request -> inConfigScope(c, mutating = true) { cfgDeleteBody(it, request) } }

    listEndpoint(
        CFEP.slotEntries,
        "The interior of one configuration: its stored slot entries, each with its own accounting.",
        publicApi = true,
        outputRef = CFEP.slotEntryType,
        noLimit = true,
        inputFields = {
            field(CFEP.name, "The configuration's name.", required = true)
            portableField()
        },
        // The entry-level VIEW; the matching per-slot **edit** is `bundle/patch` (issue #732), which applies the
        // edits over the current slots and writes the whole set through the validated bundle write rather than
        // touching a raw slot -- so it needs no slot validation union of its own. This view makes each entry's
        // accounting legible.
    ) { c, request -> inConfigScope(c) { cfgSlotEntriesBody(it, request) } }

    type(CFEP.reloadResultType) {
        type = SCT.kObject
        description = "What reloading a client's stored configuration on this node did."
        property(CFEP.client, "The client reloaded.", required = true)
        property(CFEP.loaded, "How many stored configurations the node now runs for the client.", required = true) { type = SCT.integer }
        property(CFEP.evictedTypes, "Compiled-type cache entries dropped for the client's endpoints.", required = true) { type = SCT.integer }
        property(
            CFEP.issues,
            "Every problem the client's configuration now has, found in any phase of the reload and forgiven (issue #840).",
        ) {
            type = SCT.array
            items { ref(CLD.configIssueTypeQualified) }
        }
    }

    // The mechanism the multi-node sync trigger (#618) calls, exposed for an administrator to call by hand:
    // this node re-reads the caller's client's stored configuration and rebuilds that client's derived state
    // without a restart (issue #616). Per client only -- the caller's own, as every endpoint here is.
    generalEndpoint(
        CFEP.reload,
        "Reloads this client's stored configuration on this node, without a restart.",
        HttpMethod.POST,
        outputRef = CFEP.reloadResultType,
    ) { c, _ -> inConfigScope(c) { cfgReloadBody(it) } }

    type(CFEP.tierType) {
        type = SCT.kObject
        description = "A client's configuration protection tier in this environment (issue #617)."
        property(CFEP.client, "The client.", required = true)
        property(CFEP.publishedOnlyField, "Whether the client consumes only its published configuration here.", required = true) {
            type = SCT.boolean
        }
    }

    // The published-only tier toggle (issue #617): sets whether the caller's client consumes only its published
    // configuration on nodes in this environment. Per the caller's own client, as every endpoint here is;
    // refused in production for a `staticConfig` client, which takes nothing stored there. The runtime effect follows
    // on the next reload -- this records the state, `POST config/reload` rebuilds against it.
    generalEndpoint(
        CFEP.publishedOnly,
        "Sets whether this client consumes only its published configuration on nodes in this environment.",
        HttpMethod.POST,
        outputRef = CFEP.tierType,
        inputFields = {
            field(CFEP.publishedOnlyField, "Whether to consume published configuration only.", required = true) { type = SCT.boolean }
        },
    ) { c, request -> inConfigScope(c, mutating = true) { cfgPublishedOnlyBody(it, request) } }
}

// --- Shared handler bodies (issue #685) -------------------------------------------------------------------
//
// Each keys everything off `cxt.client`, so the two surfaces differ only in which client that is: the
// `clientAdmin` endpoints above run them on the caller's own context, and the `/admin` endpoints below run them
// on a sub-context bound to a *named* client ([adminConfigCxt]). The service already binds to the config's own
// client for its write/publish, but building the config id and reload/tier calls off `cxt.client` is what makes
// one body serve both -- so the cross-client surface is the same logic under a different bound client, not a
// second implementation that could drift.
//
// Each also opens with `AdminRules.requireClientAdministrator` (issue #805): the `clientAdmin` section admits any
// administrator, including one in `public` who administers only their own users -- and the `public` client's
// configuration is every `public` user's, not theirs.

private fun cfgBundlesBody(c: KdrCxt): List<Map<String, Any?>> {
    AdminRules.requireClientAdministrator(c)
    val published = publishedRevisionsOf(c, c.client)
    return GedraConfigService.get(c).listConfigs(c).map { summaryOf(c, it, published) }
}

/** Each of [client]'s config classes' latest published revision, by config name (issues #1001, #1461). */
private fun publishedRevisionsOf(cxt: KdrCxt, client: String): Map<String, GedraConfigRow> =
    GedraConfigService.get(cxt).configsAt(cxt, client, published = true).associateBy { it.configId.baseId }

private fun cfgBundleBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val row = when (val revision = request[CFEP.revision].toOptStr() ?: CFEP.revisionLatest) {
        CFEP.revisionLatest -> GedraConfigService.get(c).readLatest(c, configId(c, name))
            ?: throw KdrException("No configuration '$name' for client '${c.client}'.", code = EXC.notFound)
        // Its latest published revision (issue #1436): what a published-only client runs while the latest is a draft.
        CFEP.revisionPublished -> GedraConfigService.get(c).configsAt(c, c.client, published = true)
            .firstOrNull { it.configId.baseId == name }
            ?: throw KdrException("Configuration '$name' of client '${c.client}' has no published revision.", code = EXC.notFound)
        else -> throw KdrException.mkInput("'$revision' is not a revision; ask for '${CFEP.revisionLatest}' or '${CFEP.revisionPublished}'.")
    }
    return bundleOf(c, row, portable = request[PORT.portableField] == true)
}

/**
 * The bundle editor page's one read (issue #1436): every configuration the bound client stores, most recently written
 * first, each its latest revision with its latest published one beside it when the latest is a draft over it; and each
 * slot's declared shape ([ConfigSlotShapes.served]).
 */
private fun cfgEditorBody(c: KdrCxt): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val svc = GedraConfigService.get(c)
    val published = svc.configsAt(c, c.client, published = true).associateBy { it.configId.baseId }
    val configs = svc.listConfigs(c).map { latest ->
        val name = latest.configId.baseId
        buildMap {
            put(CFEP.name, name)
            put(CFEP.revisionLatest, bundleOf(c, latest))
            // Each entry's stamp, beside the bundle's slots and in their order (issue #1460): what an edit says it read.
            put(
                CFEP.latestStamps,
                latest.entries.groupBy { it[GE.traitId].toOptStr().orEmpty() }.mapValues { (_, entries) -> entries.map { it[GE.updatedAt] } },
            )
            published[name]?.takeIf { it.version != latest.version }?.let { put(CFEP.revisionPublished, bundleOf(c, it)) }
        }
    }
    val shapes = ConfigSlotShapes.served(c)
    return mapOf(
        CFEP.client to c.client,
        CFEP.configs to configs,
        CFEP.slotTypes to shapes.entryTypes,
        CFEP.slotKeys to shapes.keys,
        SCH.dDefs to shapes.defs,
    )
}

private fun cfgWriteBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    // Portable form resolved first (issue #1435), so nothing after it sees `@self`.
    val namespace = fromPortable(request[CFEP.namespaceField].toOptStr(), c.client)?.toString()
        ?: throw KdrException.mkInput("A configuration bundle must name its '${CFEP.namespaceField}'.")
    val slots = slotsFromPortable(slotsOf(request[CFEP.slots], GedraConfigService.get(c).knownSlots()), c.client)
    // Gate the slots and reassemble the bundle into a GedraConfig (which re-runs the builder, so its contents are
    // validated as source would be), then write it. The config id is built from `c.client`, so on the `/admin`
    // surface the bound client is what the bundle is filed under -- an unwritten client id here is how a brand-new
    // client is created over the API (its `clientDef` slot, made present by the next reload).
    // The gate judges what the bundle changes (issue #1052), so the revision it replaces is handed to it: a
    // bundle read, edited and written back is not refused for a fault it already had.
    val svc = GedraConfigService.get(c)
    val stored = svc.readLatest(c, configId(c, name))?.entriesBySlot()
    val config = reassembleForWrite(c, name, namespace, c.client, slots, stored)
    requireOperatorFieldsKept(c, config.client)
    requireCustomersKept(c, slots[CCT.customerDef], stored?.get(CCT.customerDef))
    // Authoritative by default: a bundle is the whole configuration, so a slot the bundle omits is dropped, as
    // the write service defaults. A caller doing a partial, additive write sends `impliedDelete = false`.
    val impliedDelete = request[CFEP.impliedDelete] as? Boolean ?: true
    // Strict at write (issue #843): refused when a trial reload of the client with it in place finds a new problem.
    return bundleOf(c, svc.writeConfig(c, config, impliedDelete, trial = true)).withReloadIfAsked(c, request)
}

private fun cfgPatchBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val requested = request[CFEP.configs].toJsonListOfMaps()
    if (requested.isEmpty()) throw KdrException.mkInput("A config patch must name at least one configuration in '${CFEP.configs}'.")
    val byName = requested.map { one ->
        val name = requireName(one)
        // Portable form resolved first (issue #1435): the staleness check and the edits address entries by keys that
        // may be type names.
        val edits = one[CFEP.edits].toJsonListOfMaps().map { edit -> editFromPortable(edit, c.client) }
        if (edits.isEmpty()) throw KdrException.mkInput("The patch of configuration '$name' must carry at least one edit.")
        edits.forEach { requireReadAsSaid(name, it) }
        name to edits
    }
    val svc = GedraConfigService.get(c)
    val pk = svc.configSlotPrimaryKeys()
    val dryRun = request[CFEP.dryRun] == true
    // The edits run through `patchConfigs`, which applies each configuration's to its latest revision read **under
    // the write lock** and reassembles+writes it there, all in one transaction (issue #1352) -- so a per-slot patch
    // reuses the validated bundle write (no separate validation path), cannot be computed off a stale read and
    // clobber a concurrent write (issue #732), and an entry moved between configurations is never half moved.
    // `patchConfigs` reads the raw slots, so an untouched `testFeatures` round-trips on a test instance and, off one,
    // the write is refused rather than silently dropping it (the #685 explicit-write rule). A configuration not yet
    // written is created; one named twice is `patchConfigs`' refusal.
    val patches: ConfigPatches.() -> Unit = {
        for ((name, edits) in byName) {
            edit(configId(c, name), staleness = { latest -> staleEdits(name, edits, pk, latest) }) { current ->
                applyConfigSlotEdits(current, edits, pk).also { patched ->
                    // Read strictly (issue #1051). A definition the edits left malformed reads as none, and is not
                    // judged here: `patchConfigs` gates the edited slots next, and refuses it as what it is.
                    requireOperatorFieldsKept(c, patched[CCT.clientDef]?.firstOrNull()?.let { readClientDef(c, it).def })
                    requireCustomersKept(c, patched[CCT.customerDef], current[CCT.customerDef])
                }
            }
        }
    }
    if (dryRun) {
        // The bundles as they would be written, nothing stored (issue #1434), and what they would do to the data the
        // client stores -- live at once for a client without a sandbox, at publishing for one with -- so it is judged
        // whatever the client's tier.
        val wouldWrite = svc.dryRunPatchConfigs(c, patches)
        return mapOf(
            CFEP.dryRun to true,
            CFEP.configs to wouldWrite.map { bundleOf(c, it) },
            CFEP.impact to ConfigImpact.reportFor(c, c.client, wouldWrite, anyTier = true).toMap(),
        )
    }
    val written = svc.patchConfigs(c, trial = true, patches)
    return mapOf(CFEP.configs to written.map { bundleOf(c, it) }).withReloadIfAsked(c, request)
}

/**
 * Refuses an edit that says what its caller read in a way that cannot be judged (issue #1434): an `updatedAt` that is
 * not a moment, or both an `updatedAt` and [CFEP.expectNew] -- an entry cannot have been both there and not.
 */
private fun requireReadAsSaid(name: String, edit: Map<String, Any?>) {
    val stamp = edit[CFEP.expectUpdatedAt]
    if (stamp != null && stamp.toOptInstantOrNull() == null) {
        throw KdrException.mkInput("An edit of configuration '$name' gives '${CFEP.expectUpdatedAt}' as '$stamp', which is not a moment.")
    }
    if (stamp != null && edit[CFEP.expectNew] == true) {
        throw KdrException.mkInput(
            "An edit of configuration '$name' gives both '${CFEP.expectUpdatedAt}' and '${CFEP.expectNew}'; an entry was " +
                "either there when read, or not.",
        )
    }
}

/**
 * The [edits] of configuration [name] whose entries have changed since their caller read them (issue #1434), judged
 * against [latest] -- the configuration's latest revision, read under the lock -- by each entry's own `updatedAt`, to
 * the millisecond the store keeps. An edit saying nothing of what it read is not judged. A delete of an entry already
 * gone is not stale: it is what the delete was for.
 */
private fun staleEdits(name: String, edits: List<Map<String, Any?>>, pk: Map<String, List<String>>, latest: GedraConfigRow?): List<StaleEntry> {
    val stored = latest?.entries.orEmpty()
    return edits.mapNotNull { edit ->
        val expectNew = edit[CFEP.expectNew] == true
        val expected = edit[CFEP.expectUpdatedAt].toOptInstantOrNull()?.truncateToMs()
        if (!expectNew && expected == null) return@mapNotNull null
        // An unknown slot is the edit's own refusal, made as it is applied.
        val slot = edit[CFEP.slot].toOptStr() ?: return@mapNotNull null
        val keyFields = pk[slot] ?: return@mapNotNull null
        val data = edit[GE.data].toJsonMapOrEmpty()
        val entry = stored.firstOrNull { it[GE.traitId].toOptStr() == slot && addressesEntry(keyFields, it[GE.data].toJsonMapOrEmpty(), data) }
        val found = entry?.get(GE.updatedAt).toOptInstantOrNull()?.truncateToMs()
        val deleting = edit[GED.action].toOptStr() == GedraEditAction.deleteOrNoOp.name
        val isStale = when {
            expectNew -> entry != null
            entry == null -> !deleting
            else -> found != expected
        }
        if (!isStale) null else StaleEntry(name, slot, keyFields.associateWith { data[it] }, expected, found)
    }
}

/**
 * Refuses a **client administrator's** change to a field only the platform may set -- a field whose policy is
 * [ClientFieldEdit.platform] ([ClientFieldPolicies], issues #820, #1108) -- the write by which a client's own
 * administrator would raise its own client's authority, writing `audience: internal`, say, or move what it holds or
 * where it runs. An `allClients` administrator is the operator, on either surface. Judged against the client's
 * definition as it stands -- stored, or in source -- or, for a client with none, the least authority. Load trusts
 * what is stored, since this is the gate every client-scoped write passes.
 */
private fun requireOperatorFieldsKept(c: KdrCxt, proposed: ClientDef?) {
    if (proposed == null || AdminRules.canSeeAllClients(c)) return
    val stored = ClientStoredEdit.definitionHolder(c)?.entriesBySlot()?.get(CCT.clientDef)?.firstOrNull()
    val current = stored ?: ClientService.get(c).known(c.client)?.toInfo()
    val changed = ClientFieldPolicies.platformChangesBy(proposed.toInfo(), current)
    if (changed.isEmpty()) return
    val keep = changed.joinToString(", ") { field ->
        (current ?: ClientFieldPolicies.leastAuthority)[field]?.let { "$field '$it'" } ?: "$field unset"
    }
    val fields = if (changed.size <= 2) changed.joinToString(" and ") else "${changed.dropLast(1).joinToString(", ")}, and ${changed.last()}"
    throw KdrException(
        "Client '${c.client}''s $fields may be set only by a platform operator; a client administrator's write must " +
            "keep them as they are ($keep).",
        code = EXC.notAuthorized,
    )
}

/**
 * Refuses a change to the customers a configuration declares by anyone but a platform operator (issue #1107). Only
 * `hub`'s stored configuration may hold customers (`storedOwnershipProblem` refuses them anywhere else), and `hub`'s
 * own administrators are not thereby the platform's: a customer decides whose namespace a client id falls in, so
 * only an `allClients` administrator declares, changes, or removes one. A write handing back the customers as stored
 * passes, so the rest of the configuration stays editable.
 */
private fun requireCustomersKept(c: KdrCxt, proposed: List<Map<String, Any?>>?, current: List<Map<String, Any?>>?) {
    if (AdminRules.canSeeAllClients(c)) return
    // Compared as definitions, so whatever a stored entry carries beside its fields is no change.
    fun defs(entries: List<Map<String, Any?>>?): Set<Any> =
        entries.orEmpty().map { e -> runCatching { CustomerDef.fromInfo(e) }.getOrElse { e } }.toSet()
    if (defs(proposed) == defs(current)) return
    throw KdrException(
        "Client '${c.client}''s customers may be declared, changed or removed only by a platform operator.",
        code = EXC.notAuthorized,
    )
}

/**
 * Bulk-imports config bundles (issue #733). Each **client's** bundles are written together, in one transaction under
 * the client's config lock, and judged together by a trial reload (issue #843): a client's definition is spread
 * across its configs, so bundles that are only sound as a set -- one declaring the client, another the traits its
 * workflows collect -- must be judged as the set, and a client whose set is refused keeps none of it. One client's
 * refusal does not stop the others. On a non-test node a bundle's `testFeatures` are stripped and logged rather
 * than refused -- the other half of the single-client write's refuse. A bundle is held to the slot shapes as any
 * write is (issue #1052), judged against what **this** node already holds of it: a restore from another deployment
 * that carries an entry at fault is refused for that client, each path named, and is mended at its source --
 * deliberately, since an import that carried faults across would be how they spread. Affected clients are then
 * reloaded (unless asked not to) so the import is live and a brand-new client becomes present. Returns what was
 * written, stripped, reloaded, and could not be applied.
 */
private fun cfgImportBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val bundles = request[ACEP.bundlesField].toJsonListOfMaps()
    if (bundles.isEmpty()) {
        throw KdrException.mkInput("A config import must carry at least one bundle.")
    }
    val doReload = request[ACEP.reloadField] as? Boolean ?: true
    val svc = GedraConfigService.get(c)
    val isTest = c.instanceConfig.isTestInstance
    val written = mutableListOf<Map<String, Any?>>()
    val stripped = mutableListOf<Map<String, Any?>>()
    val failures = mutableListOf<Map<String, Any?>>()
    val affected = LinkedHashSet<String>()

    // First each bundle is read into a config on its own, so a malformed one is reported by name.
    class Prepared(val write: ConfigWrite, val strippedFeatures: List<String>)
    val preparedByClient = LinkedHashMap<String, MutableList<Prepared>>()
    val unpreparedClients = LinkedHashSet<String>()
    for (bundle in bundles) {
        val client = bundle[CFEP.client].toOptStr()?.trim()?.ifEmpty { null }
        val name = bundle[CFEP.name].toOptStr()
        try {
            if (client == null) throw KdrException.mkInput("A bundle must name its '${CFEP.client}'.")
            if (name == null) throw KdrException.mkInput("A bundle must name its '${CFEP.name}'.")
            // Each bundle's portable form resolved against its own client (issue #1435).
            val namespace = fromPortable(bundle[CFEP.namespaceField].toOptStr(), client)?.toString()
                ?: throw KdrException.mkInput("A bundle must name its '${CFEP.namespaceField}'.")
            var slots = slotsFromPortable(slotsOf(bundle[CFEP.slots], svc.knownSlots()), client)
            // Strip testFeatures off a test instance rather than refuse (issue #733): a whole restore is not
            // failed by one field. On a test instance they round-trip. Recorded (and logged) only once the write
            // succeeds, so a bundle that then fails is not also reported as stripped.
            var strippedFeatures: List<String> = emptyList()
            if (!isTest) {
                val (clean, features) = strippedOfTestFeatures(slots)
                if (features.isNotEmpty()) {
                    slots = clean
                    strippedFeatures = features
                }
            }
            val bound = c.mkSubContext("configImport", client)
            val stored = svc.readLatest(bound, GedraId.of(GedraConfigType.configDoc, client, name))?.entriesBySlot()
            val config = reassembleForWrite(bound, name, namespace, client, slots, stored)
            val impliedDelete = bundle[CFEP.impliedDelete] as? Boolean ?: true
            val prepared = Prepared(ConfigWrite(config, impliedDelete), strippedFeatures)
            preparedByClient.getOrPut(client) { mutableListOf() }.add(prepared)
        } catch (e: Throwable) {
            // One bad bundle is reported, not fatal (issue #733) -- the restore continues.
            failures.add(dropNulls(linkedMapOf(CFEP.client to client, CFEP.name to name, ACEP.message to importFailureText(e))))
            if (client != null) unpreparedClients.add(client)
        }
    }

    // Then each client's set is written as one. A client missing one of its bundles is not written at all: the set
    // is what is judged, and the rest of it may not be sound without the one that failed.
    for ((client, prepared) in preparedByClient) {
        val names = prepared.map { it.write.config.name }
        try {
            if (client in unpreparedClients) {
                throw KdrException.mkInput("Not imported: another of client '$client''s bundles could not be read.")
            }
            val rows = svc.writeConfigs(c, client, prepared.map { it.write }, trial = true)
            for ((row, prep) in rows.zip(prepared)) {
                val name = prep.write.config.name
                written.add(linkedMapOf(CFEP.client to client, CFEP.name to name, CFEP.version to row.version))
                if (prep.strippedFeatures.isNotEmpty()) {
                    stripped.add(linkedMapOf(CFEP.client to client, ACEP.features to prep.strippedFeatures))
                    LogStartup.info(c) {
                        "Config import stripped testFeatures ${prep.strippedFeatures} from client '$client' -- " +
                            "honored only on a test instance."
                    }
                }
            }
            affected.add(client)
        } catch (e: Throwable) {
            val message = importFailureText(e)
            for (name in names) {
                failures.add(linkedMapOf(CFEP.client to client, CFEP.name to name, ACEP.message to message))
            }
        }
    }
    val reloaded = mutableListOf<String>()
    if (doReload) {
        for (client in affected) {
            try {
                val result = GedraConfigReload.reloadClient(c, client)
                ClientSyncService.get(c).announceReload(c, result)
                reloaded.add(client)
                // A reload can *drop* a config it just wrote -- a namespace or trait-id clash the write-time check
                // (which sees only loaded owners) could not catch, e.g., two imported clients claiming one new
                // namespace. That is reported by the reload, not thrown, so surface it as a failure rather than
                // letting the client read as cleanly reloaded (issue #733 review).
                for (issue in result.issues) {
                    failures.add(linkedMapOf(CFEP.client to client, ACEP.message to "${issue.message} ${issue.degradedTo}"))
                }
            } catch (e: Throwable) {
                failures.add(linkedMapOf(CFEP.client to client, ACEP.message to "reload failed: ${e.message ?: "unknown error"}"))
            }
        }
    }
    return linkedMapOf(
        ACEP.written to written,
        ACEP.stripped to stripped,
        ACEP.failures to failures,
        ACEP.reloaded to reloaded,
    )
}

/**
 * What an import says of a bundle that failed: the error's whole chain of messages (a refusal that wraps the fault
 * it found says whose it is, and its cause what is wrong -- issue #1051), since `failures` carries text and nothing else.
 */
private fun importFailureText(e: Throwable): String =
    (e as? KdrException)?.fullMessage()?.ifEmpty { null } ?: e.message ?: "unknown error"

/**
 * A bundle's slots with `${CLD.testFeatures}` removed from its `clientDef` entry (issue #733), paired with the
 * features dropped -- the write-side analog of [GedraConfigRow.entriesForEmission], over raw input slots. Returns
 * the slots unchanged (and an empty list) when there is nothing to strip.
 */
fun strippedOfTestFeatures(
    slots: Map<String, List<Map<String, Any?>>>,
): Pair<Map<String, List<Map<String, Any?>>>, List<String>> {
    val clientDefs = slots[CCT.clientDef] ?: return slots to emptyList()
    val features = mutableListOf<String>()
    val cleaned = clientDefs.map { data ->
        val tf = data[CLD.testFeatures]
        if (tf != null) {
            features.addAll(tf.toJsonListOfStrings())
            data - CLD.testFeatures
        } else {
            data
        }
    }
    if (features.isEmpty()) return slots to emptyList()
    return (slots + (CCT.clientDef to cleaned)) to features
}

private fun cfgPublishBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val names = requireNames(request)
    val svc = GedraConfigService.get(c)
    // Publish refuses a class with no revision; surface that as a 404 rather than a 400, since to this caller a
    // config they cannot find is one that is not there.
    names.firstOrNull { svc.readLatest(c, configId(c, it)) == null }?.let {
        throw KdrException("No configuration '$it' for client '${c.client}'.", code = EXC.notFound)
    }
    val published = svc.publish(c, names.map { configId(c, it) }, trial = true, impact = impactGate(request))
    return mapOf(CFEP.configs to published.map { summaryOf(c, it) }).withReloadIfAsked(c, request)
}

private fun cfgImpactBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    return ConfigImpact.report(c, c.client, requireNames(request)).toMap()
}

/** A publish request's [IMP.acknowledgeImpact] as the gate it asks for (issue #935). */
private fun impactGate(request: Map<String, Any?>): ImpactGate =
    if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse

/** The publish endpoints' [IMP.acknowledgeImpact] input. */
private fun InputFieldsBuilder.acknowledgeImpactField() {
    field(
        IMP.acknowledgeImpact,
        "Publish although the impact report on the client's stored data finds something (issue #935); without it, " +
            "such a publish is refused with the report.",
    ) { type = SCT.boolean }
}

private fun cfgRevertBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    // As with publish, a config the caller cannot find reads as a 404 rather than the service's 400.
    if (GedraConfigService.get(c).readLatest(c, configId(c, name)) == null) {
        throw KdrException("No configuration '$name' for client '${c.client}'.", code = EXC.notFound)
    }
    return summaryOf(c, GedraConfigService.get(c).revertToEditable(c, configId(c, name)))
}

private const val deleteDescription =
    "Deletes an empty configuration (issue #1461): one whose latest revision and latest published revision hold no " +
        "entries -- so a published one is emptied, the empty revision published, and then deleted. Refused when either " +
        "holds entries (400), and when the configuration has been written since it was read (409, errorCode " +
        "'${CFEP.staleCode}'). Its revisions stop being loaded, listed, and read; one made again under its name starts " +
        "past every revision it had."

/** What a delete takes: the configuration, what its caller read of it, and whether to reload. */
private fun InputFieldsBuilder.deleteInput() {
    field(CFEP.name, "The configuration's name.", required = true)
    field(
        CFEP.expectUpdatedAt,
        "When the configuration's latest revision was written, as its caller read it: the delete is refused if it has " +
            "been written since.",
        required = true,
    ) { dateTime() }
    reloadRequestField()
}

private fun cfgDeleteBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val read = request[CFEP.expectUpdatedAt].toOptInstantOrNull()
        ?: throw KdrException.mkInput("A delete says when the configuration was written as it was read, in '${CFEP.expectUpdatedAt}'.")
    val deleted = GedraConfigService.get(c).deleteConfig(c, configId(c, name), read)
    return mapOf(CFEP.name to name, CFEP.version to deleted.version).withReloadIfAsked(c, request)
}

private fun cfgSlotEntriesBody(c: KdrCxt, request: Map<String, Any?>): List<Map<String, Any?>> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val row = GedraConfigService.get(c).readLatest(c, configId(c, name))
        ?: throw KdrException("No configuration '$name' for client '${c.client}'.", code = EXC.notFound)
    // Redacted at the row (issue #696), the same source the bundle read uses -- so testFeatures a cloned config
    // carries is not echoed off a test instance here either. The slot is named as a patch names it, not by the
    // stored envelope's `traitId` (issue #1352).
    val portable = request[PORT.portableField] == true
    return row.entriesForEmission(c.instanceConfig.isTestInstance).map { entry ->
        val slot = entry[GE.traitId]
        val data = if (portable && slot !in PORT.keptSlots) toPortable(entry[GE.data], c.client) else entry[GE.data]
        linkedMapOf(CFEP.slot to slot) + (entry - GE.traitId) + (GE.data to data)
    }
}

/** A patch edit with its data resolved from portable form for [client] (issue #1435), unless its slot is one kept as it is. */
private fun editFromPortable(edit: Map<String, Any?>, client: String): Map<String, Any?> =
    if (edit[CFEP.slot] in PORT.keptSlots || GE.data !in edit) edit else edit + (GE.data to fromPortable(edit[GE.data], client))

/** The [CFEP.revision] a bundle read takes (issue #1436): the latest revision, or the latest published one. */
private fun InputFieldsBuilder.revisionField() {
    field(CFEP.revision, "Which revision to answer: the latest (the default), or the latest published.") {
        option(CFEP.revisionLatest)
        option(CFEP.revisionPublished)
    }
}

/** The [PORT.portableField] a bundle or entries read takes (issue #1435). */
private fun InputFieldsBuilder.portableField() {
    field(
        PORT.portableField,
        "Answer in portable form (issue #1435): the client's own namespace written '${PORT.namespace}', so the data loads " +
            "as it is into another client, which every write resolves it for. The client's definition is as stored.",
    ) {
        type = SCT.boolean
        allowCoerce = true
    }
}

private fun cfgReloadBody(c: KdrCxt): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    return reloadAndAnnounce(c)
}

/**
 * Reloads the bound client's stored configuration on this node and announces it to its peers (issues #616, #618) --
 * what the reload endpoint does, and what a write, patch, or publish asking for [CFEP.reloadField] does once its
 * change is stored (issue #1156). One function, so the two cannot drift. Answers the reload's result.
 */
private fun reloadAndAnnounce(c: KdrCxt): Map<String, Any?> {
    val result = GedraConfigReload.reloadClient(c, c.client)
    // Announce to peers that this node reloaded newer configuration (issue #618), so a node behind catches up.
    ClientSyncService.get(c).announceReload(c, result)
    return linkedMapOf(
        CFEP.client to result.client,
        CFEP.loaded to result.loaded,
        CFEP.evictedTypes to result.evictedTypes,
        CFEP.issues to result.issues.map { it.toWireMap() },
    )
}

/**
 * This answer with the client reloaded, when [request] asked for it with [CFEP.reloadField] (issue #1156): the change
 * is stored by now, so the reload runs it, and how the reload went is added under the same key. Not asked, the answer
 * is as it was -- the change waits for a reload somebody asks for, as in production it should.
 */
private fun Map<String, Any?>.withReloadIfAsked(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> =
    if (request[CFEP.reloadField] == true) this + (CFEP.reloadField to reloadAndAnnounce(c)) else this

/** A config write's [CFEP.reloadField] input, on a write type declared as a whole (issue #1156). */
private fun SchTypeBuilder.reloadRequestProperty() {
    property(CFEP.reloadField, reloadRequestDescription) { type = SCT.boolean }
}

/** A config write's [CFEP.reloadField] input, on an endpoint's own fields (issue #1156). */
private fun InputFieldsBuilder.reloadRequestField() {
    field(CFEP.reloadField, reloadRequestDescription) { type = SCT.boolean }
}

private const val reloadRequestDescription =
    "Reload the client once the change is stored, as the reload endpoint does, so it is live at once (issue #1156). " +
        "Without it the change waits for a reload asked for -- the deliberate step a production change takes."

/** How the reload a write asked for went (issue #1156), present only when it asked. */
private fun SchTypeBuilder.reloadResultProperty() {
    property(CFEP.reloadField, "How the reload this call asked for went; absent when it asked for none.") {
        ref(CFEP.reloadResultType)
    }
}

private fun cfgPublishedOnlyBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val value = request[CFEP.publishedOnlyField] as? Boolean
        ?: throw KdrException.mkInput("'${CFEP.publishedOnlyField}' is required.")
    val effective = GedraConfigService.get(c).setPublishedOnly(c, c.client, value, trial = true)
    return linkedMapOf(CFEP.client to c.client, CFEP.publishedOnlyField to effective)
}

/**
 * Runs a `/clientAdmin/config` [body] where its configuration lives (issue #930): in the caller's own client, or --
 * called in a Shadow Sandbox, which holds none -- in the sandbox's parent, acting as the caller's user there
 * ([SandboxEdits.parentCxt]). A [mutating] call redirected so then reloads the parent, and with it the sandbox, so
 * the sandbox shows the change on its next request; a published-only parent still runs its published revision.
 */
private fun <T> inConfigScope(c: KdrCxt, mutating: Boolean = false, body: (KdrCxt) -> T): T {
    val parent = SandboxEdits.parentCxt(c, c.client) ?: return body(c)
    val out = body(parent)
    if (mutating) SandboxEdits.reloadParent(c, parent.client)
    return out
}

/** The revision-class id of the named config in the caller's own client -- never another client's. */
private fun configId(cxt: KdrCxt, name: String): GedraId = GedraId.of(GedraConfigType.configDoc, cxt.client, name)

private fun requireName(request: Map<String, Any?>): String = request[CFEP.name].toOptStr()
    ?: throw KdrException.mkInput("A configuration must be named by its '${CFEP.name}'.")

/** The [CFEP.names] a request names, at least one, each once (issue #1352). */
private fun requireNames(request: Map<String, Any?>): List<String> {
    val names = request[CFEP.names].toJsonListOrEmpty().mapNotNull { it.toOptStr()?.ifBlank { null } }
    if (names.isEmpty()) throw KdrException.mkInput("Name at least one configuration in '${CFEP.names}'.")
    names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
        throw KdrException.mkInput("Configuration '$it' is named more than once.")
    }
    return names
}

/**
 * Coerces a bundle's `slots` (slot -> array of entry objects) to the shape [reassembleGedraConfig] takes, after
 * refusing what would otherwise be dropped silently: a slot name that is not one of [knownSlots] (a typo, which
 * `reassembleGedraConfig` reads past by name), and a slot whose value is not an array (`toJsonListOfMaps` turns
 * a bare object into an empty list). Either is a lost slot -- and since a bundle write is authoritative, a lost
 * slot is a *deleted* one -- so it is a 400 rather than a quiet no-op.
 */
private fun slotsOf(raw: Any?, knownSlots: Set<String>): Map<String, List<Map<String, Any?>>> {
    val map = raw.toJsonMapOrEmpty()
    val unknown = map.keys - knownSlots
    if (unknown.isNotEmpty()) {
        throw KdrException.mkInput(
            "Unknown config slot(s): ${unknown.joinToString(", ")}. A bundle's slots are " +
                "${knownSlots.joinToString(", ")}.",
        )
    }
    return map.mapValues { (slot, v) ->
        if (v !is List<*>) {
            throw KdrException.mkInput("Config slot '$slot' must be an array of entries.")
        }
        v.toJsonListOfMaps()
    }
}

/**
 * The [CFEP.slots] a whole-bundle write takes (issue #1476): one array of entries per slot, each entry an object whose
 * shape is the gate's to judge and the reference's to say ([configReferenceLink]). Open to other keys so that an unknown
 * slot reaches the handler, whose refusal names the slots there are.
 */
private fun SchTypeBuilder.bundleSlotsProperty(slots: Collection<String>) {
    property(
        CFEP.slots,
        "The configuration's contents, one array of entries per config slot. What each slot's entries hold is in " +
            "$configReferenceLink.",
        required = true,
    ) {
        type = SCT.kObject
        for (slot in slots) {
            property(slot, "The slot's entries, each its data alone.") {
                type = SCT.array
                items { type = SCT.kObject }
            }
        }
        additionalProperties = true
    }
}

/** Where an endpoint's description sends a caller for what a configuration's slots hold (issue #1476). */
private const val configReferenceLink = "[the client configuration reference](/docs/reference/client-configuration.md)"

/**
 * The shared input of the per-slot config patch (issues #732, #1476): the configurations to edit, each its name and its
 * edits. An edit's envelope is typed -- its [slots], its action, what it says it read -- and its `data` is an object: a
 * slot's shape, and what an action needs of it, is the gate's to judge and the reference's to say ([configReferenceLink]),
 * since typed here it would refuse what the gate rightly accepts (a schema body naming a sibling type, a template
 * workflow's overlay).
 */
private fun InputFieldsBuilder.configPatchInput(slots: Collection<String>) {
    field(
        CFEP.configs,
        "The configurations to edit together, each its name and its edits, applied in order. What each slot's entries " +
            "hold, and what each action's data must carry, is in $configReferenceLink.",
        required = true,
    ) {
        type = SCT.array
        items {
            type = SCT.kObject
            property(CFEP.name, "The configuration's name. One not yet written is created.", required = true)
            property(CFEP.edits, "The edits to apply to it, in order.", required = true) {
                type = SCT.array
                items {
                    type = SCT.kObject
                    property(CFEP.slot, "The slot the entry is in.", required = true) { slots.forEach { option(it) } }
                    property(GED.action, "What to do with the entry.", required = true) { options(GedraEditAction.entries) }
                    property(
                        GE.data,
                        "The entry's data, carrying the slot's key fields, which say which entry is meant: the key alone " +
                            "for a delete, the fields to change for a merge, the whole entry for a replace. Only a delete " +
                            "from a slot with one entry may leave it out.",
                    ) { type = SCT.kObject }
                    property(
                        CFEP.expectUpdatedAt,
                        "The entry's updatedAt when the caller read it (issue #1434): the patch is refused (409, errorCode " +
                            "'${CFEP.staleCode}') if the entry has changed since, or is gone.",
                    ) { dateTime() }
                    property(
                        CFEP.expectNew,
                        "True when the caller read no such entry: the patch is refused (409, errorCode '${CFEP.staleCode}') " +
                            "if one exists now.",
                    ) {
                        type = SCT.boolean
                    }
                }
            }
        }
    }
    field(
        CFEP.dryRun,
        "Run the patch -- its checks, its edits, and the trial reload -- without storing it (issue #1434): the answer " +
            "is the configurations as they would be written, with '${CFEP.impact}', what they would do to the data the " +
            "client stores. A patch that would be refused is refused as the save would be. Nothing is stored or reloaded.",
    ) { type = SCT.boolean }
    reloadRequestField()
}

/** The [CFEP.names] a publish or an impact preview takes: one or more configurations, by name (issue #1352). */
private fun InputFieldsBuilder.namesField(description: String) {
    field(CFEP.names, description, required = true) {
        type = SCT.array
        // On the impact preview's GET it arrives as query text: a JSON array, or the names comma-separated.
        allowCoerce = true
        items { type = SCT.string }
    }
}

/**
 * Applies per-slot [edits] over the [current] slots of a config (issue #732), returning the new slot set --
 * the pure core of the config patch, so it is unit-testable without a database. Each edit names a [CFEP.slot]
 * (a config-trait id), a [GED.action], and its [GE.data]; the entry it addresses within the slot is the one
 * whose primary-key fields ([pkBySlot]) all match the edit's data, or -- for a single-instance slot with no
 * primary key, like `clientDef` -- the one entry there is. An unknown slot or action is a 400, not a silent
 * no-op. The three actions mirror [GedraEditAction]: delete-or-no-op, add-or-merge, add-or-replace.
 *
 * It does not validate the entry data: that is `reassembleGedraConfig`'s job over the whole result, which is
 * how the patch reuses the bundle write's validation rather than duplicating it.
 *
 * The same three actions and the same addressing rule (the entry an edit names is its primary-key values, carried
 * in the edit's own data) as a data patch's `GedraDataService.applyEdit`, shared with it through [applyTo],
 * [missingKeyFields] and [addressesEntry] (issue #909); the data side adds staleness, visibility-gate and stamping
 * concerns a config slot does not have.
 */
fun applyConfigSlotEdits(
    current: Map<String, List<Map<String, Any?>>>,
    edits: List<Map<String, Any?>>,
    pkBySlot: Map<String, List<String>>,
): Map<String, List<Map<String, Any?>>> {
    val out = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
    current.forEach { (slot, entries) -> out[slot] = entries.toMutableList() }
    for (edit in edits) {
        val slot = edit[CFEP.slot].toOptStr()
            ?: throw KdrException.mkInput("Each config edit must name its '${CFEP.slot}'.")
        val pk = pkBySlot[slot]
            ?: throw KdrException.mkInput("Unknown config slot '$slot'. The slots are ${pkBySlot.keys.joinToString(", ")}.")
        val actionName = edit[GED.action].toOptStr()
            ?: throw KdrException.mkInput("Config edit for '$slot' must name its '${GED.action}'.")
        val action = GedraEditAction.entries.firstOrNull { it.name == actionName }
            ?: throw KdrException.mkInput(
                "Unknown edit action '$actionName'. The actions are ${GedraEditAction.entries.joinToString(", ") { it.name }}.",
            )
        val data = edit[GE.data].toJsonMapOrEmpty()
        // A keyed slot's edit must carry every primary-key field, so it names one entry (issue #732 review). A
        // single-instance slot (empty pk) needs none.
        val missingKey = missingKeyFields(pk, data)
        if (missingKey.isNotEmpty()) {
            throw KdrException.mkInput(
                "Config edit for slot '$slot' is missing primary-key field(s) ${missingKey.joinToString(", ")}, " +
                    "which say which entry the edit is for.",
            )
        }
        val list = out.getOrPut(slot) { mutableListOf() }
        val idx = list.indexOfFirst { existing -> addressesEntry(pk, existing, data) }
        when (val result = action.applyTo(list.getOrNull(idx), data)) {
            KeyedEdit.NoOp -> {}
            KeyedEdit.Remove -> list.removeAt(idx)
            is KeyedEdit.Put -> if (idx >= 0) list[idx] = result.data else list.add(result.data)
        }
    }
    // Drop a slot the edits emptied, so the write does not carry an empty slot array.
    return out.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }
}

/**
 * A listing summary of one config revision, with the version of its class's latest published revision (issue #1001)
 * and what each holds (issue #1461) -- read from [publishedRevisions] when the caller has the client's at hand, as a
 * listing does.
 */
private fun summaryOf(
    cxt: KdrCxt,
    row: GedraConfigRow,
    publishedRevisions: Map<String, GedraConfigRow> = publishedRevisionsOf(cxt, row.client),
): Map<String, Any?> = dropNulls(
    linkedMapOf(
        CFEP.name to row.configId.baseId,
        CFEP.client to row.client,
        CFEP.version to row.version,
        CFEP.published to row.isPublished,
        CFEP.publishedAt to row.publishedAt,
        CFEP.publishedVersion to publishedRevisions[row.configId.baseId]?.version,
        CFEP.slotCount to row.entriesBySlot().size,
        CFEP.entryCount to row.entries.size,
        CFEP.publishedSlotCount to publishedRevisions[row.configId.baseId]?.entriesBySlot()?.size,
        CFEP.publishedEntryCount to publishedRevisions[row.configId.baseId]?.entries?.size,
        CFEP.createdAt to row.createdAt,
        CFEP.updatedAt to row.updatedAt,
        CFEP.issues to configIssuesOf(cxt, row),
    ),
)

/**
 * The issues this stored config holds on this node (issue #840): the problems forgiven in its definitions when the
 * node last loaded the client. Keyed by the config's revision class, which is the id a loaded config carries.
 */
private fun configIssuesOf(cxt: KdrCxt, row: GedraConfigRow): List<Map<String, Any?>> =
    ClientConfigIssues.get(cxt).issuesForConfig(row.client, row.configId.fullId).map { it.toWireMap() }

/** The `issues` property a stored-config summary and bundle carry (issue #840). */
private fun SchTypeBuilder.configIssuesProperty() {
    property(CFEP.issues, "Problems forgiven in this configuration's definitions when this node last loaded it (issue #840).", required = true) {
        type = SCT.array
        items { ref(CLD.configIssueTypeQualified) }
    }
}

/**
 * A whole config revision as a bundle: its summary plus its contents by slot. The slots come from
 * [GedraConfigRow.slotsForEmission], so `testFeatures` is stripped from the stored client definition off a test
 * instance at the row -- the one redaction point the trait-level read shares (issue #696).
 */
/** [row] as a whole bundle -- in portable form when [portable] (issue #1435), its own namespace written `client.@self`. */
private fun bundleOf(cxt: KdrCxt, row: GedraConfigRow, portable: Boolean = false): Map<String, Any?> = dropNulls(
    linkedMapOf(
        CFEP.name to row.configId.baseId,
        CFEP.namespaceField to row.resolvedNamespace().let { if (portable) toPortable(it, row.client) else it },
        CFEP.client to row.client,
        CFEP.version to row.version,
        CFEP.published to row.isPublished,
        CFEP.publishedAt to row.publishedAt,
        CFEP.slots to row.slotsForEmission(cxt.instanceConfig.isTestInstance).let { if (portable) slotsToPortable(it, row.client) else it },
        CFEP.createdAt to row.createdAt,
        CFEP.updatedAt to row.updatedAt,
        CFEP.issues to configIssuesOf(cxt, row),
    ),
)

/** Drops null-valued keys so an absent optional field (a null publish time, say) never reaches its validation. */
private fun dropNulls(map: Map<String, Any?>): Map<String, Any?> =
    map.filterValues { it != null }

/**
 * The **cross-client** admin surface over stored client configuration (issue #685): the same read/write/publish/
 * reload/tier operations as [CFEP], but full-scope. The paths lead with [SECT.admin], so the section gate takes
 * `admin` **and** the `allClients` capability -- a deployment-wide administrator managing *any* client, named in
 * the request, rather than confined to their own as the [CFEP] surface is. This is what lets a client's whole
 * configuration be created and driven over the API (including a brand-new client, whose `clientDef` slot written
 * here becomes present on the next reload), which the client-scoped surface cannot do -- you cannot be the
 * client-admin of a client that does not exist yet.
 *
 * Every handler runs the shared body ([cfgWriteBody] and friends) on a sub-context bound to the **named** client
 * ([adminConfigCxt]), so the logic, the `testFeatures` write-refuse, and the #696 emission redactions are exactly
 * the client-scoped surface's -- one implementation under a different bound client. The whole-bundle write is the
 * "post the entire definition, replace what is there" create/update variant; the per-slot PATCH is
 * [ACEP.bundlePatch], and the bulk import/clone/restore path is [ACEP.import] (issue #733).
 */
@Suppress("DuplicatedCode")
fun adminGedraConfigSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, ACEP.namespace) {
    val slots = coreConfigTraits(cxt).configTraits.keys
    // What a caller sends to write a named client's bundle: the client, plus the bundle-write fields the
    // client-scoped surface takes (its `client` is implicit there, the caller's own).
    type(ACEP.writeType) {
        type = SCT.kObject
        description = "A configuration to write for a named client, as a whole bundle."
        // Free text, not a choice of clients (issue #1000): writing a bundle for a client id no node knows yet is how
        // a brand-new client is created over the API.
        property(CFEP.client, "The client to write the configuration for.", required = true)
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.namespaceField, "The namespace the configuration's generated types live in.", required = true)
        bundleSlotsProperty(slots)
        property(CFEP.impliedDelete, "Whether a slot the bundle omits is dropped (true, the default) or carried forward (false).") {
            type = SCT.boolean
        }
        reloadRequestProperty()
    }

    listEndpoint(
        ACEP.bundles,
        "Lists a named client's configurations, each as a summary of its latest revision (issue #685).",
        outputRef = "${CFEP.namespace}.${CFEP.summaryType}",
        noLimit = true,
        inputFields = { field(CFEP.client, "The client whose configurations to list.", required = true) { clientAttribute() } },
    ) { c, request -> cfgBundlesBody(adminConfigCxt(c, request)) }

    itemEndpoint(
        ACEP.bundle,
        "Fetches one of a named client's configurations as a whole bundle (issue #685).",
        HttpMethod.GET,
        outputRef = "${CFEP.namespace}.${CFEP.bundleType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true) { clientAttribute() }
            field(CFEP.name, "The configuration's name.", required = true)
            revisionField()
            portableField()
        },
    ) { c, request -> cfgBundleBody(adminConfigCxt(c, request), request) }

    itemEndpoint(
        ACEP.editor,
        "Everything the bundle editor page draws for a named client, in one read (issue #1436), as the client-scoped " +
            "read answers it.",
        HttpMethod.GET,
        outputRef = "${CFEP.namespace}.${CFEP.editorType}",
        inputFields = {
            field(CFEP.client, "The client whose configuration to read.", required = true) { clientAttribute() }
        },
    ) { c, request -> cfgEditorBody(adminConfigCxt(c, request)) }

    generalEndpoint(
        ACEP.bundleWrite,
        "Writes a named client's configuration from a whole bundle -- create or replace (issue #685). What each slot's " +
            "entries hold is in $configReferenceLink.",
        HttpMethod.POST,
        // Its input may be a whole configuration, past the default body limit (issue #1446).
        largeBody = true,
        outputRef = "${CFEP.namespace}.${CFEP.bundleType}",
        inputRef = ACEP.writeType,
        // The one endpoint that may name a not-yet-existing client: writing a `clientDef` slot for a fresh id is
        // how a brand-new client is created (made present by the next reload), so it skips the existence guard.
    ) { c, request -> cfgWriteBody(adminConfigCxt(c, request, requireExisting = false), request) }

    generalEndpoint(
        ACEP.bundlePatch,
        "Edits the slot entries of one or more of a named client's configurations -- per-slot add/replace/merge/" +
            "delete over each latest revision, in one transaction judged together (issues #732, #1352); with a dry run " +
            "and a staleness check, as the client-scoped patch has (issue #1434). What each slot's entries hold, and what " +
            "each action's data must carry, is in $configReferenceLink.",
        HttpMethod.POST,
        // Its input may be a whole configuration, past the default body limit (issue #1446).
        largeBody = true,
        outputRef = "${CFEP.namespace}.${CFEP.patchResultType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true) { clientAttribute() }
            configPatchInput(slots)
        },
    ) { c, request -> cfgPatchBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.bundlePublish,
        "Publishes the latest editable revision of one or more of a named client's configurations, together " +
            "(issues #685, #1352).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.publishResultType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configurations.", required = true) { clientAttribute() }
            namesField("The configurations to publish together.")
            acknowledgeImpactField()
            reloadRequestField()
        },
    ) { c, request -> cfgPublishBody(adminConfigCxt(c, request), request) }

    itemEndpoint(
        ACEP.bundleImpact,
        "What publishing a named client's configurations together would do to the data that client already stores " +
            "(issues #935, #1352).",
        HttpMethod.GET,
        outputRef = "${CFEP.namespace}.${IMP.reportType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configurations.", required = true) { clientAttribute() }
            namesField("The configurations whose publishing together is judged.")
        },
    ) { c, request -> cfgImpactBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.bundleRevert,
        "Reopens a named client's published configuration for editing (issue #734): mints a new editable " +
            "revision copied from the published head, which stays immutable. A no-op when already editable; " +
            "refused for a published-only client.",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.summaryType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true) { clientAttribute() }
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgRevertBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.bundleDelete,
        deleteDescription,
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.deleteResultType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true) { clientAttribute() }
            deleteInput()
        },
    ) { c, request -> cfgDeleteBody(adminConfigCxt(c, request), request) }

    listEndpoint(
        ACEP.slotEntries,
        "The interior of one of a named client's configurations: its stored slot entries (issues #685, #1352).",
        outputRef = "${CFEP.namespace}.${CFEP.slotEntryType}",
        noLimit = true,
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true) { clientAttribute() }
            field(CFEP.name, "The configuration's name.", required = true)
            portableField()
        },
    ) { c, request -> cfgSlotEntriesBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.reload,
        "Reloads a named client's stored configuration on this node, and announces the sync marker (issue #685).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.reloadResultType}",
        inputFields = { field(CFEP.client, "The client to reload.", required = true) { clientAttribute() } },
    ) { c, request -> cfgReloadBody(adminConfigCxt(c, request)) }

    generalEndpoint(
        ACEP.publishedOnly,
        "Sets whether a named client consumes only its published configuration in this environment (issue #685).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.tierType}",
        inputFields = {
            field(CFEP.client, "The client to set the tier for.", required = true) { clientAttribute() }
            field(CFEP.publishedOnlyField, "Whether to consume published configuration only.", required = true) { type = SCT.boolean }
        },
    ) { c, request -> cfgPublishedOnlyBody(adminConfigCxt(c, request), request) }

    type(ACEP.importResultType) {
        type = SCT.kObject
        description = "What a bulk config import wrote, stripped, reloaded, and could not apply (issue #733)."
        property(ACEP.written, "The configurations written, each by client, name and resulting version.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(CFEP.client, "The owning client.", required = true)
                property(CFEP.name, "The configuration's name.", required = true)
                property(CFEP.version, "The resulting revision version.", required = true) { type = SCT.integer }
            }
        }
        property(ACEP.stripped, "The clients whose testFeatures were stripped on this non-test node, with what was dropped.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(CFEP.client, "The client whose definition carried testFeatures.", required = true)
                property(ACEP.features, "The testFeatures that were stripped.", required = true) { type = SCT.array; items { type = SCT.string } }
            }
        }
        property(ACEP.failures, "The bundles that could not be applied, each with why -- the rest still landed.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(CFEP.client, "The client the failed bundle named.")
                property(CFEP.name, "The configuration name the failed bundle named.")
                property(ACEP.message, "Why it failed.", required = true)
            }
        }
        property(ACEP.reloaded, "The clients reloaded so their imported configuration is live.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    // Bulk import / clone / restore (issue #733, #685 Slice C): write a whole set of client configurations at
    // once. Each client's bundles are written and judged as one set (issue #843) -- a refused client is reported in
    // `failures` and the other clients still land, so a restore is not aborted by one -- and `testFeatures` is
    // stripped and logged on a non-test node rather than refused (the split the single-client write's refuse is the
    // other half of). Affected clients are reloaded so the import goes live, which is how a brand-new client becomes
    // present.
    generalEndpoint(
        ACEP.import,
        "Bulk-imports a set of client configurations (issue #733): each client's bundles written and judged as one " +
            "set, testFeatures stripped+logged off a test instance, affected clients reloaded.",
        HttpMethod.POST,
        // Its input may be a whole configuration, past the default body limit (issue #1446).
        largeBody = true,
        outputRef = "${ACEP.namespace}.${ACEP.importResultType}",
        inputFields = {
            field(ACEP.bundlesField, "The configuration bundles to import, each `{${CFEP.client}, ${CFEP.name}, " +
                "${CFEP.namespaceField}, ${CFEP.slots}, ${CFEP.impliedDelete}?}`.", required = true) {
                type = SCT.array
                items { type = SCT.kObject }
            }
            field(ACEP.reloadField, "Whether to reload the affected clients after writing (default true).") { type = SCT.boolean }
        },
    ) { c, request -> cfgImportBody(c, request) }

    // --- creating a client (issue #1054) ---------------------------------------------------------------------------
    //
    // Here, with the surface that writes a named client's configuration, because that is what it does: the bundle
    // write above for one slot, with its input typed. The input is the definition's own declaration for the fields a
    // create takes, so a form can be drawn from it and nothing is declared twice.
    ClientDef.defineCreateType(this)

    // The templates a client made from data may extend: defined in source code, and of the template kind -- what
    // the load's own rule admits (`extendsProblem`). A choice list for a form; the rule is still the load's.
    optionsProvider(CLD.templateOptions) { c, _ ->
        val clients = ClientService.get(c)
        clients.declaredClients()
            .filter { it.usageType == ClientUsageType.template && clients.originOf(it.clientId) == GedraConfigOrigin.source }
            .map { SchOption(it.clientId, clientLabel(it.clientId, it.name)) }
    }

    // The customers a client may be filed under (issue #1107), for a form; whether the id falls in the chosen one's
    // namespace is the create's to judge.
    optionsProvider(CUST.customerOptions) { c, _ ->
        ClientService.get(c).customers.values.map { SchOption(it.def.customerId, clientLabel(it.def.customerId, it.def.name)) }
    }

    type(CLD.createResultTypeName) {
        type = SCT.kObject
        description = "What creating a client did (issue #1054): where its definition was written, the definition as stored, and whether this node carries the client."
        clientCreateResultFields()
        property(CLD.customer, "The customer made with the client, as stored, when one was asked for.") {
            type = SCT.kObject
            CustomerDef.declareFields(this)
        }
        property(
            CLD.customerFailure,
            "Present when the client and its new customer were made but the client could not be named the " +
                "customer's root: why. The root is named through the customer's configuration once the cause is mended.",
        )
    }

    generalEndpoint(
        ADEP.clientCreate,
        "Creates a client (issue #1054): writes its definition as its first stored configuration ('" +
            CLD.definitionConfigName + "'), publishes it and loads the client, so it is present at once wherever " +
            "this node's environment is one it is enabled in. The definition is held to what any written definition " +
            "is -- its schema, a domain no other client holds, a trial load that judges its id, its environments " +
            "and the template it extends -- and refused for the same reasons, in the same words. Refused with a 409 " +
            "for an id that is taken, by a client in source code or in stored configuration. With '" + CLD.newCustomer +
            "' it makes a customer too, whose id is the client's and whose root the client is (issue #1107). Unless '" +
            CLD.starterWorkflows + "' is false, it starts with a creation workflow and a survey made from the " +
            "deployment's templates, and includes the trait they collect (issue #1346). What else a definition does not " +
            "hold at creation -- its other traits and workflows, its test features -- is added through its " +
            "configuration afterwards.",
        HttpMethod.POST,
        outputRef = CLD.createResultTypeName,
        inputRef = CLD.createTypeName,
    ) { c, request ->
        val result = ClientCreate.create(c, request)
        dropNulls(result.wireFields() + mapOf(CLD.customer to result.customer, CLD.customerFailure to result.customerFailure))
    }

    // --- retiring a client (issue #1111) ----------------------------------------------------------------------------
    generalEndpoint(
        ADEP.clientRetirementSet,
        "Retires a client, or brings a retired one back (issue #1111). A retired client keeps everything it stored and " +
            "is inert: absent in every environment, its users unable to sign in, their open sessions no longer " +
            "honored, and its sandbox with it. Brought back, it is as it was. Takes effect live, whatever the client's " +
            "tier. Refused for a client defined in source code (retired there), a sandbox (retired with its parent), " +
            "a customer's root client, and a definition whose configuration carries unpublished changes.",
        HttpMethod.POST,
        outputRef = CLD.retirementResultTypeName,
        inputFields = {
            field(CLD.client, "The client to retire or bring back.", required = true)
            field(CLD.retired, "True to retire the client, false to bring it back.", required = true) {
                type = SCT.boolean
                allowCoerce = false
            }
        },
    ) { c, request ->
        val result = ClientRetirement.set(c, request[CLD.client].toOptStr().orEmpty(), request[CLD.retired] == true)
        linkedMapOf(
            CLD.client to result.client,
            CLD.retired to result.retired,
            CLD.definition to result.info,
            CLD.present to result.present,
        )
    }

    type(CLD.retirementResultTypeName) {
        type = SCT.kObject
        description = "What retiring or bringing back a client did (issue #1111)."
        property(CLD.client, "The client.", required = true)
        property(CLD.retired, "Whether it is retired now.", required = true) { type = SCT.boolean }
        property(CLD.definition, "The client's stored definition.", required = true) { ref(CLD.infoTypeQualified) }
        property(CLD.present, "Whether this node carries the client now.", required = true) { type = SCT.boolean }
    }
}

/**
 * A sub-context bound to the [CFEP.client] a cross-client `/admin` config request names, so the shared bodies --
 * which key off `cxt.client` -- act on that client while ownership/audit stamp from it and the caller stays the
 * actor. The `admin` section gate has already confined the caller to `allClients`, so naming any client is theirs
 * to do; an absent client is a 400.
 *
 * [requireExisting] (the default) refuses a client that is neither known -- declared anywhere, present here or
 * not -- nor has any stored configuration --
 * so a typo'd id on the tier or reload endpoints reads as a 404 rather than writing an orphan tier row or
 * reporting a no-op reload as success (issue #685 review). The bundle write passes it `false`, since writing a
 * `clientDef` slot for a fresh id is exactly how a new client is created; a client written but not yet reloaded
 * is admitted by the stored-config half, so the create-then-reload flow still works.
 */
private fun adminConfigCxt(c: KdrCxt, request: Map<String, Any?>, requireExisting: Boolean = true): KdrCxt {
    val client = request.getReqNonBlankStr(CFEP.client)
    val ac = c.mkSubContext("adminConfig", client)
    if (requireExisting && !GedraConfigService.get(ac).clientExists(ac)) {
        throw KdrException(
            "No client '$client': it is not present and has no stored configuration. Write its configuration first.",
            code = EXC.notFound,
        )
    }
    return ac
}

/**
 * What every client create answers with (issues #1054, #1112): the client, where its definition was written, the
 * definition as stored, whether this node carries it, what its load forgave, and why loading it failed when it did.
 * The operator's create and a root administrator's both lead with these.
 */
internal fun SchTypeBuilder.clientCreateResultFields() {
    property(CLD.client, "The client created.", required = true)
    property(COV.configName, "The stored configuration its definition was written to.", required = true)
    property(CLD.definition, "The client's stored definition.", required = true) { ref(CLD.infoTypeQualified) }
    property(
        CLD.present,
        "Whether this node carries the client now. False for one not enabled in this node's environment: it is " +
            "stored, and present wherever it is enabled.",
        required = true,
    ) { type = SCT.boolean }
    property(CLD.issues, "What the load found in the client's configuration and forgave.", required = true) {
        type = SCT.array
        items { ref(CLD.configIssueTypeQualified) }
    }
    property(
        CLD.loadFailure,
        "Present when the client was created -- stored and published -- but loading it on this node failed: " +
            "why. The create is not to be repeated; the client is reloaded once the cause is mended.",
    )
}

/** [clientCreateResultFields]' values for this create, the load failure absent when there was none. */
internal fun ClientCreate.Result.wireFields(): Map<String, Any?> = dropNulls(
    linkedMapOf(
        CLD.client to client,
        COV.configName to configName,
        CLD.definition to info,
        CLD.present to present,
        CLD.issues to issues.map { it.toWireMap() },
        CLD.loadFailure to loadFailure,
    ),
)
