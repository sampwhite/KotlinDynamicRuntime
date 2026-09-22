package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.menuItem
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.PFO
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.prefillFromOwner
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SLDM
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.uiblock.UIB

/**
 * The sample fragment file and the keys it carries (issue #456) -- named rather than written as literals
 * because four different layers refer to them, and a typo in any one of them is a key that silently never wins.
 */
@Suppress("ConstPropertyName")
object SF {
    /** The fragment file every sample layer contributes to. */
    const val content = "sampleContent"

    const val welcome = "welcome"
    const val footer = "footer"
    const val title = "title"
    const val intro = "intro"
    const val support = "support"
    const val copyright = "copyright"

    /**
     * Acme's workflow copy: a **backend** fragment file (issue #533), pulled by its creation workflow's labels
     * and never served. Shipped by the component, since client-declared base files are deferred.
     */
    const val acmeWf = "acmeWf"

    /**
     * Layout-copy help: a **backend** fragment file (issue #605), pulled by a `g-layout`'s `description` with
     * `%{@t("questionnaire.topicHelp")}` and resolved server-side at delivery. Never served.
     */
    const val formHelp = "sampleFormHelp"
}

/** The sample workflows' ids (issue #533): one creation workflow per client, under one shared name. */
@Suppress("ConstPropertyName")
object SW {
    /** Both clients' creation workflow -- the same id in two scopes, which is what per-client registries are for. */
    const val createForm = "createForm"
    const val identify = "identify"
    const val create = "create"

    /**
     * Acme's survey workflow (issue #656): the owner revisits the same form data outside creation. Two tasks,
     * to exercise the survey's multi-task allowance (creation is capped at one) and the task list. Its labels
     * come from the same `acmeWf` backend fragment file the creation workflow uses.
     *
     * `details` is the review task -- both of the creation workflow's traits in one panel, mirroring
     * `createForm` (issue #710); `profile` is the supplied-defaults demo (issue #711), a `userInfo` trait
     * prefilled from the form owner.
     */
    const val reviewForm = "reviewForm"
    const val details = "details"
    const val profile = "profile"
    const val saveDetails = "saveDetails"
    const val saveProfile = "saveProfile"

    /**
     * Acme's **normal** workflow (issue #794) -- many-per-form and chosen rather than automatic, unlike the
     * one-per-form creation and survey. It is what the per-workflow state is computed against: a form in acme
     * carries a `workflowState` entry for it, and can be engaged with it. Deliberately plain for this slice --
     * eligibility (#783), a CTA (#785) and an approval task (#787) each arrive with their own.
     */
    const val auditReview = "auditReview"
    const val recordAudit = "recordAudit"
    const val saveAudit = "saveAudit"
}

/** The sample UiBlock and the keys inside it (issue #457). */
@Suppress("ConstPropertyName")
object SB {
    /** The block the sample registers. */
    const val nav = "sampleNav"

    const val title = "title"
    const val items = "items"

    /** The primary key its items merge by -- what makes an overlay's item *the same item*. */
    const val id = "id"
    const val label = "label"

    const val overview = "overview"
    const val users = "users"
    const val perimeter = "perimeter"
    const val retired = "retired"

    /** Acme's own item, added by overlay rather than present in the base. */
    const val siteAudits = "siteAudits"
}

/** The sample clients' own names (issue #379). */
@Suppress("ConstPropertyName")
object SC {
    /** A client that narrows what it took and adds one thing of its own. */
    const val acme = "acme"

    /** A client that takes everything global offers and builds beside it. */
    const val globex = "globex"

    // Acme's own trait, in its own namespace, which no other client can see.
    const val acmeNamespace = "acmeconfig"
    const val siteAudit = "acmeSiteAudit"
    const val siteAuditEntry = "SiteAuditEntry"
    const val auditor = "auditor"
    const val findings = "findings"

    // The supplied-defaults demo trait (issue #711): the owner's name and email, both prefilled from the form
    // owner, presented by their `defaultMode` -- name filled, email offered.
    const val userInfo = "userInfo"
    const val userInfoEntry = "UserInfoData"
    const val userName = "name"
    const val userEmail = "email"

    /**
     * A cfact acme declares and nothing yet produces (issue #455) -- the ordinary shape of a client
     * declaration, since a client's config is data and cannot carry the Kotlin that would decide it.
     */
    const val underAudit = "acmeUnderAudit"

    /** The friendly label [underAudit] presents under. */
    const val auditGroup = "Site audits"

    // Globex extends a global type rather than altering it: a new name, constraining nothing.
    const val globexNamespace = "globexconfig"
    const val richAddress = "RichAddress"
    const val what3words = "what3words"

    /** The countries acme operates in -- a subset of what the global schema admits. */
    val acmeCountries: List<String> = listOf("gb", "ie")

    /** The topics acme's questionnaire offers, where the global one offers free text. */
    val acmeTopics: List<String> = listOf("delivery", "billing")
}

/**
 * The clients the `sample` module defines (issue #379).
 *
 * Between them, they exercise everything a client may do to schema, which is the point of having two rather
 * than one: the interesting cases are the ones where two clients disagree about the same type, and a single
 * client cannot show that.
 *
 * | | acme | globex |
 * |---|---|---|
 * | takes | two global traits, named | everything, via `#allGlobal` |
 * | alters a trait | yes -- `questionnaire`'s data | no |
 * | alters an interior type | yes -- `SiteAddress` | no |
 * | extends | no | yes -- `RichAddress` |
 * | declares its own trait | yes -- `acmeSiteAudit` | no |
 * | omits a global trait | yes -- `managerApproval` | no |
 *
 * Both are `dev` and `customer`, which is what lets them take a functional group at all: `#allGlobal` is
 * refused only to a `customer` client in `production`.
 */
fun sampleClients(cxt: KdrCxt): List<GedraConfig> = listOf(acmeClient(cxt), globexClient(cxt))

/**
 * A client that narrows what it took.
 *
 * **It omits `managerApproval`**, and what that does is worth being exact about: excluding a trait does not
 * stop acme's users storing one, it stops it being *validated*. An entry carrying an unsupported trait lands
 * on the union's open default branch and is kept as supplied -- which is #301's answer to meeting a trait a
 * reader does not know, reached from the other end. `includedTraits` is a statement about forms and
 * validation, never a prohibition.
 */
private fun acmeClient(cxt: KdrCxt): GedraConfig =
    gedraConfig(cxt, "${SC.acme}Client", SC.acmeNamespace, SC.acme) {
        defineClient(
            ClientDef(
                clientId = SC.acme,
                name = "Acme",
                description = "Narrows what it took, and has one trait of its own.",
                usageType = ClientUsageType.dev,
                audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                // Named one at a time rather than by group, which is what leaves `managerApproval` out.
                // `siteVisit` is here so the interior alteration below has something to reach it through.
                includedTraits = listOf(ST.expenseReport, ST.questionnaire, ST.siteVisit),
                // Opt in to the demo state derivation (issue #599): on a test instance, acme's forms get
                // `traitPresenceByYear` computed on create. A test-only behavior toggle, not a schema variant.
                testFeatures = setOf(ST.captureTraitPresenceByYear),
            ),
        )

        // --- altering a trait ---------------------------------------------------------------------------
        //
        // Reached through the trait's **data** type, which is a type of its own since #379 -- an overlay
        // reaches a type's keys and its property set and stops, so the generated entry envelope around it is
        // neither restated nor at risk of being dropped.
        //
        // Two of the three narrowings at once: a choice list applied to a field that offered none, and a
        // property (`notes`) left out. Every property acme keeps is named, because mentioning keys *is* how
        // the set is reduced; `keepProperty` says "as it already is" so that keeping one is not restating it.
        type("${ST.namespace}.${traitDataTypeName(ST.questionnaireEntry)}") {
            property(ST.topic, "What this questionnaire is about.") {
                for (t in SC.acmeTopics) option(t)
            }
            keepProperty(ST.hasIssue)
            keepProperty(ST.explanation)
        }

        // --- altering an interior type ------------------------------------------------------------------
        //
        // The case that cannot be faked with namespacing. Nothing about `SiteVisitEntry` is edited, and it
        // does not know: its `$ref` names `SiteAddress`, and for acme that name resolves here.
        type("${ST.namespace}.${ST.siteAddress}") {
            type = SCT.kObject
            description = "Where a visit happened."
            property(ST.country, "Country the site is in.", required = true) {
                for (c in SC.acmeCountries) option(c)
            }
            property(ST.postcode, "Postal code, as written locally.")
        }

        // --- copy of its own ----------------------------------------------------------------------------
        //
        // Applied after every component layer, so acme's wording wins over both the base and the sample's own
        // overlay -- a client is the most specific thing with an opinion. It names two keys and says nothing
        // about the rest of the file, which keep whatever the layers underneath say.
        fragmentOverlay(SF.content) {
            namespace(SF.welcome) {
                key(SF.title, "Welcome to Acme")
                key(SF.support, "Acme site services will help.")
            }
        }

        // --- copy of the real UI ------------------------------------------------------------------------
        //
        // The shell's own wordmark, not a fixture: an acme user sees "ACME KDR" where everybody else sees
        // "KDR", in the app bar and in the home hero, and no frontend code knows acme exists.
        //
        // The namespace and key are literals here and in `Home.kt`/`AppBar.kt`, which is the tension
        // `vocabulary-code-vs-data.md` is about -- and the reason the orphan check earns its place: a typo in
        // either of these fails the boot with "overlay keys no base declares" rather than quietly leaving
        // acme reading the default.
        fragmentOverlay(HFRAG.home) {
            namespace("home") {
                key("brand", "ACME KDR")
            }
        }

        // The mails too (issue #773): every mail about an acme user -- a code, an invitation, a claim answer
        // -- is signed by acme, because the footer is shared by every mail and the copy is rendered for the
        // client of the user the mail is about, whoever sent it. One key; the bodies stay as shipped.
        fragmentOverlay(AFRAG.mail) {
            namespace(MCOPY.common) {
                key(MCOPY.footer, "Sent on behalf of Acme. Acme site services will help if something looks wrong.")
            }
        }

        // --- an interface of its own --------------------------------------------------------------------
        //
        // Acme renames one item and adds one of its own. The renamed item is matched by the base's primary
        // key, so it changes that item rather than becoming a second; the added one states its own
        // displayOrder, because only acme knows where it belongs. Nothing else in the block is mentioned.
        uiBlockOverlay(SB.nav) {
            items(SB.items) {
                item { set(SB.id, SB.overview); set(SB.label, "Acme overview") }
                item { set(SB.id, SB.siteAudits); set(SB.label, "Site audits"); set(UIB.displayOrder, 150) }
            }
        }

        // --- suppressing a shared menu item (issue #488) ------------------------------------------------
        //
        // Acme hides the "Client facts" entry from the *home* menu, though every other qualifying caller keeps
        // it: the overlay names the item by its id and sets its condition to `#never`, the documented way an
        // overlay turns an item off without the merge having to learn to delete an element. The endpoint stays
        // reachable -- this is presentation, not permission -- so an acme operator who navigates to it directly
        // still sees the page; only the menu offer is withdrawn.
        uiBlockOverlay(HMENU.block) {
            items(HFLD.menu) {
                menuItem(HMENU.cfactReference, cfactExpression = CFACT.neverName)
            }
        }

        // --- a cfact of its own -------------------------------------------------------------------------
        //
        // Declared and not produced, which is what a client declaration *is*: acme is saying the name exists
        // so that its own data may write `acmeUnderAudit` in an expression. Nothing else's registry has it,
        // which is the half that matters -- a global expression naming it would refuse to parse everywhere,
        // rather than parsing here and quietly meaning nothing anywhere else.
        cfact(
            SC.underAudit, SC.auditGroup,
            "True while a site acme is looking at has an audit open against it. Nothing sets it yet: acme " +
                "declares it ahead of the workflow that will.",
        )

        // --- a creation workflow (issue #533) --------------------------------------------------------------
        //
        // The richer of the two sample fixtures, so the gate and the view see more than one trait: an expense
        // report (required) beside a questionnaire (optional), a layout that puts the optional one first, and
        // labels pulled from a backend fragment file -- which is what drives the label boot check. Acme does
        // not include `name`, so its forms stay untitled, as they always have; globex has the name-only one.
        workflow(SW.createForm, WfEntry.creation) {
            // The page's title (issue #719), from the same fragment file as the task labels.
            label = "%{@t(\"${SF.acmeWf}.${SW.createForm}.label\")}"
            task(SW.identify, "%{@t(\"${SF.acmeWf}.${SW.identify}.label\")}") {
                trait(ST.expenseReport)
                trait(ST.questionnaire, required = false)
                layout(listOf(ST.questionnaire, ST.expenseReport))
                save(SW.create, "%{@t(\"${SF.acmeWf}.${SW.identify}.save\")}")
            }
        }

        // --- a survey workflow (issue #656) ----------------------------------------------------------------
        //
        // The second face of the create/edit paradigm: an owner revisits the same global form data outside
        // creation, over the very traits the creation workflow collected. Two tasks -- which a creation
        // workflow may not have -- so the survey exercises the multi-task allowance and the task list, and its
        // saves are `edit` (they update the existing form, they do not create a second one). Labels come from
        // the same `acmeWf` backend fragment file, so the survey's own labels ride the label boot check too.
        workflow(SW.reviewForm, WfEntry.survey) {
            label = "%{@t(\"${SF.acmeWf}.${SW.reviewForm}.label\")}"
            // The review task -- both of the creation workflow's traits in one panel (issue #710), so the survey
            // edits the same data the creation form collected rather than splitting it across two tasks. Same
            // trait pair and layout order as `createForm.identify`, with an `edit` save.
            task(SW.details, "%{@t(\"${SF.acmeWf}.${SW.details}.label\")}") {
                trait(ST.expenseReport)
                trait(ST.questionnaire, required = false)
                layout(listOf(ST.questionnaire, ST.expenseReport))
                save(SW.saveDetails, "%{@t(\"${SF.acmeWf}.${SW.details}.save\")}", WfSaveKind.edit)
            }
            // The supplied-defaults demo (issue #711): a `userInfo` trait whose `name` and `email` are both
            // prefilled from the form owner. The two prefill functions supply the values; how each is presented
            // is the trait's `defaultMode` (name `filled`, email `offer`), so one task exercises both modes.
            task(SW.profile, "%{@t(\"${SF.acmeWf}.${SW.profile}.label\")}") {
                trait(SC.userInfo)
                function(prefillFromOwner {
                    // The owner's **name** -- a person's full name. Not `publicName`, which is the login
                    // username and falls back to the email when the user has not chosen one, so it would show
                    // the email in a "Name" field. An owner with no name on file simply prefills nothing here.
                    userAttribute = PFO.name
                    targetTrait = SC.userInfo
                    targetValuePath = SC.userName
                })
                function(prefillFromOwner {
                    userAttribute = PFO.email
                    targetTrait = SC.userInfo
                    targetValuePath = SC.userEmail
                })
                save(SW.saveProfile, "%{@t(\"${SF.acmeWf}.${SW.profile}.save\")}", WfSaveKind.edit)
            }
        }

        // A normal workflow (issue #794): the many-per-form kind a user chooses for a form, and the first thing
        // the per-workflow state is computed against. Literal labels rather than fragment pulls -- this exists to
        // exercise the state machinery, and a fragment key per label would be noise until it has a real page.
        workflow(SW.auditReview, WfEntry.normal) {
            label = "Audit review"
            task(SW.recordAudit, "Record the audit") {
                trait(SC.siteAudit)
                save(SW.saveAudit, "Save the audit", WfSaveKind.edit)
            }
        }

        // --- a trait of its own -------------------------------------------------------------------------
        //
        // Supported by having been declared, without appearing in `includedTraits`: a client does not include
        // itself. Global has never heard of it, so a global reader carries one of these on the default branch.
        trait(
            SC.siteAuditEntry,
            SC.siteAudit,
            setOf(GedraDataType.formDoc),
            "An audit acme runs on one of its sites.",
        ) {
            property(SC.auditor, "Who carried out the audit.", required = true)
            property(SC.findings, "What they found.")
        }

        // The supplied-defaults demo trait (issue #711): the form owner's name and email, both prefilled by the
        // survey's `profile` task. Its `g-layout` sets each field's `defaultMode` -- `name` filled (low value:
        // shown, marked, confirm) and `email` offered (high value: a deliberate click to accept) -- which is
        // what the frontend reads to present the two differently. It also overrides the prefill-summary wording
        // (issue #710) to prove a client can reword that line; `${'$'}{count}` is resolved on the frontend.
        trait(
            SC.userInfoEntry,
            SC.userInfo,
            setOf(GedraDataType.formDoc),
            "The form owner's contact details, offered as defaults.",
        ) {
            property(SC.userName, "The owner's name.")
            property(SC.userEmail, "The owner's email address.")
            layout {
                field(SC.userName, label = "Name", defaultMode = SLDM.filled)
                field(SC.userEmail, label = "Email", defaultMode = SLDM.offer)
                string(LAYSTR.prefillSummary, $$"We filled ${count} detail(s) in from your account — save to keep them.")
            }
        }

        // --- trait-usage rules (issues #537, #538) ------------------------------------------------------
        //
        // The first thing acme's config changes about a page other than its own form: its forms *list* shows
        // an "Auditor" column, pulled from the site-audit trait's `auditor` field. Declaring any usage of its
        // own **overrides** the global default `name` column (`GedraConfigCollector.usagesFor`), so acme -- which
        // omits `name` -- shows Auditor and not a blank Name column. globex declares its own too (Name, plus a
        // Year over the yearly trait, #674); the global default is what a client declaring *no* usage inherits.
        // Two clients, two different column sets.
        //
        // Both are also **searchable** (issue #538), and between them they exercise every search kind: Auditor
        // is a `string`, searchable exact and -- with `substring` -- by a contains parameter, since a name is
        // the sort of thing half-remembered; Year is a `number` pulled from the expense report, searchable as a
        // `>=`/`<=` range. So acme's forms list can be filtered by who audited a site or by reporting year.
        traitUsage(SC.siteAudit, "Auditor", $$"${auditor}", substring = true)
        traitUsage(ST.expenseReport, "Year", $$"${year}", UsageKind.number)
    }

/**
 * A client that takes everything and builds beside it.
 *
 * The counterpart acme needs to be interesting: `SiteAddress` means one thing here and another for acme, and
 * a single client could not show that. It also shows that an **extension** is not an alteration -- a new name
 * constrains nothing, so `RichAddress` may add a field where acme's narrowing may not.
 */
private fun globexClient(cxt: KdrCxt): GedraConfig =
    gedraConfig(cxt, "${SC.globex}Client", SC.globexNamespace, SC.globex) {
        defineClient(
            ClientDef(
                clientId = SC.globex,
                name = "Globex",
                description = "Takes every global trait, and extends a type rather than narrowing one.",
                usageType = ClientUsageType.dev,
                audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                includedTraits = listOf(CLD.allGlobal),
            ),
        )

        // Globex signs its own mails (issue #773) and, unlike acme, rewords nothing else -- the one-line
        // overlay a client that only wants its name on the mails would write.
        fragmentOverlay(AFRAG.mail) {
            namespace(MCOPY.common) {
                key(MCOPY.footer, "Sent on behalf of Globex Corporation.")
            }
        }

        // --- a creation workflow (issue #533) --------------------------------------------------------------
        //
        // The plain fixture: asks for the form's name and nothing else, with literal labels -- no fragment
        // file, because a label with no template blocks is already a template. Same id as acme's, in a
        // different scope, which the per-client registries keep apart.
        workflow(SW.createForm, WfEntry.creation) {
            task(SW.identify, "Name the form") {
                trait(GT.name)
                save(SW.create, "Create form")
            }
        }

        // --- extending ------------------------------------------------------------------------------------
        //
        // A name of its own, so nothing existing changes meaning and the narrowing rules do not apply: this
        // adds a property, which an alteration could not. What it does *not* do is change `SiteAddress`, so a
        // trait referring to that still gets the global one here.
        type(SC.richAddress) {
            type = SCT.kObject
            description = "An address with a memorable locator beside it."
            property(ST.country, "Country the site is in.", required = true) {
                for (c in ST.countries) option(c)
            }
            property(ST.postcode, "Postal code, as written locally.")
            property(SC.what3words, "A three-word locator for the exact spot.")
        }

        // --- trait-usage rules (issues #537, #538, #674) ------------------------------------------------
        //
        // globex takes every global trait, so it is the sample's home for the `yearly` trait -- the multi-entry
        // one (issue #487), a record per year. Declaring a usage here is what makes its data a column, a search
        // parameter, and (issue #666) a sort key; without a rule a stored field is invisible to all three, which
        // is what #674 was filed about. The `yearly` usage reads `year` as a `number`, searchable as a `>=`/`<=`
        // range and sorted numerically -- so globex's list can be sliced by reporting year.
        //
        // Two constraints shape this, both deliberate. Declaring any usage overrides the inherited global `name`
        // column wholesale (`GedraConfigCollector.usagesFor`), so `name` is re-declared here to keep globex's Name
        // column -- globex now shows Name and Year rather than Name alone. And a usage is keyed by its trait id
        // everywhere (the display map, the search predicate, the sort, the frontend column key), so a trait gets
        // **one** column: `year` and `note` cannot both be columns of the `yearly` trait without a per-usage key,
        // which is the model change #674's second part is about. The column also reflects the *first* stored
        // `yearly` entry, since a display expression reads one entry (`computeDisplayValues`).
        traitUsage(GT.name, "Name", $$"${name}", substring = true)
        traitUsage(ST.yearly, "Year", $$"${year}", UsageKind.number)
    }
