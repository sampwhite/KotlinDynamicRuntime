## Design View: marking a form with the definitions behind it (issue #972)

An administrator's switch in the app bar (`Design`) turns the workflow pages into an annotated view: each trait
block and field is outlined on hover with its id, a click opens a side inspector (`DesignInspector.kt`) with the
definition behind it, where it was declared, and its authored JSON, and the fields the form leaves out are drawn
as hatched ghosts saying why. A field's copy -- label, description, hint -- can be edited there as **this workflow's
own** (issue #984, `WorkflowCopyEditor`): the edit is written into the workflow's stored definition as an alteration
of the field's type, shows on that workflow's pages only, and **Reset to shared** removes it. It is offered only
where the workflow is the client's own stored definition; elsewhere the inspector says why, from the block's
`editRefusal`. **A Design View save is made as the Clients page's editors make theirs** (`ClientStoredEdit`, issue
#1026): published and live at once for a client without a sandbox -- so it is refused while the config carries
somebody's unpublished changes (`unpublishedChanges`) -- and a draft of the parent's config, shown by the sandbox, for
one with a sandbox, whose own page points to the sandbox instead (`publishedOnly`). The two kinds of editor therefore
never leave each other a draft to refuse. A field's summary keeps what the data requires apart from what this form
asks for (issue #1022): **Required by the data** beside **Required on this form** and **Choices on this form**, the
form's requirements from its layout entry. The workflow copy editor sets them (issue #1048,
`formRequirementControls`): a **Required on this form** checkbox -- locked when the data already requires the field,
and replaced by the reason on a derived or `g-visibleWhen` field, which the load check refuses -- and, for a field
with a closed list, a row per schema choice, offered or not, with the form's label for it, filled in from the
schema's. They save into the workflow's own entry beside its copy (`formEntryFrom`), so Reset to shared removes them
too. The schema's list left as it is writes no `choices`; any change -- a choice dropped, one relabeled -- writes the
list in **full**, every label with it: a form customizing its choices controls exactly what it shows, and does not
inherit a later relabel. The editor keeps the list's order rather than offering to change it.

The **shared definition** -- what every workflow on the client draws -- is edited separately (issue #1029,
`SharedFieldSection`), behind a deliberate **Edit the shared definition** button and never as an option beside a
workflow's Save. It is offered for a definition the client declares in its own stored configuration, and says first
where it is used ("Used by 2 workflows: …") and which workflows keep their own copy of the field. It edits the
field's copy and its choices -- relabel, add or remove one; a changed value is a removal and an addition. A save that
removes a choice is checked against the forms the client stores (issue #1040, #935's impact report, on any tier) and
refused with the report when one holds it; the dialog's **Save anyway** resends it with `acknowledgeImpact`. In a
sandbox the save is only a draft, so the check waits for its publish. Its two headings say what an edit changes -- **Form copy** (what forms show) and **Choices** (what
the field accepts) -- under one save (issue #1039). A blank copy input is not empty copy: the form falls back to the
field's own title or name, description and range hint, and both copy editors show that fallback in place
(`copyFallback`, mirroring `SchemaForm`'s own fallbacks -- change the two together). A field the type's layout leaves
out of a list that owns the order (`reorder`, `authoritative`) cannot take shared copy, and the read says so per field
(`sharedCopyRefusals`), so the inputs are disabled with the reason rather than refused on Save. The facts come from the
definition read (`parseSharedFacts`); the save is `/clientAdmin/design/sharedField`. Where a definition comes from is said in one sentence (`provenanceText`, issue
#1013): where it is declared, and the client configuration altering it when one does -- the reader sees what every
client shares and what is this client's own, however it was assembled.

**Shared wording** (issue #1010, `SharedWordingSection`): field copy **pulled from a fragment file** --
`%{@t("questionnaire.topicHelp")}` in a label, description or hint -- is edited at its key, as the client's copy
override (#918, the Clients page's `/clientAdmin/client/copy/set` and `/reset`), so the change reaches everywhere the
key is used, in every workflow. It is offered whatever the definition's origin -- a global or source-declared
definition included, which nothing else in Design View can edit -- since an override is the client's own data. It
keeps the rule every Design View save keeps (#1026): a client with a sandbox changes its wording from the sandbox,
where the draft shows, so its own page shows the words with the reason (`DSV.sharedWordingRefusal`). The
definition read names, per type, field and slot, the keys a slot pulls with the words the client reads and the shipped
ones (`DSV.pulledCopy`, `pulledCopyOf`); the user sees **words, never the template**, with a source line naming the
wording and whose it is (`pulledSourceLine`), **Save shared wording**, and a reset only where the client's stored
configuration sets the key (`pulledResetLabel`). A slot mixing a pull with other text shows composed, read-only, with
each key editable beneath. Elsewhere a pull reads as its words too: the workflow copy editor's "Shared:" line and the
read-only facts (`inheritedCopyText`), and the shared editor shows a pulled slot read-only rather than offer to
overwrite the pull with literal text.

**Page-level copy** (issue #1070, `LabelSection`, `HeadingSection`): the copy above a form's fields. The **labels a
workflow owns** -- its own (the page title, also its name in listings and lock notices), each task's, each save's
button -- are selected by the frames on the title, the task rail or panel label and the save button (`DesignTarget.Task`,
`DesignTarget.Save`), and from the inspector's lists of tasks and saves, since a one-task form draws no task name and a
save shows only while the form is filled in. They are edited in the workflow's definition **in place**
(`/clientAdmin/design/label`, `labelEditBody`): the workflow owns them, so there is no shared level and no reset; the
page title may be cleared to the generic one, a task's or save's may not. A **trait's heading** -- its data type's
layout `label` -- has the two levels a field's copy has: the workflow's own, as its alteration of the type, with
**Reset to shared** and the note when the shared heading has changed since (`/clientAdmin/design/heading`, the block's
`DSV.headingEdits`), and the shared one behind **Edit the shared heading** (`/clientAdmin/design/sharedHeading`). A
label or heading that pulls a fragment key is shared wording (`DSV.pulledLabels`, `DSV.pulledHeadings`), edited at the
key in the same `SharedWordingSection` a field's copy uses -- whatever the workflow's origin, so a source-declared
workflow's pulled labels (the sample's acme `createForm`) are editable there -- and never overwritten with literal text
by the label or heading editor. What the inspector says the page shows is read from the view, never from the selection,
which predates any save's re-read.

**Which fields a form shows** (issue #1071, `FieldsShownSection`): a trait's inspector offers **Choose which fields this
form shows**, off by default. Turning it on writes the fields shown now as the workflow's own `authoritative` list (its
layout alteration of the type, `/clientAdmin/design/shownFields`, the block's `DSV.shownFields`), so nothing changes
until a field is unchecked; a checklist of every field the type declares (`shownFieldRows`, filterable past eight)
then picks them, saved together. A field the data may require is locked by the load check's own rule
(`fieldsAListMustShow`), and one this form requires must stop being required first. Off goes back to the shared
layout's fields and keeps any copy. Under the list, a field's copy edit keeps its place and **Reset to shared** leaves
the field on the form (`withLayoutEntry`). The save follows the list: an edit save sends only the shown fields
(`shownPayload`) and the backend merges them over the stored entry, refusing any other, so workflows filling different
parts of one questionnaire keep each other's answers.

- **The backend explains; the page does not work it out.** The switch is a `sessionStorage` flag that rides every
  request as `X-Kdr-View: design` (see `applyRequestHeaders`); the backend honors it only for a client
  administrator, adding a `design` block to the workflow view -- an address and origin for every type the page
  draws. Never infer provenance in the webapp: a definition's origin is the backend's to say.
- **The page explains only its own decisions.** Which fields a form hides, and why, is decided in `SchemaForm` --
  so the webapp names those reasons (`FieldHidden`). They all come from one function, `formFieldPlan`
  (`fieldHiddenReason` per field), which both presentations follow: the ordinary form skips a field with a
  reason, Design View draws it as a ghost. **A new rule that hides a field goes into `fieldHiddenReason`**, never
  as a fresh `return@forEach` in the render loop -- a rule added there would hide the field from Design View too,
  with no ghost to say so.
- **Another client's form is designed in its own client.** An administrator who sees every client can open any
  client's form, and its workflows and definitions are that client's, not theirs (`hub`, usually). So the session
  carries the form's client (`DesignSession.client`, the page's surface client) and every Design View read and save
  names it (`DSV.client`); the backend binds the request to it, and refuses it from anyone else (`overseenClient`).
- **Only the workflow form is marked.** It provides `DesignViewContext`; every other `SchemaForm` (the catalog,
  the raw editors) sees null and draws exactly as before.
- **Seeing it.** The `design-demo` simulation writes the `designdemo` client -- defined in data, so its definitions
  show as the client's own. Run it from the **Simulations** page (`simulations-page.md`), with a suffix for a fresh copy
  (`designdemouat`), and sign in as its designer in one click; or `kdr-probe --url http://localhost:7072 design-demo`.
  Its third workflow, **Plan the logistics** (issue #1071), is a normal one whose form shows only the fields it
  arranges, so it and the **Event request** survey each save their own part of one request; its last step is a
  reviewer's approval. The report's **reviewer** is an administrator carrying the client's `reviewer` label; the
  designer, without it, sees that step as someone else's ("A reviewer approves the plan").
