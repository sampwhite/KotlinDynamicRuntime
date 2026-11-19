// `assistantTrials` -- named, repeatable runs of the configuration assistant against the real model (issue #1859): a
// node started with the assistant on, a fresh client per trial configured through prompts alone, and a report of what the
// assistant did and where it varied from what was expected. Development only, as `multiNodeTest` is: nothing depends on
// it, `launch` does not carry it, and its runs sit outside `check`, `test`, and `allTests`, since a run spends money.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // The harness talks to a node over HTTP, as the configuration assistant's page does; it never links the assistant.
    implementation(project(":config"))

    // Its own unit test runs a trial in-process against a scripted model, so the assistant is on the test classpath alone,
    // and only when the workspace builds it.
    findProject(":assistant")?.let { testImplementation(project(it.path)) }
}

val launchProject = project(":launch")

/** What both tasks hand the harness: where the node's classpath is, and where runs are kept. */
fun JavaExec.harness(command: String) {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.dynamicruntime.trials.TrialRunnerKt")
    systemProperty("kdr.trials.launchJar", launchProject.layout.buildDirectory.file("libs/launch-path.jar").get().asFile.path)
    systemProperty("kdr.trials.runsDir", layout.buildDirectory.dir("trialRuns").get().asFile.path)
    args(command)
}

// `./gradlew :assistantTrials:runTrials -Ptrials=grantsBasics` starts a node on the first free port of 7076-7079, runs the
// named trials, writes the report, and leaves the node running. Not `run`: a bare `./gradlew run` reaches every project's
// task of that name, and a run of this one spends money. `-Ptrials=list` lists the trials and their variants.
tasks.register<JavaExec>("runTrials") {
    description = "Runs named configuration-assistant trials against the real model (-Ptrials=<name>,<name>; costs money)."
    dependsOn(":launch:pathingJar")
    harness("run")
    for (option in listOf("trials", "variant", "attach", "model", "effort", "budget", "provider")) {
        providers.gradleProperty(option).orNull?.let { args("--$option", it) }
    }
}

// `./gradlew :assistantTrials:stopTrials` lists the trial nodes still running; `-Pport=7076` or `-Pport=all` stops them.
tasks.register<JavaExec>("stopTrials") {
    description = "Lists the trial nodes still running, and stops the ones -Pport names (a port, or all)."
    harness("stop")
    providers.gradleProperty("port").orNull?.let { args("--port", it) }
}

// `./gradlew :assistantTrials:restartTrials -Prun=20261119-093707` starts a run's node again, on its port and database and
// from the run's own copy of the jars, after a reboot or anything else that stopped it.
tasks.register<JavaExec>("restartTrials") {
    description = "Starts a trial run's node again, as the run left it (-Prun=<runId>)."
    dependsOn(":launch:pathingJar")
    harness("restart")
    providers.gradleProperty("run").orNull?.let { args("--run", it) }
}
