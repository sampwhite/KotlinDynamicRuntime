// Settings for the `build-logic` included build. This is a standalone Gradle
// build whose only job is to compile the project's convention plugin(s) so the
// modules can apply them by id. It is version-controlled (it lives under
// KotlinDynamicRuntime/) and is wired into the main build from the
// (non-versioned) top-level settings.gradle.kts via
// `pluginManagement { includeBuild("KotlinDynamicRuntime/build-logic") }`.
rootProject.name = "build-logic"
