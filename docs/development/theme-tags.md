# Theme tags

A change to how part of the runtime behaves has to find every place that describes that behavior: other code, KDoc,
skills, design notes, and these documents. A grep for the identifiers a change touched finds a mention by name, and
misses prose that describes a behavior without naming any code. An issue number names a change, not an idea, and one
idea is usually spread over several.

A **theme tag** is an invented token of four to six capital letters, suggestive of what it names and occurring nowhere
naturally, such as `SBXAU` for sandbox authority. It marks every place that implements or describes one cross-cutting
idea, so one grep finds them all (issue #1630).

## Using them

**Before changing a file, grep each tag it carries, and review every other place that carries it.** A place whose
words or logic the change makes wrong is changed with it, in the same commit:

```bash
grep -rn SBXAU --exclude-dir=build --exclude-dir=node_modules .
```

**When adding a place that implements or describes a tagged idea, tag it.** A place is tagged where it implements or
describes the idea, not wherever the idea is merely used: the KDoc of the class that enforces a rule, the skill section
that explains it, the design note that argues for it. Not every call site.

## Writing one

A tag is written as a hashtag, `#SBXAU`, after the word "Theme" (or "Themes", for several):

- **In KDoc**, as the last paragraph: ` * Theme: #SBXAU.`
- **In a code comment**: `// Theme: #SBXAU.`
- **In Markdown**, as an HTML comment under the heading of the section it marks, so a rendered page does not show it:
  `<!-- Theme: #SBXAU -->`. The app's renderer drops such a comment, as GitHub does.

**Where they go:** comments and KDoc, skills, `CLAUDE.md` files, design notes (`docs/design/`), and `docs/development/`.
**Where they never go:** anything a client or an end user reads -- the generated references (`docs/reference/`), copy
and fragment files (`md-fragments/`), and user-facing messages.

A hashtag made only of the letters A to F is a hex color (`#FFFFFF`), so no tag is spelled that way.

## When an idea earns a tag

- It is implemented or described in at least three places, in at least two kinds of file: code, and a skill, document,
  or design note.
- Changing it would quietly make those descriptions wrong. A behavioral rule, not a widely used utility, which a grep
  for its name already finds.
- A grep for it returns a set worth reviewing: roughly 5 to 30 places.

Add a tag when a change makes you wish it existed: register it here, then tag its places in the same commit.

## The check

`ThemeTagsTest` (`./gradlew :base:common:themeTagsTest`, part of `check`) holds the repository to this list. It fails
on a hashtag of four to six capitals that is not registered here, on a registered tag in a generated reference or a
copy file, and on a registered tag marking fewer than three places: unused, or not a theme. It prints how many places
each tag marks, and warns when one marks more than 40, too broad to review and a candidate to split.

## The tags

| Tag | What it names | Tag a place when it |
| --- | --- | --- |
| `#SECGT` | The section gate: which roles and capabilities each section admits, the role ladder, and how a refusal is answered. | decides or describes who may call a section, how roles rank, or what a refused caller is told. |
| `#RDSCP` | Read scope: whose rows a caller reads, by level, organization, and `allClients`. | decides or describes how far a caller's reads reach, or admits a row against a scope. |
| `#SBXAU` | Sandbox authority: opening a sandbox, and what a sandbox user acts with, flowing from the parent and never back. | decides or describes who may open a sandbox, or what roles a sandbox user acts with. |
| `#ENVAT` | Env auth and test access: what env auth is, what a test instance opens, and env debug. | decides or describes when env auth is trusted, or what test access or env debug opens. |
| `#APIKR` | API key and token reach: read versus full access, session-only endpoints, and in-process calls. | decides or describes what a call made with a key or a token may do. |
| `#CLCPY` | A client's own copies of endpoints: who may call one, and how it confines. | decides or describes the per-client copy of an endpoint, its callers, or its confinement. |
| `#CFGFG` | Stored-config forgiveness: strict at write, forgiving at load, and the client issue list. | decides or describes how stored configuration is judged at a write or a load, or reports its issues. |
| `#RELSY` | Client reload and cross-node sync: how stored configuration becomes live on one node and then the rest. | reloads a client's configuration, or carries a reload to other nodes. |
| `#NARRW` | What a client's alteration of a shared type may change: narrowing, never widening. | decides or describes what a client may change in a type it shares with others. |
| `#TPLPS` | The two template passes: `%{…}` on the server at delivery, `${…}` on the page. | resolves, checks, or describes either pass, or keeps the two apart. |
