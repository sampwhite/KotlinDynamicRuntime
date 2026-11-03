## The workflow assistant page (issue #1527)

A client administrator tells the **workflow assistant** what they want, and it drafts it in the client's sandbox for
them to try there and publish from the Clients page. The backend is the optional `assistant` module
(`docs/design/workflow-assistant.md` at the repo root says what it is, what it sends, and what it may reach); the page
is `AssistantPage.kt` at `/wa/assistant`, its pure half `AssistantTranscript.kt` and `AssistantApi.kt` (covered by
`AssistantTranscriptTest`). Its wire names are the kernel's: `ASST` and `ACNV` for the endpoints, `ABLK` for a stored
message's content blocks (the Messages API's own names), and `ATOOL` for the tools, which is what a call's line is
labeled by.

**Offered by the module, not by core.** The menu entry is an overlay the assistant component adds
(`AssistantComponent.uiBlocks`), gated on `administersClient` and the module's cfact `assistant:on`, placed right after
Reports by reading the base menu's numbering (`orderAfter`). Core names no cfact that exists only while the module is
loaded -- a cfact expression naming an undeclared cfact is refused. The operator's page (`/wa/operator/assistant`,
`OperatorAssistantPage.kt`) is offered the same way under the Operator group. Both routes exist unconditionally; where
the module is not loaded the page says it could not load the assistant.

**It works from the client and from inside its sandbox.** "Try it in the sandbox" moves the session into the sandbox, and
the person comes back to the page to go on. The backend answers for the person either way
(`AssistantTurns.personOf`): signed in to the sandbox, it is the user the sandbox user was opened from, and the tools
act as the session's own sandbox user. A sandbox user nobody opened has nobody outside to speak for, and is refused.

Panels, by the rules of "Page structure" in `webapp/CLAUDE.md`:

1. **Sandbox** (live), only while the client has none: what a sandbox does, and **Turn on sandbox**, behind a
   `Popconfirm` saying the client then runs only published configuration. It calls the Clients page's own
   `ClientsApi.setSandbox`, then bumps the refresh, so the app bar offers the sandbox too. A client defined in source
   code (the status's `sandboxInSource`) has its sandbox turned on there, so the panel says so instead and offers no
   button: the switch would only refuse.
2. **Conversation** (live): the conversation picker (the person's conversations, and "New conversation"), the
   transcript, the text box, **Send**, and **Stop** while a turn runs. Ctrl or Cmd+Enter sends. The send is blocked,
   saying why, when the conversation is read-only, the month's allowance is used up, or there is no sandbox. "What is
   sent to Anthropic" is a `Disclosure` beside the first message of a conversation, the status's `dataNotice`. The
   last turn's ending, when it did not run to its end, is said under the transcript (`turnEndText`).
3. **Drafts from this conversation** (view): each bundle a `patch_config` write made, at its latest version, with the
   workflows its edits added or changed, and the included traits when `set_included_traits` succeeded (`draftsOf`). A
   dry run or a refused write makes no draft. **Try it in the sandbox** opens the sandbox (unless already in it) at the
   workflow's forms (`workflows/<id>/forms`), or at the forms listing. **Check impact and publish** is a link to the
   Clients page's publishing panel (`/wa/clients/<client>#client-publishing`; the sandbox's own page from inside the
   sandbox), where Check impact and Publish selected live. The Clients page follows a named panel (`HP.section`) while
   the sections above it fill in, until it stays put. **This page has no Publish button**, by design.
4. **Allowance** (view): the month's use as a share of the budget, as a bar and a line (`allowanceText`), never an
   amount -- money is the operator's -- and when it is used up, the day it starts again (UTC's first of the month)
   and that an operator can raise it.

**The transcript** (`transcriptItems`): what the person wrote, and the model's answers rendered as Markdown with the
kernel's renderer. Its summarized thinking shows as muted notes; an empty note (thinking the API did not summarize)
shows nothing. **The tool results the API stores as the person's are never shown as the person's**: each is joined to
its call, and the calls of one answer collapse into one line (`toolsLabel`) -- "Looked at: workflows, bundle `flows`",
"Checked a change to `flows`", "Drafted `flows` (v3)" -- that opens to each call's input and result. A call still
running ends with an ellipsis, and a refused one says so. A write's version comes from its answer, which may have been
cut to fit what the model is shown and then is not JSON: the label goes without a version, rather than failing.

**Polling** (`pollsConversation`): every 1.5 seconds while a turn runs, and from a send until its turn is seen to end, even
before the conversation first reads as running. Each poll reads only the messages after the last one the page has
(`afterSeq`). The poll is the effect's own coroutine (`delay`), which React cancels when the effect re-runs or the page
goes, so a wait never doubles up. Idle, the page reads nothing. A turn's end re-reads the status and the conversation
list.

**A message sent again keeps its id** (`messageIdFor`): a send that got no answer at all (`ApiFailure`) keeps the id it
was given while its text is unchanged, so pressing Send again is the same turn on the server rather than a second one.
A refusal is an answer, and the next send gets a new id.

The open conversation rides the address as `cv` (`HP.conversation`), written with `replaceRoute`, so a reload reopens
it. Choosing another conversation does not add a history entry.

**The operator's page**: whether this node can reach the model (provider, model, or why not), and a table of every
client -- its switch, its monthly budget in dollars (its own, or the default), this month's use in dollars and as a
share, the model calls made, the last turn (relative time), and the share of input the prompt cache served. Each row's
controls act at once. **Switch on…** asks first, showing the data notice, since switching a client on acknowledges it.
Set a budget in dollars, or go back to the default.
