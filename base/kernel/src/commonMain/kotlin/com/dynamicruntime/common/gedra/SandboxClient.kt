package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException

/**
 * The fixed pieces of a **sandbox** client's id (issue #927, part of the Shadow Sandbox, #925).
 *
 * A sandbox is a second client linked to a parent: it runs the parent's latest configuration with users and
 * data of its own, and is where a parent admin sees unpublished changes before publishing them. Its id says
 * both that it is a sandbox and whose -- `acme:sandbox` is Acme's -- so nothing has to be looked up to find the
 * parent, and the id sorts beside its parent in any listing.
 *
 * **No author can write one.** A colon is the one character a sandbox id has that an authored client id may
 * not: [GedraId] accepts a colon in a client only in this form, and `gedraConfig` refuses a sandbox as the
 * client a config is filed under, and a client definition whose id holds a colon. So the only way a sandbox id
 * comes to exist is the system making one from its parent.
 *
 * The form is `<parent>:<role>` with [role] the only role today; the trailing word leaves room for a second
 * kind of linked client without committing to one.
 *
 * ### Where a client id is embedded, and what the colon does there
 *
 * Audited when the form was introduced, so the next place a client id is put should be judged the same way:
 * - **Gedra ids** keep `.` and `~` as their separators; a colon in the client segment does not disturb parsing.
 * - **Endpoint copies** put the client in the path (`/gedra/acme:sandbox/formDoc`); a colon is legal unescaped in
 *   a path segment. The endpoint key `"$path:$method"` is built and looked up whole, never split, so the colon
 *   in the path is harmless there. The server matches on the decoded path, and the edge forwards the decoded
 *   path, so a caller that percent-encodes the colon reaches the same endpoint.
 * - **Webapp hash routes** (`c=acme:sandbox`) are encoded on the way out and decoded on the way in.
 * - **Keys built by prefix** (the UiBlock predicate cache's `"$client|…"`) stay distinct: `acme|` is not a prefix
 *   of `acme:sandbox|`.
 * - **Schema namespaces** built from a client (`client.<clientId>`, `"${client}Copy"`) never see a sandbox, because
 *   a sandbox owns no configuration and `gedraConfig` refuses to file one under it. That matters: an OpenAPI
 *   component key may not hold a colon.
 * - **File paths**: none holds a client id today. A future per-client file or object-store key layout has to
 *   encode the colon, which Windows forbids in a file name.
 */
@Suppress("ConstPropertyName")
object SBX {
    /** Separates a sandbox id's parent from its role. A colon appears in a client id nowhere else. */
    const val roleSep = ':'

    /** The role that ends every sandbox id. */
    const val role = "sandbox"

    /** What every sandbox id ends in: the separator and the role. */
    const val suffix = "$roleSep$role"
}

/**
 * The parent [client] is the sandbox of, or null when [client] is not a sandbox id -- by form alone: exactly one
 * colon, followed by [SBX.role], after a non-empty parent. Whether that parent exists, or has a sandbox, is not
 * a question an id can answer.
 */
fun sandboxParentOf(client: String): String? {
    if (!client.endsWith(SBX.suffix)) return null
    val parent = client.dropLast(SBX.suffix.length)
    return parent.takeIf { it.isNotEmpty() && SBX.roleSep !in it }
}

/** Whether [client] is a sandbox id, `<parent>:sandbox`; see [sandboxParentOf]. */
fun isSandboxClient(client: String): Boolean = sandboxParentOf(client) != null

/**
 * The id of [parent]'s sandbox. Refuses a parent that is itself a sandbox (or holds a colon for any other
 * reason), since a sandbox has no sandbox of its own, and the [GID.globalClient], which owns application-level
 * data rather than a client's and so has nothing to preview. The parent's own spelling is [GedraId]'s to judge,
 * where the sandbox id is first used.
 */
fun sandboxOf(parent: String): String {
    if (SBX.roleSep in parent) {
        throw KdrException.mkInput(
            "'$parent' cannot have a sandbox: it holds a colon, so it is a sandbox already or not a client id.",
        )
    }
    if (parent == GID.globalClient) {
        throw KdrException.mkInput("The '${GID.globalClient}' client has no sandbox: it owns application data, not a client's.")
    }
    return parent + SBX.suffix
}
