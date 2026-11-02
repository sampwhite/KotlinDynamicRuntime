# Workflow assistant

The **workflow assistant** lets a client's administrator build a workflow without git. They say what they want in a
text box. Claude, using this application's own endpoints as tools, finds out what exists (traits, cfacts, templates,
the client's bundles) and writes the workflow **as a draft in the client's sandbox**. The administrator tries it on a
real form there, and a person publishes it with the Publish button that already exists. The assistant never publishes.

This document says how it is built and why. It grows with the issues below.

**Status: in progress.**

| Phase | Issue | Status |
|---|---|---|
| Running an endpoint in-process as a user, with an API key's limits and an allowlist | #1524 | built |
| The `assistant` module: the model provider, operator opt-in, and monthly budgets | #1525 | built |
| Conversations: tools, the turn loop, storage, and budget enforcement | #1526 | built |
| The page: chat, try it in the sandbox, the publish link, and the allowance | #1527 | not started |

## Where it lives

A module of its own, `assistant/`, shaped like `edge/`: it depends only on `:config`, nothing compile-depends on it,
and it is found through `ServiceLoader` (`AssistantComponent`, on application nodes only). `launch` takes it
`runtimeOnly`, guarded, so a workspace whose `settings.gradle.kts` leaves it out still builds. To add it to a workspace:

```kotlin
include("assistant")
project(":assistant").projectDir = file("KotlinDynamicRuntime/assistant")
```

What core and the webapp must name of it -- the endpoint paths, the field names, the menu page, and the cfact -- is in
the kernel's `ASST`, so a rename breaks a compile rather than a page.

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
- **`KDR_ASSISTANT_PROVIDER=fake`** runs a built-in demo that answers with canned text, to try the page locally with
  no key and no spending. A test instance only: elsewhere it refuses the boot, as simulated mail does.

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
operator's terms, and exact for the default model.

**The `assistant:on` cfact** is true when this node has a model and the caller's client (a sandbox's parent) is
switched on. It does not say whether the month's budget is spent: the page says that. It is read on every cfact
assembly, so the switch is held in a table cache. The cache holds the enabled rows only, which is exactly the set the
cfact asks about.

## Conversations and turns

**Who the tools act as.** The administrator's own user in the client's sandbox -- their enabled one, or, when they have
none, one claimed in their own signed-in request (`claimOwnSandboxUser`), as opening the sandbox does; one another
administrator disabled is refused, not enabled again -- re-read on every call by the in-process seam (#1524). So a
configuration edit can only become a draft on the parent, written as the administrator who stands behind the sandbox
user; reads show the drafts; and the assistant never holds more than the administrator does at that moment. A client
with no sandbox is refused (`noSandbox`): the administrator turns one on from the Clients page, and the assistant
never does. The assistant works only in the client the administrator is signed in to, and not from inside a sandbox.

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
correct from. A patch's answer leaves out the bundles the endpoint echoes -- the model sent them -- so what it needs,
a dry run's impact or a write's reload issues, is never what a long answer cuts.

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
   asks again, continuing the answer; `refusal` ends, saying so plainly, and makes the conversation read-only, since
   the refused exchange stays in its history and would meet the next request; an empty answer is not stored, since it
   could not be sent back. A stop reason this code does not know ends the turn, answering any call in it as not run.

The history is append-only, and every tool call is answered in the very next message -- on a failure too, so no failure
leaves a conversation that cannot go on. A cancel (`POST /clientAdmin/assistant/cancel`) stops the turn at its next
step, answering calls it had not run. A turn holds a lease of 15 minutes, renewed before every model call and every tool
call; one call to the model times out after 5 minutes and is retried once, so no step outlasts it. A call that times out
is billed but answers with no usage, so it is not counted against the budget: `KDR_ASSISTANT_MAX_TOKENS` is held to
32,000, what a model writes well within the timeout, until the calls stream
([`deferred-work.md`](../development/deferred-work.md#when-an-assistant-answer-needs-more-than-one-call-can-write)). A
turn whose lease lapsed -- its node stopped -- is marked interrupted by the next request that looks at its conversation,
which answers any call it left, and frees the conversation.

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

## Not decided yet

Recorded here as proposed, to settle as the later issues are worked:

- **Retention.** A conversation's transcript kept until the conversation is deleted, and turn records 13 months.
  Neither is built: nothing deletes a conversation or a turn record yet.
- **Audit.** Through the transcript and the logs, with no field on a revision naming the assistant.
- **Progress notes.** The request asks for `display: "updates"`, so the model's notes between tool calls are stored in
  its thinking blocks, for the page (#1527) to show.
- **Per-plan default budgets.** Later; see
  [`deferred-work.md`](../development/deferred-work.md#when-a-deployment-offers-the-workflow-assistant-at-more-than-one-price).
