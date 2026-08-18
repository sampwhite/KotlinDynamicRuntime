# Guidance for AI coding agents

Auto-loaded by Claude Code sessions working in this repository. This file is **version-controlled**, so it
holds guidance that should reach *every* developer's agent sessions — theirs and yours alike. Personal,
machine-specific configuration belongs in your own non-versioned `CLAUDE.md` in the **workspace directory**
(the parent of this repo), not here.

Coding conventions live in [`code-guide.md`](code-guide.md); environment variables in
[`environment-variables.md`](environment-variables.md).

## The workspace directory is not yours

The Gradle build root is the **workspace directory** — the directory that *contains* this repo, holding
`settings.gradle.kts`, `gradle.properties`, `default-environment-variables.properties`, the wrapper, and
`.idea/`. None of it is version-controlled, so a change you make there has no diff, no review, and no easy
revert.

**Default: treat every file in the workspace directory as human-owned.** Do not create, edit, extend, or
reorganize them. This default holds even when nothing says so — a workspace with no `CLAUDE.md` of its own is
a workspace you do not own.

### `default-environment-variables.properties` in particular

This is the file the rule exists for, because it is the one an agent will reason its way into editing: it
looks exactly like the natural home for "the port I should use". In a developer's primary workspace it carries
*their* choices — database type, test-instance flags, OAuth client ids — and an edit changes how **their**
server boots.

**You never need to touch it.** `KdrInstanceConfig.readDefaultEnvVars` keeps only the entries whose key is
**not** already a defined environment variable — the real environment always wins — so passing the variable on
your own command line is always sufficient and disturbs nobody:

```
KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true ./gradlew :launch:run
```

## Additional checkouts (issue #345)

Two agent sessions can work at once on two checkouts, each visible to its own IntelliJ project — no worktrees.
The workspaces then differ in who owns the configuration:

- **Primary workspace** — the developer's. The rule above applies unchanged: hands off.
- **Secondary workspace** — created for an agent's use. There, the agent **does** own
  `default-environment-variables.properties` and keeps its own settings in it, while the human overrides any
  of them per-run through the IntelliJ run configuration's environment variables, which always win.

**A workspace is primary unless its own `CLAUDE.md` says otherwise.** That is deliberate and is what makes the
policy safe to roll out: no developer has to be told anything, and no existing workspace has to change, because
the default is already the conservative behavior. Only a secondary workspace needs a declaration — and
whichever agent creates one writes that declaration as part of creating it.

Recipe for standing one up: [`examples/additional-checkouts.md`](examples/additional-checkouts.md).

### If you started before this file existed

A session already running does not see a file that arrives by `git pull`. So the workspace copy of
`default-environment-variables.properties` should carry a one-line header naming its owner: unlike this file,
a header is read at the moment of editing, by any session, however long it has been running. If you are about
to change a workspace file and find no statement of ownership either way, treat it as human-owned and ask.

## Ports

`7070` is the developer's own IntelliJ instance. **Never bind it, and never kill what is on it.** Start your
own server on a free port instead (see the command above). Each additional workspace takes its own port; the
one in use is recorded in that workspace's `CLAUDE.md`.

`kdr-probe` defaults to `http://localhost:7071` — a *shared, versioned* constant, so it is identical in every
checkout. From a workspace whose server is elsewhere it therefore probes a **different workspace's** server and
reports confident, wrong results about code that is not there.

**Always pass `--url`.** Not "unless you are certain the default is yours": the default belongs to no workspace
in particular, and 7071 may in any case be held by another session, so being certain is rarely available and
being wrong costs nothing to discover — the probe answers, plausibly, instead of failing.
