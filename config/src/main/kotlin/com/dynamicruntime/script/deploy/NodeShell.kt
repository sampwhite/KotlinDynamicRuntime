package com.dynamicruntime.script.deploy

import com.dynamicruntime.script.CmdResult
import com.dynamicruntime.script.Subprocess.capture
import com.dynamicruntime.script.Subprocess.inherit
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * How the deploy commands reach a node and wait on it (issues #1243, #1244), shared by `kdr-deploy` and `kdr-node`:
 * the system `ssh`, so the operator's own configuration, agent and known hosts are used and no key passes through
 * this code, and the health checks a change to the node waits for.
 */
object NodeShell {
    private val sshOptions = listOf("-o", "BatchMode=yes", "-o", "ConnectTimeout=15")

    /** Runs [command] on the node over SSH, with [stdin] as its input when given, and captures its output. */
    fun ssh(target: DeployTarget, command: String, stdin: String? = null): CmdResult =
        capture(null, "ssh", *sshOptions.toTypedArray(), target.sshDestination, command, stdin = stdin)

    /** Runs [command] on the node over SSH with its output going straight to the terminal, as a log follows. */
    fun sshToTerminal(target: DeployTarget, command: String): Boolean =
        inherit(File("."), "ssh", *sshOptions.toTypedArray(), target.sshDestination, command)

    /**
     * Waits for the application to answer as healthy on the node itself, then through [DeployTarget.publicUrl], saying
     * which it is waiting for; whether both answered.
     */
    fun healthy(target: DeployTarget): Boolean {
        print("Waiting for the application on the node")
        val nodeUp = waitFor(DPL.nodeHealthSeconds) { ssh(target, Deploy.nodeHealthCommand(target)).out == "200" }
        println(if (nodeUp) " -- up." else " -- no answer.")
        if (!nodeUp) return false
        print("Checking ${target.publicUrl}")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI.create("${target.publicUrl}/kda/health")).timeout(Duration.ofSeconds(10)).GET().build()
        val publicUp = waitFor(DPL.publicHealthSeconds) {
            runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }.getOrDefault(false)
        }
        println(if (publicUp) " -- up." else " -- no answer through the public address (the node itself answers).")
        return publicUp
    }

    /** Asks [question] on the terminal; whether the answer was yes. */
    fun confirm(question: String): Boolean {
        print("$question [y/N] ")
        return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
    }

    private fun waitFor(seconds: Int, check: () -> Boolean): Boolean {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < until) {
            if (check()) return true
            print(".")
            Thread.sleep(2000)
        }
        return false
    }
}
