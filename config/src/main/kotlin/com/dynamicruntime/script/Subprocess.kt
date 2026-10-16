package com.dynamicruntime.script

import java.io.File

/** A finished subprocess: its exit code and its combined output. */
data class CmdResult(val code: Int, val out: String) {
    val failed: Boolean get() = code != 0
}

/**
 * Running another program from a command-line tool (`kdr-prune-branches`, `kdr-deploy`): either capturing what it
 * says, or letting it talk to the terminal. A program that cannot be started at all is reported as a failure rather
 * than thrown, so a tool can say what it was trying to do.
 */
object Subprocess {
    /** The exit code reported for a program that could not be started. */
    const val couldNotRunExit = 1

    /**
     * Runs [cmd] in [dir] (the current directory when null) and captures its output, with [stdin] as its input when
     * given and none otherwise. Stderr is merged into stdout: these commands say little, and one stream cannot then
     * fill its pipe while we are reading the other.
     */
    fun capture(dir: File?, vararg cmd: String, stdin: String? = null): CmdResult =
        try {
            val builder = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
            if (stdin == null) builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            val process = builder.start()
            if (stdin != null) process.outputStream.bufferedWriter().use { it.write(stdin) }
            val out = process.inputStream.bufferedReader().use { it.readText() }
            CmdResult(process.waitFor(), out.trim())
        } catch (e: Exception) {
            CmdResult(couldNotRunExit, "could not run ${cmd.firstOrNull()}: ${e.message}")
        }

    /** Runs [cmd] in [dir] with its output going straight to the terminal. True on a zero exit. */
    fun inherit(dir: File, vararg cmd: String): Boolean =
        try {
            ProcessBuilder(*cmd)
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            System.err.println("could not run ${cmd.firstOrNull()}: ${e.message}")
            false
        }
}
