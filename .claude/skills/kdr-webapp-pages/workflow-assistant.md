## The workflow assistant page (issue #1527)

A client administrator tells the **workflow assistant** what they want, and it drafts it in the client's sandbox for
them to try there and publish from the Clients page. The backend is the optional `assistant` module
(`docs/design/workflow-assistant.md` at the repo root says what it is, what it sends, and what it may reach); the page
is `AssistantPage.kt` at `/wa/assistant`, its pure half `AssistantTranscript.kt` and `AssistantApi.kt` (covered by
`AssistantTranscriptTest`). Its wire names are the kernel's: `ASST` and `ACNV` for the endpoints, `ABLK` for a stored
message's content blocks (the Messages API's own names), and `ATOOL` for the tools, which is what a call's line is
labeled by.

**Offered by the module, not by core.** The menu entry is an overlay the assistant component adds
(`AssistantComponent.uiBlocks`), gated on `administersClient` and the module's cfact `assistant:usable`, placed right
after Reports by reading the base menu's numbering (`orderAfter`). `assistant:usable` is `assistant:on` and, in a
sandbox, the endpoints' own rule (`AssistantTurns.personOrNull`), so the menu never offers the page to a caller every
assistant endpoint would refuse -- "see" and "use" are one answer. Core names no cfact that exists only while the module
is loaded -- a cfact expression naming an undeclared cfact is refused. A client's menu editor lists the entry under its
shipped condition, which has no audience name: it may be put back to it, "As shipped" (`HomeMenuAudiences.choicesFor`). The operator's page (`/wa/operator/assistant`,
`OperatorAssistantPage.kt`) is offered the same way under the Operator group. Both routes exist unconditionally; where
the module is not loaded the page says it could not load the assistant.

**It works from the client and from inside its sandbox.** "Try it in the sandbox" moves the session into the sandbox, and
the person comes back to the page to go on. The backend answers for the person either way
(`AssistantTurns.personOf`): signed in to the sandbox, it is the user the sandbox user was opened from, and the tools
act as the session's own sandbox user. A sandbox user nobody opened has nobody outside to speak for, and is refused.

**Switching it on, where it is offered** (issue #1568). While the assistant is off but offered to the client -- a
self-serve client, on a deployment with `KDR_ASSISTANT_SELF_SERVE` on and a model, that no operator has set -- the page
shows one live panel instead of the rest, **Switch on the assistant**: the data notice, and the switch behind a
`Popconfirm` (`AssistantApi.switchOn`, `POST /clientAdmin/assistant/switchOn`), whose answer is the status the page then
shows. It names the client by its name (`clientName`), since a self-serve client's id is generated. From the sandbox it
says to switch it on from the client itself. Off and not offered, the page says an operator switches it on. Which of
these it shows is `assistantViewOf`, covered by `AssistantTranscriptTest`. Each status read, and the switch-on, takes a
token, so a read under way when the assistant is switched on cannot put the panel back. The menu offers the page while it
is offered (`assistant:usable`), so the person finds it.

**Where a person starts** (issue #1569). A self-serve client's create lands here when the new client's menu offers
the page (`landingAfterCreate`, on the create card). On a new conversation, for a person with none yet, the
Conversation panel offers a few example requests under the box (`assistantExamples`, `showsExamples`): each fills the
box and puts the focus back in it. Filling the box hides them, until it is cleared again, which is deliberate: they are
a way to start, not a menu. The list of conversations is read again as soon as a first message makes one, so a new
conversation begun while that turn runs does not offer them again. The one about what a new form asks for is left out
for a client defined in code. For such a client the panel also
says, under its heading, that what its New form asks for is changed in code (the status's `sandboxInSource`, which is
whether the client is defined in code).

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
   amount -- money is the operator's -- when it starts again, and once it is used up that an operator can raise it. Use
   is kept by the UTC month, and when it starts again is shown in the client's zone as every moment is
   (`formatTimestamp`): a client west of UTC reads the last afternoon of the month before. A failed re-read of the
   status keeps what the page had, and says so here.

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
list. **A failed poll is tried again**, each time later (`pollDelayMs`: 1.5 s doubling up to 12 s), so one dropped read
never strands the page saying the assistant is working. A read is dropped when it comes back for a conversation no
longer open -- its token is taken before a poll's wait, and the open conversation checked again on its answer -- so one
conversation's messages never land in another's. The counters a coroutine bumps are bumped through their setters, never
counted from the render that launched it. The transcript keeps its newest message in view, and is a polite live region.

**A refused send is acted on**: a spent allowance, no sandbox, or the assistant off re-reads the status; a conversation
that cannot go on, or one a turn is already running in, is read again, which blocks the box or follows the turn. Stop
has its own failure line.

**A message sent again keeps its id** (`messageIdFor`): a send that got no answer at all (`ApiFailure`) keeps the id it
was given while its text is unchanged, so pressing Send again is the same turn on the server rather than a second one.
A refusal is an answer, and the next send gets a new id.

The open conversation rides the address as `cv` (`HP.conversation`), written with `replaceRoute`, so a reload reopens
it. Choosing another conversation does not add a history entry.

**The operator's page**: whether this node can reach the model (provider, model, or why not), and a table of every
client -- its switch, its monthly budget in dollars (its own, or the default), this month's use in dollars and as a
share, the model calls made, the last turn (relative time), and the share of input the prompt cache served. Each row's
controls act at once, and each is an ordinary button: a row's control, not the page's one verb. **Switch on…** asks
first, showing the data notice, since switching a client on acknowledges it. Set a budget in dollars -- "25", "$25", or
"1,000.50" (`budgetDollarsOf`); anything else is said not to be an amount -- or go back to the default.
