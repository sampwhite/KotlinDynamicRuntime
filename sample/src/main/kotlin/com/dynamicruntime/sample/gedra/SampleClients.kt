package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.schema.SCT

/**
 * The sample fragment file and the keys it carries (issue #456) -- named rather than written as literals
 * because four different layers refer to them and a typo in any one is a key that silently never wins.
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
    }
