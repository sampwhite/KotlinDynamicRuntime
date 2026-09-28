// `multiNodeTest` -- the multi-node test harness and the in-backend fixtures it drives (issue #872). Loaded for
// development: `launch` puts it on its runtime classpath (guarded, since a workspace's settings may not include
// it) and leaves it out of the deployable distribution. Its `MultiNodeTestComponent` stays inert unless its
// configuration switches it on.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    implementation(project(":config"))
}

// The harness: `./gradlew :multiNodeTest:harness` starts three backends on a dedicated Postgres database and runs
// the scenario suites against them; `-Pattach=http://localhost:7071,http://localhost:7073` runs them against nodes
// already running instead (started by hand, say, in IntelliJ). Outside `test allTests`: it needs Postgres, starts
// processes, and takes a minute or two.
val launchProject = project(":launch")
tasks.register<JavaExec>("harness") {
    group = "verification"
    description = "Runs the multi-node test suites against backends it starts (or -Pattach=<urls>)."
    dependsOn(":launch:pathingJar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.dynamicruntime.multinode.MultiNodeHarnessKt")
    systemProperty("kdr.multiNode.launchJar", launchProject.layout.buildDirectory.file("libs/launch-path.jar").get().asFile.path)
    systemProperty("kdr.multiNode.logDir", layout.buildDirectory.dir("multiNode").get().asFile.path)
    providers.gradleProperty("attach").orNull?.let { args("--attach", it) }
    providers.gradleProperty("suite").orNull?.let { args("--suite", it) }
}
