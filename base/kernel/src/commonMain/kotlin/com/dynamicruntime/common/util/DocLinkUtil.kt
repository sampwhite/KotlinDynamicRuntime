package com.dynamicruntime.common.util

/**
 * Rewrites the interior links of a Markdown *document* as it should read from inside the running app (issue
 * #492). A document (a README, a help page) is authored for a Git checkout, so its links are **repo-relative**
 * -- `code-guide.md`, `examples/intellij-dev-setup.md`, `#a-heading`. Rendered verbatim in the single-page app
 * those point nowhere (or worse, a bare `#anchor` is read by the app's own hash router). This maps each one to
 * where it actually belongs:
 *
 *  - a target that is **itself a served document** ([docKeyByPath]) becomes an **in-app link** to that document
 *    ([docHref] mints the app's own URL for it), so the whole doc set browses without leaving the app;
 *  - any other repo file becomes a link into the **source repository** ([repoBlobBase], e.g.
 *    `https://github.com/owner/repo/blob/main`);
 *  - an absolute `http(s)`/`mailto` link, or anything scheme-bearing, is left exactly as written;
 *  - a same-document `#anchor` is left as written -- it targets a heading id [renderMarkdown] now emits, and
 *    the frontend's Markdown component scrolls to it in-page rather than letting the app's hash router consume it.
 *
 * Pure and transpile-safe (kernel), so the frontend runs it while rendering and a JVM test covers it. It is
 * passed to [renderMarkdown] as its `resolveUrl` hook; the renderer still runs the result through `safeUrl`,
 * so this can only ever retarget a link, never make one executable.
 *
 * When [repoBlobBase] is null (the deployment did not configure a source repo), a non-document relative link is
 * left **unchanged** -- the pre-#492 behavior, so nothing regresses.
 *
 * @param rawUrl the link target exactly as written in the Markdown.
 * @param currentSourcePath the repo-relative path of the document being rendered (e.g. `"README.md"`), against
 *   which a relative target is resolved.
 * @param docKeyByPath repo source path -> the app's key for the in-app document served from it.
 * @param repoBlobBase the source repository's blob base (`.../blob/<branch>`), or null when unconfigured.
 * @param docHref how the app addresses an in-app document by its key (its own URL scheme).
 */
fun resolveDocLink(
    rawUrl: String,
    currentSourcePath: String,
    docKeyByPath: Map<String, String>,
    repoBlobBase: String?,
    docHref: (docKey: String) -> String,
): String {
    val url = rawUrl.trim()
    if (url.isEmpty()) {
        return ""
    }
    // A same-document anchor is left as written: it targets a heading id renderMarkdown emits, which the
    // Markdown component scrolls to in-page (the app's hash router leaves an in-page anchor alone).
    if (url.startsWith("#")) {
        return url
    }
    // Scheme-bearing (http, https, mailto, ...) or protocol-relative: an external target, left untouched.
    if (hasScheme(url) || url.startsWith("//")) {
        return rawUrl
    }
    // Relative: peel off any fragment/query, resolve the path against this document's directory, then decide
    // whether it names another in-app document or a plain repo file.
    val cut = listOf(url.indexOf('#'), url.indexOf('?')).filter { it >= 0 }.minOrNull() ?: url.length
    val repoPath = normalizeRepoPath(currentSourcePath, url.substring(0, cut)) ?: return rawUrl
    val docKey = docKeyByPath[repoPath]
    if (docKey != null) {
        // An in-app document. The app fetches and renders it whole, with no heading anchors, so a fragment is
        // dropped rather than carried onto a link that could not honor it.
        return docHref(docKey)
    }
    return if (repoBlobBase != null) joinUrl(repoBlobBase, repoPath) + url.substring(cut) else rawUrl
}

/** Whether [u] carries a URL scheme -- a colon with no slash before it (`mailto:x`, `https://y`). */
private fun hasScheme(u: String): Boolean {
    val colon = u.indexOf(':')
    if (colon < 0) {
        return false
    }
    val slash = u.indexOf('/')
    return !(slash in 0 until colon)
}

/** Joins a base and a path with exactly one slash between them. */
private fun joinUrl(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

/**
 * Resolves [relative] against the directory of [currentSourcePath] into a clean repo path (`.`/`..`/empty
 * segments collapsed), or null when it cannot -- a `..` that climbs above the repo root, or an empty result --
 * in which case the caller leaves the link as written. A leading `/` makes [relative] repo-absolute.
 */
private fun normalizeRepoPath(currentSourcePath: String, relative: String): String? {
    val segments = if (relative.startsWith("/")) {
        mutableListOf()
    } else {
        currentSourcePath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    }
    for (seg in relative.split('/')) {
        when (seg) {
            "", "." -> {} // collapse redundant separators and same-directory markers
            ".." -> {
                if (segments.isEmpty()) {
                    return null // climbs above the repo root; not resolvable
                }
                segments.removeAt(segments.size - 1)
            }
            else -> segments.add(seg)
        }
    }
    return if (segments.isEmpty()) null else segments.joinToString("/")
}
