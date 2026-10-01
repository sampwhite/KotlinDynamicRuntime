package com.dynamicruntime.common.naming

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

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || this in '0'..'9'
