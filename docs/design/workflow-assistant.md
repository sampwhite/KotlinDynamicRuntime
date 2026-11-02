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
| Conversations: tools, the turn loop, storage, and budget enforcement | #1526 | not started |
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
- `POST /operator/assistant/client/set`: switch a client on or off, and set its budget or go back to the default.

**The `assistant:on` cfact** is true when this node has a model and the caller's client (a sandbox's parent) is
switched on. It does not say whether the month's budget is spent: the page says that. It is read on every cfact
assembly, so the switch is held in a table cache. The cache holds the enabled rows only, which is exactly the set the
cfact asks about.

## Not decided yet

Recorded here as proposed, to settle as the later issues are worked:

- **Retention.** A conversation's transcript kept until the conversation is deleted, and turn records 13 months.
- **Audit.** Through the transcript and the logs, with no field on a revision naming the assistant.
- **Per-plan default budgets.** Later; see
  [`deferred-work.md`](../development/deferred-work.md#when-a-deployment-offers-the-workflow-assistant-at-more-than-one-price).
