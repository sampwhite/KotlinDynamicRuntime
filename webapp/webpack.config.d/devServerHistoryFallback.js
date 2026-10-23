// Path-based routing (issue #1370, stage 1 of #1125): the dev server answers a deep path -- /docs/x -- with
// index.html, as the backend answers /wa/docs/x with the shell, so a refresh or a pasted link at a page's path
// loads the app rather than a 404. The fallback leaves alone a request for a file (one with a dot) and any
// request that does not ask for HTML, so the bundle, the stylesheet, and the proxied API are untouched.
// Kotlin/JS merges every file in webpack.config.d into the generated config; the dev-server block it builds is
// in build.gradle.kts, and has no setting for this.
if (config.devServer) {
    config.devServer.historyApiFallback = true;
}
