package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.fileref.FREF
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
 * - **[CCT.customerDef]** -- a customer (issue #1107), by customer id, its fields declared inline from
 *   [CustomerDef.declareFields]. Only `hub`'s stored configuration may hold one.
 * - **[CCT.traitDef]** -- a data trait's **declaration**, by trait id. The DSL inputs are stored (`traitId`,
 *   `typeName`, `appliesTo`, description, and the data shape as a `schemaDocument()`, whose `g-primaryKey` keys
 *   the trait's entries), **not** the `<Name>Entry`/`<Name>Data` types the declaration generates: a trait is a
 *   declaration re-run on load, not a schema (the trait-vs-schema line #316's review drew). There is no
 *   state-trait slot (issue #873): state is global, declared by components, and a stored config is always a
 *   client's.
 * - **[CCT.usageDef]** -- a trait-usage rule ([ClientTraitUsage]), by trait id and label (issue #1490).
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
 * - **[CCT.counterDef]** -- a counter (issue #1849), by counter id: what numbers a field whose schema names it with
 *   `g-counter`. Its fields are declared inline from [CTR].
 * - **[CCT.formTypeDef]** -- a form type (issue #1902), by its id: what a kind of form is called, the traits it collects,
 *   and who may set it. Its trait refs are a task's, by **reference** to the definition schema under [WFD.namespace].
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
        description = "The client this configuration defines, as a stored entry. For a client defined in code it is instead " +
            "a change to that definition (issue #1677), which may say only whether the client has a sandbox: " +
            "`clientId`, `\"overrides\": \"codeOverlay\"`, and `sandbox`.",
    )
    // Only `hub`'s stored configuration holds customers (issue #1107), and only the platform writes them.
    // Inline, as a keyed slot's data must be, from the one declaration of its fields.
    configTrait(
        "CustomerDefEntry", CCT.customerDef, setOf(GedraConfigType.configDoc),
        "One customer the platform declares, keyed by its customer id.",
    ) {
        primaryKey(CUST.customerId)
        CustomerDef.declareFields(this)
    }
    configTrait(
        "TraitDefEntry", CCT.traitDef, setOf(GedraConfigType.configDoc),
        "One data-trait declaration this configuration contributes, keyed by trait id.",
    ) {
        primaryKey(CCT.traitId)
        traitDeclarationFields()
        overridesCodeProperty()
    }
    configTrait(
        "UsageDefEntry", CCT.usageDef, setOf(GedraConfigType.configDoc),
        "One column of this client's forms listing, reading a trait, keyed by the trait and the column's label.",
        // Trait and label (issue #1490): a trait may have several columns, told apart by their headings.
    ) {
        primaryKey(CCT.traitId, CCT.label)
        property(CCT.traitId, "The trait this usage rule is for.", required = true)
        property(CCT.label, "The column label the listing shows.", required = true)
        // A string-script template, stored as text. A malformed one fails *silently* -- it is evaluated per row with
        // its failures swallowed, so a typo blanks the column for every row rather than surfacing -- which is why a
        // write parse-checks it (`checkTemplateSyntax`, in `configSlotFailures`, issue #1052): a shape cannot say it.
        property(CCT.display, "The string-script expression evaluated against the trait's data for the value.", required = true)
        property(
            CCT.kind,
            $$"How the value reads -- what a search treats it as. Absent, a lone `${field}` display reads its field's schema " +
                "(issue #1508), and a computed one is text.",
        ) { options(UsageKind.entries) }
        property(CCT.substring, "For a string value, whether the search also offers a substring match.") {
            type = SCT.boolean
            allowCoerce = false
        }
        // Parse-checked at the write as `display` is (issue #1323): it is evaluated where the listing is drawn, and a
        // presentation that does not parse would only ever show the value.
        property(CCT.presentation, "A string script over the trait's data, with the column's value as `value`, that the cell reads through.")
        // The column's own name (issue #1490), needed only on a second column of one trait. It names query parameters.
        property(
            CCT.columnId,
            "The column's name in its search parameters and its sort, when it is not the trait id: needed on a second " +
                "column of the same trait. Letters, digits, and underscores, starting with a letter.",
        ) { pattern = columnIdPattern }
        // A named size, not pixels (issue #1855): the page decides what each is on its screen.
        property(CCT.width, "How much room the column wants: `narrow` for a short value such as an id, `wide` for a long one. Absent is `normal`.") {
            options(ColumnWidth.entries)
        }
        overridesCodeProperty()
    }
    configTrait(
        "WorkflowDefEntry", CCT.workflowDef, setOf(GedraConfigType.configDoc),
        "One workflow definition of this configuration, keyed by its workflow id.",
    ) {
        primaryKey(CCT.workflowId)
        property(CCT.workflowId, "The workflow's id within this configuration.", required = true)
        property(CCT.definition, "The workflow definition, as the definition schema describes it.", required = true) {
            ref("${WFD.namespace}.${WFD.defType}")
        }
        overridesCodeProperty(overlay = true)
    }
    configTrait(
        "ReportDefEntry", CCT.reportDef, setOf(GedraConfigType.configDoc),
        "One named report of this configuration, keyed by its report id.",
    ) {
        primaryKey(CCT.reportId)
        property(CCT.reportId, "The report's id within this configuration.", required = true)
        property(CCT.definition, "The report definition, as the definition schema describes it.", required = true) {
            ref("${RDEF.namespace}.${RDEF.defType}")
        }
        overridesCodeProperty()
    }
    configTrait(
        "SchemaDefEntry", CCT.schemaDef, setOf(GedraConfigType.configDoc),
        "One schema type this configuration declares directly, keyed by the type's name.",
    ) {
        primaryKey(CCT.typeName)
        property(CCT.typeName, "The qualified name of the type being defined.", required = true)
        property(CCT.schema, "The type's schema body, validated by parsing it.", required = true) { schemaDocument() }
        overridesCodeProperty(overlay = true)
    }
    configTrait(
        "FragmentDefEntry", CCT.fragmentDef, setOf(GedraConfigType.configDoc),
        "One Markdown-fragment overlay this client contributes, keyed by the fragment file id.",
    ) {
        primaryKey(CCT.fileId)
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
    ) {
        primaryKey(CCT.blockId)
        property(CCT.blockId, "The UiBlock this overlays.", required = true)
        property(CCT.content, "The overlay content.", required = true) { type = SCT.kObject }
    }
    configTrait(
        "CFactDefEntry", CCT.cfactDef, setOf(GedraConfigType.configDoc),
        "One cfact name this configuration declares exists (declaration only), keyed by name.",
    ) {
        primaryKey(CCT.name)
        property(CCT.name, "The cfact name this configuration declares.", required = true)
        property(CCT.group, "The cfact's group.", required = true)
        property(CCT.description, "What the cfact means.", required = true)
        property(CCT.toFrontend, "Whether the cfact is delivered to the frontend.") {
            type = SCT.boolean
            allowCoerce = false
        }
        overridesCodeProperty()
    }
    // What a file *is* --
    // its type, its size, the name it was uploaded under -- is deliberately no field of this: it is the file
    // store's record, written by the upload that checked the bytes, or what a local file is found to be when it
    // is read (issue #1154) -- and nothing an entry could be typed to claim.
    configTrait(
        "FileRefDefEntry", CCT.fileRefDef, setOf(GedraConfigType.configDoc),
        "One file ref of this client -- a named file, by where its bytes are -- keyed by its ref id. The file " +
            "store's are named by their `hash`; a local file's by a `root` and a `path` under it, and by no hash.",
    ) {
        primaryKey(CCT.refId)
        property(CCT.refId, "The ref's id within its client: a variable name, at most ${FREF.maxRefIdLength} characters.", required = true) {
            maxLength = FREF.maxRefIdLength
            pattern = FREF.refIdPattern
        }
        property(CCT.source, "Where the file's bytes are.", required = true) {
            option(FREF.sourceStore, "The file store")
            option(FREF.sourceLocal, "A local file")
            option(FREF.sourceClientLocal, "A local file in the client's own directory of a root open to clients")
        }
        property(CCT.hash, "For the file store: the SHA-256 of the file's bytes, in lowercase hex, which is what names them there.") {
            pattern = FREF.hashPattern
        }
        property(CCT.root, "For a local file: the root it is under, by the name the deployment declared it with.") {
            pattern = FREF.rootPattern
        }
        property(
            CCT.path,
            "For a local file: its path under the root -- or, for a client's own, under the client's directory there " +
                "-- in segments of letters, digits, `.`, `_` and `-`, none beginning with a dot.",
        ) {
            maxLength = FREF.maxPathLength
        }
        property(CCT.description, "What the file is for, in the administrator's words.") { maxLength = FREF.maxDescriptionLength }
        overridesCodeProperty()
    }
    // One entry, as the client definition is: no key, and no `overrides` mark, since a stored one stands over the code's
    // by itself (issue #1599).
    configTrait(
        "ListingDefEntry", CCT.listingDef, setOf(GedraConfigType.configDoc),
        "The client's listing-wide settings: how its forms listing is drawn as a whole. A stored entry stands over the " +
            "one its code or template declares.",
    ) {
        property(CCT.workflowLayout, "How each row's workflows are drawn: `split` (the default) or `combined`.") {
            option(WorkflowLayout.split.name, "A Your turn column beside the Workflows cell")
            option(WorkflowLayout.combined.name, "One Workflows cell holding every link")
        }
    }
    // Declared last, so the slot list a write is held to ends with the newest (issues #1153, #1599, #1849).
    configTrait(
        "CounterDefEntry", CCT.counterDef, setOf(GedraConfigType.configDoc),
        "One counter of this client -- what numbers a field's ids, a prefix and then a zero-padded count -- keyed by its " +
            "counter id. A field names it with `g-counter`.",
    ) {
        primaryKey(CTR.counterId)
        property(CTR.counterId, "The counter's id within its client, which a field's `g-counter` names: ${CTR.idRule}.", required = true) {
            pattern = CTR.idPattern
        }
        // A template, parse-checked at the write (`configSlotFailures`) and rendered for two days there, since a shape
        // can say neither that it parses nor that a daily counter's ids would not repeat.
        property(
            CTR.prefix,
            $$"A string-script template for the text before the count. It reads `${$${CTR.day}}`, the client's day the id " +
                $$"is issued on (2026-11-17), and `${$${CTR.compactDay}}`, the same day without its dashes (20261117). " +
                "Empty for ids that are the count alone.",
            required = true,
        )
        property(
            CTR.digits,
            "How many digits the count is padded to with zeroes: ${CTR.minDigits} to ${CTR.maxDigits}, and " +
                "${CTR.defaultDigits} when left out. A count that outgrows them widens.",
        ) {
            type = SCT.integer
            minimum = CTR.minDigits
            maximum = CTR.maxDigits
            allowCoerce = false
        }
        property(CTR.reset, "When the count starts again at 1: `${CTR.resetNever}` (the default) or `${CTR.resetDaily}`, on each of the client's days.") {
            option(CTR.resetNever, "Never")
            option(CTR.resetDaily, "Each day")
        }
        property(
            CTR.issue,
            "When a form is given its id: `${CTR.issueOnOpen}` (the default), as the form opens, so the person sees it " +
                "before saving, or `${CTR.issueOnSave}`, when the form is saved, so a form opened and left uses none up " +
                "and the count has fewer gaps. With `${CTR.issueOnSave}` a page does not show the field until the form " +
                "is saved.",
        ) {
            option(CTR.issueOnOpen, "As the form opens")
            option(CTR.issueOnSave, "When the form is saved")
        }
        property(
            CTR.enabled,
            "Whether new ids are issued; true when left out. Disable a counter rather than remove it, so its ids still mean something.",
        ) {
            type = SCT.boolean
            allowCoerce = false
        }
        property(CTR.description, "What the counter numbers, in the administrator's words.")
        overridesCodeProperty()
    }
    configTrait(
        "FormTypeDefEntry", CCT.formTypeDef, setOf(GedraConfigType.configDoc),
        "One form type of this client -- a kind of form, and the traits it collects -- keyed by its id. A form says which " +
            "type it is in its `${GT.formType}` entry, a trait the client has once it defines a form type.",
    ) {
        primaryKey(FTYPE.formTypeId)
        property(FTYPE.formTypeId, "The type's id within its client: ${FTYPE.idRule}.", required = true) {
            pattern = FTYPE.idPattern
        }
        property(FTYPE.label, "What the type is called, in the choices of a form's type.", required = true)
        property(FTYPE.description, "What a form of the type is, in the administrator's words.")
        property(
            FTYPE.traits,
            "The traits a form of the type collects, in order. None for a type that only classifies. A form may hold a " +
                "trait its type does not list: a type says what is asked for, and refuses nothing.",
        ) {
            type = SCT.array
            items { ref("${WFD.namespace}.${WFD.traitRefType}") }
        }
        // Parse-checked, against the client's facts, by the write's trial.
        property(
            FTYPE.setWhen,
            "Who may set a form to this type: a cfact expression over the facts of whoever is writing. Absent, whoever may " +
                "write the form.",
        )
        property(
            FTYPE.mayLeave,
            "Whether a form of this type may be changed to another, or lose its type; false when left out, so a form's type " +
                "is fixed once it is stored.",
        ) {
            type = SCT.boolean
            allowCoerce = false
        }
        overridesCodeProperty()
    }
}

/**
 * The mark a keyed slot's stored entry carries to stand over the code's entry of the same key (issue #1634): the
 * template's copies, the client's own code, a sandbox's code. Its values are a closed list, so anything else is refused
 * at the write: `code` on every keyed slot, and with [overlay] -- a workflow's and a schema type's, the slots with merge
 * rules -- `codeOverlay` too, for an entry holding only what it changes (issue #1657).
 */
private fun SchTypeBuilder.overridesCodeProperty(overlay: Boolean = false) {
    val whole = "Set to `${CCT.overridesCodeValue}` when this entry stands over the client's code's entry of the same key -- its " +
        "template's, or its own code's -- which it replaces whole."
    val asOverlay = " Set to `${CCT.overridesCodeOverlayValue}` when it holds only what it changes of the code's entry, which " +
        "every read merges it over, so a later change to the code reaches the parts it leaves alone."
    property(
        CCT.overrides,
        whole + (if (overlay) asOverlay else "") + " Without it, an entry matching a code entry is refused.",
    ) {
        option(CCT.overridesCodeValue, "Stands over the code's entry of the same key")
        if (overlay) option(CCT.overridesCodeOverlayValue, "Changes the code's entry of the same key, merged over it")
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
    property(CCT.description, "What the trait is for.")
    property(CCT.dataSchema, "The schema of the trait's own data, validated by parsing it.", required = true) {
        schemaDocument()
    }
}
