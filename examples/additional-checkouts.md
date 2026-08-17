# Additional checkouts: two workspaces, two agent sessions

Two Claude sessions can work at the same time on two checkouts of this repository, each with its own IntelliJ
project view. Git worktrees would be the obvious mechanism and are deliberately **not** used: a worktree is
awkward to open as a separate IntelliJ project, which is the whole point of the exercise.

Instead, each session gets a full **workspace directory** of its own — the directory that *contains*
`KotlinDynamicRuntime/`, as described in [`intellij-dev-setup.md`](intellij-dev-setup.md). The convention on
Sam's machine is `~/dev/kd1`, `~/dev/kd2`, `~/dev/kd3`; nothing depends on those names.

Everything that follows is deployment-local. Like `settings.gradle.kts` and the run configurations, none of it
is version-controlled — this file is the checked-in recipe.

## The one thing that actually collides: environment variables

Two workspaces share a machine, so they share ports and, if configured that way, a database. The runtime reads
its settings through one funnel with a fixed precedence:

1. The real process environment (a shell `VAR=… ./gradlew …`, or an IntelliJ run configuration's environment
   variables) — **always wins**.
2. `default-environment-variables.properties` in the workspace directory — a fallback, consulted only for keys
   the real environment does not define (`KdrInstanceConfig.readDefaultEnvVars`).

That precedence is what makes the split below work without either side having to coordinate.

## Who owns `default-environment-variables.properties`

| | Primary workspace | Secondary workspace |
| --- | --- | --- |
| Whose it is | the developer's | the agent's |
| The file holds | the developer's settings | the agent's settings |
| The other party overrides via | — | the IntelliJ run configuration |
| The agent may edit it | **no** | yes |

A workspace is **primary unless its own `CLAUDE.md` says otherwise**, so an existing workspace needs no change
and no developer needs to be told anything. See the repository's root [`CLAUDE.md`](../CLAUDE.md) for the rule
as the agent receives it.

The ownership flip is what makes the secondary workspace usable by both parties at once: the agent keeps a
working default in the file so that a forgotten variable still lands somewhere safe, and the human overrides
whatever they need per-run — which is a one-time GUI setting they were making anyway.

## Standing up a secondary workspace

1. **Clone** into a new workspace directory: `mkdir ~/dev/kd3 && cd ~/dev/kd3 && git clone <repo>`.
2. **Copy the workspace scaffolding** from an existing workspace: `gradlew`, `gradlew.bat`, `gradle/`, and
   `gradle.properties` (or take the latter from [`gradle.properties.example`](gradle.properties.example) — the
   raised heap is required, or the Kotlin/JS webpack tasks OOM).
3. **Create `settings.gradle.kts`** from [`settings.gradle.kts.example`](settings.gradle.kts.example) rather
   than copying a live one, so no stale component injections come along. Uncomment the `injectComponent` lines
   you actually want.
4. **Copy `customConfig/`** if the deployment uses one (drop any `build/` and `out/` directories).
5. **Write `default-environment-variables.properties`** with the agent's settings — at minimum a `KDR_PORT`
   that collides with nothing — and a header naming the owner (see below).
6. **Write the workspace `CLAUDE.md`**, declaring that this is a secondary workspace the agent owns and naming
   its port.
7. **Verify** before relying on it: `JAVA_HOME=… ./gradlew :base:kernel:compileKotlinJvm`.
8. **Open the workspace directory** (not the repo) as an IntelliJ project, and create the run configurations
   from [`intellij-dev-setup.md`](intellij-dev-setup.md), adding the environment variables that override the
   agent's defaults — a different `KDR_PORT`, and `KDR_IN_MEMORY_ONLY=false` if you want a real database.

## Header the properties file in *every* workspace

Including the primary one, where the header says the opposite:

```properties
# Owned by <name>. Agents: do not edit -- the real environment always wins, so pass variables on your
# own command line instead.
```

This is not redundant with `CLAUDE.md`. A session that was already running when the policy arrived never sees
a file fetched by `git pull`, but it does see a header in the file it is about to edit. The header is the only
part of this policy that reaches an already-running session.

## Ports in use

Record each workspace's port in that workspace's `CLAUDE.md`, and keep `7070` reserved for the developer's own
IntelliJ instance in the primary workspace.

One trap worth knowing: `ProbeSession.defaultProbeUrl` is a hardcoded `http://localhost:7071` in *versioned*
source, so it is identical in every workspace. From a workspace whose server is on a different port, a bare
`kdr-probe` reaches a **different workspace's** server and reports confident, wrong results about code that is
not there — the exact failure mode `ProbeSession`'s own documentation is written against. Pass `--url`
explicitly until that default is resolved per workspace.
