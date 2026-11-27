package com.dynamicruntime.trials

import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * One message a trial sends the assistant. A prompt goes on with the conversation before it unless [newConversation]
 * starts one of its own: a conversation is sent whole with every step, so one per area keeps a trial cheaper, and an area
 * that builds on the last is a prompt that goes on.
 */
class TrialPrompt(val text: String, val newConversation: Boolean = true)

/**
 * One phrasing of a trial's goal: `explicit` in the documentation's own words, `loose` as a person says it in passing, or
 * `ownWords` in an organization's own terms. Every variant of a trial is held to the trial's expectations, so two reports
 * of one trial show what the wording changed. A variant that names something exactly -- these four choices, that mail's
 * subject -- has [expectations] of its own beside them (issue #1914), which one that only means it is not held to.
 */
class TrialVariant(
    val name: String,
    val description: String,
    val prompts: List<TrialPrompt>,
    val expectations: List<Expectation> = emptyList(),
)

/** How an expectation came out. Each name matches its value. */
@Suppress("EnumEntryName")
enum class OutcomeKind {
    /** Found as expected. */
    met,

    /** Found, but not as expected: the detail says what was found instead. */
    varied,

    /** Not found. */
    notMet,

    /** Not a judgment: something worth knowing about the run. */
    info,
}

/** An expectation's outcome, and what was found. */
class Outcome(val kind: OutcomeKind, val detail: String) {
    companion object {
        fun met(detail: String) = Outcome(OutcomeKind.met, detail)
        fun varied(detail: String) = Outcome(OutcomeKind.varied, detail)
        fun notMet(detail: String) = Outcome(OutcomeKind.notMet, detail)
        fun info(detail: String) = Outcome(OutcomeKind.info, detail)
    }
}

/**
 * Something a trial expects of the end state or of how the assistant worked, checked against a [TrialResult]. A check
 * matches by meaning rather than by name -- the model chooses its own ids and labels -- and says what it found either way.
 */
class Expectation(val name: String, val check: (TrialResult) -> Outcome)

/**
 * The configuration a trial's client has before its first prompt (issue #1916): settings of the client's [definition],
 * then whole [bundles], each a `name` and its `slots` in portable form, written into the sandbox in order. A trial of
 * changes to earlier work starts here rather than asking for that work first, so every run starts from the same place,
 * and pays for no turns to get there.
 */
class TrialStart(val definition: Map<String, Any?>, val bundles: List<Map<String, Any?>>) {
    companion object {
        /** The start in the classpath resource [path], a JSON object with `clientDefinition` and `bundles`. */
        fun fromResource(path: String): TrialStart {
            val text = TrialStart::class.java.getResource(path)?.readText() ?: error("No trial start at $path.")
            val start = text.jsonMap().orEmpty()
            return TrialStart(start["clientDefinition"].toJsonMapOrEmpty(), start["bundles"].toJsonListOfMaps())
        }
    }
}

/**
 * A named, repeatable piece of work for the assistant: a [goal], the [variants] it may be phrased in, and the
 * [expectations] each is held to. A trial's client is created fresh, as a self-serve client named by [clientNameFor], and the
 * assistant configures its sandbox, from nothing or from a [start]. What it configured is then used, in the ways [walk]
 * names (issue #1915), each an expectation too. A new capability extends one trial or starts another, so a trial stays
 * small enough to run on its own.
 */
class Trial(
    val name: String,
    val goal: String,
    val clientName: String,
    val variants: List<TrialVariant>,
    val expectations: List<Expectation>,
    val walk: List<WalkStep> = emptyList(),
    val start: TrialStart? = null,
) {
    init {
        require(variants.isNotEmpty()) { "Trial '$name' has no variants." }
        require(variants.map { it.name }.toSet().size == variants.size) { "Trial '$name' names a variant twice." }
    }

    /**
     * The name of the client [variant] runs in: [clientName] with the trial and variant after it, as "Riverbend Grants
     * (grantsPolish, loose)", so the Clients page tells a run's clients apart. Their ids cannot: an id is the name's first
     * sixteen letters and a random part. A node runs one run, so the run need not be named.
     */
    fun clientNameFor(variant: TrialVariant): String = "$clientName ($name, ${variant.name})"

    /** The variant called [name], or the first when [name] is null. */
    fun variant(name: String?): TrialVariant = if (name == null) {
        variants.first()
    } else {
        variants.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException(
                "Trial '${this.name}' has no variant '$name'. Its variants: ${variants.joinToString { it.name }}.",
            )
    }
}
