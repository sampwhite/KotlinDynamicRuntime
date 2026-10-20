// `edge` — the KdrEdge component (issues #347, #386). A KDR node booted by `StartEdge` in the `edge` boot role
// serves this component's endpoints and its own context roots, and gates entry on "Google Env Auth".
//
// **Nothing else may compile-depend on this module.** An edge is a KDR node with a diverting front door, not a
// different runtime, so everything a *backend* needs to sit behind one — recognizing the env-auth
// header, carrying the address on the context — lives in `base/common` and stays there. The test for where a
// behavior belongs: does an ordinary node need it to behave correctly? Yes means common; no means here.
//
// `launch` takes it `runtimeOnly`, guarded so a workspace that leaves the module out still configures, and it is
// discovered through ServiceLoader with the component gating itself on the `edge` boot role -- so nothing anywhere
// names this module at compile time.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // `config` re-exports the base modules (common + kdn) via `api`: the component/service model, the
    // InstanceRegistry, the ContentServer hook, and the HTTP request types. The same dependency `appui` uses.
    implementation(project(":config"))

    // Jetty's reverse proxy (issue #419), and the only module that gets it: an ordinary node has no data
    // plane, and keeping the artifact off its classpath is part of what "nothing compile-depends on this
    // module" is protecting. It brings jetty-client and jetty-server at compile scope, which is where the
    // Handler and HttpClient types below come from. Version from the jetty-bom in `kdr.kotlin-conventions`.
    implementation("org.eclipse.jetty:jetty-proxy")
}
