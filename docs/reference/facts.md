<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Facts

A **fact** is a yes-or-no statement about the request, a task, or a form: the caller is signed in, a task is the next
one to do, a form's core details are complete. A fact is present or absent, and never holds a value. A client's configuration
tests facts to decide who sees a menu entry or a field, who may save a task, and which forms a workflow takes. This
page says where facts come from and how a test is written, and lists the facts the platform declares. The list is made
from the declarations themselves.

## Where facts come from

- **The request.** For every request, the platform works out facts about the caller and the node: whether the caller
  is signed in, how far their administration reaches, and whether the node is a test instance.
- **A task.** As a task is shown, the platform works out facts about it, such as whether it is the one to do next
  (`kdr:isCta`). A task's own functions add facts about the person looking at it: `userHasLabel` gives
  `kdr:reviewer` to a user with the label it names.
- **A form.** Facts about a form are kept with it and worked out again whenever it is saved: whether its core details
  are complete, what the core details workflow's functions concluded from its data, and what its workflows say about
  it, such as `kdr:finished`.
- **A workflow.** While a form is in a normal workflow, the workflow has facts of its own about it: what the workflow's
  functions concluded from the form's data, and the fact of each approval a reviewer gave.
- **The caller and the form.** As a form is read for someone, the platform works out whether they own it
  (`kdr:isOwner`), from the form's owner and never from its data. It is never stored, so only a test about one
  person and one form sees it: it is how a step says it is the owner's turn.

A client can declare facts of its own, in the `kdr:cfactDef` slot. A declaration only says that a fact exists, so
that a test may name it. Something then has to set it:

- **A `computeCFactsFromData` function** reads one field and gives the fact when the field's value is one it maps. In the
  core details workflow, the fact is the form's; in a normal workflow, it is the workflow's.
- **An approval task** names, in its `approval`, the fact a reviewer's approval gives.

| Key | What it means |
|---|---|
| `name` | The name a test writes. A client's own is a bare name, such as `isWorkshop`; the platform's begin with `kdr:`. |
| `group` | A heading the fact is listed under, for people. |
| `description` | What makes the fact true. |
| `toFrontend` | Whether the fact is sent to the browser with the page. Off unless set. |

See [Workshop logistics](../demo-client/workshop-logistics.md#a-computed-fact) for a fact the core details workflow
computes, and [Committee review](../demo-client/committee-review.md) for one an approval gives.

## Where you write a test

What a test can see depends on where it is written.

| Where | What it decides | The facts it sees |
|---|---|---|
| A menu entry's `cfactExpression` | Whether the entry is shown. | The request's: the caller and the node. |
| A field's `g-visibleWhen`, in its schema | Whether the field is on the form. A write that would change a field kept off is refused. | The request's, and only those sent to the browser. |
| A [page widget](page-widgets.md)'s `cfactExpression` | Whether the widget is drawn on the page. It is judged in the browser. | The request's, and only those sent to the browser. |
| A task's `saveWhen` | Who may save the task. Without one, anyone who can see the form may. | The request's, the task's, those the task's functions give the person looking, and `kdr:isOwner`. |
| An approval's `approveWhen` | Who may approve the task. Without one, a reviewer who does not own the form: `kdr:reviewer,~kdr:isOwner`. | The same as `saveWhen`. |
| A filter a task's `ctaFilterByValue` function gives | Whom the task waits on while it is the workflow's next step. It narrows `saveWhen` and `approveWhen`, and never widens them. Its answer for the person looking is `kdr:passesCtaFilter`. | The request's, those the task's functions give the person looking, and `kdr:isOwner`: none of the task's or the form's, so a list of forms can test it without reading them. |
| A `cfactExpression` in a task's `display` | Which of the task's displays is shown: the first whose test passes. | The same as `saveWhen`. |
| A `test` in a workflow's `eligibility` | Whether a form may enter the workflow. | The form's alone, never the caller's, so the answer can be kept with the form. |
| A `when` in a workflow's `singletons` | Whether the workflow gives the form the rule's fact. | The form's, and the workflow's own. |
| A `when` in a workflow's `locks` | Whether the lock holds. | The form's, and the workflow's own. |
| A lock's `overrideWhen` | Who may change the locked trait anyway. | The request's, for whoever is writing. |

`kdr:isOwner` is about one person and one form, so a test where the form is judged whoever looks -- an eligibility
test, a singleton rule, a lock, or a lock's override -- may not name it, and is refused when its configuration is checked.

## How a test is written

A test is not an [expression](expressions.md). It has no `${` and `}`, and no values to compare: it names facts, and
joins them.

| Write | Means |
|---|---|
| `kdr:loggedIn` | The fact is present. |
| `a,b` | Both: every part is present. |
| `a\|b` | Either: at least one part is present. |
| `~a` | Not: the part is absent. |
| `(a,b)\|c` | Grouping. |
| `#always` | Always true. |
| `#never` | Never true. |

- **`,` and `|` are not mixed without parentheses.** A test such as `a,b|c` is refused, since
  a reader would have to know which joins tighter. Write `(a,b)|c` or `a,(b|c)`.
- **Every name must be declared.** A test that names a fact nobody declared is refused when its configuration is
  checked, so a misspelling is caught at once. Otherwise `~kdr:admn` would always be true, and show something
  to everyone.
- **Prefer the positive form.** `kdr:anonymous` says what is meant, where `~kdr:loggedIn` says it
  by exclusion.
- **Leaving a test out means always,** where a test may be left out. An empty test is refused; write `#always`
  to say so.
- **`#never` takes something away.** A client hides one of the platform's menu entries by giving it `#never`,
  and brings one back that a layer beneath turned off by giving it `#always`.

## The platform's facts

Every deployment declares these. The last column says whether a fact is sent to the browser with the page. A field's
`g-visibleWhen` and a page widget's `cfactExpression` may name only a fact that is, and see only the
request's facts among them, so the caller's facts marked Yes are the ones they can test. Each is sent by a deliberate
choice. A client's own facts are sent only where its declaration sets `toFrontend`, and are about its forms, so
a page widget's condition may not name them.

A component a deployment adds can declare facts under a name of its own, as the workflow assistant declares
`assistant:on`. The **Client facts** page, for a client's operators and administrators, lists every fact the client
knows, its own included, with whether each is present for them now.

### Node

| Fact | What makes it true | Reaches the page |
|---|---|---|
| `kdr:app` | True on an ordinary application node -- the boot role a node has when it declares none. Exactly one boot-role cfact is present on any node. | No |
| `kdr:edge` | True on an edge node: the perimeter that fronts other servers. | No |
| `kdr:isTestInstance` | True on a test instance -- one carrying the test-only fixtures, whoever is asking. That a caller may use them is `kdr:hasTestAccess`. | No |

### Caller

| Fact | What makes it true | Reaches the page |
|---|---|---|
| `kdr:loggedIn` | True when the request carries an authenticated identity -- neither the anonymous profile nor the unauthenticated system one. | Yes |
| `kdr:anonymous` | True when the request carries no authenticated identity. The positive form of `~kdr:loggedIn`, and preferred over it: a test written by exclusion admits every state invented later. | Yes |
| `kdr:hasOperatorLevel` | True when the caller ranks at `operator` or above on the privilege ladder -- so an administrator satisfies it without holding `operator` at all. **Level only**: the `operator` section also requires `allClients`, so this is not the test that section applies -- `kdr:isDeploymentOperator` is. | No |
| `kdr:isDeploymentOperator` | True when the caller may reach the `operator` section -- the deployment-wide surface, which takes the operator level **and** the `allClients` capability. A client-scoped administrator is not one, despite the ladder ranking them above operator. | No |
| `kdr:isDeploymentAdmin` | True when the caller may reach the `admin` section -- the deployment-wide administrative surface, which takes the admin level **and** the `allClients` capability. A client-scoped administrator is not one. It is what may name another client on a scoped surface. | Yes |
| `kdr:isClientOperator` | True when the caller may reach the `clientOperator` section -- the client-scoped operator surface (its own client's pages), which takes the operator level but **not** `allClients`, so an operator or an administrator confined to one client is one, unlike `kdr:isDeploymentOperator`. | No |
| `kdr:administersClient` | True when the caller administers a client -- their own, or every one with `allClients`. An administrator in the `public` placeholder is not one: they reach their own users only, so `kdr:hasAdminLevel` is true for them and this is not. It is what a caller's reads reach beyond their own rows by, so a surface named for whose rows it shows asks it. | No |
| `kdr:administersCustomer` | True when the caller administers a customer's root client, client-wide: the administrators who act for the customer as a whole, and may see its page. An administrator confined to an organization of the root is not one. | No |
| `kdr:isEditor` | True when the caller is an editor of its client: holds the `editor` role and is no administrator. An editor sees every user and form of its client and edits forms, but administers nothing -- no user edits, no user creation, no client configuration. | No |
| `kdr:seesClientForms` | True when the caller's reads reach every form of a client: an administrator of one (`kdr:administersClient`) or an editor (`kdr:isEditor`). So a surface named for whose forms it shows -- "Forms" against "My forms" -- asks it. | No |
| `kdr:publicMember` | True when the caller is signed in to the `public` placeholder client: a visitor who registered, who may create a client of their own there. A visitor who has not signed in acts in `public` too, and is not one. | No |
| `kdr:madeOwnClient` | True when the caller is signed in to `public` (`kdr:publicMember`) and has made their own client, the one a person may make there -- whatever has become of it since. | No |
| `kdr:selfServeClient` | True when the caller's client is a self-serve one -- created by a person from the public home page, its id under the self-serve prefix -- or that client's sandbox. | No |
| `kdr:clientHasSandbox` | True when the caller's client has a sandbox, so its configuration changes are drafts until published -- or the caller is in that sandbox. Whether the caller may open it is a separate question. | No |
| `kdr:inSandbox` | True when the caller's client is a sandbox: another client's, running its latest configuration over users and data of its own. | No |
| `kdr:clientHasWaitingWorkflows` | True when the caller's client has a workflow whose step can wait on somebody: a normal or a core details workflow, whose current task stays to be taken after a form is made. | No |
| `kdr:hasAdminLevel` | True when the caller ranks at `admin` on the privilege ladder. **Level only**: it says what the caller may do, not over whose rows, so it is not the same as reaching the deployment-wide `/admin` section, which takes the `allClients` capability too. | Yes |
| `kdr:hasEnvAuth` | True when the session's env auth is effective: the request came through an authenticated edge, or a direct local start assumed it, and it is not suppressed. Gates a field a caller may set only from such a channel -- e.g. importing with entry ids preserved. | Yes |
| `kdr:signInOpenToEveryone` | True when the caller may sign in or register whatever their address: anywhere but a public test deployment that refuses the public a sign-in. The home page's Register card is offered on it. | No |
| `kdr:signInOpenToSome` | True when the caller may sign in or register with some address: `kdr:signInOpenToEveryone`, or a public test deployment where an administrator on the admin domain still may. The menu's Register and Log in are offered on it. | No |
| `kdr:hasTestAccess` | True when the caller may use the test-only fixtures: a test instance, with env auth effective. The Simulations menu item is offered on it, since the simulations exist for nobody else. | No |
| `kdr:isEnvDebug` | True when the session is in ENV DEBUG: env auth is effective and it turned debug on. The debug menu items are offered on it. | No |
| `kdr:canEnableDebug` | True when the session may **turn debug on**: env auth is effective and debug is not already on -- so the 'Enable debug' item is offered exactly to a session that could act on it. | No |

### Workflow

| Fact | What makes it true | Reaches the page |
|---|---|---|
| `kdr:taskComplete` | True, about the task being rendered, when an entry is present for every trait the task requires. Presence, not content: an entry with empty data counts. | No |
| `kdr:taskAvailable` | True, about the task being rendered, when it may be worked on now. **Always true today**: a placeholder holding the shape until availability rules (dates, prior tasks) exist. | No |
| `kdr:isCta` | True, about the task being rendered, when it is the workflow's call to action: the earliest task, in list order, that is not both complete and valid. | No |
| `kdr:reviewer` | True, about the task being rendered, when the person viewing it may review it -- concluded as the task is shown, by the task's own functions (typically `userHasLabel`), and never stored. | No |
| `kdr:passesCtaFilter` | True, about the task being rendered, unless it is the workflow's call to action and the person viewing it fails the call to action's filter: the filter evaluated for them, as the task is shown, and never stored. Always true on every other task, and on a call to action with no filter. | No |
| `kdr:isOwner` | True, about the form being worked on, when the caller owns it: its owning user, or in production another user of the same person; on a test instance only the owning user, so one tester's personas can play owner and reviewer. Worked out from the form's owner as it is read, and never stored. Named in a task's `saveWhen` or `display`, a CTA filter, or an approval's `approveWhen`, and nowhere else. | No |
| `kdr:ctaReview` | True, about a workflow, while its call to action carries the `review` search keyword -- in the workflow's own facts, for its display branches. The form's status for it is `kdr:needsReview`. | No |
| `kdr:needsReview` | True, about a form, when a workflow it is engaged with is waiting on a review: its call to action carries the `review` search keyword, which an approval task carries without saying so. | Yes |
| `kdr:finished` | True, about a form, when a workflow it is engaged with has finished -- contributed by that workflow's singleton rule. | Yes |
| `kdr:ownerOnly` | True, about a form, when it is kept to its owner: an editor may change it only as its owner or as a reviewer of an engaged workflow's current task -- contributed by a workflow's singleton rule. | Yes |

### Core details

| Fact | What makes it true | Reaches the page |
|---|---|---|
| `kdr:coreDetailsComplete` | True, about a form, when every trait its client's core details workflow requires has an entry present -- asserted by the form's stored core details state. Presence, not content. | Yes |
| `kdr:coreDetailsValid` | True, about a form, when the present core details trait data passes its schema (ignoring missing required values) and meets the core details form's own requirements (a field its layout requires, a choice it offers) -- asserted by the form's stored core details state. | Yes |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes its list of facts from the declarations
themselves, so it cannot disagree with them. Read it on the server's **Documents** page, under **Reference**, then
**Facts**, where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/facts](https://dynamicruntime.org/wa/docs/facts). `./gradlew :launch:refDocsDump` copies it
into this file, and the suite fails while the copy is behind the code, so edit the generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The platform's facts, each with its group, what makes it true, and whether it reaches the page | `addCommonCFacts` in [`CoreCFacts.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/cfact/CoreCFacts.kt) |
| The grammar the page's syntax states | [`CFactParser.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/cfact/CFactParser.kt) |
| The reference page, made from these | [`FactsReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/cfact/FactsReference.kt) |
