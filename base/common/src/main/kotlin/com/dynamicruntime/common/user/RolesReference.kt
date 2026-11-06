@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.http.request.SectionAccess
import com.dynamicruntime.common.http.request.SectionRules
import com.dynamicruntime.common.portal.PTL
import com.dynamicruntime.common.startup.SS

/**
 * The **roles and access reference** (issue #1610): what each role grants, which sections each reaches, whose data a
 * caller reads, and how sandboxes, a client's own copies of endpoints, test instances, and API keys bear on it, with how
 * to ask a node why a caller is refused. A Markdown document served on the Documents page to anyone, signed in or not.
 *
 * Its tables are made from what the gate enforces -- [ROLE]'s roles and their meanings, [RoleLadder], the section rules
 * in [SectionAccess], the sandbox rules [SandboxAccess.Withheld] names, and the access report's own keys -- so they
 * cannot disagree with it. The prose between them, on how the pieces combine, is written here. Depends on nothing but
 * code, as a generated document must: its build id is a hash of this text.
 *
 * The page is one Markdown literal, as `validationMessagesMarkdown` is: each table made by a function, and every name
 * the code owns substituted with `$${…}`.
 *
 * Themes: #CLCPY, #RDSCP, #SBXAU, #SECGT.
 */
fun rolesAndAccessMarkdown(): String =
    // language=Markdown
    $$"""# Roles and access

Two things decide what a caller may do. Their **roles** decide which endpoints they may call, and their **read scope**
decides whose data those endpoints show them. Both are worked out on every request from the roles as they are now, so
a role given or taken away takes effect at the caller's next request, with no need to sign in again.

This page lists the roles, the sections of the API and who may call each, and how far each caller reads. Then it
covers what changes in a sandbox, on a client's own copy of an endpoint, on a test instance, and for an API key, and
how to ask a node why a caller is refused. It is made from the role constants and the section rules the runtime
enforces, so it says exactly what the runtime does.

## Roles

A user holds roles of two kinds.

- **A level**, one of $${RoleLadder.ordered.joinToString(", ") { "`$it`" }}, from least to most. A higher level reaches whatever a
  lower one does, so an `$${ROLE.admin}` may call what an `$${ROLE.operator}` may. A user holds one level: setting another
  replaces it. `$${ROLE.user}` is the floor, kept whatever level is set.
- **A role beside the levels**, held outright. A deployment may add roles of its own; they match only themselves, and
  never stand in for a level.

$${rolesTable()}

**Who may grant what.** An administrator sets a user's roles on the Users page, which calls
`POST $${UADEP.userSetRoles}` (or `POST $${ADEP.userSetRoles}` for a user of any client). A role list must keep
`$${ROLE.user}`. Nobody may grant `$${ROLE.allClients}` without holding it, nor `$${ROLE.operator}`, whose section needs
`$${ROLE.allClients}` too. An administrator may not take away their own `$${ROLE.admin}`, nor disable their own account,
so the last one cannot lock everyone out. A deployment's first administrator is made outside the API: by signing in
with an address on the domain `$${ADMR.adminEmailDomainEnvVar.name}` names, or by the `GrantRole` command.

## Sections

The first segment of an endpoint's path is its **section**: `$${SECT.clientAdmin}` for `$${UADEP.userSetRoles}`. The
section decides who may call it, and there is no rule for one endpoint alone. [Endpoints](endpoints.md) lists every
endpoint under its section. A page has a section too, the first segment after its context root, and the same rules
apply to it: `/cp/portal` is in `portal`. A page whose section is not listed here is open to anyone.

$${sectionsTable()}

A section marked "or a direct read" also admits a GET that reaches the node without passing through a proxy, signed in
or not: one made on the node itself, such as by a command run there.

The administrative sections pair a level with a reach:

| | Every client (with `$${ROLE.allClients}`) | The caller's own client |
|---|---|---|
| `$${ROLE.admin}` | `$${SECT.admin}` | `$${SECT.clientAdmin}` |
| `$${ROLE.operator}` | `$${SECT.operator}` | `$${SECT.clientOperator}` |

An administrator of every client may also call `$${SECT.clientAdmin}`, where their reach is not confined.

**A refused call.** A caller signed in is answered 403, and one who is not, 401. Either way, the message names the
role or capability that is missing: "Request requires the '$${ROLE.allClients}' role." The endpoint catalog,
`GET $${PTL.endpointsApi}`, lists only what the caller may call, so an endpoint withheld from them is absent from it
rather than marked.

## Whose data a caller reads

Within a section that admits them, what a caller reads, and whose users an administrator administers, is their **read
scope**. It is narrow unless something widens it.

| Caller | Reads |
|---|---|
| Any signed-in user | Their own rows. |
| `$${ROLE.editor}` | Every row of their client, its users included, administering none of them. |
| `$${ROLE.admin}` | Every row of their client, and administers its users. |
| `$${ROLE.admin}` in the `public` client | Their own rows, as any user does, and administers only their own users. |
| `$${ROLE.admin}` with `$${ROLE.allClients}` | Every client's rows and users. |

A client may group its users in **organizations**. An editor or administrator with a primary organization reads that
organization's rows and the client's rows that belong to no organization, not the whole client. `$${ROLE.allClients}` is
not narrowed by an organization.

## A client's own copy of an endpoint

Some endpoints have a copy for each client, at a path naming the client: `/gedra/<client>/formDoc` for
`/gedra/formDoc`. [Endpoints](endpoints.md) marks them "per client". A client's copy answers with that client's schema,
and is open to that client's users and to a holder of `$${ROLE.allClients}`. To anyone else it does not exist: it is answered
as a path with no endpoint, so it does not reveal which clients exist.

A copy also confines. It refuses a row of another client, and a listing through it lists only its client's rows, even
for a holder of `$${ROLE.allClients}`, whose reach on the shared endpoint is every client.

## Sandboxes

A client may have a **sandbox**, `<client>:sandbox`, where changes are tried before anyone else sees them. It has users
of its own, and authority over it flows from the client to the sandbox, never back.

- **Who may open it:** an administrator of the client, or one with `$${ROLE.allClients}`, and never from inside a sandbox.
  Opening it moves the session to the person's own user in the sandbox, made on first use.
- **A user opened that way** acts with exactly the roles and organization of the user it was opened from, read at every
  request. A role taken away outside is gone inside at the next request. If that user is disabled, or no longer stands
  for the client (as a user of it, or an administrator of every client), the sandbox user acts with no roles at all.
- **A user made in the sandbox** acts with the roles its row holds, except as the rules below say. `$${ROLE.admin}` is
  never granted to one: setting it is refused.

$${sandboxRulesTable()}

## Test instances and env auth

**Env auth** says how a request arrived, not who is acting: an edge server signed the person in to the environment and
vouches for them in a header the node trusts (`$${ENVA.trustEnvAuthHeaderEnvVar.name}`), or, on a developer's own test node, it
is assumed (`$${ENVA.assumeEnvAuthEnvVar.name}`). It grants no role, and a session may switch it off to see what everyone else
sees.

**A test instance** (`$${KdrInstanceConfig.testInstanceEnvVar.name}`) refuses to start outside the `local` and `unit` environments,
except as a public test deployment (`$${PTD.publicTestDeploymentEnvVar.name}`), which hardens env auth. A caller with
**test access**, on a test instance and with env auth, has:

- the endpoints [Endpoints](endpoints.md) marks "test only", among them the `fixture` section's. To a caller without
  test access they do not exist, as on a real deployment;
- the diagnostic `_debug` tags, such as `$${SS.explainAccess.name}` below, the debug pages, and error detail on screen.

On any deployment, a session with env auth switched to debug has the diagnostic tags, debug pages, and error detail
too, but not the test only endpoints.

## API keys and access tokens

An API key acts as its user, with that user's roles and read scope as they are at each request: a role taken away
from the user is gone from the key at once. An access token exchanged for a key carries the key's access. Either of
them:

- with `$${AKEY.read}` access, makes GET requests and nothing else, save exchanging a key for a token;
- with `$${AKEY.full}` access, may do whatever its user may, except call an endpoint [Endpoints](endpoints.md) marks
  "session only": managing keys, signing in or out, switching users or clients, and changing a password.

How to make, use, and exchange a key is in [API keys and access tokens](../api-keys-and-tokens.md). The workflow
assistant calls the API for the user it works for in the same way, and reaches only the endpoints its work needs.

## Asking why a caller is refused

Add `_debug=$${SS.explainAccess.name}` to a call of the endpoint catalog, `GET $${PTL.endpointsApi}` or `GET /schema/endpoint`, as the
caller. It answers on a test instance for a caller with test access, or for a session in env debug; anywhere else the
report is simply left out. The report is under `_meta.$${SS.accessExplained}`:

$${accessReportTable()}
"""

/** The roles: each with its kind and what it is. */
private fun rolesTable(): String = table(
    "| Role | Kind | What it is |",
    ROLE.meanings.map { (role, meaning) -> "| `$role` | ${roleKind(role)} | $meaning |" },
)

/** A role's kind, as the table names it: a level by its rank, else a role implied by a level, else a capability. */
private fun roleKind(role: String): String {
    val rank = RoleLadder.rankOf(role)
    val impliedBy = RoleLadder.impliedBy[role]
    return when {
        rank != null -> "Level ${rank + 1} of ${RoleLadder.ordered.size}"
        impliedBy != null -> "Role, implied by `$impliedBy`"
        else -> "Capability"
    }
}

/** The sections, in the order the gate's lists give them: who may call each, and what it holds. */
private fun sectionsTable(): String = table(
    "| Section | Who may call it | What it holds |",
    SectionAccess.rules.values.map { "| `${it.section}` | ${whoMayCall(it)} | ${SectionAccess.meanings[it.section].orEmpty()} |" },
)

/** Who [rules] admits, briefly, for a table cell; `sectionAccessText` says it in sentences for the endpoint index. */
private fun whoMayCall(rules: SectionRules): String {
    if (!rules.isGated) return if (rules.needsLogin) "Signed in" else "Anyone"
    val role = rules.requiredRole
    val parts = mutableListOf<String>()
    when {
        role == null -> {}
        role == ROLE.user -> parts.add("Signed in")
        RoleLadder.rankOf(role) != null -> parts.add("`$role` or above")
        else -> parts.add("`$role`" + (RoleLadder.impliedBy[role]?.let { " (or `$it`)" } ?: ""))
    }
    rules.requiredCapability?.let { parts.add("`$it`") }
    val who = parts.joinToString(", with ")
    return if (rules.directReads) "$who; or a direct read" else who
}

/** The sandbox rules, by the name the access report gives each. */
private fun sandboxRulesTable(): String = table(
    "| Rule | What it says |",
    SandboxAccess.Withheld.entries.map {
        val (name, text) = it.rule.split(": ", limit = 2)
        "| `$name` | ${text.replaceFirstChar { c -> c.uppercase() }}. |"
    },
)

/** The access report's keys, and what each holds. */
private fun accessReportTable(): String = table(
    "| Key | What it holds |",
    listOf(
        "| `${SS.actingRoles}` | The roles the caller acts with now, which the catalog compared. |",
        "| `${SS.withheld}` | Each section withheld from the caller: its `${SS.section}`, the `${SS.requiredRole}` and " +
            "`${SS.requiredCapability}` it asks for, and its endpoints. |",
        "| `${SS.rolesWithheld}` | For a sandbox user, each role its row holds that does not take effect: the `${SS.role}`, " +
            "and the `${SS.rule}` above that withholds it. |",
        "| `${SS.sandboxActsAs}` | For a sandbox user opened from another user, the user it acts as, and that user's " +
            "client and organization. |",
    ),
)

/** A Markdown table: its [header] row, the rule under it, then its [rows]. */
private fun table(header: String, rows: List<String>): String {
    val rule = "|" + "---|".repeat(header.count { it == '|' } - 1)
    return (listOf(header, rule) + rows).joinToString("\n")
}
