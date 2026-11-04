# Copy

Copy is the words a client's pages and mails show, written in Markdown and mostly kept in fragment files. The full
reference describes the Markdown the runtime reads, the roles that set a phrase or an image apart (`[text]{.value}`,
`{.float-left width=140}`), images by `img:` and `ref:`, escaping, and how one source renders as HTML and as a mail's
plain text. It also describes the fragment file format and how a client's copy overlay changes a key.

**The reference is generated, so it is not kept in this file.** A running server makes its list of roles from the
roles themselves, which the copy checks hold copy to, so it cannot disagree with them. Read it on the server's
**Documents** page, under **Copy**, where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/copy](https://dynamicruntime.org/wa/docs/copy).

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The roles, each with what it is written on, what it does, and how a mail shows it | `markdownRoles` in [`MarkdownConstants.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/MarkdownConstants.kt) |
| The Markdown the page's syntax states | [`MarkdownRenderUtil.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/MarkdownRenderUtil.kt) |
| The fragment file format | [`MarkdownFragmentUtil.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/MarkdownFragmentUtil.kt) |
| The reference page, made from these | [`CopyReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/CopyReference.kt) |
