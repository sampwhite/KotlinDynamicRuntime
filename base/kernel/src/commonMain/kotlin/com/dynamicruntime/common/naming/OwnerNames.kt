package com.dynamicruntime.common.naming

import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.util.isVariableName

/**
 * The fixed pieces of **owner-rooted** names (issue #921): what makes a client's definitions and a global one's
 * disjoint by construction, so that no release of the runtime -- which cannot see who is downstream -- can add a
 * name some client already uses.
 *
 * The principle: **constrain the side the runtime mints, not the side authors write.** Clients are created through
 * the runtime, so their names are held to a shape no global name has; components are written by anyone, so each
 * owner of them takes a **root** and puts it on every name it defines. Core's one root is `kdr`, and it never
 * takes another; a root has exactly one owner, and whoever owns `abc` owns every `abc.` type and every `abc:`
 * trait, cfact, workflow and task.
 *
 * Two spellings, one idea:
 * - **Types** are rooted by their **namespace**: `kdr.financial.ExpenseReport` for a global type,
 *   `client.acme.ExpenseReport` for a client's -- a client's namespace is always under the reserved root
 *   `client`, so a client id itself needs no reservation. A dot, because a type name becomes a `$defs` key
 *   and an OpenAPI component key, which may not hold a colon.
 * - **Traits, cfacts, workflows, and tasks** are rooted with a colon, `kdr:expenseReport`, because those names end
 *   up in data, where a dot is read as a path. A client's are bare, and a colon in a client's configuration only
 *   ever *refers* to another owner's definition.
 *
 * A sandbox client's id (`acme:sandbox`, see `SBX`) also holds a colon, and is a different thing: a client id is
 * never parsed where a rooted name is, and the two must never be made to share a parser.
 */
@Suppress("ConstPropertyName")
object OWNR {
    /** Separates a rooted name's root from its local part: `kdr:expenseReport`. */
    const val rootSep = ':'

    /** Core's one root (issue #950). It never takes another, which is what keeps an upgrade from colliding. */
    const val kdrRoot = "kdr"

    /** The reserved root every client namespace sits under (issue #949): `client.acme`. */
    const val clientRoot = "client"

    /** Separates a namespace's segments, and a qualified type name's namespace from its name: `client.acme.Form`. */
    const val namespaceSep = '.'
}

/** The kinds of name that are rooted with a colon, each with the rule its local part is held to. */
@Suppress("EnumEntryName")
enum class OwnedNameKind(val label: String) {
    /**
     * A trait id: letters, digits and `_`. **No dot**: a trait id becomes a field name in flattened and indexed data,
     * where a tool reads a dot as a path (OpenSearch expands `a.b` into nested objects).
     */
    trait("trait id"),

    /** A cfact name: letters, digits, `_` and `.` -- the cfact grammar's name characters, less the colon. */
    cfact("cfact name"),

    /** A workflow id: a variable name, since a workflow is addressed by it from code, data, and stored references. */
    workflow("workflow id"),

    /** A task id: a variable name, as a workflow id is. */
    task("task id");

    /** Whether [local] is a legal local part for this kind. */
    fun isLocalPart(local: String): Boolean = when (this) {
        trait -> local.isNotEmpty() && local.all { it.isAsciiLetterOrDigit() || it == '_' }
        cfact -> local.isNotEmpty() && local.all { it.isLetterOrDigit() || it == '_' || it == '.' }
        workflow, task -> local.isVariableName()
    }

    /** The local-part rule, as a phrase for a refusal. */
    val localRule: String get() = when (this) {
        trait -> "letters, digits and underscores"
        cfact -> "letters, digits, underscores and dots"
        workflow, task -> "a variable name: a letter or underscore, then letters, digits and underscores"
    }
}

/** Whether [root] is a legal owner root: ASCII letters and digits, starting with a letter. */
fun isOwnerRoot(root: String): Boolean =
    root.isNotEmpty() && root[0].isAsciiLetter() && root.all { it.isAsciiLetterOrDigit() }

/**
 * What is wrong with [name] as a **client's own** [kind] name, or null: it must be bare -- a colon means a
 * reference to another owner's definition, never one of the client's -- and its local part must follow [kind]'s
 * rule. What every client configuration is held to, at the write and at load.
 */
fun clientNameProblem(kind: OwnedNameKind, name: String): String? = when {
    OWNR.rootSep in name ->
        "A client's own ${kind.label} may not hold '${OWNR.rootSep}': '$name' reads as another owner's " +
            "definition. A client defines with bare names and refers to another owner's with rooted ones."
    !kind.isLocalPart(name) -> "'$name' cannot be a ${kind.label}: it has to be ${kind.localRule}."
    else -> null
}

/**
 * What is wrong with [name] as a **rooted** [kind] name `<root>:<local>`, or null: exactly one colon, an
 * [isOwnerRoot] root, and a local part following [kind]'s rule. What a global name is held to.
 */
fun rootedNameProblem(kind: OwnedNameKind, name: String): String? {
    val at = name.indexOf(OWNR.rootSep)
    if (at < 0 || name.indexOf(OWNR.rootSep, at + 1) >= 0) {
        return "'$name' is not a rooted ${kind.label}: it has to be '<root>${OWNR.rootSep}<name>', with exactly one " +
            "'${OWNR.rootSep}'."
    }
    val root = name.substring(0, at)
    if (!isOwnerRoot(root)) {
        return "'$root' in '$name' is not an owner root: a root is letters and digits, starting with a letter."
    }
    val local = name.substring(at + 1)
    if (!kind.isLocalPart(local)) return "'$local' in '$name' cannot be a ${kind.label}: it has to be ${kind.localRule}."
    return null
}

/** Whether [name] is well formed as a [kind] name, bare or rooted. What a parser or a constructor can check. */
fun isOwnedName(kind: OwnedNameKind, name: String): Boolean =
    if (OWNR.rootSep in name) rootedNameProblem(kind, name) == null else kind.isLocalPart(name)

/**
 * The namespace [client]'s own types live under (issue #949): `client.<clientId>`. A sandbox has none of its own --
 * it runs, and shows, its parent's (issue #928) -- so for a sandbox this is its parent's.
 */
fun clientNamespace(client: String): String = "${OWNR.clientRoot}${OWNR.namespaceSep}${sandboxParentOf(client) ?: client}"

/** Whether [namespace] is [client]'s own: [clientNamespace] itself, or beneath it (`client.acme.forms`). */
fun isClientNamespace(namespace: String, client: String): Boolean {
    val own = clientNamespace(client)
    return namespace == own || namespace.startsWith("$own${OWNR.namespaceSep}")
}

/**
 * What is wrong with [namespace] as [client]'s config namespace, or null (issue #949): a client authors only into
 * its own, so its types can never land where a global name -- or another client's -- lives.
 */
fun clientNamespaceProblem(namespace: String, client: String): String? =
    if (isClientNamespace(namespace, client)) {
        null
    } else {
        "A client's configuration declares its types in its own namespace, '${clientNamespace(client)}' or beneath " +
            "it; '$namespace' is not."
    }

/** The root of [namespace]: its first segment (`kdr` of `kdr.core`). */
fun namespaceRoot(namespace: String): String = namespace.substringBefore(OWNR.namespaceSep)

/**
 * What is wrong with [namespace] as the namespace of a **component's** contribution -- a schema module or a global
 * config -- or null (issue #950). Its root must be an owner root, never the reserved [OWNR.clientRoot], and either
 * the component's own [ownerRoot] or the root the contribution explicitly opts into with [contributesTo]. Naming the
 * root a second time is what makes extending another owner's root deliberate rather than an accident of typing.
 */
fun componentNamespaceProblem(namespace: String, ownerRoot: String?, contributesTo: String?): String? {
    val root = namespaceRoot(namespace)
    return when {
        !isOwnerRoot(root) ->
            "'$namespace' has no owner root: its first segment, '$root', has to be letters and digits, starting with a letter."
        root == OWNR.clientRoot ->
            "'$namespace' is under the reserved '${OWNR.clientRoot}' root, which holds clients' own namespaces only."
        contributesTo != null && contributesTo != root ->
            "'$namespace' says it contributes to the root '$contributesTo', but it is under '$root'."
        contributesTo != null -> null
        ownerRoot == null ->
            "'$namespace' is contributed by a component that declares no owner root; a component's global names " +
                "live under the root it declares (ownerRoot)."
        root != ownerRoot ->
            "'$namespace' is under '$root', not the component's own root '$ownerRoot'. A contribution to another " +
                "owner's root says so on its declaration: contributesTo = \"$root\"."
        else -> null
    }
}

/**
 * What is wrong with [typeName], a type a contribution in [namespace] **declares**, or null (issue #950): it sits
 * under the contribution's own root. A dotted name is taken as written -- right for a reference, wrong for a
 * declaration -- so this is what keeps a declaration from landing under somebody else's root.
 */
fun declaredTypeProblem(typeName: String, namespace: String): String? {
    val root = namespaceRoot(namespace)
    return if (namespaceRoot(typeName) == root) {
        null
    } else {
        "The type '$typeName' is declared in '$namespace' but is not under its root '$root'."
    }
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || this in '0'..'9'
