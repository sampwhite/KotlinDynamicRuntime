package com.dynamicruntime.trials

/**
 * One message a trial sends the assistant. A prompt goes on with the conversation before it unless [newConversation]
 * starts one of its own: a conversation is sent whole with every step, so one per area keeps a trial cheaper, and an area
 * that builds on the last is a prompt that goes on.
 */
class TrialPrompt(val text: String, val newConversation: Boolean = true)

/**
 * One phrasing of a trial's goal: `explicit` in the documentation's own words, `loose` as a person says it in passing, or
 * `ownWords` in an organization's own terms. Every variant of a trial is held to the same expectations, so two reports of
 * one trial show what the wording changed.
 */
class TrialVariant(val name: String, val description: String, val prompts: List<TrialPrompt>)

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
 * A named, repeatable piece of work for the assistant: a [goal], the [variants] it may be phrased in, and the
 * [expectations] each is held to. A trial's client is created fresh, as a self-serve client called [clientName], and the
 * assistant configures its sandbox. A new capability extends one trial or starts another, so a trial stays small enough
 * to run on its own.
 */
class Trial(
    val name: String,
    val goal: String,
    val clientName: String,
    val variants: List<TrialVariant>,
    val expectations: List<Expectation>,
) {
    init {
        require(variants.isNotEmpty()) { "Trial '$name' has no variants." }
        require(variants.map { it.name }.toSet().size == variants.size) { "Trial '$name' names a variant twice." }
    }

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
