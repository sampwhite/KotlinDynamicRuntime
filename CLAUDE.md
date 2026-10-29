# Guidance for AI coding agents

Auto-loaded by Claude Code sessions working in this repository. This file is **version-controlled**, so it
holds guidance that should reach *every* developer's agent sessions — theirs and yours alike. Personal,
machine-specific configuration belongs in your own non-versioned `CLAUDE.md` in the **workspace directory**
(the parent of this repo), not here.

Coding conventions live in [`docs/development/code-guide.md`](docs/development/code-guide.md). Environment variables are
**declared once in code** as `EnvVarDef` values, grouped by area (`DbEnv`, `NodeUtil`, `LogSetup`, and their neighbors)
— the declaration is the reference, since the read path takes a declared def, so an undeclared variable cannot be read;
a running node serves the live set, with each variable's resolved value, at the `operator` endpoint
`/operator/env/reference`.

## Skills: the house APIs, already written down

`.claude/skills/` documents this codebase's own APIs. Read the relevant one **before** working an API out from
its source or reaching for a library — that is what they are for, and each covers ground that is easy to get
subtly wrong.

| skill | covers |
| --- | --- |
| `kdr-core-utils` | `JsonUtil` (our own JSON), `DateUtil` (`Instant`-based), `ConvertUtil`/`StrUtil` coercion |
| `kdr-schema-builder` | the `Sch*` JSON-Schema DSL, `$ref`, parsing, validate/coerce |
| `kdr-endpoint-builder` | `schemaModule` + the endpoint builders, response envelopes, handler lambdas |
| `kdr-table-cache` | in-memory table caches (`common/sql/cache`) — and which tables must *not* be cached |
| `kdr-testing` | verifying anything here: booting a server, `mkTestBootCxt`, `TestHttpClient`, `kdr-probe` |
| `kdr-test-clients` | a new client per test scenario on a shared instance: the `gedraConfig` DSL, stored config, reload |
| `kdr-deploy-flavors` | deployment flavors (`KDR_DEPLOY_FLAVORS`): declaring and contributing, precedence, the refusals |
| `kdr-demo-docs` | writing `docs/demo-client/`: the page shape, the voice and word rules, the `kdr-doc` markers and their test |
| `kdr-webapp-pages` | what each front-end page decides and why, one file per page (`webapp/CLAUDE.md` keeps what applies across them) |

**A skill's code examples are compiled as tests** (`*SkillExamplesTest`), so a signature change breaks the
build rather than quietly staling the documentation. (`kdr-demo-docs` has prose examples instead; the pages it
describes are held to the code by `ConfDemoDocsTest`. `kdr-webapp-pages` is prose with no test behind it at
all: only the rule that a page's change updates its file in the same edit keeps it current.) When you change an
API, change its skill in the same edit — the test will tell you which one, and a skill is read *instead of* the
source, so a wrong one is followed rather than noticed.

## Before a pull request

- **Run the full suite from the build root: `./gradlew check`** (`bin/kdr-tests` runs exactly this). `./gradlew test`
  is not it: the multiplatform modules (`base/kernel`, `webapp`) have no `test` task, so Gradle skips their tests
  without a word, and it skips the Dokka check too, which fails on any KDoc `[link]` that does not resolve. Read
  Gradle's own exit code, not the exit code of the last command in a pipeline.
- **Prose follows the code guide too**: US spelling and the serial comma, in comments, KDoc, and Markdown (see
  [its section on prose](docs/development/code-guide.md#prose-in-comments-and-documents)).

## What not to build

- **An enhancement nobody asked for is neither built ahead nor filed as an issue.** It goes into
  [`deferred-work.md`](docs/development/deferred-work.md) under a heading naming the observable trigger that would
  make it worth doing, usually "When a customer wants …". The issue tracker is a queue of work to act on soon.
- **Before proposing to remove something unused, read its KDoc.** IntelliJ's "unused" warnings are kept on purpose,
  as a signal of what is not used yet (see the code guide), and some declarations are reserved for planned work:
  `GedraDataType.wfData` has no creator yet, and stays.

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
your own command line is always sufficient and disturbs nobody.

**And do not inherit it either: pass `KDR_DEFAULTS_FILE=none`** (issue #802). Overriding only works for the
variables you know about, and the file keeps growing a developer's own choices — an admin domain, a switch that
transmits real mail to it — which a server of yours would otherwise pick up the day they are added. With
`none`, your server runs on your command line and the built-in defaults alone, and its startup line says so
(`Workspace defaults: no defaults file read`):

```
KDR_DEFAULTS_FILE=none KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true ./gradlew :launch:run
```

In a **secondary** workspace (below), whose defaults file is the agent's own, leave `KDR_DEFAULTS_FILE` unset
so that file is read — or point it at another file with a path.

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
