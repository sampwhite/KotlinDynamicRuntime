package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.home.HFRAG

/**
 * A **search keyword** (issues #1594, #1596): a kind of call to action the platform provides behavior for -- a verb on a
 * link, a filter, a column, a status -- so a closed list ([CTAK]) rather than something a client invents.
 *
 * A CTA carrying [name] sets two facts, both derived and never declared by a workflow:
 *  - [statusCfact] on the **form**, one of [WSC]: what the Status column reads, and what another workflow's eligibility
 *    tests ("anything awaiting review"). `review` sets `kdr:needsReview`, so there is one mechanism answering "what
 *    needs review".
 *  - [workflowCfact] in the **workflow's own** cfacts, for its display branches.
 *
 * Its [copy] holds templates, not words: each pulls its text from a fragment file, so a client rewords a keyword as it
 * rewords other copy.
 */
class CtaKeyword(
    val name: String,
    val statusCfact: String,
    val workflowCfact: String,
    val copy: CtaKeywordCopy,
)

/**
 * A keyword's words (issue #1596), each a template evaluated like a label -- in the backend pass, which pulls the
 * fragment it names (`%{@t(...)}`) for the caller's client, overlays applied. As shipped each pulls the `home` file's
 * `cta<Name>` namespace ([ofHomeFile]).
 */
class CtaKeywordCopy(
    /** The verb on a link to the CTA ("Review"). */
    val verb: String,
    /** The label of the listing's filter for it. */
    val filterLabel: String,
    /** A listing column's header for it. */
    val columnHeader: String,
    /** A report's label for it. */
    val reportLabel: String,
    /** The text of the status it sets ("Needs Review"): what the Status column shows. */
    val statusText: String,
) {
    /** The five templates by their field names ([CTKF]), in declaration order. */
    fun byField(): Map<String, String> = linkedMapOf(
        CTKF.verb to verb, CTKF.filterLabel to filterLabel, CTKF.columnHeader to columnHeader,
        CTKF.reportLabel to reportLabel, CTKF.statusText to statusText,
    )

    companion object {
        /** The `home` file's namespace for keyword [name]'s words: `ctaReview` for `review`. */
        fun homeNamespace(name: String): String = "cta" + name.replaceFirstChar { it.uppercase() }

        /** Templates pulling each of keyword [name]'s words from its `home` file namespace ([homeNamespace]). */
        fun ofHomeFile(name: String): CtaKeywordCopy {
            fun pull(key: String) = "%{@t(\"${HFRAG.home}.${homeNamespace(name)}.$key\")}"
            return CtaKeywordCopy(
                pull(CTKF.verb), pull(CTKF.filterLabel), pull(CTKF.columnHeader), pull(CTKF.reportLabel), pull(CTKF.statusText),
            )
        }
    }
}

/** The fields a keyword's words travel under (issue #1596): [CtaKeywordCopy]'s, plus the [keyword] they belong to. */
@Suppress("ConstPropertyName")
object CTKF {
    const val keyword = "keyword"
    const val verb = "verb"
    const val filterLabel = "filterLabel"
    const val columnHeader = "columnHeader"
    const val reportLabel = "reportLabel"
    const val statusText = "statusText"

    /** The named type of one keyword's words, resolved for a caller. */
    const val wordsType = "CtaKeywordWords"
}

/**
 * The **search keywords** a call to action may carry (issue #1594): the closed list, each a [CtaKeyword]. A task's
 * [WFD.keyword] is held to it, and an approval task carries [review] without saying so.
 */
@Suppress("ConstPropertyName")
object CTAK {
    /** The CTA waits on someone to look the form over and act on it. What an approval task carries without saying so. */
    const val review = "review"

    /** The workflow's own fact while its CTA carries [review] (issue #1596), for its display branches. */
    const val reviewCfact = "kdr:ctaReview"

    /** Every keyword, in the order a page lists them. */
    val keywords: List<CtaKeyword> = listOf(
        CtaKeyword(review, statusCfact = WSC.needsReview, workflowCfact = reviewCfact, copy = CtaKeywordCopy.ofHomeFile(review)),
    )

    /** Every keyword's name, in the order a page lists them. */
    val all: List<String> = keywords.map { it.name }

    /** The keyword named [name], or null for none or a name not on the list. */
    fun of(name: String?): CtaKeyword? = keywords.firstOrNull { it.name == name }

    /** The form-level status cfacts the keywords set ([CtaKeyword.statusCfact]): never a workflow's to declare. */
    val statusCfacts: Set<String> = keywords.map { it.statusCfact }.toSet()
}
