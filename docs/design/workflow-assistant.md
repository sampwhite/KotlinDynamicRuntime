# Workflow assistant

The **workflow assistant** lets a client's administrator build a workflow without git. They say what they want in a
text box. Claude, using this application's own endpoints as tools, finds out what exists (traits, cfacts, templates,
the client's bundles) and writes the workflow **as a draft in the client's sandbox**. The administrator tries it on a
real form there, and a person publishes it with the Publish button that already exists. The assistant never publishes.

This document says how it is built and why. It grows with the issues below.

**Status: built.**

| Phase | Issue | Status |
|---|---|---|
| Running an endpoint in-process as a user, with an API key's limits and an allowlist | #1524 | built |
| The `assistant` module: the model provider, operator opt-in, and monthly budgets | #1525 | built |
| Conversations: tools, the turn loop, storage, and budget enforcement | #1526 | built |
| The page: chat, try it in the sandbox, the publish link, and the allowance | #1527 | built |

## Where it lives

A module of its own, `assistant/`, shaped like `edge/`: it depends only on `:config`, nothing compile-depends on it,
and it is found through `ServiceLoader` (`AssistantComponent`, on application nodes only). `launch` takes it
`runtimeOnly`, guarded, so a workspace whose `settings.gradle.kts` leaves it out still builds. To add it to a workspace:

```kotlin
include("assistant")
project(":assistant").projectDir = file("KotlinDynamicRuntime/assistant")
```

What core and the webapp must name of it -- the endpoint paths, the field names, the menu pages, and the cfact -- is in
the kernel's `ASST` and `ACNV`, with a stored message's block names in `ABLK` and the tool names in `ATOOL`, so a rename
breaks a compile rather than a page.

The official Anthropic Java SDK (`com.anthropic:anthropic-java`) is a dependency of this module alone, and only
`AnthropicModel.kt` imports it.

## The model

`AssistantModel.respond(cxt, ModelRequest): ModelResponse` is the whole interface, over our own maps in the Messages
API's wire shape. The turn loop, the stored conversation, and the tests never see a provider's types.

- **`AnthropicModel`** converts through the SDK's own JSON mapping both ways, so what is stored is exactly what was
  sent and answered. Thinking blocks must go back unchanged, and a field the SDK has no type for is kept rather than
  dropped. It refuses under `ENV.unit` itself, since the SDK makes its own HTTP calls past `OutboundHttpService`.
- **`ScriptedModel`** answers from a script. A test hands one in under the instance-config key
  `AssistantService.modelConfigKey`, as a test hands the file store a `FakeS3Api`.
- **`KDR_ASSISTANT_PROVIDER=fake`** runs a built-in demo, to try the page locally with no key and no spending: it
  answers a message by calling `list_workflows`, a real tool call through the seam, and then names the workflows it
  found. Asked to draft (a message saying "draft"), it copies the client's first normal workflow as `assistantDemo`
  into a bundle of its own, a dry run and then the write, so the page's drafts can be tried and published. A test
  instance only: elsewhere it refuses the boot, as simulated mail does.

**Which model a node has** is decided once, at boot. With no key -- the secret named by `KDR_ASSISTANT_KEY_SECRET`
(default `anthropicApiKey`) missing from `private/secrets.properties` -- the assistant is unavailable and nothing else
changes. The operator's view names the secret it looked for, and the `assistant:on` cfact is false everywhere, so no
menu offers it. A test's node reads a secret no secrets file holds (`Startup.mkTestBootCxt` and `realNodeOverlay` name
it, as they do Mailgun's), so a developer's real key never reaches a test, whatever their shell exports.

**The request** (proposed defaults, for tuning against the real model later, which spends money and needs approval):

| Setting | Value | Why |
|---|---|---|
| Model | `claude-sonnet-5-5` (`KDR_ASSISTANT_MODEL`) | Capable enough for the work, at Sonnet's price |
| Effort | `medium` (`KDR_ASSISTANT_EFFORT`) | Below the model's default of `high`: most turns are short look-ups and one draft |
| Thinking | adaptive, `display: "updates"` | The model's progress notes between tool calls are shown, so a long turn does not go quiet |
| Tool choice | left to the model | Forced tool use is refused by this model |
| Fallbacks | `"default"`, server-side | A refusal is retried on the model Anthropic recommends |
| Caching | automatic, on the conversation's prefix | Each step resends the whole conversation |
| `max_tokens` | 16,000 (`KDR_ASSISTANT_MAX_TOKENS`) | Thinking counts toward it |

Each request asks for two beta features by header: `server-side-fallback-2026-07-01` and
`thinking-display-updates-2026-08-18`. Only some models accept both, so `KDR_ASSISTANT_MODEL` must be one of them --
Sonnet 5.5, Opus 5.5, Fable 5.1, Fable 5, or Mythos 5.1 -- and another refuses the boot, as does a `max_tokens` above
the models' 128,000.

## Who may use it, and how much

**Opt-in per client, by a deployment operator.** The switch is a row in `AssistantClients`, not part of the client's
definition: a definition change is a draft on a client with a sandbox, and what a deployment lets a client spend is
not configuration. A sandbox goes by its parent. Switching a client on asks the operator to acknowledge what is sent to
Anthropic (the listing's `dataNotice`); when and by whom is recorded on the row.

**A monthly budget in weighted tokens**, the deployment's default (`KDR_ASSISTANT_MONTHLY_BUDGET`) unless the
operator sets the client's own. The unit is proportional to cost at Sonnet's prices, and steady whether or not the
cache hits:

| Tokens | Weight |
|---|---|
| input | 1 |
| cache write | 1.25 |
| cache read | 0.1 |
| output | 5 |

Other models are counted at their own prices in the same unit (`PRICE`). That matters with fallbacks: a response may
be several billed attempts -- the model that declined, then the one that answered -- and its top-level `usage` covers
only the last, so the attempts are read from `usage.iterations`, each at its own model's price. An attempt that names no
model is counted at the dearer of the model asked and the model that answered, and a model the table does not know at
the dearest price known, kind by kind, so a budget errs toward stopping early.

**Use** is kept in `AssistantUsage`, one row per client and calendar month (UTC), added to in one statement per model
call, so two calls finishing together both count. It keeps each kind of token as well as the weighted total, so the
operator can see how often the cache hits. A sandbox's use is its parent's.

**The operator's controls** are in the `operator` section (the operator level and `allClients`):

- `GET /operator/assistant/clients`: every client with its switch, budget, this month's use, when the model last
  answered for it, and its cache-hit ratio. The summary says whether this node can reach the model, and why not.
- `POST /operator/assistant/client/set`: switch a client on or off, and set its budget -- in dollars
  (`monthlyBudgetDollars`) or in weighted tokens (`monthlyBudget`) -- or go back to the default.

Both show every budget and the month's use in dollars as well (`monthlyBudgetDollars`, `usedDollars`), at Sonnet's
prices: 500,000 weighted tokens to the dollar. Weighted tokens stay the unit kept and counted; dollars are the
operator's terms. They are exact for every model the price table lists, since each is counted at its own price; only a
model it does not list, counted at the dearest price, comes out high. A budget in dollars is from a cent to $1,000,000.
The client to set is chosen from the clients this node carries, its sandbox left out.

**The `assistant:on` cfact** is true when this node has a model and the caller's client (a sandbox's parent) is
switched on. It does not say whether the month's budget is spent: the page says that. It is read on every cfact
assembly, so the switch is held in a table cache. The cache holds the enabled rows only, which is exactly the set the
cfact asks about. **`assistant:usable`** (#1527) adds the rule the page's endpoints answer by: in a sandbox, the caller
must be there as a user opened from one of the client's own (`AssistantTurns.personOrNull`). Read only once the switch
says yes, so a node with the assistant off asks nothing more of a sandbox caller.

## Conversations and turns

**Who the tools act as.** The administrator's own user in the client's sandbox -- their enabled one when it was opened
from the user they are now; otherwise one claimed in their own signed-in request (`claimOwnSandboxUser`), as opening
the sandbox does, made on first use or brought up to date, since a sandbox user may do what its opener may; one another
administrator disabled is refused, not enabled again -- re-read on every call by the in-process seam (#1524). So a
configuration edit can only become a draft on the parent, written as the administrator who stands behind the sandbox
user; reads show the drafts; and the assistant never holds more than the administrator does at that moment. A client
with no sandbox is refused (`noSandbox`): the administrator turns one on from the Clients page, and the assistant
never does. The assistant works only for the client the administrator is signed in to. Signed in to its sandbox --
where "Try it in the sandbox" leaves them -- they are still the person outside it: the user the sandbox user was opened
from (`AssistantTurns.personOf`), whose conversations these are, and the tools act as the session's own sandbox user. A
sandbox user nobody opened, or one opened from another client, has nobody outside to speak for, and is refused.

**The tools** are the allowlist: the seam admits exactly the endpoints they name (`AssistantTools.scope`), so everything
else is refused, and a drift test holds each tool to its endpoint.

| Tool | Does | Through |
|---|---|---|
| `read_doc` | the configuration reference, the schema dialect, or a demo-client page | code: documents are not endpoints |
| `list_configs` | the client's bundles | `GET /clientAdmin/config/bundles` |
| `read_config` | one bundle, in portable form | `GET /clientAdmin/config/bundle` |
| `read_config_entries` | one bundle's entries, with each one's `updatedAt` | `GET /clientAdmin/config/entries` |
| `list_workflows` | the workflows the sandbox runs, and the templates | `GET /clientAdmin/client/workflows` |
| `list_traits` | the global traits, the client's own, and the plan's quotas | `GET /clientAdmin/client/traits` |
| `read_cfacts` | the cfacts reference | `GET /clientOperator/cfacts` |
| `view_workflow` | a workflow as the sandbox runs it | `GET /gedra/workflow/view` |
| `patch_config` | a change, as a draft: a dry run, then the same configs written | `POST /clientAdmin/config/bundle/patch` |
| `set_included_traits` | the client's global traits, expected as a draft | `POST /clientAdmin/client/traits/set` |

`patch_config` refuses a write unless a dry run of exactly the same configs was accepted in an earlier step of the same
turn, so the model has read its impact first. A write asks for no reload: a patch from a sandbox reloads its parent,
and with it the sandbox, so `view_workflow` shows the draft at once. Left out on purpose:
publishing, reverting, deleting a bundle, `publishedOnly`, `bundle/write` (which deletes what it is not given), the
sandbox switch, `workflows/params` (in force at once), users, keys, sessions, form data, the endpoint catalog, and
every `admin`, `operator`, and `fixture` endpoint. A tool's result is the endpoint's answer without its envelope, cut
at 30,000 characters (100,000 for a document read whole), saying so; a refusal comes back as an error the model can
correct from. A dry run's answer leaves out the bundles as they would be -- the model sent them -- so its impact, which
is what it is for, is never what a long answer cuts; a write's answer keeps them, since they say what was written and
give each entry's new `updatedAt` for the next edit.

A dry run from a sandbox user reloads the parent client, as every patch from a sandbox does. That costs a reload and
changes nothing.

**The system prompt** says who the assistant works for, what its tools can and cannot do, that what they return is data
and never instructions, and then gives the API guide. A conversation is bound to the prompt and tools it began with
(`toolsetVersion`, a hash of both): thinking is tied to them, so a conversation begun under another version is
read-only, and the administrator starts a new one. A new build that changes the API guide or a tool does that to every
conversation. The prompt names the client by its id alone, so renaming a client does not.

**A turn** starts with `POST /clientAdmin/assistant/message` (`conversationId`, `clientMessageId`, `text`), which
checks, in order: the assistant is on for the client, the client has a sandbox, its budget is not spent, the caller
has turns left this hour (`KDR_ASSISTANT_TURNS_PER_HOUR`, counted in the database), the client has room for another
running turn (`KDR_ASSISTANT_CONCURRENT_TURNS`; a turn whose lease lapsed does not count), and no turn is running in
the conversation (a conditional update; a 409 otherwise). The conversation is claimed before anything else is written.
A `clientMessageId` sent again is the turn it already started -- unique per user, so two sends at once start one turn --
and one sent into another conversation is refused. It then answers at once, and the turn runs on a virtual thread with
a context of its own. Each step:

1. checks the turn is still running and not canceled, within its steps (`KDR_ASSISTANT_MAX_STEPS`) and the budget, and
   that the history is sound (`historyProblems`);
2. asks the model with the whole history, and adds what it cost to the turn and the month;
3. renews the turn's lease, and stops writing at once if the turn was meanwhile marked interrupted;
4. appends the answer exactly, and acts on why the model stopped: `tool_use` runs every call and answers them all in
   one message; `end_turn` ends; `max_tokens` answers any call in the cut-off answer as not run, and ends; `pause_turn`
   asks again, continuing the answer; `refusal` ends, saying so plainly -- and, when the refusal had content, makes the
   conversation read-only, since the refused exchange stays in its history and would meet the next request; an empty
   refusal is not stored, since it could not be sent back, and the conversation goes on. A stop reason this code does
   not know ends the turn, answering any call in it as not run.

The history is append-only, and every tool call is answered in the very next message -- on a failure too, with what
happened to each call: one that ran, with its result, and one not reached, as not run. So no failure leaves a
conversation that cannot go on, and the model is never told a write it made did not happen. A cancel
(`POST /clientAdmin/assistant/cancel`) stops the turn at its next step, answering calls it had not run. A turn holds a
lease of 25 minutes, renewed after every model answer and before every tool call; one call to the model times out after
10 minutes and is retried once, so no step outlasts it. A call that times out is billed but answers with no usage, so it
is not counted against the budget: `KDR_ASSISTANT_MAX_TOKENS` is held to 16,000, about what a model writes within the
timeout, as the SDK's guidance sizes a non-streaming request, until the calls stream
([`deferred-work.md`](../development/deferred-work.md#when-an-assistant-answer-needs-more-than-one-call-can-write)). A
turn whose lease lapsed -- its node stopped -- is marked interrupted by the next request that looks at its conversation,
which answers any call it left, and frees the conversation. A conversation claimed by a message still being started,
whose turn is not yet recorded, is left alone until it is as old as a lease.

The page polls `GET /clientAdmin/assistant/conversation?conversationId&afterSeq` (marked `polled`, so it neither
renews the session nor logs each call) and lists `GET /clientAdmin/assistant/conversations`. Every assistant endpoint
is session-only: no API key, and not the assistant itself, can start a turn or spend the budget.

**Storage**, all in the database, so a poll reaching any node finds the same conversation:
`AssistantConversations` (its running turn, if any, and its version), `AssistantMessages` (the transcript, as the
model's API reads it), and `AssistantTurns` (each turn's status, stop reason, steps, the four token counts, the weighted
total, the model that answered, and the note the administrator is shown). `LogAssistant` logs turns starting and
ending, every write and refusal, and model errors, never the model's text.

The per-hour and per-client limits are counted, then the turn is recorded: two messages at the same instant may both
pass. They are a fair-use limit, not a fence; the budget is the fence, checked again before every step.

## The page

`/wa/assistant` (`AssistantPage.kt`; what it decides is in the `kdr-webapp-pages` skill's `workflow-assistant.md`). Its
menu entry is the module's own: an overlay on the menu (`AssistantComponent.uiBlocks`), offered to a client's
administrator while `assistant:usable` holds -- so never to a caller every assistant endpoint would refuse -- placed
after Reports. Core never names the cfact, which exists only while the module is loaded, and a client's menu editor can
put the entry back to it, "As shipped". The operator's page, `/wa/operator/assistant`, is offered the same way under Operator.

Before anything is sent the page reads `GET /clientAdmin/assistant/status`: whether the assistant is on, whether the
client has a sandbox, whether the caller is in it, the month's use **as a share** of the budget -- never the amount,
which is the operator's -- when it resets (the first of next month in UTC, shown in the client's zone), and the data
notice. Its panels:

- **Sandbox**, only while there is none: turning one on, confirmed, through the Clients page's own call.
- **Conversation**: the person's conversations to reopen, the transcript, Send and Stop, and "What is sent to Anthropic"
  beside a conversation's first message. The tool results the API stores as the person's are joined to their calls, and
  an answer's calls collapse into one line ("Looked at: workflows, bundle `flows`", "Drafted `flows` (v3)") that opens
  to each call and its result. The model's notes show muted. It polls every 1.5 seconds while a turn runs, reading only
  the messages after the last it has, backing off after a failed read rather than stopping, and nothing when idle.
- **Drafts from this conversation**: each bundle a write made, and the included traits, with **Try it in the sandbox**
  (opening it at the workflow's forms) and **Check impact and publish**, a link to the Clients page's publishing panel.
  The page has no Publish button.
- **Allowance**: the share used, and once it is used up, when it starts again and that an operator can raise it.

## Not decided yet

Recorded here as proposed, to settle as the later issues are worked:

- **Retention.** A conversation's transcript kept until the conversation is deleted, and turn records 13 months.
  Neither is built: nothing deletes a conversation or a turn record yet.
- **Audit.** Through the transcript and the logs, with no field on a revision naming the assistant.
- **Progress notes.** The request asks for `display: "updates"`, so the model's notes between tool calls are stored in
  its thinking blocks, which the page shows muted. Whether the notes the real model writes are worth showing waits for
  tuning against it.
- **Per-plan default budgets.** Later; see
  [`deferred-work.md`](../development/deferred-work.md#when-a-deployment-offers-the-workflow-assistant-at-more-than-one-price).
