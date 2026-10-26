# Contributing

Contributions are welcome. This repository is developed largely with AI coding agents, so its conventions are
written down where an agent reads them: [`CLAUDE.md`](CLAUDE.md), the skills under [`.claude/skills/`](.claude/skills/),
and the [code guide](docs/development/code-guide.md). An agent that starts from that guidance writes code close to
what the maintainers' own sessions write, which is what lets an outside contribution be reviewed without being
rewritten.

AI assistance is recommended, not required. What is required is the outcome: code that follows the conventions and
passes the full test suite. If your employer does not allow AI tools, you are just as welcome; the guidance files
are ordinary documentation and read the same way to a person.

## 1. Discuss first

Open an issue that describes the change, and agree on the approach before writing code. The guidance files take
care of conventions: naming, constants, KDoc, and how tests are written. They cannot make the design judgments: where
something belongs, what is in scope, and what has deliberately been put off (see
[`deferred-work.md`](docs/development/deferred-work.md)). Those judgments are where an outside contribution most
often goes wrong, with or without an AI. Describe them in the issue, and we, the maintainers, will say what we think
before you write the pull request, while a change of direction still costs nothing.

## 2. Set up with `bin/kdr-install`

The Gradle build root is the directory that **contains** the checkout, not the checkout itself. Clone your fork into
a directory of its own and run the installer from there; it creates the build root's settings file and wrapper:

```sh
mkdir kdr && cd kdr
git clone https://github.com/<you>/KotlinDynamicRuntime.git
./KotlinDynamicRuntime/bin/kdr-install
```

The README's [Getting started](README.md#getting-started) says what it sets up and how to run the server.

## 3. If you use an AI agent, start it from `CLAUDE.md`

Run the agent in the checkout. Claude Code reads `CLAUDE.md` on its own; [`AGENTS.md`](AGENTS.md) points other
agents to it. `CLAUDE.md` indexes the rest, and each skill loads when a task needs it. Do not ask the agent to read
every Markdown file up front: that spends its context and dilutes its attention.

Notes about your own machine belong in a `CLAUDE.md` of your own in the build root, which is not versioned, rather
than in the repository's.

## 4. Run the full test suite

From the build root:

```sh
./gradlew check        # or bin/kdr-tests, which runs exactly this
```

`./gradlew test` is not the full suite: it skips the multiplatform modules' tests and the KDoc link check. See the
README's [Testing](README.md#testing) section.

## 5. Say whether an AI wrote the code

In the pull request, say whether an AI agent wrote the code, and which one. It helps the review. Open the pull
request against `main`, and link the issue it settles (`Closes #123`).
