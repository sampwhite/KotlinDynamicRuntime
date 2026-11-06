<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Copy

Copy is the words a client's pages and mails show: a home page, the explanations beside a page's heading, a mail's
body, and a form's labels and descriptions. It is written in Markdown, and most of it is kept in fragment files, which
a client changes key by key with a copy overlay. This page describes the Markdown the runtime reads, the roles that set
a phrase or an image apart, and the fragment file format. The list of roles is made from the roles themselves.

A line of copy can also hold an expression between `${` and `}`, described in [Expressions](expressions.md).

## The Markdown it reads

| Write | For |
|---|---|
| `# Title` to `###### Title` | A heading. |
| Lines with a blank line between them | Paragraphs. Lines next to each other join into one paragraph. |
| `**bold**` or `__bold__`, `*italic*` or `_italic_` | Emphasis. |
| Text between backticks | Code, shown as written. |
| `[text](https://example.com)` | A link. Its target may be http, https, mailto, or a relative path; any other is shown with no target. |
| `![alt](src)` | An image; see [Images](#images). |
| `- item` or `1. item` | A list, one item per line. |
| `> text` | A quotation. |
| A fence of three backticks | A block of code, shown as written. |
| `---` | A horizontal rule. |
| `\| a \| b \|`, then `\|---\|---\|` | A table: a header row, a dividing row, then a row per line. |
| `<!-- note -->`, on lines of its own | A comment, which is not shown. |

Some Markdown is not read, and is shown in a simpler form:

- **A list inside a list** is shown flat, each item beside the one above it.
- **A reference link**, whose target is given elsewhere by a label, is shown as text. Write the target in the link.
- **A heading underlined** with `===` or `---` is shown as a paragraph. Start the line with `#` instead.
- **HTML** is shown as written, never run, so copy can never add markup to a page.

## Roles

A role sets a phrase or an image apart. It is written in braces after a span in square brackets, `[text]{.value}`, or
after an image, `![alt](src){.float-left}`. A block may hold several roles, and on an image a `width` and a
`height` in pixels: `{.float-left .icon width=20}`. Anything else in the braces, such as a misspelled
`widht=`, leaves the whole block shown as written.

| Role | Written on | What it does | In a mail |
|---|---|---|---|
| `.value` | A span | Sets a substituted value apart from the prose, such as an address or a client, so a reader can see what to copy. | In bold. |
| `.code` | A span | A code a reader reads off and types elsewhere: monospaced, bold, and spaced out. | Large, monospaced, and bold. |
| `.clear` | A span | Written as an empty span, `[]{.clear}`. Ends a floated image here, so what follows goes below the image rather than beside it. | Nothing, since a mail floats no image. |
| `.float-left` | An image | Floats the image left of the text that follows. On a screen narrower than 480 pixels, it sits above the text instead. | Nothing: a mail shows an image as its alt text. |
| `.float-right` | An image | Floats the image right of the text that follows. On a screen narrower than 480 pixels, it sits above the text instead. | Nothing: a mail shows an image as its alt text. |
| `.icon` | An image | With a float, keeps a small image beside its line at any width, a character's space from it: `{.float-left .icon width=20}`. | Nothing: a mail shows an image as its alt text. |

A role that is not one of these, or one written on what it does not apply to, does nothing, so it is caught where a
misspelling would otherwise go unnoticed: a client's configuration whose copy writes one is refused, and the copy every
client shares is checked when the server starts.

## Images

- **`![alt](img:<name>)`** shows an image a component ships, such as `img:kdr:walkPart1`. It is the same image for every
  client.
- **`![alt](ref:<id>)`** shows one of the client's own files, by its file ref, which the Clients page's **Images**
  panel manages. The copy says which image, never which file, so a replaced image needs no change to the copy.
- **`![alt](https://…)`** or a relative path shows that address.

An image is drawn as its alt text wherever it cannot be shown: in a mail, which is read where none of the client's
files can be fetched, or when no component ships the image or the client has no such ref. Copy naming an image that
does not exist is caught as a misspelled role is. Write alt text that stands in for the image, since some readers see
only that.

An image is placed with the roles above. `![Our logo](ref:logo){.float-left width=140}` floats a logo beside the
paragraph that follows, and `[]{.clear}` ends the float where you want what follows to go below the image.

## Escaping

A backslash before a punctuation character shows the character as written: `\*` is an asterisk, not the start of
italics, and `\[` is a bracket, not the start of a link. A value the runtime puts into a mail, such as an address, is
escaped this way, so `_ops_@acme.test` reads as written.

## One source, two renderings

The same copy is drawn as HTML on a page and in a mail's HTML part, and as plain text in a mail's text part. In the
text, emphasis and roles are dropped, a link is its text with its address in parentheses, an image is its alt text, a
table's cells are joined by ` | `, and a rule is left out. Copy written for a mail therefore needs no second version.

## Fragment files

A fragment file holds copy as keys under namespaces. A page reads a key as `namespace.key`, and a value can name another
key of the same file the same way: `${home.title}`.

```
/- A comment, which may span lines. -/
# @home
# +title Welcome
# +intro
The first paragraph of the intro.

A second paragraph, still part of the intro.


# @mail
# +subject Your code is ${code}
```

- **`# @name`** starts a namespace. The keys after it belong to it, until the next one.
- **`# +key value`** is a key whose value is on the same line. It ends at an empty line, or at the next line that
  starts with `# `.
- **`# +key`** with nothing after it is a key whose value is on the lines below. It ends at two empty lines in a row, or
  at the next line that starts with `# `, so a value can hold several paragraphs.
- **`/-` and `-/`** enclose a comment, which may span lines and is removed before anything else is read.
- **A line starting `## `**, or more hashes, is ordinary content: a heading inside a value.

A name may not hold a `.`, since `namespace.key` could not reach it, and a key is declared once in a namespace. Each
value is trimmed of the space around it.

## A client's copy overlay

A client changes copy one key at a time. An overlay names a fragment file, and under it the namespaces and keys it
rewords. Every key it does not name keeps the file's words. The Clients page's **Copy** panel writes one, and so does
an entry in the `kdr:fragmentDef` slot of a client's configuration, with the file in `fileId` and the keys in
`content`:

```json
{
  "fileId": "home",
  "content": { "home": { "title": "Welcome to Acme" } }
}
```

- **The most specific overlay wins**: a deployment's over the platform's, a template a client extends over the
  deployment's, and the client's own over the template's.
- **An empty value is a value.** It replaces the words with nothing, which some copy reads as "none": an empty
  explanation shows no "What is this?" link.
- **A key the file does not have is refused.** It would change nothing, which most often means the key was renamed and
  the overlay was left behind.

See the [client configuration reference](client-configuration.md) for the slot, and
[Copy from a file](../demo-client/reference.md#copy-from-a-file) for a layout taking its heading from a fragment file.
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes its list of roles from the roles themselves,
which the copy checks hold copy to, so it cannot disagree with them. Read it on the server's **Documents** page, under
**Reference**, then **Copy**, where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/copy](https://dynamicruntime.org/wa/docs/copy). `./gradlew :launch:refDocsDump` copies it
into this file, and the suite fails while the copy is behind the code, so edit the generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The roles, each with what it is written on, what it does, and how a mail shows it | `markdownRoles` in [`MarkdownConstants.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/MarkdownConstants.kt) |
| The Markdown the page's syntax states | [`MarkdownRenderUtil.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/MarkdownRenderUtil.kt) |
| The fragment file format | [`MarkdownFragmentUtil.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/MarkdownFragmentUtil.kt) |
| The reference page, made from these | [`CopyReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/CopyReference.kt) |
