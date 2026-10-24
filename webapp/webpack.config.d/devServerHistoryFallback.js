// Path-based routing (issue #1370, stage 1 of #1125): the dev server answers a deep path -- /docs/x -- with
// index.html, as the backend answers /wa/docs/x with the shell, so a refresh or a pasted link at a page's path
// loads the app rather than a 404. Only a request that asks for HTML (a page load) is answered so, and only when
// no file is there: the bundle, the stylesheet, and the proxied API are untouched. The dot rule (a path with a dot
// in its last segment is a file) is off, since a form's id has dots in it: /forms/gd.fd.acme.e2026... is a page.
// Kotlin/JS merges every file in webpack.config.d into the generated config; the dev-server block it builds is
// in build.gradle.kts, and has no setting for this.
if (config.devServer) {
    config.devServer.historyApiFallback = { disableDotRule: true, htmlAcceptHeaders: ["text/html"] };
}
