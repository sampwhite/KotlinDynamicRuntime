package com.dynamicruntime.trials

import com.dynamicruntime.common.util.jsonArray
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr
import java.io.File
import kotlin.math.roundToLong
import kotlin.time.Instant

/** Where a run's node is, and how it was set up, for the report's header. */
class RunInfo(
    val runId: String,
    val nodeUrl: String,
    /** The node's port, when the harness started it and so can stop it. */
    val port: Int?,
    val settings: Map<String, String>,
    val commit: String?,
    val startedAt: Instant,
)

/**
 * A run's report (issue #1859), in Markdown: per trial, the expectations and what was found, the turns with their tool
 * calls and replies, and the client's whole configuration -- which is also written beside the report, a file per bundle,
 * to inspect or diff on its own.
 */
@Suppress("ConstPropertyName")
object TrialReport {
    /**
     * The input price per million tokens, in dollars, of each model the assistant accepts (`KDR_ASSISTANT_MODEL`). A
     * turn's weighted tokens count an input token as one and an output token as five, as these models bill them, so the
     * weighted tokens times this price is about what the turn cost: a little over, since a cache read is weighted at a
     * tenth of an input token and some of these models bill it at less.
     */
    private val inputPricePerMillion = mapOf(
        "claude-sonnet-5-5" to 2.0, "claude-opus-5-5" to 4.0, "claude-fable-5-1" to 10.0, "claude-fable-5" to 10.0,
        "claude-mythos-5-1" to 10.0,
    )

    /** About what [weighted] tokens cost on [model], as dollars and cents; null for a model with no price here. */
    fun cost(weighted: Long, model: String?): String? = inputPricePerMillion[model]?.let { price ->
        val cents = (weighted * price / 10_000.0).roundToLong()
        "$%d.%02d".format(cents / 100, cents % 100)
    }

    /** Writes the report and the configuration files into [runDir]; answers the report's file. */
    fun write(runDir: File, info: RunInfo, results: List<Pair<TrialResult, String>>): File {
        runDir.mkdirs()
        for ((result, _) in results) {
            val dir = File(runDir, "$configDir/${slug(result)}").also { it.mkdirs() }
            for ((name, bundle) in result.config) File(dir, "$name.json").writeText(bundle.toJsonStr() + "\n")
            File(runDir, transcriptsDir).mkdirs()
            File(runDir, "$transcriptsDir/${slug(result)}.json").writeText(
                result.conversations.map { mapOf("conversationId" to it.conversationId, "turns" to it.turns, "messages" to it.messages) }
                    .toJsonStr() + "\n",
            )
        }
        val report = File(runDir, "report.md")
        report.writeText(render(info, results))
        return report
    }

    /** The directories of a run holding each trial's transcript, and its configuration by bundle. */
    const val transcriptsDir = "transcripts"
    private const val configDir = "config"

    fun slug(r: TrialResult): String = slug(r.trial, r.variant)

    private fun slug(trial: Trial, variant: TrialVariant): String = "${trial.name}-${variant.name}"

    /**
     * The results [runDir] holds, of the variants of [trials] there are now, read back from what [write] wrote: the
     * conversations and the configuration, which are all a check reads. What else a result says -- its client, its problems,
     * its times -- is the run's own report's.
     */
    fun read(runDir: File, trials: List<Trial>): List<TrialResult> =
        trials.flatMap { trial -> trial.variants.mapNotNull { read(runDir, trial, it) } }

    private fun read(runDir: File, trial: Trial, variant: TrialVariant): TrialResult? {
        val slug = slug(trial, variant)
        val transcript = File(runDir, "$transcriptsDir/$slug.json").takeIf { it.isFile } ?: return null
        val conversations = transcript.readText().jsonArray().toJsonListOfMaps().map {
            TrialConversation(
                it["conversationId"].toOptStr().orEmpty(), it["turns"].toJsonListOfMaps(), it["messages"].toJsonListOfMaps(),
            )
        }
        val config = File(runDir, "$configDir/$slug").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .associate { it.name.removeSuffix(".json") to it.readText().jsonMap().orEmpty() }
        val at = Instant.fromEpochMilliseconds(transcript.lastModified())
        return TrialResult(trial, variant, null, null, conversations, config, emptyList(), at, at)
    }

    /** A saved run held to the checks as they are now: each result's expectations, as the report gives them. */
    fun renderRecheck(runId: String, results: List<TrialResult>): String = buildString {
        appendLine("# Assistant trials: run $runId, rechecked")
        appendLine()
        appendLine("The run's transcripts and configuration, held to the checks as they are now. The report beside this says what")
        appendLine("the checks of its day did.")
        for (r in results) {
            appendLine()
            appendLine("## ${r.trial.name}, ${r.variant.name}")
            appendLine()
            renderExpectations(r)
        }
    }

    /** The report itself. [results] pairs each trial's result with the address of the person who ran it. */
    fun render(info: RunInfo, results: List<Pair<TrialResult, String>>): String = buildString {
        appendLine("# Assistant trials: run ${info.runId}")
        appendLine()
        appendLine("- **Started:** ${info.startedAt}")
        appendLine("- **Node:** ${info.nodeUrl}" + (info.port?.let { " (started by the harness; still running)" } ?: " (attached)"))
        info.commit?.let { appendLine("- **Commit:** $it") }
        for ((k, v) in info.settings) appendLine("- **$k:** $v")
        appendLine()
        appendLine("| Trial | Variant | Met | Varied | Not met | Turns | Weighted tokens | About |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        for ((r, _) in results) {
            val counts = r.outcomes().groupingBy { it.second.kind }.eachCount()
            appendLine(
                "| ${r.trial.name} | ${r.variant.name} | ${counts[OutcomeKind.met] ?: 0} | ${counts[OutcomeKind.varied] ?: 0} | " +
                    "${counts[OutcomeKind.notMet] ?: 0} | ${r.turns.size} | ${r.weighted} | ${cost(r.weighted, r.model) ?: "?"} |",
            )
        }
        appendLine()
        appendLine("## Looking at the results")
        appendLine()
        appendLine("Open ${info.nodeUrl}/wa and use **Become a user…** with a trial's address, below, to act as its administrator.")
        appendLine("The assistant's drafts are in the client's sandbox (open it from the bar if the session is not in it), and its")
        appendLine("conversations are on the Configuration assistant page, where you can go on with one and ask why it chose what")
        appendLine("it did.")
        info.port?.let {
            appendLine()
            appendLine("Stop the node when you are done: `./gradlew :assistantTrials:stopTrials -Pport=$it`.")
        }
        for ((r, email) in results) renderTrial(r, email)
    }

    private fun StringBuilder.renderTrial(r: TrialResult, email: String) {
        appendLine()
        appendLine("## ${r.trial.name}, ${r.variant.name}")
        appendLine()
        appendLine(r.trial.goal)
        appendLine()
        appendLine("- **Variant:** ${r.variant.description}")
        appendLine("- **Administrator:** `$email`")
        appendLine("- **Client:** `${r.client ?: "none"}`, sandbox `${r.sandbox ?: "none"}`")
        appendLine("- **Model:** ${r.models.joinToString().ifEmpty { "unknown" }}")
        appendLine("- **Took:** ${r.endedAt - r.startedAt}; ${r.weighted} weighted tokens, about ${cost(r.weighted, r.model) ?: "?"}")
        appendLine()
        appendLine("### Expectations")
        appendLine()
        renderExpectations(r)
        if (r.problems.isNotEmpty()) {
            appendLine()
            appendLine("### Problems")
            appendLine()
            r.problems.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("### Turns")
        for (conversation in r.conversations) {
            for (turn in conversation.turns.map { TurnInfo(it) }) {
                appendLine()
                appendLine("#### ${turn.text.take(80).replace("\n", " ")}${if (turn.text.length > 80) "…" else ""}")
                appendLine()
                appendLine(
                    "${turn.status}${turn.note?.let { " ($it)" }.orEmpty()}; ${turn.steps} steps, ${turn.weighted} weighted tokens; " +
                        "conversation `${conversation.conversationId}`",
                )
                appendLine()
                turn.text.lines().forEach { appendLine("> $it") }
                val calls = conversation.calls.filter { it.turnId == turn.turnId }
                if (calls.isNotEmpty()) {
                    appendLine()
                    calls.forEachIndexed { i, call ->
                        val refused = if (call.isError) " **refused:** ${call.result.take(200).replace("\n", " ")}" else ""
                        appendLine("${i + 1}. `${call.summary.replace("`", "'")}`$refused")
                    }
                }
                appendLine()
                appendLine("**Reply:**")
                appendLine()
                conversation.reply(turn.turnId).ifBlank { "(none)" }.lines().forEach { appendLine("> $it") }
            }
        }
        appendLine()
        appendLine("### Configuration")
        appendLine()
        appendLine("Each bundle is also in `config/${slug(r)}/`, a file per bundle.")
        for ((name, bundle) in r.config) {
            appendLine()
            appendLine("<details><summary><code>$name</code></summary>")
            appendLine()
            appendLine("```json")
            appendLine(bundle.toJsonStr())
            appendLine("```")
            appendLine()
            appendLine("</details>")
        }
    }

    private fun StringBuilder.renderExpectations(r: TrialResult) {
        appendLine("| Outcome | Expectation | Found |")
        appendLine("| --- | --- | --- |")
        for ((expectation, outcome) in r.outcomes()) {
            appendLine("| ${label(outcome.kind)} | ${cell(expectation.name)} | ${cell(outcome.detail)} |")
        }
    }

    private fun label(kind: OutcomeKind): String = when (kind) {
        OutcomeKind.met -> "met"
        OutcomeKind.varied -> "**varied**"
        OutcomeKind.notMet -> "**not met**"
        OutcomeKind.info -> "info"
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")
}
