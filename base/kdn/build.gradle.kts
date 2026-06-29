// `base/kdn` — the dynamic-runtime core. Standard source tree. Depends on
// `common`.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    implementation(project(":base:common"))
}
