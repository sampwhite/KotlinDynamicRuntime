package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.http.request.SECT

/**
 * The client-scoped stored-configuration endpoints (issue #627) -- their paths, type names, and field names. The
 * endpoints themselves are `gedraConfigSchema` in `base:common`, whose documentation says how they work; the
 * names are here, in the kernel, so the frontend reads a stored configuration's summary by the names the backend
 * writes it under (the Clients page, issue #906). The paths lead with [SECT.clientAdmin], the section gate.
 */
@Suppress("ConstPropertyName")
object CFEP {
    /** The schema namespace the config-endpoint types live in (distinct from the `clientAdmin` section path). */
    const val namespace = "kdr.clientAdminConfig"

    // --- paths (all in the `clientAdmin` section, so the section gate is the path prefix) ---
    const val bundles = "/${SECT.clientAdmin}/config/bundles"
    const val bundle = "/${SECT.clientAdmin}/config/bundle"
    const val bundleWrite = "/${SECT.clientAdmin}/config/bundle/write"
    const val bundlePatch = "/${SECT.clientAdmin}/config/bundle/patch"
    const val bundlePublish = "/${SECT.clientAdmin}/config/bundle/publish"
    const val bundleRevert = "/${SECT.clientAdmin}/config/bundle/revert"

    /** What publishing a configuration would do to the client's stored data (issue #935); the names are [IMP]'s. */
    const val bundleImpact = "/${SECT.clientAdmin}/config/bundle/impact"
    /**
     * The stored entries of a configuration's slots, each with its own stamps (issue #1352): "slot", never "trait",
     * outward -- that slots are stored as config traits is the storage's business, and `/clientAdmin/client/traits`
     * is the client's data traits.
     */
    const val slotEntries = "/${SECT.clientAdmin}/config/entries"
    const val reload = "/${SECT.clientAdmin}/config/reload"
    const val publishedOnly = "/${SECT.clientAdmin}/config/publishedOnly"

    /**
     * Everything the bundle editor page draws, in one read (issue #1436): every configuration, its latest revision with
     * the published one beside it when the latest is a draft over it, and each slot's declared shape.
     */
    const val editor = "/${SECT.clientAdmin}/config/editor"

    // --- type names ---
    const val bundleType = "ConfigBundle"
    const val bundleWriteType = "ConfigBundleWrite"
    const val summaryType = "ConfigSummary"
    const val patchResultType = "ConfigPatchResult"
    const val publishResultType = "ConfigPublishResult"
    const val slotEntryType = "ConfigSlotEntry"
    const val reloadResultType = "ConfigReloadResult"
    const val tierType = "ConfigTier"
    const val editorType = "ConfigEditor"
    const val editorBundleType = "ConfigEditorBundle"

    // --- field names (each matches its value) ---
    const val name = "name"

    /** The configurations a publish or an impact preview acts on together, by name (issue #1352). */
    const val names = "names"

    /**
     * The configurations a request acts on, or answers with, one per configuration (issue #1352): on a patch the
     * `{name, edits}` it applies in one transaction, and on a patch's or a publish's answer each one written.
     */
    const val configs = "configs"
    const val namespaceField = "namespace"
    const val client = "client"
    const val version = "version"
    const val published = "published"
    const val publishedAt = "publishedAt"

    /**
     * On a summary (issue #1001): the version of the latest **published** revision, absent when none is. When the
     * latest revision is a draft, this is the one a published-only client runs.
     */
    const val publishedVersion = "publishedVersion"
    const val slots = "slots"
    const val impliedDelete = "impliedDelete"

    /**
     * On a bundle write, patch, or publish (issue #1156): `true` to reload the client once the change is stored, as
     * [reload] does -- and, on the answer, how that reload went. Absent, the change waits for a reload asked for.
     */
    const val reloadField = "reload"
    const val edits = "edits"
    const val slot = "slot"
    const val entries = "entries"
    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"
    const val loaded = "loaded"
    const val evictedTypes = "evictedTypes"
    const val issues = "issues"
    const val publishedOnlyField = "publishedOnly"

    /**
     * On a config patch (issue #1434): `true` to run it -- the staleness check, the edits, and the trial -- on the
     * revisions it builds, storing none of them, and answer with the configurations as they would be written and
     * [impact]. A patch the save would refuse is refused the same way.
     */
    const val dryRun = "dryRun"

    /** On a dry run's answer: what the patch would do to the data the client already stores (`IMP`'s report). */
    const val impact = "impact"

    /**
     * On a config patch's edit (issue #1434): the `updatedAt` its entry had when the caller read it. The patch is
     * refused (409, [staleCode]) if the entry has changed since, or is gone.
     */
    const val expectUpdatedAt = "expectUpdatedAt"

    /**
     * On a config patch's edit (issue #1434): `true` when its entry did not exist when the caller read the
     * configuration. The patch is refused (409, [staleCode]) if one exists now.
     */
    const val expectNew = "expectNew"

    /**
     * On a bundle read (issue #1436): which revision to answer, [revisionLatest] (the default) or [revisionPublished].
     * On the editor's answer, the same two names hold each configuration's two revisions.
     */
    const val revision = "revision"
    const val revisionLatest = "latest"
    const val revisionPublished = "published"

    /** On the editor's answer (issue #1436): each slot's entry type, whose `data` property is one entry's shape. */
    const val slotTypes = "slotTypes"

    /** On the editor's answer: the fields each slot's entries are keyed by; empty for a single-instance slot. */
    const val slotKeys = "slotKeys"

    /**
     * On the editor's answer, beside each configuration's latest revision (issue #1460): each entry's `updatedAt`, by
     * slot, in the order the revision's slots hold them -- what an edit of the entry says it read ([expectUpdatedAt]).
     */
    const val latestStamps = "latestStamps"

    /** The `errorCode` of a patch refused because entries changed since its caller read them (issue #1434). */
    const val staleCode = "staleEntries"

    /** In a stale refusal's `extraData`: each entry that changed, by [name], [slot], and [key]. */
    const val conflicts = "conflicts"

    /** On a stale entry: its primary-key fields, as the edit named them. */
    const val key = "key"
}

/**
 * The full-scope stored-configuration endpoints (issue #741): the same operations on a **named** client, for an
 * `allClients` administrator, under the `admin` section. The endpoints are `adminGedraConfigSchema` in
 * `base:common`; the names are here for the reason [CFEP]'s are.
 */
@Suppress("ConstPropertyName")
object ACEP {
    /** The schema namespace the admin config-endpoint types live in (distinct from the `admin` section path). */
    const val namespace = "kdr.adminClientConfig"

    const val bundles = "/${SECT.admin}/client/config/bundles"
    const val bundle = "/${SECT.admin}/client/config/bundle"

    /** The bundle editor page's one read, for a named client (issue #1436): as [CFEP.editor] answers it. */
    const val editor = "/${SECT.admin}/client/config/editor"
    const val bundleWrite = "/${SECT.admin}/client/config/bundle/write"
    const val bundlePatch = "/${SECT.admin}/client/config/bundle/patch"
    const val bundlePublish = "/${SECT.admin}/client/config/bundle/publish"
    const val bundleRevert = "/${SECT.admin}/client/config/bundle/revert"
    const val bundleImpact = "/${SECT.admin}/client/config/bundle/impact"
    const val slotEntries = "/${SECT.admin}/client/config/entries"
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
