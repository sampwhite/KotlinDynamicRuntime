// `assistant` -- the workflow assistant (issues #1524-#1527): client administrators describe a workflow in words, and
// Claude, using this application's own endpoints as tools, drafts it in their sandbox for a person to try and publish.
//
// **Nothing else may compile-depend on this module**, as with `edge`: `launch` takes it `runtimeOnly`, guarded so a
// workspace that leaves the module out still configures, and it is discovered through ServiceLoader. What the webapp
// and core must name of it -- paths, fields, the cfact -- is in the kernel's `ASST`.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // `config` re-exports the base modules (common + kdn) via `api`, as for `edge`.
    implementation(project(":config"))

    // The official Anthropic Java SDK, and the only module that gets it: one file (`AnthropicModel.kt`) imports it,
    // as `S3Api.kt` is the one that imports AWS's. All of what it brings ships in the distribution: OkHttp and Okio,
    // Jackson with its Kotlin, JDK 8, and java.time modules, kotlin-reflect (resolved to the build's Kotlin), the
    // victools JSON-schema generator with its Jackson and Swagger modules, swagger-annotations, opentelemetry-api,
    // standardwebhooks, error_prone_annotations, and kotlin-stdlib-jdk8 1.9.10, a jar of its own beside the build's
    // standard library, which takes precedence over the SDK's.
    implementation("com.anthropic:anthropic-java:2.73.0")
}
