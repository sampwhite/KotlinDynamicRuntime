package com.dynamicruntime.script.deploy

import com.dynamicruntime.script.deploy.Deploy.shellQuote
import com.dynamicruntime.script.deploy.NodeReset.NRST

/**
 * How a node runs [NodeReset] (issue #1722): the command `kdr-node reset-data` and `kdr-deploy --reset-data` run over
 * ssh, and, for a target asking for it ([DeployTarget.resetDataMonthly]), the systemd **user** units that run the
 * daily check -- a service and its timer, which `kdr-deploy` writes, updates, or removes to match the target. They are
 * user units beside the application's own service, so nothing needs `sudo`; the node's user lingers (the guide's setup
 * step 6), so they run with nobody signed in.
 *
 * The units name the release through `current`, so they follow every deploy without being rewritten, and they name
 * Java by its path rather than through a shell: systemd expands `$` and `%` in a unit's command line, and a path written
 * out needs neither.
 */
@Suppress("ConstPropertyName")
object ResetUnits {
    /** When the daily check runs: early morning in the United States, quiet on the reference site. */
    const val calendar = "*-*-* 09:00:00 UTC"

    /** The name of the units for `target`: its service's, with `-reset`. */
    fun unitName(target: DeployTarget): String = "${target.service}-reset"

    /** Where the node's user units are, as a shell expands it. */
    const val unitDir = $$"\"$HOME/.config/systemd/user\""

    fun servicePath(target: DeployTarget): String = "$unitDir/${unitName(target)}.service"

    fun timerPath(target: DeployTarget): String = "$unitDir/${unitName(target)}.timer"

    /** The program's arguments after its class: the [mode], then where the node is and what runs it. */
    private fun programArgs(target: DeployTarget, mode: String): List<String> =
        listOf(mode, NRST.baseDir, target.baseDir, NRST.service, target.service, NRST.port, target.port.toString())

    /**
     * The command `kdr-node reset-data` and `kdr-deploy --reset-data` run on the node over ssh: [NodeReset] in its
     * "now" mode, from the release `current` points at, under the node's own settings (`kdr.env`, and its `JAVA_HOME`),
     * as the store-emptying run always was.
     */
    fun resetNowCommand(target: DeployTarget): String =
        "set -a; . ${shellQuote(target.envFile)}; set +a; " +
            $$"\"${JAVA_HOME:+$JAVA_HOME/bin/}java\" $${NRST.heap} -cp $${shellQuote("${target.currentLink}/lib/*")} $${NRST.mainClass} " +
            programArgs(target, NRST.now).joinToString(" ") { shellQuote(it) }

    /** Java on the node, by path: the target's `JAVA_HOME`, else the system's. */
    fun javaPath(target: DeployTarget): String = target.env["JAVA_HOME"]?.trimEnd('/')?.let { "$it/bin/java" } ?: "/usr/bin/java"

    /** One argument of a unit's command line, double-quoted as systemd reads it. */
    private fun unitArg(arg: String): String = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun header(target: DeployTarget): String =
        "# Written by kdr-deploy from the target file ${target.name}.properties (resetDataMonthly). Change the target, not\n" +
            "# this file: the next deploy writes it again, or removes it.\n"

    /** The service unit that runs the daily check: [NodeReset] in its "check" mode, once, under the node's settings. */
    fun serviceText(target: DeployTarget): String = header(target) + """
        |[Unit]
        |Description=KotlinDynamicRuntime: reset the data once it is a month old
        |
        |[Service]
        |Type=oneshot
        |WorkingDirectory=${target.baseDir}
        |EnvironmentFile=${target.envFile}
        |ExecStart=${(listOf(javaPath(target), NRST.heap, "-cp", "${target.currentLink}/lib/*", NRST.mainClass) +
        programArgs(target, NRST.check)).joinToString(" ") { unitArg(it) }}
        |""".trimMargin()

    /** The timer that runs the check daily, and at the next start when the node was down at its time. */
    fun timerText(target: DeployTarget): String = header(target) + """
        |[Unit]
        |Description=KotlinDynamicRuntime: check daily whether the data is a month old
        |
        |[Timer]
        |OnCalendar=$calendar
        |Persistent=true
        |Unit=${unitName(target)}.service
        |
        |[Install]
        |WantedBy=timers.target
        |""".trimMargin()

    /** What a deploy does to the node's units, from what `target` asks for and what the node has. */
    enum class Change { Install, Update, Unchanged, Remove, None }

    /** The [Change] for `target`, given the node's current unit texts, each null when the node has none. */
    fun change(target: DeployTarget, service: String?, timer: String?): Change {
        val present = service != null || timer != null
        return when {
            !target.resetDataMonthly -> if (present) Change.Remove else Change.None
            !present -> Change.Install
            service == serviceText(target) && timer == timerText(target) -> Change.Unchanged
            else -> Change.Update
        }
    }

    /** What the deploy plan says of a [change]. */
    fun planText(target: DeployTarget, change: Change): String = when (change) {
        Change.Install -> "install ${unitName(target)}.timer, a daily check that resets data a month old"
        Change.Update -> "rewrite ${unitName(target)}.timer and its service, a daily check that resets data a month old"
        Change.Unchanged -> "the daily check (${unitName(target)}.timer) is installed, unchanged"
        Change.Remove -> "remove ${unitName(target)}.timer and its service: the target asks for no monthly reset"
        Change.None -> "no monthly reset (the target does not ask for one)"
    }

    /** Prints the node's two unit files, each followed by a separator line, empty for one it has not got. */
    fun readCommand(target: DeployTarget): String =
        "cat ${servicePath(target)} 2>/dev/null; echo; echo $separator; cat ${timerPath(target)} 2>/dev/null; echo; echo $separator"

    const val separator = "--kdr:unit--"

    /** What [readCommand] printed, as the service's text and the timer's, each null when the node has none. */
    fun parseRead(output: String): Pair<String?, String?> {
        val parts = output.split("\n$separator")
        fun part(i: Int) = parts.getOrNull(i)?.removePrefix("\n")?.removeSuffix("\n")?.takeIf { it.isNotBlank() }?.let { "$it\n" }
        return part(0) to part(1)
    }

    /** Writes the unit file at [path] from what is sent on standard input. */
    fun writeCommand(path: String): String = "mkdir -p $unitDir && cat > $path.new && mv $path.new $path"

    /** Makes the written units known to the user's systemd, and the timer running. */
    fun enableCommand(target: DeployTarget): String =
        "systemctl --user daemon-reload && systemctl --user enable --now ${shellQuote("${unitName(target)}.timer")}"

    /** Stops and removes the units. */
    fun removeCommand(target: DeployTarget): String =
        "systemctl --user disable --now ${shellQuote("${unitName(target)}.timer")} 2>/dev/null; " +
            "rm -f ${servicePath(target)} ${timerPath(target)}; systemctl --user daemon-reload"
}
