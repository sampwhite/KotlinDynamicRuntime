package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder

/** The core config traits (issues #316, #625): the trait ids the storage slots are keyed by, and their data fields. */
@Suppress("ConstPropertyName")
object CCT {
    const val configName = "coreConfigTraits"

    // --- the storage slots (trait ids) ---

    /** The client definition itself; single-instance, since a bundle defines at most one client. */
    const val clientDef = "clientDef"

    /** One data-trait **declaration** this configuration contributes, keyed by trait id (issue #625). */
    const val traitDef = "traitDef"

    /** One state-trait declaration, keyed by trait id (issue #625). */
    const val stateTraitDef = "stateTraitDef"

    /** One trait-usage rule -- how a client presents a trait in a listing -- keyed by trait id (issue #625). */
    const val usageDef = "usageDef"

    /** One workflow definition, keyed by its workflow id. */
    const val workflowDef = "workflowDef"

    /** One schema type definition, keyed by the type's name. */
    const val schemaDef = "schemaDef"

    /** One Markdown-fragment overlay, keyed by the fragment file id (issue #625). */
    const val fragmentDef = "fragmentDef"

    /** One UiBlock overlay, keyed by the block id (issue #625). */
    const val uiBlockDef = "uiBlockDef"

    /** One cfact **name declaration** (declaration only, never a production), keyed by name (issue #625). */
    const val cfactDef = "cfactDef"

    // --- field names inside the slots' data (each matches its value) ---

    const val traitId = "traitId"
    const val typeName = "typeName"
    const val appliesTo = "appliesTo"
    const val primaryKey = "primaryKey"
    const val dataSchema = "dataSchema"
    const val stateClass = "stateClass"
    const val description = "description"

    const val label = "label"
    const val display = "display"
    const val kind = "kind"
    const val substring = "substring"

    const val workflowId = "workflowId"
    const val definition = "definition"

    const val schema = "schema"

    const val fileId = "fileId"
    const val blockId = "blockId"
    const val content = "content"

    const val name = "name"
    const val group = "group"
    const val toFrontend = "toFrontend"
}

/**
 * The config traits a **stored** client configuration is made of (issues #316, #611, #625): the pieces of a
 * [GedraConfig] that a database row keeps as separate entries so each has its own accounting -- *when* and *by
 * whom* it changed -- while an editor still sees the one JSON structure a component declares.
 *
 * ### One slot per field of a `GedraConfig`
 *
 * #316 declared the first three; #625 completed the set so a stored config can round-trip faithfully rather
 * than lose the fields it had no slot for. There is one slot per thing a `GedraConfig` carries, keyed as #611
 * says:
 *
 * - **[CCT.clientDef]** -- the client definition, single-instance, by **reference** to the canonical
 *   `ClientInfo` ([CLD.infoTypeQualified]).
 * - **[CCT.traitDef]** / **[CCT.stateTraitDef]** -- a data / state trait's **declaration**, by trait id. The
 *   DSL inputs are stored (`traitId`, `typeName`, `appliesTo`, `primaryKey`, description, and the data shape as
 *   a `schemaDocument()`), **not** the `<Name>Entry`/`<Name>Data` types the declaration generates: a trait is a
 *   declaration re-run on load, not a schema (the trait-vs-schema line #316's review drew). A state trait adds
 *   its [StateTraitClass].
 * - **[CCT.usageDef]** -- a trait-usage rule ([ClientTraitUsage]), by trait id.
 * - **[CCT.workflowDef]** -- a workflow, by workflow id, by **reference** to the definition schema under
 *   [WFD.namespace]. Its tasks travel inside it: a task has no standalone type, so there is no task slot.
 * - **[CCT.schemaDef]** -- a type the config declares **directly** (`type(...)`), by type name, its body
 *   validated by **parsing** it (`schemaDocument()`). A trait's *generated* types are never stored here; the
 *   provenance split (a type is generated when a trait's `typeName` names it, or it is a trait's inlined data
 *   type) is #613's to apply when it stores `defs`.
 * - **[CCT.fragmentDef]** / **[CCT.uiBlockDef]** -- a Markdown-fragment / UiBlock overlay, by file / block id.
 *   Only the persisted core is a field -- the id and the `content` map; `isOverlay`, `origin`, `client` and the
 *   like are reconstructed on load from the config that owns the entry, not stored.
 * - **[CCT.cfactDef]** -- a cfact **name declaration**, by name. **Declaration only**: a component is what
 *   makes a cfact *true* (that needs Kotlin), so a config authored from data may only declare that a name
 *   *exists*, under the additive-only rule. There is no way to author a production here, and the loader (#614)
 *   treats these as declarations.
 *
 * ### Referencing, not copying
 *
 * The client and workflow slots reference the canonical shapes rather than redeclaring them, so a field added
 * to the real `ClientInfo` or `WfDef` reaches the stored form with no second declaration to remember. Both
 * refs resolve at boot, where every component's `$defs` are compiled together (`clientCatalogSchema` and the
 * workflow schema are always present).
 *
 * ### Declared, not yet contributed
 *
 * Nothing registers this config with a component: the entries these slots describe are stored by #613 and
 * loaded by #614, and until a row can hold one, contributing these types would put entry types into every
 * catalog that nothing writes or reads. The slots are the storage **vocabulary**; the exact serialization to
 * and from them is #613's, and it conforms to these shapes.
 */
fun coreConfigTraits(cxt: KdrCxtBase): GedraConfig = gedraConfig(cxt, CCT.configName, GCFG.globalNamespace) {
    configTrait(
        "ClientDefEntry", CCT.clientDef, setOf(GedraConfigType.configDoc),
        dataType = CLD.infoTypeQualified,
        description = "The client this configuration defines, as a stored entry.",
    )
    configTrait(
        "TraitDefEntry", CCT.traitDef, setOf(GedraConfigType.configDoc),
        "One data-trait declaration this configuration contributes, keyed by trait id.",
        primaryKey = listOf(CCT.traitId),
    ) {
        traitDeclarationFields()
    }
    configTrait(
        "StateTraitDefEntry", CCT.stateTraitDef, setOf(GedraConfigType.configDoc),
        "One state-trait declaration this configuration contributes, keyed by trait id.",
        primaryKey = listOf(CCT.traitId),
    ) {
        traitDeclarationFields()
        property(CCT.stateClass, "How the state behaves under recomputation, and who may write it.", required = true) {
            options(StateTraitClass.entries)
        }
    }
    configTrait(
        "UsageDefEntry", CCT.usageDef, setOf(GedraConfigType.configDoc),
        "How this client presents one trait in a listing, keyed by trait id.",
        primaryKey = listOf(CCT.traitId),
    ) {
        property(CCT.traitId, "The trait this usage rule is for.", required = true)
        property(CCT.label, "The column label the listing shows.", required = true)
        property(CCT.display, "The string-script expression evaluated against the trait's data for the value.", required = true)
        property(CCT.kind, "How the value reads -- what a search treats it as.") { options(UsageKind.entries) }
        property(CCT.substring, "For a string value, whether the search also offers a substring match.") {
            type = SCT.boolean
        }
    }
    configTrait(
        "WorkflowDefEntry", CCT.workflowDef, setOf(GedraConfigType.configDoc),
        "One workflow definition of this configuration, keyed by its workflow id.",
        primaryKey = listOf(CCT.workflowId),
    ) {
        property(CCT.workflowId, "The workflow's id within this configuration.", required = true)
        property(CCT.definition, "The workflow definition, as the definition schema describes it.", required = true) {
            ref("${WFD.namespace}.${WFD.defType}")
        }
    }
    configTrait(
        "SchemaDefEntry", CCT.schemaDef, setOf(GedraConfigType.configDoc),
        "One schema type this configuration declares directly, keyed by the type's name.",
        primaryKey = listOf(CCT.typeName),
    ) {
        property(CCT.typeName, "The qualified name of the type being defined.", required = true)
        property(CCT.schema, "The type's schema body, validated by parsing it.", required = true) { schemaDocument() }
    }
    configTrait(
        "FragmentDefEntry", CCT.fragmentDef, setOf(GedraConfigType.configDoc),
        "One Markdown-fragment overlay this client contributes, keyed by the fragment file id.",
        primaryKey = listOf(CCT.fileId),
    ) {
        property(CCT.fileId, "The fragment file this overlays.", required = true)
        property(CCT.content, "The overlay content: a two-tier map of namespace to key to value.", required = true) {
            type = SCT.kObject
        }
    }
    configTrait(
        "UiBlockDefEntry", CCT.uiBlockDef, setOf(GedraConfigType.configDoc),
        "One UiBlock overlay this client contributes, keyed by the block id.",
        primaryKey = listOf(CCT.blockId),
    ) {
        property(CCT.blockId, "The UiBlock this overlays.", required = true)
        property(CCT.content, "The overlay content.", required = true) { type = SCT.kObject }
    }
    configTrait(
        "CFactDefEntry", CCT.cfactDef, setOf(GedraConfigType.configDoc),
        "One cfact name this configuration declares exists (declaration only), keyed by name.",
        primaryKey = listOf(CCT.name),
    ) {
        property(CCT.name, "The cfact name this configuration declares.", required = true)
        property(CCT.group, "The cfact's group.", required = true)
        property(CCT.description, "What the cfact means.", required = true)
        property(CCT.toFrontend, "Whether the cfact is delivered to the frontend.") { type = SCT.boolean }
    }
}

/**
 * The declaration fields shared by [CCT.traitDef] and [CCT.stateTraitDef] (issue #625): what a trait's DSL
 * `trait(...)` call takes, so re-running it on load re-manufactures the entry types rather than storing them.
 * `appliesTo` is a set of data-kind names (bounded to [GedraDataType]); `dataSchema` is the trait's own data
 * shape, validated by parsing it -- the same `schemaDocument()` a [CCT.schemaDef] uses.
 */
private fun SchTypeBuilder.traitDeclarationFields() {
    property(CCT.traitId, "The trait's globally unique id.", required = true)
    property(CCT.typeName, "The name of the entry type the trait generates.", required = true)
    property(CCT.appliesTo, "The gedra kinds an entry of this trait may be carried on.", required = true) {
        type = SCT.array
        items { options(GedraDataType.entries) }
    }
    property(CCT.primaryKey, "The ordered key fields within the trait's data; empty when it is single-instance.") {
        type = SCT.array
        items { type = SCT.string }
    }
    property(CCT.description, "What the trait is for.")
    property(CCT.dataSchema, "The schema of the trait's own data, validated by parsing it.", required = true) {
        schemaDocument()
    }
}
