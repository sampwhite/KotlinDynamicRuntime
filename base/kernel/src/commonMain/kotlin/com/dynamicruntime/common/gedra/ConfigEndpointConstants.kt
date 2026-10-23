package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.http.request.SECT

/**
 * The client-scoped stored-configuration endpoints (issue #627) -- their paths, type names and field names. The
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

    // --- type names ---
    const val bundleType = "ConfigBundle"
    const val bundleWriteType = "ConfigBundleWrite"
    const val summaryType = "ConfigSummary"
    const val patchResultType = "ConfigPatchResult"
    const val publishResultType = "ConfigPublishResult"
    const val slotEntryType = "ConfigSlotEntry"
    const val reloadResultType = "ConfigReloadResult"
    const val tierType = "ConfigTier"

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
     * On a bundle write, patch or publish (issue #1156): `true` to reload the client once the change is stored, as
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
