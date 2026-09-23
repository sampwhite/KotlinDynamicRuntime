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
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.util.getReqNonBlankStr
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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
 *  - **Trait-level** ([CFEP.traits]) -- the interior, the stored config-trait entries with their per-slot
 *    accounting (each carries its own `createdAt`/`updatedAt`), so *when and by whom a single slot changed* is
 *    inspectable. This is a read view; the matching per-slot **edit** is [CFEP.bundlePatch] (issue #732).
 *
 * ### Authorization: client-scoped admin
 *
 * The endpoints live in the **`clientAdmin`** section (their paths lead with [SECT.clientAdmin]), so they take
 * [com.dynamicruntime.common.http.request.ROLE.admin] confined by scope -- the same gate the client-scoped user
 * administration uses (#466). A configuration belongs to a client, so every endpoint acts within the caller's
 * own client ([KdrCxt.client]): the config id is built from that client and the supplied name, so a caller
 * cannot read or write another client's configuration by naming it.
 *
 * ### What is not here
 *
 * Loading a written config at boot (#614), the two-revision cache (#615), reload (#616) and protection tiers
 * (#617). Because nothing loads a stored config yet, the end-to-end browser round trip is deferred to #614, as
 * the issue allows; these endpoints are verified by service- and HTTP-level tests.
 */
@Suppress("ConstPropertyName")
object CFEP {
    /** The schema namespace the config-endpoint types live in (distinct from the `clientAdmin` section path). */
    const val namespace = "clientAdminConfig"

    // --- paths (all in the `clientAdmin` section, so the section gate is the path prefix) ---
    const val bundles = "/${SECT.clientAdmin}/config/bundles"
    const val bundle = "/${SECT.clientAdmin}/config/bundle"
    const val bundleWrite = "/${SECT.clientAdmin}/config/bundle/write"
    const val bundlePatch = "/${SECT.clientAdmin}/config/bundle/patch"
    const val bundlePublish = "/${SECT.clientAdmin}/config/bundle/publish"
    const val bundleRevert = "/${SECT.clientAdmin}/config/bundle/revert"
    const val traits = "/${SECT.clientAdmin}/config/traits"
    const val reload = "/${SECT.clientAdmin}/config/reload"
    const val publishedOnly = "/${SECT.clientAdmin}/config/publishedOnly"

    // --- type names ---
    const val bundleType = "ConfigBundle"
    const val bundleWriteType = "ConfigBundleWrite"
    const val summaryType = "ConfigSummary"
    const val traitEntryType = "ConfigTraitEntry"
    const val reloadResultType = "ConfigReloadResult"
    const val tierType = "ConfigTier"

    // --- field names (each matches its value) ---
    const val name = "name"
    const val namespaceField = "namespace"
    const val client = "client"
    const val version = "version"
    const val published = "published"
    const val publishedAt = "publishedAt"
    const val slots = "slots"
    const val impliedDelete = "impliedDelete"
    const val edits = "edits"
    const val slot = "slot"
    const val entries = "entries"
    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"
    const val loaded = "loaded"
    const val evictedTypes = "evictedTypes"
    const val issues = "issues"
    const val publishedOnlyField = "publishedOnly"
}

@Suppress("DuplicatedCode")
fun gedraConfigSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, CFEP.namespace) {

    // A config's identity and accounting, without its contents -- what a listing row shows.
    type(CFEP.summaryType) {
        type = SCT.kObject
        description = "One stored configuration: its identity, current version and whether it is published."
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.client, "The owning client.", required = true)
        property(CFEP.version, "The latest revision's version number.", required = true) { type = SCT.integer }
        property(CFEP.published, "Whether the latest revision has been published.", required = true) { type = SCT.boolean }
        property(CFEP.publishedAt, "When the latest revision was published; absent while it is still editable.") { dateTime() }
        property(CFEP.createdAt, "When this revision was created.") { dateTime() }
        property(CFEP.updatedAt, "When this revision was last written.") { dateTime() }
        configIssuesProperty()
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
        property(CFEP.slots, "The configuration's contents, one array of entries per config slot.", required = true) {
            type = SCT.kObject
        }
        property(CFEP.impliedDelete, "Whether a slot the bundle omits is dropped (true, the default) or carried forward (false).") {
            type = SCT.boolean
        }
    }

    // One stored config-trait entry, its envelope carried whole so the per-slot accounting is visible.
    type(CFEP.traitEntryType) {
        type = SCT.kObject
        description = "One stored config-trait entry: the slot it belongs to, its data, and its own accounting."
        property(GE.traitId, "The config slot this entry belongs to.", required = true)
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
        outputRef = CFEP.summaryType,
        noLimit = true,
    ) { c, _ -> cfgBundlesBody(c) }

    itemEndpoint(
        CFEP.bundle,
        "Fetches one configuration's latest revision as a whole bundle.",
        HttpMethod.GET,
        outputRef = CFEP.bundleType,
        inputFields = {
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgBundleBody(c, request) }

    generalEndpoint(
        CFEP.bundleWrite,
        "Writes a configuration from a whole bundle, applying the version/publish and diff-before-stamp rules.",
        HttpMethod.POST,
        outputRef = CFEP.bundleType,
        inputRef = CFEP.bundleWriteType,
    ) { c, request -> cfgWriteBody(c, request) }

    generalEndpoint(
        CFEP.bundlePatch,
        "Edits a configuration's interior slot entries individually (issue #732): per-slot add/replace/merge/" +
            "delete, addressed by slot + primary key, applied over the latest revision. The rest of the config " +
            "is left as it was.",
        HttpMethod.POST,
        outputRef = CFEP.bundleType,
        inputFields = { configPatchInput() },
    ) { c, request -> cfgPatchBody(c, request) }

    generalEndpoint(
        CFEP.bundlePublish,
        "Publishes a configuration's latest editable revision.",
        HttpMethod.POST,
        outputRef = CFEP.summaryType,
        inputFields = {
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgPublishBody(c, request) }

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
    ) { c, request -> cfgRevertBody(c, request) }

    listEndpoint(
        CFEP.traits,
        "The interior of one configuration: its stored config-trait entries, each with its own accounting.",
        outputRef = CFEP.traitEntryType,
        noLimit = true,
        inputFields = {
            field(CFEP.name, "The configuration's name.", required = true)
        },
        // The trait-level VIEW; the matching per-slot **edit** is `bundle/patch` (issue #732), which applies the
        // edits over the current slots and writes the whole set through the validated bundle write rather than
        // touching a raw slot -- so it needs no config-trait validation union of its own. This view makes the
        // per-slot accounting legible.
    ) { c, request -> cfgTraitsBody(c, request) }

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

    // The mechanism the multi-node sync trigger (#618) will call, exposed for an administrator to call by hand:
    // this node re-reads the caller's client's stored configuration and rebuilds that client's derived state
    // without a restart (issue #616). Per client only -- the caller's own, as every endpoint here is.
    generalEndpoint(
        CFEP.reload,
        "Reloads this client's stored configuration on this node, without a restart.",
        HttpMethod.POST,
        outputRef = CFEP.reloadResultType,
    ) { c, _ -> cfgReloadBody(c) }

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
    // refused for a `staticConfig` client, whose tier is fixed in source. The runtime effect follows on the
    // next reload -- this records the state, `POST config/reload` rebuilds against it.
    generalEndpoint(
        CFEP.publishedOnly,
        "Sets whether this client consumes only its published configuration on nodes in this environment.",
        HttpMethod.POST,
        outputRef = CFEP.tierType,
        inputFields = {
            field(CFEP.publishedOnlyField, "Whether to consume published configuration only.", required = true) { type = SCT.boolean }
        },
    ) { c, request -> cfgPublishedOnlyBody(c, request) }
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
    return GedraConfigService.get(c).listConfigs(c).map { summaryOf(c, it) }
}

private fun cfgBundleBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val row = GedraConfigService.get(c).readLatest(c, configId(c, name))
        ?: throw KdrException("No configuration '$name' for client '${c.client}'.", code = EXC.notFound)
    return bundleOf(c, row)
}

private fun cfgWriteBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val namespace = request[CFEP.namespaceField].toOptStr()
        ?: throw KdrException.mkInput("A configuration bundle must name its '${CFEP.namespaceField}'.")
    val slots = slotsOf(request[CFEP.slots], GedraConfigService.get(c).knownSlots())
    // Reassemble the bundle into a GedraConfig (which re-runs the builder, so its contents are validated as
    // source would be), then write it. The config id is built from `c.client`, so on the `/admin` surface the
    // bound client is what the bundle is filed under -- an unwritten client id here is how a brand-new client is
    // created over the API (its `clientDef` slot, made present by the next reload).
    val config = reassembleGedraConfig(c, name, namespace, c.client, slots)
    // Authoritative by default: a bundle is the whole configuration, so a slot the bundle omits is dropped, as
    // the write service defaults. A caller doing a partial, additive write sends `impliedDelete = false`.
    val impliedDelete = request[CFEP.impliedDelete] as? Boolean ?: true
    return bundleOf(c, GedraConfigService.get(c).writeConfig(c, config, impliedDelete))
}

private fun cfgPatchBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val edits = request[CFEP.edits].toJsonListOfMaps()
    if (edits.isEmpty()) {
        throw KdrException.mkInput("A config patch must carry at least one edit.")
    }
    val svc = GedraConfigService.get(c)
    val pk = svc.configSlotPrimaryKeys()
    // The edits run through `patchConfig`, which applies them to the latest revision read **under the write
    // lock** and reassembles+writes it there -- so a per-slot patch reuses the validated bundle write (no
    // separate validation path) and cannot be computed off a stale read and clobber a concurrent write (issue
    // #732). `patchConfig` reads the raw slots, so an untouched `testFeatures` round-trips on a test instance
    // and, off one, the write is refused rather than silently dropping it (the #685 explicit-write rule). The
    // 404 for a missing config is `patchConfig`'s.
    return bundleOf(c, svc.patchConfig(c, configId(c, name)) { current -> applyConfigSlotEdits(current, edits, pk) })
}

/**
 * Bulk-imports config bundles (issue #733), each applied independently so one bad bundle does not abort the
 * rest. On a non-test node a bundle's `testFeatures` are stripped and logged rather than refused -- the other
 * half of the single-client write's refuse. Affected clients are then reloaded (unless asked not to) so the
 * import is live and a brand-new client becomes present. Returns what was written, stripped, reloaded, and could
 * not be applied.
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
    for (bundle in bundles) {
        val client = bundle[CFEP.client].toOptStr()?.trim()?.ifEmpty { null }
        val name = bundle[CFEP.name].toOptStr()
        try {
            if (client == null) throw KdrException.mkInput("A bundle must name its '${CFEP.client}'.")
            if (name == null) throw KdrException.mkInput("A bundle must name its '${CFEP.name}'.")
            val namespace = bundle[CFEP.namespaceField].toOptStr()
                ?: throw KdrException.mkInput("A bundle must name its '${CFEP.namespaceField}'.")
            var slots = slotsOf(bundle[CFEP.slots], svc.knownSlots())
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
            val bcxt = c.mkSubContext("configImport", client)
            val config = reassembleGedraConfig(bcxt, name, namespace, client, slots)
            val impliedDelete = bundle[CFEP.impliedDelete] as? Boolean ?: true
            val row = svc.writeConfig(bcxt, config, impliedDelete)
            written.add(linkedMapOf(CFEP.client to client, CFEP.name to name, CFEP.version to row.version))
            if (strippedFeatures.isNotEmpty()) {
                stripped.add(linkedMapOf(CFEP.client to client, ACEP.features to strippedFeatures))
                LogStartup.info(c) {
                    "Config import stripped testFeatures $strippedFeatures from client '$client' -- honored only on a test instance."
                }
            }
            affected.add(client)
        } catch (e: Throwable) {
            // One bad bundle is reported, not fatal (issue #733) -- the restore continues.
            failures.add(dropNulls(linkedMapOf(CFEP.client to client, CFEP.name to name, ACEP.message to (e.message ?: "unknown error"))))
        }
    }
    val reloaded = mutableListOf<String>()
    if (doReload) {
        for (client in affected) {
            try {
                val result = GedraConfigReload.reloadClient(c, client)
                ClientSyncService.get(c).announceAndMark(c, client, result.marker)
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
    val name = requireName(request)
    // Publish refuses a class with no revision; surface that as a 404 rather than a 400, since to this caller a
    // config they cannot find is one that is not there.
    if (GedraConfigService.get(c).readLatest(c, configId(c, name)) == null) {
        throw KdrException("No configuration '$name' for client '${c.client}'.", code = EXC.notFound)
    }
    return summaryOf(c, GedraConfigService.get(c).publish(c, configId(c, name)))
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

private fun cfgTraitsBody(c: KdrCxt, request: Map<String, Any?>): List<Map<String, Any?>> {
    AdminRules.requireClientAdministrator(c)
    val name = requireName(request)
    val row = GedraConfigService.get(c).readLatest(c, configId(c, name))
        ?: throw KdrException("No configuration '$name' for client '${c.client}'.", code = EXC.notFound)
    // Redacted at the row (issue #696), the same source the bundle read uses -- so testFeatures a cloned config
    // carries is not echoed off a test instance here either.
    return row.entriesForEmission(c.instanceConfig.isTestInstance)
}

private fun cfgReloadBody(c: KdrCxt): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val result = GedraConfigReload.reloadClient(c, c.client)
    // Announce to peers that this node reloaded newer configuration (issue #618), so a node behind catches up.
    ClientSyncService.get(c).announceAndMark(c, c.client, result.marker)
    return linkedMapOf(
        CFEP.client to result.client,
        CFEP.loaded to result.loaded,
        CFEP.evictedTypes to result.evictedTypes,
        CFEP.issues to result.issues.map { it.toWireMap() },
    )
}

private fun cfgPublishedOnlyBody(c: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
    AdminRules.requireClientAdministrator(c)
    val value = request[CFEP.publishedOnlyField] as? Boolean
        ?: throw KdrException.mkInput("'${CFEP.publishedOnlyField}' is required.")
    val effective = GedraConfigService.get(c).setPublishedOnly(c, c.client, value)
    return linkedMapOf(CFEP.client to c.client, CFEP.publishedOnlyField to effective)
}

/** The revision-class id of the named config in the caller's own client -- never another client's. */
private fun configId(cxt: KdrCxt, name: String): GedraId = GedraId.of(GedraConfigType.configDoc, cxt.client, name)

private fun requireName(request: Map<String, Any?>): String = request[CFEP.name].toOptStr()
    ?: throw KdrException.mkInput("A configuration must be named by its '${CFEP.name}'.")

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

/** The shared input of the per-slot config patch (issue #732): the config name, and the edits to apply. Each
 *  edit is an open object `{slot, action, data}` -- its `data` is a slot-specific shape validated on reassemble,
 *  not here. */
private fun InputFieldsBuilder.configPatchInput() {
    field(CFEP.name, "The configuration's name.", required = true)
    field(
        CFEP.edits,
        "The per-slot edits to apply, in order. Each is `{${CFEP.slot}, ${GED.action}, ${GE.data}}`: the slot " +
            "(a config-trait id), the action (${GedraEditAction.entries.joinToString(", ") { it.name }}), and the " +
            "entry data (carrying the slot's primary-key fields, which say which entry is meant).",
        required = true,
    ) {
        type = SCT.array
        items { type = SCT.kObject }
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
        // A keyed slot's edit must carry every primary-key field, so it names one entry: without this a missing
        // or misspelled key would silently no-op a delete or append a keyless entry instead of replacing the
        // intended one (issue #732 review). A single-instance slot (empty pk) needs none.
        val missingKey = pk.filter { data[it] == null }
        if (missingKey.isNotEmpty()) {
            throw KdrException.mkInput(
                "Config edit for slot '$slot' is missing primary-key field(s) ${missingKey.joinToString(", ")}, " +
                    "which say which entry the edit is for.",
            )
        }
        val list = out.getOrPut(slot) { mutableListOf() }
        // The addressed entry: pk-fields all equal (an empty pk -- a single-instance slot -- matches the one
        // entry there is, since `all` over no fields is true).
        val idx = list.indexOfFirst { existing -> pk.all { existing[it] == data[it] } }
        when (action) {
            GedraEditAction.deleteOrNoOp -> if (idx >= 0) list.removeAt(idx)
            GedraEditAction.addOrReplace -> if (idx >= 0) list[idx] = data else list.add(data)
            GedraEditAction.addOrMerge -> if (idx >= 0) list[idx] = list[idx] + data else list.add(data)
        }
    }
    // Drop a slot the edits emptied, so the write does not carry an empty slot array.
    return out.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }
}

/** A listing summary of one config revision. */
private fun summaryOf(cxt: KdrCxt, row: GedraConfigRow): Map<String, Any?> = dropNulls(
    linkedMapOf(
        CFEP.name to row.configId.baseId,
        CFEP.client to row.client,
        CFEP.version to row.version,
        CFEP.published to row.isPublished,
        CFEP.publishedAt to row.publishedAt,
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
private fun bundleOf(cxt: KdrCxt, row: GedraConfigRow): Map<String, Any?> = dropNulls(
    linkedMapOf(
        CFEP.name to row.configId.baseId,
        CFEP.namespaceField to row.resolvedNamespace(),
        CFEP.client to row.client,
        CFEP.version to row.version,
        CFEP.published to row.isPublished,
        CFEP.publishedAt to row.publishedAt,
        CFEP.slots to row.slotsForEmission(cxt.instanceConfig.isTestInstance),
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
 * "post the entire definition, replace what is there" create/update variant; the per-slot PATCH and the bulk
 * import/clone-restore paths the issue also names are later slices.
 */
@Suppress("ConstPropertyName")
object ACEP {
    /** The schema namespace the admin config-endpoint types live in (distinct from the `admin` section path). */
    const val namespace = "adminClientConfig"

    const val bundles = "/${SECT.admin}/client/config/bundles"
    const val bundle = "/${SECT.admin}/client/config/bundle"
    const val bundleWrite = "/${SECT.admin}/client/config/bundle/write"
    const val bundlePatch = "/${SECT.admin}/client/config/bundle/patch"
    const val bundlePublish = "/${SECT.admin}/client/config/bundle/publish"
    const val bundleRevert = "/${SECT.admin}/client/config/bundle/revert"
    const val traits = "/${SECT.admin}/client/config/traits"
    const val reload = "/${SECT.admin}/client/config/reload"
    const val publishedOnly = "/${SECT.admin}/client/config/publishedOnly"
    const val import = "/${SECT.admin}/client/config/import"

    /** The write input adds the named [CFEP.client] to what the client-scoped write takes. */
    const val writeType = "AdminConfigBundleWrite"
    const val importResultType = "ConfigImportResult"

    // --- import field names (each matches its value) ---
    const val bundlesField = "bundles"
    const val reloadField = "reload"
    const val written = "written"
    const val stripped = "stripped"
    const val failures = "failures"
    const val reloaded = "reloaded"
    const val features = "features"
    const val message = "message"
}

@Suppress("DuplicatedCode")
fun adminGedraConfigSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, ACEP.namespace) {
    // What a caller sends to write a named client's bundle: the client, plus the bundle-write fields the
    // client-scoped surface takes (its `client` is implicit there, the caller's own).
    type(ACEP.writeType) {
        type = SCT.kObject
        description = "A configuration to write for a named client, as a whole bundle."
        property(CFEP.client, "The client to write the configuration for.", required = true)
        property(CFEP.name, "The configuration's name (its id within the client).", required = true)
        property(CFEP.namespaceField, "The namespace the configuration's generated types live in.", required = true)
        property(CFEP.slots, "The configuration's contents, one array of entries per config slot.", required = true) {
            type = SCT.kObject
        }
        property(CFEP.impliedDelete, "Whether a slot the bundle omits is dropped (true, the default) or carried forward (false).") {
            type = SCT.boolean
        }
    }

    listEndpoint(
        ACEP.bundles,
        "Lists a named client's configurations, each as a summary of its latest revision (issue #685).",
        outputRef = "${CFEP.namespace}.${CFEP.summaryType}",
        noLimit = true,
        inputFields = { field(CFEP.client, "The client whose configurations to list.", required = true) },
    ) { c, request -> cfgBundlesBody(adminConfigCxt(c, request)) }

    itemEndpoint(
        ACEP.bundle,
        "Fetches one of a named client's configurations as a whole bundle (issue #685).",
        HttpMethod.GET,
        outputRef = "${CFEP.namespace}.${CFEP.bundleType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true)
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgBundleBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.bundleWrite,
        "Writes a named client's configuration from a whole bundle -- create or replace (issue #685).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.bundleType}",
        inputRef = ACEP.writeType,
        // The one endpoint that may name a not-yet-existing client: writing a `clientDef` slot for a fresh id is
        // how a brand-new client is created (made present by the next reload), so it skips the existence guard.
    ) { c, request -> cfgWriteBody(adminConfigCxt(c, request, requireExisting = false), request) }

    generalEndpoint(
        ACEP.bundlePatch,
        "Edits a named client's configuration slot entries individually -- per-slot add/replace/merge/delete " +
            "over the latest revision (issue #732).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.bundleType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true)
            configPatchInput()
        },
    ) { c, request -> cfgPatchBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.bundlePublish,
        "Publishes a named client's configuration's latest editable revision (issue #685).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.summaryType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true)
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgPublishBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.bundleRevert,
        "Reopens a named client's published configuration for editing (issue #734): mints a new editable " +
            "revision copied from the published head, which stays immutable. A no-op when already editable; " +
            "refused for a published-only client.",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.summaryType}",
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true)
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgRevertBody(adminConfigCxt(c, request), request) }

    listEndpoint(
        ACEP.traits,
        "The interior of one of a named client's configurations: its stored config-trait entries (issue #685).",
        outputRef = "${CFEP.namespace}.${CFEP.traitEntryType}",
        noLimit = true,
        inputFields = {
            field(CFEP.client, "The client that owns the configuration.", required = true)
            field(CFEP.name, "The configuration's name.", required = true)
        },
    ) { c, request -> cfgTraitsBody(adminConfigCxt(c, request), request) }

    generalEndpoint(
        ACEP.reload,
        "Reloads a named client's stored configuration on this node, and announces the sync marker (issue #685).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.reloadResultType}",
        inputFields = { field(CFEP.client, "The client to reload.", required = true) },
    ) { c, request -> cfgReloadBody(adminConfigCxt(c, request)) }

    generalEndpoint(
        ACEP.publishedOnly,
        "Sets whether a named client consumes only its published configuration in this environment (issue #685).",
        HttpMethod.POST,
        outputRef = "${CFEP.namespace}.${CFEP.tierType}",
        inputFields = {
            field(CFEP.client, "The client to set the tier for.", required = true)
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
    // once. Each bundle is applied independently -- a bad one is reported in `failures` and the rest still land,
    // so a restore is not aborted by one config -- and `testFeatures` is stripped and logged on a non-test node
    // rather than refused (the split the single-client write's refuse is the other half of). Affected clients are
    // reloaded so the import goes live, which is how a brand-new client becomes present.
    generalEndpoint(
        ACEP.import,
        "Bulk-imports a set of client configurations (issue #733): each bundle applied independently, testFeatures " +
            "stripped+logged off a test instance, affected clients reloaded.",
        HttpMethod.POST,
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
}

/**
 * A sub-context bound to the [CFEP.client] a cross-client `/admin` config request names, so the shared bodies --
 * which key off `cxt.client` -- act on that client while ownership/audit stamp from it and the caller stays the
 * actor. The `admin` section gate has already confined the caller to `allClients`, so naming any client is theirs
 * to do; an absent client is a 400.
 *
 * [requireExisting] (the default) refuses a client that is neither present nor has any stored configuration --
 * so a typo'd id on the tier or reload endpoints reads as a 404 rather than writing an orphan tier row or
 * reporting a no-op reload as success (issue #685 review). The bundle write passes it `false`, since writing a
 * `clientDef` slot for a fresh id is exactly how a new client is created; a client written but not yet reloaded
 * is admitted by the stored-config half, so the create-then-reload flow still works.
 */
private fun adminConfigCxt(c: KdrCxt, request: Map<String, Any?>, requireExisting: Boolean = true): KdrCxt {
    val client = request.getReqNonBlankStr(CFEP.client)
    val ac = c.mkSubContext("adminConfig", client)
    if (requireExisting &&
        ClientService.get(ac).known(client) == null &&
        GedraConfigService.get(ac).listConfigs(ac).isEmpty()
    ) {
        throw KdrException(
            "No client '$client': it is not present and has no stored configuration. Write its configuration first.",
            code = EXC.notFound,
        )
    }
    return ac
}
