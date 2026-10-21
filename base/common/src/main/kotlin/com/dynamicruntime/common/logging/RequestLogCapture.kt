package com.dynamicruntime.common.logging

/**
 * The `_debug=captureLogs` word, and the keys of what it returns (issue #1173). Self-named, per the code guide.
 */
@Suppress("ConstPropertyName")
object RLOG {
    /** The `_debug` word that asks for a request's log lines in its response. */
    const val captureLogs = "captureLogs"

    /** Under `_meta`: the captured lines, oldest first. */
    const val logs = "logs"

    /** Under `_meta`, only when the cap cut: how many lines were left out. */
    const val logsOmitted = "logsOmitted"

    // One line.
    const val level = "level"
    const val topic = "topic"
    const val message = "message"
    const val errorClass = "errorClass"
    const val errorMessage = "errorMessage"
}

/**
 * One request's log lines, kept as well as logged (issue #1173): the [LogCapture] a request's context carries when
 * the caller asked for `_debug=captureLogs` and may have it, or when the in-process test client asks for it. Its
 * sub contexts share it, so work done on the request's behalf is kept too.
 *
 * **Bounded**, so a chatty request cannot produce a huge response: at most [maxLines] lines and [maxChars] of
 * message text in all, each message clipped to [maxLineChars]. What the cap leaves out is counted, not silently
 * dropped. A line keeps its level, topic, and message, and for an exception its class and message -- not the stack,
 * which the node's own log still has.
 *
 * [inResponse] says whether the lines go out with the response, under `_meta.logs`. The test client's capture does
 * not: it reads the lines from here, for a failure message, and leaves the response as the test would otherwise
 * see it.
 */
class RequestLogCapture(
    val inResponse: Boolean,
    val maxLines: Int = defaultMaxLines,
    val maxChars: Int = defaultMaxChars,
) : LogCapture {
    override val captureLevel: LogLevel = LogLevel.debug

    private val lines = ArrayList<Map<String, Any?>>()
    private var chars = 0
    private var omitted = 0

    @Synchronized
    override fun capture(record: LogRecord) {
        val message = record.message.clip(maxLineChars)
        if (lines.size >= maxLines || chars + message.length > maxChars) {
            omitted++
            return
        }
        chars += message.length
        lines += linkedMapOf<String, Any?>(
            RLOG.level to record.level.name,
            RLOG.topic to record.topic,
            RLOG.message to message,
        ).apply {
            record.cause?.let {
                put(RLOG.errorClass, it.javaClass.name)
                put(RLOG.errorMessage, it.message?.clip(maxLineChars))
            }
        }
    }

    /** The lines kept so far, oldest first. */
    @Synchronized
    fun lines(): List<Map<String, Any?>> = lines.toList()

    /** How many lines the cap left out. */
    @Synchronized
    fun omitted(): Int = omitted

    /** What goes under `_meta`: the lines, and the count of those left out when the cap cut. */
    @Synchronized
    fun meta(): Map<String, Any?> = linkedMapOf<String, Any?>(RLOG.logs to lines.toList()).apply {
        if (omitted > 0) put(RLOG.logsOmitted, omitted)
    }

    /** The lines as text, one to a line, for a test's failure message. */
    @Synchronized
    fun text(): String = buildString {
        for (line in lines) {
            append(line[RLOG.level]).append(' ').append(line[RLOG.topic]).append(" - ").append(line[RLOG.message])
            line[RLOG.errorClass]?.let { append(" [").append(it).append(": ").append(line[RLOG.errorMessage]).append(']') }
            append('\n')
        }
        if (omitted > 0) append("... ").append(omitted).append(" more line(s) left out\n")
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val defaultMaxLines = 200
        const val defaultMaxChars = 64_000
        const val maxLineChars = 2_000

        private fun String.clip(max: Int): String = if (length <= max) this else take(max - 1) + "…"
    }
}
