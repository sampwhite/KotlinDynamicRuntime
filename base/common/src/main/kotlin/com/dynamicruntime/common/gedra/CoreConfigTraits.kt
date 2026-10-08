package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder

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
 *   `WrittenClientInfo` ([CLD.writtenInfoTypeQualified]): the definition as a write must give it (issue #1051),
 *   which is `ClientInfo` with the list of environments closed.
 * - **[CCT.traitDef]** -- a data trait's **declaration**, by trait id. The DSL inputs are stored (`traitId`,
 *   `typeName`, `appliesTo`, `primaryKey`, description, and the data shape as a `schemaDocument()`), **not** the
 *   `<Name>Entry`/`<Name>Data` types the declaration generates: a trait is a declaration re-run on load, not a
 *   schema (the trait-vs-schema line #316's review drew). There is no state-trait slot (issue #873): state is
 *   global, declared by components, and a stored config is always a client's.
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
 * - **[CCT.reportDef]** -- a named report, by report id, by **reference** to the definition schema under
 *   [RDEF.namespace] (issue #979). Its columns travel inside it, as a workflow's tasks do.
 * - **[CCT.cfactDef]** -- a cfact **name declaration**, by name. **Declaration only**: a component is what
 *   makes a cfact *true* (that needs Kotlin), so a config authored from data may only declare that a name
 *   *exists*, under the additive-only rule. There is no way to author a production here, and the loader (#614)
 *   treats these as declarations.
 *
 * ### Referencing, not copying
 *
 * The client, workflow and report slots reference the canonical shapes rather than redeclaring them, so a field
 * added to the real `ClientInfo` (whose one declaration makes its written form too), `WfDef` or `ClientReport`
 * reaches the stored form with no second declaration to remember. The refs resolve where these shapes are compiled
 * -- `ConfigSlotShapes`, beside the three definition schemas -- since this config is not one a component contributes
 * to the schema store.
 *
 * ### Held to at write
 *
 * These shapes are what a client configuration **write** is held to (issue #1052): `configSlotFailures` validates
 * each entry a write brings against its slot's shape, and adds the few rules a shape cannot state. A flag here is a
 * boolean and nothing else (`allowCoerce = false`), since the reassembly reads anything else as false. What is
 * already stored is still read leniently.
 *
 * ### The storage vocabulary
 *
 * These slots are how a client's configuration is stored in a database row: each entry of a stored config is one
 * slot, stored by #613 and loaded by #614. The exact serialization to and from them is #613's, and it conforms to
 * these shapes.
 */
fun coreConfigTraits(cxt: KdrCxtBase): GedraConfig = gedraConfig(cxt, CCT.configName, GCFG.globalNamespace) {
    configTrait(
        "ClientDefEntry", CCT.clientDef, setOf(GedraConfigType.configDoc),
        dataType = CLD.writtenInfoTypeQualified,
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
        "UsageDefEntry", CCT.usageDef, setOf(GedraConfigType.configDoc),
        "How this client presents one trait in a listing, keyed by trait id.",
        primaryKey = listOf(CCT.traitId),
    ) {
        property(CCT.traitId, "The trait this usage rule is for.", required = true)
        property(CCT.label, "The column label the listing shows.", required = true)
        // A string-script template, stored as text. A malformed one fails *silently* -- it is evaluated per row with
        // its failures swallowed, so a typo blanks the column for every row rather than surfacing -- which is why a
        // write parse-checks it (`checkTemplateSyntax`, in `configSlotFailures`, issue #1052): a shape cannot say it.
        property(CCT.display, "The string-script expression evaluated against the trait's data for the value.", required = true)
        property(CCT.kind, "How the value reads -- what a search treats it as.") { options(UsageKind.entries) }
        property(CCT.substring, "For a string value, whether the search also offers a substring match.") {
            type = SCT.boolean
            allowCoerce = false
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
        "ReportDefEntry", CCT.reportDef, setOf(GedraConfigType.configDoc),
        "One named report of this configuration, keyed by its report id.",
        primaryKey = listOf(CCT.reportId),
    ) {
        property(CCT.reportId, "The report's id within this configuration.", required = true)
        property(CCT.definition, "The report definition, as the definition schema describes it.", required = true) {
            ref("${RDEF.namespace}.${RDEF.defType}")
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
        // Namespace to key to text: a map of maps (issue #1055), so a write's gate holds both tiers to it -- a
        // namespace that is not an object, or a key whose value is not text, is refused by its path.
        property(CCT.content, "The overlay content: a two-tier map of namespace to key to value.", required = true) {
            mapOfValues { mapOfValues { type = SCT.string } }
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
        property(CCT.toFrontend, "Whether the cfact is delivered to the frontend.") {
            type = SCT.boolean
            allowCoerce = false
        }
    }
}

/**
 * The declaration fields of a [CCT.traitDef] (issue #625): what a trait's DSL
 * `trait(...)` call takes, so re-running it on load re-manufactures the entry types rather than storing them.
 * `appliesTo` is a set of data-kind names (bounded to [GedraDataType]); `dataSchema` is the trait's own data
 * shape, validated by parsing it -- the same `schemaDocument()` a [CCT.schemaDef] uses.
 */
private fun SchTypeBuilder.traitDeclarationFields() {
    property(
        CCT.traitId, "The trait's id, unique within its client's view: its own and the global traits.",
        required = true,
    )
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
